package com.jldaren.agent.ai.datascope.controller;

import com.jldaren.agent.ai.datascope.controller.bean.StreamChatRequest;
import com.jldaren.agent.ai.datascope.entity.ChatMessage;
import com.jldaren.agent.ai.datascope.mapper.ChatMessageMapper;
import com.jldaren.agent.ai.datascope.registry.AgentScopeRegistry;
import com.jldaren.agent.ai.datascope.service.ChatSessionService;
import com.jldaren.agent.ai.datascope.service.RagService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.hook.ActingChunkEvent;
import io.agentscope.core.hook.SummaryChunkEvent;
import io.agentscope.core.message.Msg;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledFuture;


@Slf4j
@RestController
@RequestMapping("/api/scope/agent")
@Tag(name = "AgentScope Agent", description = "AgentScope 智能体管理接口")
public class AgentScopeSseController {

    // 依赖注入
    private final AgentScopeRegistry agentRegistry;
    private final RagService ragService;
    private final ChatSessionService chatSessionService;
    private final ChatMessageMapper chatMessageMapper;
    private final ScheduledExecutorService HEARTBEAT_EXECUTOR;
    private final ExecutorService RAG_EXECUTOR;
    private final ExecutorService CHAT_SAVE_EXECUTOR;
    private final Semaphore SSE_SEMAPHORE;
    private final Counter sseStartCounter = Metrics.counter("sse.connection.started");
    private final Counter saveFailedCounter = Metrics.counter("message.save.failed");
    private final Timer requestDurationTimer = Metrics.timer("agent.request.duration");

    public AgentScopeSseController(AgentScopeRegistry agentRegistry, RagService ragService,
                                   ChatSessionService chatSessionService, ChatMessageMapper chatMessageMapper,
                                   @Qualifier("heartbeatExecutor") ScheduledExecutorService heartbeatExecutor,
                                   @Qualifier("ragExecutor") ExecutorService ragExecutor,
                                   @Qualifier("chatSaveExecutor") ExecutorService chatSaveExecutor,
                                   @Value("${sse.max-concurrent:100}") int maxConcurrent) {
        this.agentRegistry = agentRegistry;
        this.ragService = ragService;
        this.chatSessionService = chatSessionService;
        this.chatMessageMapper = chatMessageMapper;
        this.HEARTBEAT_EXECUTOR = heartbeatExecutor;
        this.RAG_EXECUTOR = ragExecutor;
        this.CHAT_SAVE_EXECUTOR = chatSaveExecutor;
        this.SSE_SEMAPHORE = new Semaphore(maxConcurrent);
    }

