package com.jldaren.agent.ai.datascope.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jldaren.agent.ai.datascope.controller.bean.StreamChatRequest;
import com.jldaren.agent.ai.datascope.controller.bean.StreamResponseDTO;
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
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.message.Msg;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/scope/agent")
@Tag(name = "AgentScope Agent", description = "AgentScope 智能体管理接口")
public class AgentScopeSseController {

    // ========== 配置项 ==========
    @Value("${sse.timeout-ms:180000}")
    private long sseTimeoutMs;
    @Value("${rag.timeout-seconds:2}")
    private int ragTimeoutSeconds;
    @Value("${agent.call.timeout-seconds:180}")
    private int agentCallTimeoutSeconds;
    @Value("${request.max-message-length:4000}")
    private int maxMessageLength;
    @Value("${heartbeat.interval-seconds:15}")
    private int heartbeatIntervalSeconds;

    // ========== JSON 序列化 ==========
    private final ObjectMapper jsonMapper;

    // ========== 预编译正则 ==========
    private static final Pattern MARKDOWN_LIST_PATTERN = Pattern.compile("(?m)^\\s*[-*+]\\s+");
    private static final Pattern MARKDOWN_ORDERED_LIST_PATTERN = Pattern.compile("(?m)^\\s*\\d+\\.\\s+");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile(".*\\[.+\\]\\(.+\\).*");
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile(".*!\\[.+\\]\\(.+\\).*");

    // ========== 依赖注入 ==========
    private final AgentScopeRegistry agentRegistry;
    private final RagService ragService;
    private final ChatSessionService chatSessionService;
    private final ChatMessageMapper chatMessageMapper;
    private final ScheduledExecutorService heartbeatExecutor;
    private final ExecutorService ragExecutor;
    private final ExecutorService chatSaveExecutor;
    private final Semaphore sseSemaphore;

    // ========== 监控指标 ==========
    private final Counter sseStartCounter = Metrics.counter("sse.connection.started");
    private final Counter saveFailedCounter = Metrics.counter("message.save.failed");
    private final Timer requestDurationTimer = Metrics.timer("agent.request.duration");
    private final AtomicInteger activeSseConnections = new AtomicInteger(0);

    {
        try {
            Gauge.builder("sse.connections.active", activeSseConnections, AtomicInteger::get)
                    .description("Current active SSE connections")
                    .register(Metrics.globalRegistry);
        } catch (Exception e) {
            log.warn("Failed to register SSE connections gauge (may already exist)", e);
        }
    }

    public AgentScopeSseController(
            ObjectMapper jsonMapper,
            AgentScopeRegistry agentRegistry,
            RagService ragService,
            ChatSessionService chatSessionService,
            ChatMessageMapper chatMessageMapper,
            @Qualifier("heartbeatExecutor") ScheduledExecutorService heartbeatExecutor,
            @Qualifier("ragExecutor") ExecutorService ragExecutor,
            @Qualifier("chatSaveExecutor") ExecutorService chatSaveExecutor,
            @Value("${sse.max-concurrent:100}") int maxConcurrent) {

        this.jsonMapper = jsonMapper;
        this.agentRegistry = agentRegistry;
        this.ragService = ragService;
        this.chatSessionService = chatSessionService;
        this.chatMessageMapper = chatMessageMapper;
        this.heartbeatExecutor = heartbeatExecutor;
        this.ragExecutor = ragExecutor;
        this.chatSaveExecutor = chatSaveExecutor;
        this.sseSemaphore = new Semaphore(maxConcurrent);
    }

