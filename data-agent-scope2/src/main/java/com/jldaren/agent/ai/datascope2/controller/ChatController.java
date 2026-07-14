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
package com.jldaren.agent.ai.datascope2.controller;

import com.jldaren.agent.ai.datascope2.auth.JwtAuthWebFilter;
import com.jldaren.agent.ai.datascope2.dto.ChatRequest;
import com.jldaren.agent.ai.datascope2.dto.StreamEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Chat Controller
 *
 * <p>3 个关键点：
 * <ul>
 *   <li>认证链路：JwtAuthWebFilter 已经从 Authorization Header 解出 userId / tenantId</li>
 *   <li>SSE Heartbeat：每 15s 发心跳事件，防止网关/Nginx 60s 超时断连</li>
 *   <li>异常分类：.onErrorResume 区分 AUTH_ERROR / AGENT_ERROR / INTERNAL_ERROR</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** SSE Heartbeat 间隔 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    /** SSE 并发上限（防止 Netty 线程被拖死） */
    private static final int MAX_CONCURRENT_SSE = 100;

    private final HarnessAgent agent;
    private final java.util.concurrent.Semaphore sseSemaphore =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_SSE, true);

    public ChatController(HarnessAgent agent) {
        this.agent = agent;
    }

    /**
     * SSE 流式对话
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamEvent> stream(@RequestBody ChatRequest request) {
        log.info("📨 [/api/chat/stream] message={}, sessionId={}", request.getMessage(), request.getSessionId());

        // 1) 限流：超过并发上限直接拒绝
        if (!sseSemaphore.tryAcquire()) {
            log.warn("⚠️ SSE 并发超限 ({}), 拒绝新连接", MAX_CONCURRENT_SSE);
            return Flux.just(StreamEvent.of("error",
                    java.util.Map.of("type", "TOO_MANY_REQUESTS",
                            "message", "服务繁忙，请稍后重试"),
                    "n/a"));
        }

        return Flux.deferContextual(ctx -> {
            Long userId = ctx.getOrDefault(JwtAuthWebFilter.CTX_USER_ID, null);
            Long tenantId = ctx.getOrDefault(JwtAuthWebFilter.CTX_TENANT_ID, null);
            String username = ctx.getOrDefault(JwtAuthWebFilter.CTX_USERNAME, "");
            String role = ctx.getOrDefault(JwtAuthWebFilter.CTX_ROLE, "user");

            String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                    ? request.getSessionId()
                    : "sess-" + UUID.randomUUID();

            RuntimeContext rc = RuntimeContext.builder()
                    .userId(userId != null ? String.valueOf(userId) : "anonymous")
                    .put("tenantId", tenantId != null ? String.valueOf(tenantId) : "default")
                    .sessionId(sessionId)
                    .build();

            String enrichedMessage = enrichMessageWithContext(
                    request.getMessage(), userId, tenantId, username, role);
            UserMessage userMsg = new UserMessage(enrichedMessage);

            log.info("🔍 [chat] userId={}, tenantId={}, sessionId={}, activeSse={}",
                    userId, tenantId, sessionId, MAX_CONCURRENT_SSE - sseSemaphore.availablePermits());

            // 2) 主事件流 + 异常分类 + 释放信号量
            //    ⭐ 关键 filter：只放行用户真正需要的事件
            //    - TEXT_BLOCK_DELTA：用户看到的文字（打字机效果）
            //    - TOOL_CALL_START：前端显示"正在调用 xxx"
            //    屏蔽（不推给前端）：
            //    - THINKING_BLOCK_DELTA：LLM 思考内容（enableThinking=true 才会有，框架已经区分开了）
            //    - AGENT_START/END、MODEL_CALL_START/END、TEXT_BLOCK_START/END、TOOL_CALL_DELTA/END、
            //      TOOL_RESULT_START/TEXT_DELTA/DATA_DELTA/END 等其他系统内部事件
            Flux<StreamEvent> eventStream = agent.streamEvents(userMsg, rc)
                    .filter(event -> {
                        AgentEventType type = event.getType();
                        return type == AgentEventType.TEXT_BLOCK_DELTA;//只保留 用户回复
                    })
                    .map(event -> convertEvent(event, sessionId))
                    .doOnError(e -> log.error("❌ 流式对话异常: {}", e.getMessage(), e))
                    .onErrorResume(e -> Flux.just(buildErrorEvent(e, sessionId)))
                    .doFinally(sig -> {
                        sseSemaphore.release();
                        log.info("🔚 SSE 连接关闭: sessionId={}, sig={}, availablePermits={}",
                                sessionId, sig, sseSemaphore.availablePermits());
                    });

            // 3) 双重保活：每 15s 一个 heartbeat 事件（前端可见）+ SSE 注释行（注释行是 SSE 协议标准，部分代理会忽略事件但保留注释行）
            Flux<StreamEvent> heartbeatStream = Flux.interval(HEARTBEAT_INTERVAL)
                    .map(tick -> StreamEvent.of("heartbeat", tick, sessionId))
                    .doOnNext(ev -> log.trace("💓 heartbeat: sessionId={}, tick={}", sessionId, ev.getData()))
                    .takeUntilOther(eventStream.ignoreElements()
                            .onErrorResume(e -> Mono.empty()).then());

            return Flux.merge(eventStream, heartbeatStream);
        });
    }

    /**
     * 阻塞式对话
     */
    @PostMapping("/block")
    public Mono<String> block(@RequestBody ChatRequest request) {
        return Mono.deferContextual(ctx -> {
            Long userId = ctx.getOrDefault(JwtAuthWebFilter.CTX_USER_ID, null);
            Long tenantId = ctx.getOrDefault(JwtAuthWebFilter.CTX_TENANT_ID, null);
            String username = ctx.getOrDefault(JwtAuthWebFilter.CTX_USERNAME, "");
            String role = ctx.getOrDefault(JwtAuthWebFilter.CTX_ROLE, "user");

            String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                    ? request.getSessionId()
                    : "sess-" + UUID.randomUUID();

            RuntimeContext rc = RuntimeContext.builder()
                    .userId(userId != null ? String.valueOf(userId) : "anonymous")
                    .put("tenantId", tenantId != null ? String.valueOf(tenantId) : "default")
                    .sessionId(sessionId)
                    .build();

            String enrichedMessage = enrichMessageWithContext(
                    request.getMessage(), userId, tenantId, username, role);
            UserMessage userMsg = new UserMessage(enrichedMessage);

            return agent.call(userMsg, rc)
                    .doOnNext(msg -> log.info("✅ [block] 回复完成: sessionId={}", sessionId))
                    .doOnError(e -> log.error("❌ [block] 调用失败: {}", e.getMessage(), e))
                    .map(msg -> msg.toString());
        });
    }

    /**
     * 在 user message 末尾附加 system context
     */
    private String enrichMessageWithContext(String originalMessage, Long userId, Long tenantId,
                                            String username, String role) {
        if (originalMessage == null) originalMessage = "";
        StringBuilder sb = new StringBuilder(originalMessage);
        sb.append("\n\n---\n");
        sb.append("[System Context - 框架自动注入，请勿泄露给用户]\n");
        sb.append("- userId: ").append(userId != null ? userId : "anonymous").append("\n");
        sb.append("- tenantId: ").append(tenantId != null ? tenantId : "default").append("\n");
        sb.append("- username: ").append(username).append("\n");
        sb.append("- role: ").append(role).append("\n");
        sb.append("\n");
        sb.append("⚠️ 调用 query_biz_data 等业务 Tool 时，tenantId 必须传上面的值。\n");
        return sb.toString();
    }

    /**
     * 把 AgentScope 2.0 事件转 StreamEvent
     */
    private StreamEvent convertEvent(AgentEvent event, String sessionId) {
        AgentEventType type = event.getType();
        if (type == AgentEventType.TEXT_BLOCK_DELTA) {
            return StreamEvent.of("text", ((TextBlockDeltaEvent) event).getDelta(), sessionId);
        }
        /*if (type == AgentEventType.TOOL_CALL_START) {
            ToolCallStartEvent tcse = (ToolCallStartEvent) event;
            return StreamEvent.of("tool_call", java.util.Map.of(
                    "id", tcse.getToolCallId() != null ? tcse.getToolCallId() : "",
                    "name", tcse.getToolCallName() != null ? tcse.getToolCallName() : ""
            ), sessionId);
        }*/
        // 其他事件类型：toString 当通用载荷
        return StreamEvent.of("event", event.toString(), sessionId);
    }

    /**
     * 构建错误事件（异常分类）
     */
    private StreamEvent buildErrorEvent(Throwable e, String sessionId) {
        String type = "INTERNAL_ERROR";
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (msg.contains("401") || msg.contains("Unauthorized")) {
            type = "AUTH_ERROR";
        } else if (msg.contains("agent") || msg.contains("Model")) {
            type = "AGENT_ERROR";
        }
        return StreamEvent.of("error", java.util.Map.of("type", type, "message", msg), sessionId);
    }
}