    /**
     * POST SSE 流式聊天（基于 Hook 监听中间事件）
     * Content-Type: application/json
     * Accept: text/event-stream
     */
    @PostMapping(value = "/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "SSE流式聊天(POST)", description = "SSE流式返回Agent回复，支持推理过程实时推送")
    public SseEmitter chatStreamPost(
            @PathVariable Long id,
            @RequestBody @Valid StreamChatRequest request,
            @RequestHeader(value = "User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "Tenant-ID", required = false) String tenantIdHeader) {

        // 🔐 1. 限流检查（最多等待 1 秒）
        boolean acquired;
        try {
            acquired = SSE_SEMAPHORE.tryAcquire(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务器繁忙，请稍后重试");
        }
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务器繁忙，请稍后重试");
        }

        // 🔐 2. 参数校验（@Valid 已校验 message 非空，此处可加业务校验）
        if (request.getMessage().length() > 4000) {  // 示例：限制消息长度
            SSE_SEMAPHORE.release();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message too long (max 4000 chars)");
        }

        String userId = userIdHeader != null ? userIdHeader : "anonymous";
        String tenantId = tenantIdHeader != null ? tenantIdHeader : "default";

        // 🔍 3. 获取 Agent 实例
        ReActAgent baseAgent = agentRegistry.getAgentWithLongTermMemory(
                id, userId, request.getSessionId(), tenantId);
        if (baseAgent == null) {
            SSE_SEMAPHORE.release();
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found or not published: " + id);
        }

        // 📝 4. 会话管理 & 保存用户消息（事务保证）
        final String finalSessionId = chatSessionService.createSessionAndSaveUserMsg(
                id, userId, tenantId, request.getSessionId(), request.getMessage());
        Long userIdLong = parseLongSafe(userId, null);
        Long tenantIdLong = parseLongSafe(tenantId, null);

        // 📡 4. 创建 SSE 发射器（180 秒超时，与 Agent 超时对齐）
        SseEmitter emitter = new SseEmitter(180000L);
        sseStartCounter.increment();  // 监控：SSE 连接开始
        AtomicBoolean disconnected = new AtomicBoolean(false);
        AtomicBoolean semaphoreReleased = new AtomicBoolean(false);
        List<String> responseFragments = Collections.synchronizedList(new ArrayList<>());  // 收集完整响应
        long startTime = System.currentTimeMillis();  // 记录开始时间

        // 🔁 5. 统一的 semaphore 释放方法（CAS 防重复）
        Runnable releaseSemaphore = () -> {
            if (semaphoreReleased.compareAndSet(false, true)) {
                SSE_SEMAPHORE.release();
            }
        };

        // 💓 6. 心跳管理（防代理超时断开）
        AtomicReference<ScheduledFuture<?>> heartbeatRef = new AtomicReference<>();
        Runnable cancelHeartbeat = () -> {
            ScheduledFuture<?> f = heartbeatRef.getAndSet(null);
            if (f != null && !f.isDone()) f.cancel(false);
        };

        heartbeatRef.set(HEARTBEAT_EXECUTOR.scheduleAtFixedRate(() -> {
            if (!disconnected.get()) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IllegalStateException | IOException e) {
                    disconnected.set(true);  // 写入失败 = 客户端断开
                }
            }
        }, 15, 15, TimeUnit.SECONDS));

        // 🔄 7. 注册断开回调（三重兜底）
        emitter.onCompletion(() -> {
            disconnected.set(true);
            cancelHeartbeat.run();
            releaseSemaphore.run();
            log.debug("SSE completed: agentId={}", id);
        });
        emitter.onTimeout(() -> {
            disconnected.set(true);
            cancelHeartbeat.run();
            releaseSemaphore.run();
            log.debug("SSE timeout: agentId={}", id);
        });
        emitter.onError(e -> {
            disconnected.set(true);
            cancelHeartbeat.run();
            releaseSemaphore.run();
            log.error("SSE error: agentId={}", id, e);
        });

        // 🪝 8. 创建流式 Hook
        StreamingHook streamingHook = new StreamingHook(emitter, disconnected);

        // 🤖 9. 构建带 Hook 的 Agent 副本（不修改原 Agent）
        ReActAgent agentWithHook = buildAgentWithHooks(baseAgent, streamingHook);

        // 🔍 10. RAG 增强（带超时 + 降级）
        String ragEnhancedMessage;
        if (request.isEnableRag()) {
            try {
                ragEnhancedMessage = CompletableFuture
                        .supplyAsync(() -> ragService.enhanceWithRag(id, request.getMessage()), RAG_EXECUTOR)
                        .get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                ragEnhancedMessage = request.getMessage();
                log.warn("RAG timeout, fallback to original message: agentId={}", id);
            } catch (Exception e) {
                ragEnhancedMessage = request.getMessage();
                log.warn("RAG error, fallback to original message: agentId={}", id, e);
            }
        } else {
            ragEnhancedMessage = request.getMessage();  // 禁用 RAG 时直传
        }

        // 📝 11. 构建发送给 Agent 的消息
        Msg msgForAgent = Msg.builder().textContent(ragEnhancedMessage).build();
        log.debug("📨 [SSE] stream start: agentId={}, userId={}", id, userId);

        // 🚀 12. 执行 Agent 调用（流式 + 超时 + 线程隔离）
        agentWithHook.call(msgForAgent)
                .publishOn(Schedulers.boundedElastic())  // 确保回调在弹性线程池执行
                .timeout(Duration.ofSeconds(180),
                        Mono.just(Msg.builder().textContent("请求超时，请稍后重试").build()))
                .subscribe(
                        // ✅ onNext: 收集响应并发送 done 信号
                        msg -> {
                            // 累计完整响应
                            if (msg != null && msg.getTextContent() != null) {
                                responseFragments.add(msg.getTextContent());
                            }
                            if (!disconnected.getAndSet(true)) {
                                try {
                                    emitter.send(SseEmitter.event().name("done").data(""));
                                    emitter.complete();
                                    log.info("📨 [SSE] completed: agentId={}", id);
                                } catch (IOException | IllegalStateException e) {
                                    log.warn("SSE done send error: agentId={}", id, e);
                                } finally {
                                    releaseSemaphore.run();
                                }
                            }
                            cancelHeartbeat.run();
                        },
                        // ❌ onError: 错误处理
                        error -> {
                            cancelHeartbeat.run();
                            // 保存错误时的部分响应
                            saveAssistantMessageAsync(id, userIdLong, tenantIdLong, finalSessionId, String.join("", new ArrayList<>(responseFragments)));
                            if (!disconnected.getAndSet(true)) {
                                try {
                                    emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                                    emitter.completeWithError(error);
                                } catch (IllegalStateException | IOException ignored) {}
                            }
                            releaseSemaphore.run();
                            log.error("SSE stream error: agentId={}", id, error);
                        },
                        // 🎯 onComplete: 流正常结束，异步保存助手消息
                        () -> {
                            disconnected.set(true);
                            cancelHeartbeat.run();
                            releaseSemaphore.run();
                            // 异步落库（不阻塞 SSE）
                            long latency = System.currentTimeMillis() - startTime;
                            requestDurationTimer.record(latency, TimeUnit.MILLISECONDS);  // 监控：请求耗时
                            saveAssistantMessageAsync(id, userIdLong, tenantIdLong, finalSessionId, String.join("", new ArrayList<>(responseFragments)));
                            log.debug("SSE onComplete: agentId={}, latency={}ms", id, latency);
                        }
                );

        return emitter;
    }

