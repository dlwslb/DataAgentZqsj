/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jldaren.agent.ai.datascope.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jldaren.agent.ai.datascope.controller.bean.StreamChatRequest;
import com.jldaren.agent.ai.datascope.controller.bean.StreamResponseDTO;
import com.jldaren.agent.ai.datascope.entity.ChatMessage;
import com.jldaren.agent.ai.datascope.mapper.ChatMessageMapper;
import com.jldaren.agent.ai.datascope.registry.AgentScopeRegistry;
import com.jldaren.agent.ai.datascope.service.ChatSessionService;
import com.jldaren.agent.ai.datascope.service.RagService;
import com.jldaren.agent.ai.datascope.service.UserInfoService;
import com.jldaren.agent.ai.datascope.util.MessageFormatDetector;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.core.Disposable;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentScope SSE Controller - WebFlux 版本
 * 使用 Flux<ServerSentEvent> 实现真正的响应式流式输出
 */
@Slf4j
@RestController
@RequestMapping("/api/scope/agent")
@Tag(name = "AgentScope Agent", description = "AgentScope 智能体管理接口")
public class AgentScopeSseController {

    // ========== 配置项 ==========
    @Value("${sse.timeout-ms:180000}")
    private long sseTimeoutMs;

    @Value("${heartbeat.interval-seconds:15}")
    private int heartbeatIntervalSeconds;

    @Value("${rag.timeout-seconds:2}")
    private int ragTimeoutSeconds;

    @Value("${agent.call.timeout-seconds:180}")
    private int agentCallTimeoutSeconds;

    @Value("${request.max-message-length:4000}")
    private int maxMessageLength;

    @Value("${agent.hook.flush-delay-ms:100}")
    private int hookFlushDelayMs;

    @Value("${logging.sse.fragment.sample-rate:0.1}")
    private double sseFragmentSampleRate;

    private final ObjectMapper jsonMapper;
    private final AgentScopeRegistry agentRegistry;
    private final RagService ragService;
    private final ChatSessionService chatSessionService;
    private final ChatMessageMapper chatMessageMapper;
    private final ExecutorService ragExecutor;
    private final ExecutorService chatSaveExecutor;
    private final Semaphore sseSemaphore;
    private final MessageFormatDetector messageFormatDetector;

    @Autowired
    private UserInfoService userInfoService;

    // 监控指标
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

    @PostConstruct
    public void validateConfig() {
        if (hookFlushDelayMs < 0 || hookFlushDelayMs > 1000) {
            log.warn("Configuration warning: agent.hook.flush-delay-ms={} is out of reasonable range [0, 1000]", hookFlushDelayMs);
        }
    }

    public AgentScopeSseController(
            ObjectMapper jsonMapper,
            AgentScopeRegistry agentRegistry,
            RagService ragService,
            ChatSessionService chatSessionService,
            ChatMessageMapper chatMessageMapper,
            @Qualifier("ragExecutor") ExecutorService ragExecutor,
            @Qualifier("chatSaveExecutor") ExecutorService chatSaveExecutor,
            @Value("${sse.max-concurrent:100}") int maxConcurrent) {

        this.jsonMapper = jsonMapper;
        this.agentRegistry = agentRegistry;
        this.ragService = ragService;
        this.chatSessionService = chatSessionService;
        this.chatMessageMapper = chatMessageMapper;
        this.ragExecutor = ragExecutor;
        this.chatSaveExecutor = chatSaveExecutor;
        this.sseSemaphore = new Semaphore(maxConcurrent);
        this.messageFormatDetector = new MessageFormatDetector();
    }

    /**
     * SSE 流式聊天 - WebFlux 版本
     * 使用 Flux<ServerSentEvent> 实现真正的响应式流
     */
    @PostMapping(value = "/{id}/chat/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "SSE流式聊天(POST)", description = "SSE流式返回Agent回复，支持推理过程实时推送")
    public Flux<ServerSentEvent<String>> chatStreamPost(
            @PathVariable Long id,
            @RequestBody @Valid StreamChatRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "Tenant-ID", required = false) String tenantIdHeader) {

        // 从 Authorization header 中提取 token
        UserInfoService.TokenUserInfo user = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            user = userInfoService.getUserInfoFromToken(token);
            if (user == null) {
                log.warn("无法解析 token，token 可能无效或已过期");
            }
        }