    @PostMapping(value = "/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "SSE流式聊天(POST)", description = "SSE流式返回Agent回复，支持推理过程实时推送")
    public SseEmitter chatStreamPost(
            @PathVariable Long id,
            @RequestBody @Valid StreamChatRequest request,
            @RequestHeader(value = "User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "Tenant-ID", required = false) String tenantIdHeader) {

        try {
            if (!sseSemaphore.tryAcquire(1, TimeUnit.SECONDS)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务器繁忙，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务器繁忙，请稍后重试");
        }

        if (request.getMessage() == null || request.getMessage().length() > maxMessageLength) {
            sseSemaphore.release();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "message too long (max " + maxMessageLength + " chars)");
        }

        String userId = userIdHeader != null ? userIdHeader : "anonymous";
        String tenantId = tenantIdHeader != null ? tenantIdHeader : "default";
        String messageId = UUID.randomUUID().toString();

        MDC.put("messageId", messageId);
        MDC.put("agentId", String.valueOf(id));
        MDC.put("userId", userId);

        try {
            ReActAgent baseAgent = agentRegistry.getAgentWithLongTermMemory(id, userId, request.getSessionId(), tenantId);
            if (baseAgent == null) {
                sseSemaphore.release();
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found or not published: " + id);
            }

            final String finalSessionId = chatSessionService.createSessionAndSaveUserMsg(
                    id, userId, tenantId, request.getSessionId(), request.getMessage());
            Long userIdLong = parseLongSafe(userId, null);
            Long tenantIdLong = parseLongSafe(tenantId, null);

            SseEmitter emitter = new SseEmitter(sseTimeoutMs);
            activeSseConnections.incrementAndGet();
            sseStartCounter.increment();

            // 🔑 优化：ResourceContext 构造函数传入 cleanupCallback，统一清理 MDC
            ResourceContext ctx = new ResourceContext(emitter, messageId, () -> {
                sseSemaphore.release();
                activeSseConnections.decrementAndGet();
                MDC.clear();
            });

            setupEmitterCallbacks(emitter, ctx);
            setupHeartbeat(emitter, ctx);

            StreamingHook streamingHook = new StreamingHook(emitter, ctx, messageId, jsonMapper);
            ReActAgent agentWithHook = buildAgentWithHooks(baseAgent, streamingHook);

            executeAgentCall(agentWithHook, request, id, ctx, finalSessionId, userIdLong, tenantIdLong);

            return emitter;

        } catch (ResponseStatusException e) {
            MDC.clear();
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error in chatStreamPost: agentId={}, messageId={}", id, messageId, e);
            MDC.clear();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
        }
    }

    private void setupEmitterCallbacks(SseEmitter emitter, ResourceContext ctx) {
        Runnable cleanup = () -> {
            // 🔑 优化：断开时立即取消心跳并释放资源（MDC 在 cleanupCallback 中清理）
            ctx.cancelHeartbeat();
            ctx.releaseResources();
        };
        emitter.onCompletion(() -> { ctx.markDisconnected(); cleanup.run(); });
        emitter.onTimeout(() -> { ctx.markDisconnected(); log.debug("SSE timeout: {}", ctx.getMessageId()); cleanup.run(); });
        emitter.onError(e -> { ctx.markDisconnected(); log.error("SSE error: {}", ctx.getMessageId(), e); cleanup.run(); });
    }

    private void setupHeartbeat(SseEmitter emitter, ResourceContext ctx) {
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!ctx.isDisconnected()) {
                try { emitter.send(SseEmitter.event().comment("heartbeat")); }
                catch (IllegalStateException | IOException e) { ctx.markDisconnected(); }
            }
        }, heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
        // ✅ 修复5：保存 heartbeatFuture 供立即取消
        ctx.setHeartbeatFuture(heartbeat);
    }

    private void executeAgentCall(ReActAgent agent, StreamChatRequest request, Long agentId,
                                  ResourceContext ctx, String sessionId, Long userId, Long tenantId) {

        Mono<String> messageMono = request.isEnableRag()
                ? Mono.fromFuture(() -> CompletableFuture
                        .supplyAsync(() -> ragService.enhanceWithRag(agentId, request.getMessage()), ragExecutor)
                        .orTimeout(ragTimeoutSeconds, TimeUnit.SECONDS))
                .onErrorResume(e -> {
                    log.warn("RAG fallback: agentId={}", agentId, e);
                    return Mono.just(request.getMessage());
                })
                : Mono.just(request.getMessage());

        messageMono.flatMap(ragEnhancedMessage -> {
            Msg msgForAgent = Msg.builder().textContent(ragEnhancedMessage).build();
            log.debug("📨 [SSE] stream start: agentId={}, userId={}, messageId={}", agentId, userId, ctx.getMessageId());
            return agent.call(msgForAgent)
                    .publishOn(Schedulers.boundedElastic())
                    .timeout(Duration.ofSeconds(agentCallTimeoutSeconds),
                            Mono.just(Msg.builder().textContent("请求超时，请稍后重试").build()));
        }).subscribe(
                // 🔑 修复：onNext 负责发送 done 和保存消息
                msg -> {
                    // 🔑 关键修复：立即标记，防止 onComplete 重复执行
                    ctx.markDisconnected();
                    handleAgentResponse(msg, ctx, agentId, sessionId, userId, tenantId);
                },
                error -> handleAgentError(error, ctx, agentId, sessionId, userId, tenantId),
                // 🔑 修复：onComplete 作为兜底，通常不会执行
                () -> handleAgentComplete(ctx, agentId, sessionId, userId, tenantId)
        );
    }