    /**
     * 异步保存助手消息（不阻塞 SSE）
     */
    private void saveAssistantMessageAsync(Long agentId, Long userId, Long tenantId, String sessionId, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        final String sid = sessionId;
        try {
            CHAT_SAVE_EXECUTOR.execute(() -> {
                try {
                    String messageType = detectMessageType(content);
                    ChatMessage assistantMsg = ChatMessage.builder()
                            .sessionId(sid)
                            .agentId(agentId)
                            .userId(userId)
                            .tenantId(tenantId)
                            .role("assistant")
                            .content(content)
                            .messageType(messageType)
                            .build();
                    chatMessageMapper.insert(assistantMsg);
                    log.debug("Assistant message saved: agentId={}, contentLength={}", agentId, content.length());
                } catch (Exception e) {
                    saveFailedCounter.increment();  // 监控：消息保存失败
                    log.error("Failed to save assistant message: agentId={}", agentId, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Save task rejected, executor may be full/shutdown: agentId={}", agentId, e);
        }
    }

    // ========== 以下为复用方法（与 GET 版本相同） ==========

    private ReActAgent buildAgentWithHooks(ReActAgent source, Hook... additionalHooks) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(source.getName())
                .sysPrompt(source.getSysPrompt())
                .model(source.getModel())
                .memory(source.getMemory())
                .toolkit(source.getToolkit())
                .maxIters(source.getMaxIters())
                .checkRunning(true);

        List<Hook> mergedHooks = new java.util.ArrayList<>(source.getHooks());
        java.util.Collections.addAll(mergedHooks, additionalHooks);
        builder.hooks(mergedHooks);
        return builder.build();
    }

    /**
     * 根据返回内容检测消息类型
     */
    private String detectMessageType(String content) {
        if (content == null || content.isEmpty()) {
            return "text";
        }
        if (content.contains("$$$markdown-report") || content.contains("report content:")) {
            return "markdown-report";
        }
        if (content.contains("$$$html-report") || content.contains("report content: $$$html")) {
            return "html-report";
        }
        if (content.contains("# ") || content.contains("## ") || content.contains("```")) {
            return "markdown-report";
        }
        if (content.contains("<html") || content.contains("</div>") || content.contains("</")) {
            return "html-report";
        }
        return "text";
    }

    /**
     * 安全解析 Long
     */
    private Long parseLongSafe(String str, Long defaultValue) {
        if (str == null || str.isBlank()) return defaultValue;
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse Long from '{}'", str);
            return defaultValue;
        }
    }

    private class StreamingHook implements Hook {  // 非静态，可访问 log
        private final SseEmitter emitter;
        private final AtomicBoolean disconnected;
        private static final Scheduler SSE_WRITE_SCHEDULER = Schedulers.single();

        public StreamingHook(SseEmitter emitter, AtomicBoolean disconnected) {
            this.emitter = emitter;
            this.disconnected = disconnected;
        }

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (disconnected.get()) return Mono.just(event);

            return Mono.defer(() -> {
                try {
                    if (event instanceof ReasoningChunkEvent chunkEvent) {
                        var incremental = chunkEvent.getIncrementalChunk();
                        if (incremental != null && incremental.getTextContent() != null) {
                            emitter.send(SseEmitter.event().name("reasoning").data(incremental.getTextContent()));
                        }
                    } else if (event instanceof ActingChunkEvent) {
                        emitter.send(SseEmitter.event().name("acting").data("执行工具..."));
                    } else if (event instanceof SummaryChunkEvent summaryEvent) {
                        var incremental = summaryEvent.getIncrementalChunk();
                        if (incremental != null && incremental.getTextContent() != null) {
                            emitter.send(SseEmitter.event().name("summary").data(incremental.getTextContent()));
                        }
                    }
                } catch (IllegalStateException e) {
                    disconnected.set(true);
                    if (log.isDebugEnabled()) {
                        log.debug("SseEmitter already completed (normal disconnect), skip sending");
                    }
                } catch (IOException e) {
                    disconnected.set(true);
                    log.warn("SseEmitter IO error (abnormal disconnect)", e);
                } catch (Exception e) {
                    disconnected.set(true);
                    log.warn("Streaming hook send error", e);
                }
                return Mono.just(event);
            }).subscribeOn(SSE_WRITE_SCHEDULER);
        }
    }
}