        String messageId = UUID.randomUUID().toString();
        MDC.put("messageId", messageId);
        MDC.put("agentId", String.valueOf(id));

        // 获取用户信息
        String rawUserId = userIdHeader != null ? userIdHeader : "anonymous";
        String rawTenantId = tenantIdHeader != null ? tenantIdHeader : "default";
        String finalUserId;
        String finalTenantId;
        if (user != null) {
            finalUserId = String.valueOf(user.getId());
            finalTenantId = String.valueOf(user.getTenantId());
        } else {
            finalUserId = rawUserId;
            finalTenantId = rawTenantId;
        }
        final String userId = finalUserId;
        final String tenantId = finalTenantId;
        MDC.put("userId", userId);

        // 信号量控制并发
        try {
            if (!sseSemaphore.tryAcquire(1, TimeUnit.SECONDS)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务器繁忙，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务器繁忙，请稍后重试");
        }

        // 验证消息长度
        if (request.getMessage() == null || request.getMessage().length() > maxMessageLength) {
            sseSemaphore.release();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "message too long (max " + maxMessageLength + " chars)");
        }

        activeSseConnections.incrementAndGet();
        sseStartCounter.increment();

        // 创建会话并保存用户消息
        String sessionId = chatSessionService.createSessionAndSaveUserMsg(
                id, userId, tenantId, request.getSessionId(), request.getMessage());
        Long userIdLong = parseLongSafe(userId, null);
        Long tenantIdLong = parseLongSafe(tenantId, null);

