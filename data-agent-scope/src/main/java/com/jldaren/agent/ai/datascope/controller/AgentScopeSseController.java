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
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Value("${agent.hook.flush-delay-ms:100}")
    private int hookFlushDelayMs;

    @Value("${logging.sse.fragment.sample-rate:0.1}")
    private double sseFragmentSampleRate;

    private final ObjectMapper jsonMapper;

    // 依赖注入
    private final AgentScopeRegistry agentRegistry;
    private final RagService ragService;
    private final ChatSessionService chatSessionService;
    private final ChatMessageMapper chatMessageMapper;
    private final ScheduledExecutorService heartbeatExecutor;
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
    private final Counter emptyResponseCounter = Metrics.counter("agent.response.empty");
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
        long heartbeatMs = heartbeatIntervalSeconds * 1000L;
        if (heartbeatMs >= sseTimeoutMs) {
            log.warn("Configuration warning: heartbeat.interval-seconds({}s) * 1000 >= sse.timeout-ms({}ms). " +
                    "Heartbeat may not fire before timeout.", heartbeatIntervalSeconds, sseTimeoutMs);
        }
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
        this.messageFormatDetector = new MessageFormatDetector();
    }

    @PostMapping(value = "/{id}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "SSE流式聊天(POST)", description = "SSE流式返回Agent回复，支持推理过程实时推送")
    public SseEmitter chatStreamPost(
            @PathVariable Long id,
            @RequestBody @Valid StreamChatRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "User-ID", required = false) @Pattern(regexp = "\\d+", message = "User-ID must be numeric") String userIdHeader,
            @RequestHeader(value = "Tenant-ID", required = false) @Pattern(regexp = "\\d+", message = "Tenant-ID must be numeric") String tenantIdHeader) {

        // 从 Authorization header 中提取 token
        UserInfoService.TokenUserInfo user = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            // 一次性获取所有信息
            user = userInfoService.getUserInfoFromToken(token);
            if (user == null) {
                log.warn("无法解析 token，token 可能无效或已过期");
            }
        }

        String messageId = UUID.randomUUID().toString();
        MDC.put("messageId", messageId);
        MDC.put("agentId", String.valueOf(id));

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

        if (user != null) {
            userId = String.valueOf(user.getId());
            tenantId = String.valueOf(user.getTenantId());
        }

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

            ResourceContext ctx = new ResourceContext(emitter, messageId, () -> {
                sseSemaphore.release();
                activeSseConnections.decrementAndGet();
                MDC.remove("messageId");
                MDC.remove("agentId");
                MDC.remove("userId");
            });

            setupEmitterCallbacks(emitter, ctx);
            setupHeartbeat(emitter, ctx);

            StreamingHook streamingHook = new StreamingHook(emitter, ctx, messageId, jsonMapper,
                    messageFormatDetector, sseFragmentSampleRate);
            ReActAgent agentWithHook = buildAgentWithHooks(baseAgent, streamingHook);

            executeAgentCall(agentWithHook, request, id, ctx, finalSessionId, userIdLong, tenantIdLong);

            return emitter;

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error in chatStreamPost: agentId={}, messageId={}", id, messageId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
        } finally {
            // 兜底清理：防止异常退出时遗漏（重复 remove 无副作用）
            MDC.remove("messageId");
            MDC.remove("agentId");
            MDC.remove("userId");
        }
    }

    private void setupEmitterCallbacks(SseEmitter emitter, ResourceContext ctx) {
        Runnable cleanup = () -> {
            ctx.cancelHeartbeat();
            ctx.releaseResources();
        };
        // 回调中调用 markDisconnected() 保证幂等清理
        emitter.onCompletion(() -> { ctx.markDisconnected(); cleanup.run(); });
        emitter.onTimeout(() -> {
            ctx.markDisconnected();
            log.debug("SSE timeout: {}", ctx.getMessageId());
            cleanup.run();
        });
        emitter.onError(e -> {
            ctx.markDisconnected();
            log.error("SSE error: {}", ctx.getMessageId(), e);
            cleanup.run();
        });
    }

    private void setupHeartbeat(SseEmitter emitter, ResourceContext ctx) {
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!ctx.isDisconnected()) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IllegalStateException | IOException e) {
                    // 客户端断开或连接异常，标记断开（幂等）
                    ctx.markDisconnected();
                }
            }
        }, heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
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
                msg -> {
                    ctx.markDisconnected();
                    handleAgentResponse(msg, ctx, agentId, sessionId, userId, tenantId);
                },
                error -> handleAgentError(error, ctx, agentId, sessionId, userId, tenantId),
                () -> handleAgentComplete(ctx, agentId, sessionId, userId, tenantId)
        );
    }

    private void handleAgentResponse(Msg msg, ResourceContext ctx, Long agentId,
                                     String sessionId, Long userId, Long tenantId) {
        Schedulers.single().schedule(() -> {
            try {
                String finalContent = ctx.getResponseContent();
                if (finalContent.isBlank() && msg != null) {
                    String msgContent = msg.getTextContent();
                    if (msgContent != null && !msgContent.isBlank()) {
                        ctx.appendResponse(msgContent);
                        finalContent = msgContent;
                    }
                }

                StreamResponseDTO doneResp = StreamResponseDTO.builder()
                        .content("").messageType("text").messageId(ctx.getMessageId()).eventType("done").build();
                ctx.getEmitter().send(SseEmitter.event().name("done").data(jsonMapper.writeValueAsString(doneResp)));
                log.info("📨 [SSE] done sent: agentId={}, messageId={}, responseLength={}",
                        agentId, ctx.getMessageId(), finalContent.length());

                if (!finalContent.isBlank()) {
                    saveAssistantMessageAsync(agentId, userId, tenantId, sessionId, finalContent);
                }
                long latency = System.currentTimeMillis() - ctx.getStartTime();
                requestDurationTimer.record(latency, TimeUnit.MILLISECONDS);
                ctx.releaseResources();
                log.info("✅ [SSE] finished: agentId={}, latency={}ms, messageId={}",
                        agentId, latency, ctx.getMessageId());
            } catch (IOException e) {
                // 发送 done 时连接已断开，属于正常边界，标记断开即可
                log.debug("SSE done send failed (client disconnected): agentId={}, messageId={}", agentId, ctx.getMessageId());
                ctx.markDisconnected();
            } catch (IllegalStateException e) {
                // 可能重复完成或响应已提交
                log.debug("SSE done send failed (illegal state): agentId={}, messageId={}", agentId, ctx.getMessageId());
                ctx.markDisconnected();
            } catch (Exception e) {
                log.warn("SSE done send unexpected error: agentId={}, messageId={}", agentId, ctx.getMessageId(), e);
                ctx.markDisconnected();
            }
        }, hookFlushDelayMs, TimeUnit.MILLISECONDS);
    }

    private void handleAgentError(Throwable error, ResourceContext ctx, Long agentId,
                                  String sessionId, Long userId, Long tenantId) {
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
            } catch (IllegalStateException | IOException ignored) {
                // 连接已关闭，忽略
            }
        }
        ctx.markDisconnected(); // 幂等调用
        log.error("SSE stream error: agentId={}, messageId={}", agentId, ctx.getMessageId(), error);
    }

    private void handleAgentComplete(ResourceContext ctx, Long agentId, String sessionId, Long userId, Long tenantId) {
        if (ctx.isDisconnected()) {
            log.debug("SSE onComplete skipped (already handled): agentId={}, messageId={}",
                    agentId, ctx.getMessageId());
            return;
        }

        String content = ctx.getResponseContent();
        if (content == null || content.isBlank()) {
            emptyResponseCounter.increment();
            log.warn("Agent completed with empty response: agentId={}, messageId={}", agentId, ctx.getMessageId());
        }

        long latency = System.currentTimeMillis() - ctx.getStartTime();
        requestDurationTimer.record(latency, TimeUnit.MILLISECONDS);
        ctx.releaseResources();
        log.debug("SSE onComplete (cleanup only): agentId={}, latency={}ms, messageId={}",
                agentId, latency, ctx.getMessageId());
    }

    private void saveAssistantMessageAsync(Long agentId, Long userId, Long tenantId, String sessionId, String content) {
        if (content == null || content.isBlank()) {
            log.debug("Skip saving empty message: agentId={}, sessionId={}", agentId, sessionId);
            return;
        }
        try {
            chatSaveExecutor.execute(() -> {
                try {
                    ChatMessage assistantMsg = ChatMessage.builder()
                            .sessionId(sessionId).agentId(agentId).userId(userId).tenantId(tenantId)
                            .role("assistant").content(content).messageType(messageFormatDetector.detect(content)).build();
                    chatMessageMapper.insert(assistantMsg);
                } catch (Exception e) {
                    saveFailedCounter.increment();
                    log.error("Failed to save assistant message: agentId={}, sessionId={}", agentId, sessionId, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Save task rejected by chatSaveExecutor: agentId={}, sessionId={}", agentId, sessionId);
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

    private Long parseLongSafe(String str, Long defaultValue) {
        if (str == null || str.isBlank()) return defaultValue;
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            log.debug("Failed to parse '{}' as Long, using default: {}", str, defaultValue);
            return defaultValue;
        }
    }

    // ========== ResourceContext：资源管理上下文 ==========
    private static class ResourceContext {
        private final SseEmitter emitter;
        private final String messageId;
        private final long startTime;
        // 🔑 核心：用 AtomicBoolean 保证断开状态幂等，避免重复清理
        private final AtomicBoolean disconnected;
        private final AtomicBoolean resourcesReleased;
        private final StringBuilder responseBuffer;
        private final Object bufferLock;
        private volatile ScheduledFuture<?> heartbeatFuture;
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

        // 🔑 幂等方法：多次调用无副作用
        public void markDisconnected() {
            if (disconnected.compareAndSet(false, true)) {
                log.debug("Connection marked disconnected: messageId={}", messageId);
            }
        }

        public void setHeartbeatFuture(ScheduledFuture<?> f) { this.heartbeatFuture = f; }

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
        private final MessageFormatDetector formatDetector;
        private final double sampleRate;

        private final AtomicReference<String> globalMessageType = new AtomicReference<>();

        public StreamingHook(SseEmitter emitter, ResourceContext resourceCtx, String messageId,
                             ObjectMapper localMapper, MessageFormatDetector formatDetector, double sampleRate) {
            this.emitter = emitter;
            this.resourceCtx = resourceCtx;
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
                        Msg msg = postCallEvent.getFinalMessage();
                        if (msg != null && msg.getTextContent() != null) {
                            eventName = "final";
                            content = msg.getTextContent();
                        }
                    }

                    if (content != null && !content.isEmpty()) {
                        if (!(event instanceof PostCallEvent)) {
                            resourceCtx.appendResponse(content);
                        }

                        String messageType = resolveGlobalMessageType(content);
                        StreamResponseDTO response = StreamResponseDTO.builder()
                                .content(content).messageType(messageType).messageId(messageId).eventType(eventName).build();
                        emitter.send(SseEmitter.event().name(eventName).data(localMapper.writeValueAsString(response)));

                        if (log.isDebugEnabled() && Math.random() < sampleRate) {
                            String safeContent = content.length() > 100 ? content.substring(0, 100) + "..." : content;
                            log.debug("SSE fragment sent: messageId={}, eventType={}, contentPreview={}",
                                    messageId, eventName, safeContent);
                        }
                    }
                } catch (IllegalStateException e) {
                    // 连接已关闭或重复发送，标记断开（幂等）
                    resourceCtx.markDisconnected();
                } catch (IOException e) {
                    // IO 异常通常表示客户端断开
                    resourceCtx.markDisconnected();
                    log.debug("SseEmitter IO error (client disconnected): messageId={}", messageId);
                } catch (Exception e) {
                    resourceCtx.markDisconnected();
                    log.warn("Streaming hook send error: messageId={}", messageId, e);
                }
                return Mono.just(event);
            }).publishOn(Schedulers.single());
        }
    }
}