    private void handleAgentResponse(Msg msg, ResourceContext ctx, Long agentId, 
                                    String sessionId, Long userId, Long tenantId) {
        // 🔑 优化：延迟发送 done，等待 Hook 事件队列处理完成
        Schedulers.single().schedule(() -> {
            try {
                // 如果 Hook 没有收集到内容，使用 msg 的内容作为兜底
                String finalContent = ctx.getResponseContent();
                if (finalContent.isEmpty() && msg != null && msg.getTextContent() != null) {
                    ctx.appendResponse(msg.getTextContent());
                    finalContent = msg.getTextContent();
                }
                
                StreamResponseDTO doneResp = StreamResponseDTO.builder()
                        .content("").messageType("text").messageId(ctx.getMessageId()).eventType("done").build();
                ctx.getEmitter().send(SseEmitter.event().name("done").data(jsonMapper.writeValueAsString(doneResp)));
                log.info("📨 [SSE] done sent: agentId={}, messageId={}, responseLength={}", 
                        agentId, ctx.getMessageId(), finalContent.length());
                
                // 🔑 优化：在发送 done 后立即保存消息并释放资源
                if (!finalContent.isBlank()) {
                    saveAssistantMessageAsync(agentId, userId, tenantId, sessionId, finalContent);
                }
                long latency = System.currentTimeMillis() - ctx.getStartTime();
                requestDurationTimer.record(latency, TimeUnit.MILLISECONDS);
                ctx.releaseResources();
                log.info("✅ [SSE] finished: agentId={}, latency={}ms, messageId={}", 
                        agentId, latency, ctx.getMessageId());
            } catch (IOException | IllegalStateException e) {
                log.warn("SSE done send error: agentId={}, messageId={}", agentId, ctx.getMessageId(), e);
                ctx.releaseResources();
            }
        }, 100, TimeUnit.MILLISECONDS); // 延迟 100ms 确保 Hook 事件发送完成
    }

    private void handleAgentError(Throwable error, ResourceContext ctx, Long agentId,
                                  String sessionId, Long userId, Long tenantId) {
        // ✅ 修复4：保存前检查内容是否为空
        String content = ctx.getResponseContent();
        if (content != null && !content.isBlank()) {
            saveAssistantMessageAsync(agentId, userId, tenantId, sessionId, content);
        }
        if (!ctx.isDisconnected()) {
            try {
                StreamResponseDTO errorResp = StreamResponseDTO.builder()
                        .content(error.getMessage()).messageType("text").messageId(ctx.getMessageId()).eventType("error").build();
                ctx.getEmitter().send(SseEmitter.event().name("error").data(jsonMapper.writeValueAsString(errorResp)));
                ctx.getEmitter().completeWithError(error);
            } catch (IllegalStateException | IOException ignored) {}
        }
        ctx.releaseResources();
        log.error("SSE stream error: agentId={}, messageId={}", agentId, ctx.getMessageId(), error);
    }

    private void handleAgentComplete(ResourceContext ctx, Long agentId, String sessionId, Long userId, Long tenantId) {
        // 🔑 关键修复：onComplete 不再保存消息，只负责资源清理
        // 消息保存统一由 onNext (handleAgentResponse) 或 onError (handleAgentError) 处理
        if (ctx.isDisconnected()) {
            log.debug("SSE onComplete skipped (already handled): agentId={}, messageId={}", 
                    agentId, ctx.getMessageId());
            return;
        }
        
        // 兜底：记录指标和释放资源，但不保存消息
        long latency = System.currentTimeMillis() - ctx.getStartTime();
        requestDurationTimer.record(latency, TimeUnit.MILLISECONDS);
        ctx.releaseResources();
        log.debug("SSE onComplete (cleanup only): agentId={}, latency={}ms, messageId={}", 
                agentId, latency, ctx.getMessageId());
    }