        // 构建响应式流
        return Flux.<ServerSentEvent<String>>create(sink -> {
            AtomicReference<Disposable> heartbeatRef = new AtomicReference<>();
            try {
                ReActAgent baseAgent = agentRegistry.getAgentWithLongTermMemory(id, userId, sessionId, tenantId);
                if (baseAgent == null) {
                    sink.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found or not published: " + id));
                    sseSemaphore.release();
                    activeSseConnections.decrementAndGet();
                    return;
                }

                // 启动心跳保活
                Disposable heartbeatDisposable = setupHeartbeat(sink, messageId);
                heartbeatRef.set(heartbeatDisposable);

                // RAG 增强
                Mono<String> messageMono = request.isEnableRag()
                        ? Mono.fromFuture(() -> CompletableFuture
                                .supplyAsync(() -> ragService.enhanceWithRag(id, userIdLong, tenantIdLong, request.getMessage(), 5), ragExecutor)
                                .orTimeout(ragTimeoutSeconds, TimeUnit.SECONDS))
                        .onErrorResume(e -> {
                            log.warn("RAG fallback: agentId={}", id, e);
                            return Mono.just(request.getMessage());
                        })
                        : Mono.just(request.getMessage());

                messageMono.subscribe(ragEnhancedMessage -> {
                    Msg msgForAgent = Msg.builder().textContent(ragEnhancedMessage).build();
                    log.debug("📨 [SSE] stream start: agentId={}, userId={}, messageId={}", id, userId, messageId);

                    // 创建 StreamingHook
                    StreamingHook streamingHook = new StreamingHook(sink, messageId, jsonMapper,
                            messageFormatDetector, sseFragmentSampleRate);
                    ReActAgent agentWithHook = buildAgentWithHooks(baseAgent, streamingHook);

                    // 执行 Agent 调用
                    agentWithHook.call(msgForAgent)
                            .timeout(Duration.ofSeconds(agentCallTimeoutSeconds),
                                    Mono.just(Msg.builder().textContent("请求超时，请稍后重试").build()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe(
                                    msg -> {
                                        // 处理最终响应
                                        handleAgentResponse(msg, sink, id, messageId, sessionId, userIdLong, tenantIdLong);
                                        disposeHeartbeat(heartbeatRef.get(), messageId);
                                        sink.complete();
                                    },
                                    error -> {
                                        handleAgentError(error, sink, id, messageId, sessionId, userIdLong, tenantIdLong);
                                        disposeHeartbeat(heartbeatRef.get(), messageId);
                                        sink.error(error);
                                    }
                            );
                });

            } catch (Exception e) {
                log.error("Unexpected error in chatStreamPost: agentId={}, messageId={}", id, messageId, e);
                disposeHeartbeat(heartbeatRef.get(), messageId);
                sink.error(e);
            }
        })
        .doFinally(signalType -> {
            sseSemaphore.release();
            activeSseConnections.decrementAndGet();
            MDC.remove("messageId");
            MDC.remove("agentId");
            MDC.remove("userId");
            log.info("✅ [SSE] finished: agentId={}, signal={}, messageId={}", id, signalType, messageId);
        })
        .timeout(Duration.ofMillis(sseTimeoutMs));
    }

    private void handleAgentResponse(Msg msg, FluxSink<ServerSentEvent<String>> sink, Long agentId,
                                     String messageId, String sessionId, Long userId, Long tenantId) {
        try {
            String content = msg != null ? msg.getTextContent() : "";
            
            // 发送 done 事件
            StreamResponseDTO doneResp = StreamResponseDTO.builder()
                    .content("").messageType("text").messageId(messageId).eventType("done").build();
            sink.next(ServerSentEvent.<String>builder()
                    .event("done")
                    .data(jsonMapper.writeValueAsString(doneResp))
                    .build());

            log.info("📨 [SSE] done sent: agentId={}, messageId={}, responseLength={}",
                    agentId, messageId, content != null ? content.length() : 0);

            // 异步保存助手消息
            if (content != null && !content.isBlank()) {
                saveAssistantMessageAsync(agentId, userId, tenantId, sessionId, content);
            }
        } catch (Exception e) {
            log.warn("SSE done send error: agentId={}, messageId={}", agentId, messageId, e);
        }
    }

    private void handleAgentError(Throwable error, FluxSink<ServerSentEvent<String>> sink, Long agentId,
                                  String messageId, String sessionId, Long userId, Long tenantId) {
        try {
            StreamResponseDTO errorResp = StreamResponseDTO.builder()
                    .content(error.getMessage()).messageType("text").messageId(messageId).eventType("error").build();
            sink.next(ServerSentEvent.<String>builder()
                    .event("error")
                    .data(jsonMapper.writeValueAsString(errorResp))
                    .build());
        } catch (Exception e) {
            log.warn("SSE error send failed: agentId={}, messageId={}", agentId, messageId);
        }
        log.error("SSE stream error: agentId={}, messageId={}", agentId, messageId, error);
    }

    /**
     * 安全释放心跳资源
     */
    private void disposeHeartbeat(Disposable heartbeatDisposable, String messageId) {
        if (heartbeatDisposable != null && !heartbeatDisposable.isDisposed()) {
            try {
                heartbeatDisposable.dispose();
                log.debug("💔 [SSE] heartbeat disposed: messageId={}", messageId);
            } catch (Exception e) {
                log.warn("Failed to dispose heartbeat: messageId={}", messageId, e);
            }
        }
    }

    /**
     * 设置心跳保活 - WebFlux 版本
     * 使用 interval 定期发送注释类型的心跳事件
     */
    private Disposable setupHeartbeat(FluxSink<ServerSentEvent<String>> sink, String messageId) {
        long heartbeatIntervalMs = heartbeatIntervalSeconds * 1000L;
        
        return Flux.interval(Duration.ofMillis(heartbeatIntervalMs))
                .subscribeOn(Schedulers.single())
                .subscribe(
                        tick -> {
                            try {
                                // 发送 SSE 注释作为心跳（不触发前端 onmessage）
                                sink.next(ServerSentEvent.<String>builder()
                                        .comment("heartbeat")
                                        .build());
                                log.debug("💓 [SSE] heartbeat sent: messageId={}, tick={}", messageId, tick);
                            } catch (Exception e) {
                                log.debug("💔 [SSE] heartbeat failed (client disconnected?): messageId={}", messageId);
                            }
                        },
                        error -> log.warn("Heartbeat error: messageId={}", messageId, error)
                );
    }

    private ReActAgent buildAgentWithHooks(ReActAgent source, Hook additionalHook) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(source.getName())
                .sysPrompt(source.getSysPrompt())
                .model(source.getModel())
                .memory(source.getMemory())
                .toolkit(source.getToolkit())
                .maxIters(source.getMaxIters())
                .checkRunning(true);
        
        // 合并原有的 Hooks 和新的 Hook
        List<Hook> mergedHooks = new ArrayList<>(source.getHooks());
        mergedHooks.add(additionalHook);
        return builder.hooks(mergedHooks).build();
    }

    private void saveAssistantMessageAsync(Long agentId, Long userId, Long tenantId, 
                                           String sessionId, String content) {
        chatSaveExecutor.submit(() -> {
            try {
                String messageType = detectMessageType(content);
                ChatMessage assistantMsg = ChatMessage.builder()
                        .sessionId(sessionId)
                        .agentId(agentId)
                        .userId(userId)
                        .tenantId(tenantId)
                        .role("assistant")
                        .content(content)
                        .messageType(messageType)
                        .build();
                chatMessageMapper.insert(assistantMsg);
                log.debug("💾 [SSE] assistant message saved: sessionId={}, length={}", sessionId, content.length());
            } catch (Exception e) {
                log.error("Failed to save assistant message: sessionId={}", sessionId, e);
                saveFailedCounter.increment();
            }
        });
    }

    private String detectMessageType(String content) {
        if (content == null || content.isBlank()) return "text";
        
        // 检测 JSON 格式
        if (content.trim().startsWith("{") || content.trim().startsWith("[")) {
            return "json";
        }
        
        // 检测 Markdown 报告（包含 Markdown 标题或报告特征）
        if (content.contains("# ") || content.contains("## ") || content.contains("### ")) {
            return "markdown-report";
        }
        
        // 检测 HTML 表格
        if (content.contains("<table>") || content.contains("|")) {
            return "table";
        }
        
        return "text";
    }

    private Long parseLongSafe(String value, Long defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ========== StreamingHook：流式事件钩子 ==========
    private class StreamingHook implements Hook {
        private final FluxSink<ServerSentEvent<String>> sink;
        private final String messageId;
        private final ObjectMapper localMapper;
        private final MessageFormatDetector formatDetector;
        private final double sampleRate;
        private final AtomicReference<String> globalMessageType = new AtomicReference<>();

        public StreamingHook(FluxSink<ServerSentEvent<String>> sink, String messageId,
                             ObjectMapper localMapper, MessageFormatDetector formatDetector, double sampleRate) {
            this.sink = sink;
            this.messageId = messageId;
            this.localMapper = localMapper;
            this.formatDetector = formatDetector;
            this.sampleRate = sampleRate;
        }

        private String resolveGlobalMessageType(String content) {
            return globalMessageType.updateAndGet(t ->
                    t != null ? t : formatDetector.detect(content)
            );
        }

        @Override
        public <T extends HookEvent> Mono<T> onEvent(T event) {
            return Mono.defer(() -> {
                try {
                    String eventName = null;
                    String content = null;

                    if (event instanceof ReasoningChunkEvent chunkEvent) {
                        eventName = "reasoning";
                        var chunk = chunkEvent.getIncrementalChunk();
                        if (chunk != null) content = chunk.getTextContent();
                    } else if (event instanceof ActingChunkEvent) {
                        eventName = "acting"; 
                        content = "执行工具...";
                    } else if (event instanceof SummaryChunkEvent summaryEvent) {
                        eventName = "summary";
                        var chunk = summaryEvent.getIncrementalChunk();
                        if (chunk != null) content = chunk.getTextContent();
                    } else if (event instanceof PostCallEvent postCallEvent) {
                        Msg msg = postCallEvent.getFinalMessage();
                        if (msg != null && msg.getTextContent() != null) {
                            eventName = "final";
                            content = msg.getTextContent();
                        }
                    }

                    if (content != null && !content.isEmpty()) {
                        String messageType = resolveGlobalMessageType(content);
                        StreamResponseDTO response = StreamResponseDTO.builder()
                                .content(content).messageType(messageType).messageId(messageId).eventType(eventName).build();
                        
                        sink.next(ServerSentEvent.<String>builder()
                                .event(eventName)
                                .data(localMapper.writeValueAsString(response))
                                .build());

                        if (log.isDebugEnabled() && Math.random() < sampleRate) {
                            String safeContent = content.length() > 100 ? content.substring(0, 100) + "..." : content;
                            log.debug("SSE fragment sent: messageId={}, eventType={}, contentPreview={}",
                                    messageId, eventName, safeContent);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Streaming hook send error: messageId={}", messageId, e);
                }
                return Mono.just(event);
            }).publishOn(Schedulers.single());
        }
    }
}