    private void saveAssistantMessageAsync(Long agentId, Long userId, Long tenantId, String sessionId, String content) {
        // ✅ 修复4：双重空检查
        if (content == null || content.isBlank()) {
            log.debug("Skip saving empty message: agentId={}, sessionId={}", agentId, sessionId);
            return;
        }
        try {
            chatSaveExecutor.execute(() -> {
                try {
                    ChatMessage assistantMsg = ChatMessage.builder()
                            .sessionId(sessionId).agentId(agentId).userId(userId).tenantId(tenantId)
                            .role("assistant").content(content).messageType(detectMessageType(content)).build();
                    chatMessageMapper.insert(assistantMsg);
                } catch (Exception e) {
                    saveFailedCounter.increment();
                    log.error("Failed to save assistant message: agentId={}, sessionId={}", agentId, sessionId, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Save task rejected: agentId={}", agentId);
        }
    }

    private ReActAgent buildAgentWithHooks(ReActAgent source, Hook... additionalHooks) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(source.getName()).sysPrompt(source.getSysPrompt()).model(source.getModel())
                .memory(source.getMemory()).toolkit(source.getToolkit()).maxIters(source.getMaxIters()).checkRunning(true);
        List<Hook> mergedHooks = new ArrayList<>(source.getHooks());
        Collections.addAll(mergedHooks, additionalHooks);
        return builder.hooks(mergedHooks).build();
    }

    private String detectMessageType(String content) {
        if (content == null || content.isEmpty()) return "text";
        if (content.contains("$$$html-report") || content.contains("<html") || content.contains("</")) return "html-report";
        if (content.contains("$$$markdown-report") || content.contains("report content:")) return "markdown-report";
        if (content.contains("```")) return "markdown";
        if (content.contains("######") || content.contains("#####") || content.contains("####") ||
                content.contains("###") || content.contains("##") || content.contains("# ")) return "markdown";
        if (content.contains("**") || content.contains("__") || content.contains("* ") || content.contains("_ ")) return "markdown";
        if (MARKDOWN_LIST_PATTERN.matcher(content).find() || MARKDOWN_ORDERED_LIST_PATTERN.matcher(content).find()) return "markdown";
        if (MARKDOWN_LINK_PATTERN.matcher(content).find() || MARKDOWN_IMAGE_PATTERN.matcher(content).find()) return "markdown";
        if (content.contains("<html") || content.contains("</div>") || content.contains("</p>") || content.contains("<table") || content.contains("<br")) return "html-report";
        return "text";
    }

    private Long parseLongSafe(String str, Long defaultValue) {
        if (str == null || str.isBlank()) return defaultValue;
        try { return Long.parseLong(str); } catch (NumberFormatException e) { return defaultValue; }
    }

    // ========== ResourceContext：资源管理上下文 ==========
    private static class ResourceContext {
        private final SseEmitter emitter;
        private final String messageId;
        private final long startTime;
        private final AtomicBoolean disconnected;
        private final AtomicBoolean resourcesReleased;
        private final StringBuilder responseBuffer;
        private final Object bufferLock;
        private volatile ScheduledFuture<?> heartbeatFuture; // ✅ 修复5：volatile 保证可见性
        private final Runnable cleanupCallback;

        public ResourceContext(SseEmitter emitter, String messageId, Runnable cleanupCallback) {
            this.emitter = emitter;
            this.messageId = messageId;
            this.cleanupCallback = cleanupCallback;
            this.startTime = System.currentTimeMillis();
            this.disconnected = new AtomicBoolean(false);
            this.resourcesReleased = new AtomicBoolean(false);
            this.responseBuffer = new StringBuilder();
            this.bufferLock = new Object();
        }

        public boolean isDisconnected() { return disconnected.get(); }
        public void markDisconnected() { disconnected.set(true); }
        public void setHeartbeatFuture(ScheduledFuture<?> f) { this.heartbeatFuture = f; }

        // ✅ 修复5：取消时立即执行，不等待
        public void cancelHeartbeat() {
            ScheduledFuture<?> f = heartbeatFuture;
            if (f != null && !f.isDone()) {
                f.cancel(false);
            }
        }
        public String getMessageId() { return messageId; }
        public long getStartTime() { return startTime; }
        public SseEmitter getEmitter() { return emitter; }

        public void appendResponse(String content) {
            if (content != null) synchronized (bufferLock) { responseBuffer.append(content); }
        }
        public String getResponseContent() {
            synchronized (bufferLock) { return responseBuffer.toString(); }
        }

        public void releaseResources() {
            if (resourcesReleased.compareAndSet(false, true)) {
                cancelHeartbeat();
                if (cleanupCallback != null) cleanupCallback.run();
                log.debug("Resources released for messageId: {}", messageId);
            }
        }
    }

    // ========== StreamingHook：流式事件钩子 ==========
    private class StreamingHook implements Hook {
        private final SseEmitter emitter;
        private final ResourceContext resourceCtx;
        private final String messageId;
        private final ObjectMapper localMapper;

        // ✅ 修复2：volatile 足够，单线程调度无需 ReentrantLock
        private volatile String globalMessageType = null;

        public StreamingHook(SseEmitter emitter, ResourceContext resourceCtx, String messageId, ObjectMapper localMapper) {
            this.emitter = emitter;
            this.resourceCtx = resourceCtx;
            this.messageId = messageId;
            this.localMapper = localMapper;
        }

        private String resolveGlobalMessageType(String content) {
            if (globalMessageType != null) return globalMessageType;
            // ✅ 修复2：DCL + volatile，单线程场景下足够安全
            synchronized (this) {
                if (globalMessageType == null) {
                    globalMessageType = detectMessageType(content);
                    log.debug("🎯 Resolved global messageType: {} for messageId: {}", globalMessageType, messageId);
                }
                return globalMessageType;
            }
        }

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            if (resourceCtx.isDisconnected()) return Mono.just(event);

            return Mono.defer(() -> {
                try {
                    String eventName = null;
                    String content = null;

                    if (event instanceof ReasoningChunkEvent chunkEvent) {
                        eventName = "reasoning";
                        var chunk = chunkEvent.getIncrementalChunk();
                        if (chunk != null) content = chunk.getTextContent();
                    } else if (event instanceof ActingChunkEvent) {
                        eventName = "acting"; content = "执行工具...";
                    } else if (event instanceof SummaryChunkEvent summaryEvent) {
                        eventName = "summary";
                        var chunk = summaryEvent.getIncrementalChunk();
                        if (chunk != null) content = chunk.getTextContent();
                    } else if (event instanceof PostCallEvent postCallEvent) {
                        // 🔑 兜底：捕获 Agent 最终返回的消息
                        Msg msg = postCallEvent.getFinalMessage();
                        if (msg != null && msg.getTextContent() != null) {
                            eventName = "final";
                            content = msg.getTextContent();
                        }
                    }

                    // ✅ 修复1：实时收集流式片段到 responseBuffer（PostCallEvent 除外）
                    if (content != null && !content.isEmpty()) {
                        // 只有非 PostCallEvent 才追加到 buffer
                        if (!(event instanceof PostCallEvent)) {
                            resourceCtx.appendResponse(content);
                        }

                        String messageType = resolveGlobalMessageType(content);
                        StreamResponseDTO response = StreamResponseDTO.builder()
                                .content(content).messageType(messageType).messageId(messageId).eventType(eventName).build();
                        emitter.send(SseEmitter.event().name(eventName).data(localMapper.writeValueAsString(response)));

                        if (log.isDebugEnabled() && Math.random() < 0.1) {
                            log.debug("SSE fragment sent: messageId={}, eventType={}, contentLen={}", messageId, eventName, content.length());
                        }
                    }
                } catch (IllegalStateException e) { resourceCtx.markDisconnected(); }
                catch (IOException e) { resourceCtx.markDisconnected(); log.warn("SseEmitter IO error: {}", messageId, e); }
                catch (Exception e) { resourceCtx.markDisconnected(); log.warn("Streaming hook send error: {}", messageId, e); }
                return Mono.just(event);
            }).publishOn(Schedulers.single());
        }
    }
}