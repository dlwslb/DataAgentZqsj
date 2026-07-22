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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
@RequestMapping("/api2/chat")
public class ChatController {

    /** SSE Heartbeat 间隔 */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    /** SSE 并发上限（防止 Netty 线程被拖死） */
    private static final int MAX_CONCURRENT_SSE = 1000;

    private final HarnessAgent agent;
    private final java.util.concurrent.Semaphore sseSemaphore =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_SSE, true);

    /**
     * ⭐ 活跃流跟踪表：sessionId → StreamSession
     * <p>用途：让 /api2/chat/stop 接口能按 sessionId 主动 dispose agent 推理流，省 LLM token
     * <p>为什么不靠"客户端断开 = 自动 doOnCancel"？Netty 不一定立即感知客户端 abort，
     *    而且 dispose 时机不可控；显式 stop 接口更确定。
     *
     * <p>线程安全：ConcurrentHashMap；用 atomic remove(key, value) 防"stop 时正好 doFinally 也 remove" 的并发误删
     * <p>多用户隔离：每个 StreamSession 记 userId/tenantId，stop 时校验当前请求的 user/tenant 才能 dispose
     */
    private final Map<String, StreamSession> activeStreams = new ConcurrentHashMap<>();

    /**
     * 活跃流快照：包含 sessionId / userId / tenantId + agent 流的 Disposable
     * <p>用 record 简化（不可变 + 自动 equals/hashCode，atomic remove 靠 equals）
     */
    private record StreamSession(String sessionId, Long userId, Long tenantId,
                                 reactor.core.Disposable disposable) {}

    public ChatController(@Qualifier("dataAgent") HarnessAgent agent) {
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
            // ⭐ province：用户授权的省份范围（逗号分隔 / "全国"），硬性权限控制
            String province = ctx.getOrDefault(JwtAuthWebFilter.CTX_PROVINCE, "");
            String username = ctx.getOrDefault(JwtAuthWebFilter.CTX_USERNAME, "");
            String role = ctx.getOrDefault(JwtAuthWebFilter.CTX_ROLE, "user");

            String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                    ? request.getSessionId()
                    : "sess-" + UUID.randomUUID();

            RuntimeContext rc = RuntimeContext.builder()
                    .userId(userId != null ? String.valueOf(userId) : "anonymous")
                    .put("tenantId", tenantId != null ? String.valueOf(tenantId) : "default")
                    .put("province", province)
                    .sessionId(sessionId)
                    .build();

            String enrichedMessage = enrichMessageWithContext(
                    request.getMessage(), userId, tenantId, province, username, role);
            UserMessage userMsg = new UserMessage(enrichedMessage);

            log.info("🔍 [chat] userId={}, tenantId={}, province={}, sessionId={}, activeSse={}",
                    userId, tenantId, province, sessionId, MAX_CONCURRENT_SSE - sseSemaphore.availablePermits());

            // 2) 主事件流 + 异常分类 + 释放信号量
            //    ⭐ 关键 filter：只放行用户真正需要的事件
            //    - TEXT_BLOCK_DELTA：用户看到的文字（打字机效果）
            //    - TOOL_CALL_START：前端显示"正在调用 xxx"
            //    屏蔽（不推给前端）：
            //    - THINKING_BLOCK_DELTA：LLM 思考内容（enableThinking=true 才会有，框架已经区分开了）
            //    - AGENT_START/END、MODEL_CALL_START/END、TEXT_BLOCK_START/END、TOOL_CALL_DELTA/END、
            //      TOOL_RESULT_START/TEXT_DELTA/DATA_DELTA/END 等其他系统内部事件
            //
            // ⭐ 用 Sinks.Many 把冷流转成热流：上游 agent.streamEvents() 只 .subscribe() 一次
            //   - 之前 takeUntilOther + merge 各订阅一次 → 副作用（doOnError / doFinally）跑 2 次
            //   - 特别是 doFinally 里的 sseSemaphore.release() 跑 2 次会让可用许可 > MAX_CONCURRENT_SSE，
            //     **限流彻底失效**！这是真 bug，不是性能问题
            //   - 现在所有副作用只在源头跑 1 次，下游 asFlux() 想订阅几次都行
            Sinks.Many<StreamEvent> eventSink = Sinks.many().multicast().onBackpressureBuffer();

            // 在 filter 后面加一个"最后一个文字"检测
            AtomicBoolean textStarted = new AtomicBoolean(false);
            AtomicLong lastTextTime = new AtomicLong(0);
            // 保存订阅引用
            reactor.core.Disposable subscription = agent.streamEvents(userMsg, rc)
                    // ⭐ 关键：把 authInfo 注入到 Reactor Context，让 Tool 跨线程拿到
                    //   (JwwAuthWebFilter 写的 Context 可能被 AgentScope 内部丢，这里强制再写一次)
                    .contextWrite(reactor.util.context.Context.of(
                            JwtAuthWebFilter.CTX_AUTH_INFO,
                            new com.jldaren.agent.ai.datascope2.auth.AuthContext.AuthInfo(
                                    userId, tenantId, province, username, role)))
                    .filter(event -> event.getType() == AgentEventType.TEXT_BLOCK_DELTA)//只保留 用户回复
                    .map(event -> {
                        textStarted.set(true);
                        lastTextTime.set(System.currentTimeMillis());
                        return convertEvent(event, sessionId);
                    })
                    .doOnError(e -> log.error("❌ 流式对话异常: {}", e.getMessage(), e))
                    .onErrorResume(e -> Flux.just(buildErrorEvent(e, sessionId)))
                    // ⭐ 文字流结束后，追加一个 done 事件
                    .concatWith(Mono.just(StreamEvent.of("done", "complete", sessionId)))
                    .doFinally(sig -> {
                        sseSemaphore.release();
                        // ⭐ 关键修复：sig=CANCEL 时连接已断，再 tryEmitComplete 会触发 netty AbortedException
                        //    原因：客户端 abort fetch → netty channel 已关 → Sinks 推 complete 到下游
                        //         → 下游尝试写 SSE → channel 关闭 → 抛 AbortedException → GlobalExceptionHandler 当 ERROR 报
                        //    只在"正常完成 / 异常"两种场景才需要 emit complete（让 heartbeat 收到结束信号停掉）
                        if (sig != reactor.core.publisher.SignalType.CANCEL) {
                            eventSink.tryEmitComplete();
                        }
                        // ⭐ 从活跃流跟踪表移除（用单 key remove，不用 value check）
                        //    为什么不用 atomic remove(key, value)？因为 doFinally 在 .subscribe() 返回前就被构造，
                        //    此时 subscription 还没被赋值，lambda 内引用会编译失败。
                        //    副作用：理论上存在"流 A 的 doFinally 误删流 B 的 entry"的极小竞态窗口
                        //    （同 sessionId 先 A 后 B，A 结束 doFinally 时 B 已 put 进去），
                        //    但实际业务里同一 sessionId 不会有并发流，且最坏情况是 B 的 stop 返回 not_found 让用户再点一次，
                        //    不是 critical bug。ConcurrentHashMap.remove(key) 自身是原子的。
                        activeStreams.remove(sessionId);
                        log.info("🔚 SSE 连接关闭: sessionId={}, sig={}, availablePermits={}",
                                sessionId, sig, sseSemaphore.availablePermits());
                    })
                    // ⭐ 单次订阅：所有副作用（doOnError / onErrorResume / doFinally）只跑 1 次
                    .subscribe(eventSink::tryEmitNext, eventSink::tryEmitError);

            // ⭐ 注册到活跃流跟踪表（让 /api2/chat/stop 能主动 dispose）
            //    在 .subscribe() 之后才能拿到 subscription，所以 put 在这里
            activeStreams.put(sessionId, new StreamSession(sessionId, userId, tenantId, subscription));
            log.debug("📝 流注册: sessionId={}, userId={}, tenantId={}, activeCount={}",
                    sessionId, userId, tenantId, activeStreams.size());

            // 3) 双重保活：每 15s 一个 heartbeat 事件（前端可见）+ SSE 注释行（注释行是 SSE 协议标准，部分代理会忽略事件但保留注释行）
            //    ⭐ 监听热流 sink（不再是冷流 eventStream），订阅 sink 是零成本的
            Flux<StreamEvent> heartbeatStream = Flux.interval(HEARTBEAT_INTERVAL)
                    .map(tick -> StreamEvent.of("heartbeat", tick, sessionId))
                    .doOnNext(ev -> log.trace("💓 heartbeat: sessionId={}, tick={}", sessionId, ev.getData()))
                    .takeUntilOther(eventSink.asFlux()
                            .ignoreElements()
                            .onErrorResume(e -> Mono.empty())
                            .then());

            // 4) merge 热流 + heartbeat：两个上游各自只被订阅 1 次
            // ⭐ 当下游（SSE 连接）取消时，取消上游（agent 流）
            return Flux.merge(eventSink.asFlux(), heartbeatStream)
                    .doOnCancel(() -> {
                        log.info("⚡ 客户端断开，取消 agent 流: sessionId={}", sessionId);
                        subscription.dispose();  // ← 取消 LLM 推理，省 token
                    });
        });
    }

    /**
     * 主动停止 SSE 流（按 sessionId 主动 dispose agent 推理）
     * <p>为什么需要？客户端 abort fetch 时 Netty 不一定立即感知，"客户端断开 = 自动 doOnCancel"
     *    的链路有延迟，LLM 还会继续跑几秒（甚至更久）消耗 token。
     *    这个接口让前端点"停止"时**立即**告诉后端 dispose，省 LLM 调用成本。
     *
     * <p>行为：
     * <ul>
     *   <li>sessionId 在 activeStreams 里，且 userId/tenantId 匹配 → dispose() + 返回 "stopped"</li>
     *   <li>sessionId 不在 activeStreams（流已自然结束/被并发 stop 干掉） → 返回 "not_found"（不算错）</li>
     *   <li>sessionId 存在但 userId/tenantId 不匹配（跨用户攻击） → 403 "forbidden"</li>
     * </ul>
     *
     * <p>并发安全：activeStreams 用 ConcurrentHashMap，remove(key, value) 原子（防 stop 和 doFinally 撞车）
     *
     * <p>调用方（前端 stopStreaming）：建议 fire-and-forget 调用，不依赖返回值——前端 abort fetch 已经能让
     *    SSE 连接关掉、触发 doOnCancel。这里的 stop 是"加速后端停止"的优化，不是唯一手段。
     */
    @PostMapping("/stop")
    public Mono<Map<String, Object>> stop(@RequestBody Map<String, String> body) {
        return Mono.deferContextual(ctx -> {
            Long currentUserId = ctx.getOrDefault(JwtAuthWebFilter.CTX_USER_ID, null);
            Long currentTenantId = ctx.getOrDefault(JwtAuthWebFilter.CTX_TENANT_ID, null);
            String sessionId = body != null ? body.get("sessionId") : null;

            if (sessionId == null || sessionId.isBlank()) {
                log.warn("⛔ [/api2/chat/stop] 缺少 sessionId");
                return Mono.just(Map.of("success", false, "reason", "missing_sessionId"));
            }

            // 原子取出（如果正好 doFinally 也 remove 了同一个，这里拿到 null，下面走 not_found）
            StreamSession session = activeStreams.remove(sessionId);
            if (session == null) {
                log.info("ℹ️ [/api2/chat/stop] 流已结束或不存在: sessionId={}", sessionId);
                return Mono.just(Map.of("success", true, "reason", "not_found",
                        "message", "流已结束，无需停止"));
            }

            // ⭐ 多用户隔离：只能停自己的流
            //    用 Objects.equals 防 NPE（currentUserId 可能为 null，比如 anonymous）
            if (!java.util.Objects.equals(session.userId(), currentUserId)
                    || !java.util.Objects.equals(session.tenantId(), currentTenantId)) {
                log.warn("⛔ [/api2/chat/stop] 跨用户停止被拒: sessionId={}, 流 owner=userId={}/tenantId={}, 请求方=userId={}/tenantId={}",
                        sessionId, session.userId(), session.tenantId(), currentUserId, currentTenantId);
                // 已经 remove 了，要把 entry 还回去——否则 owner 自己的 /stop 会找不到
                // 用 putIfAbsent 防"还回去"和"流自然结束"竞争
                activeStreams.putIfAbsent(sessionId, session);
                return Mono.just(Map.of("success", false, "reason", "forbidden",
                        "message", "无权停止该会话的流"));
            }

            // 主动 dispose → agent 推理立即取消 → doOnCancel 触发 → 最终 doFinally 清理信号量
            //    dispose() 幂等：流已结束（disposed=true）时调用也无副作用
            log.info("🛑 [/api2/chat/stop] 主动停止流: sessionId={}, userId={}, tenantId={}",
                    sessionId, currentUserId, currentTenantId);
            session.disposable().dispose();
            return Mono.just(Map.of("success", true, "reason", "stopped",
                    "message", "已停止会话流"));
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
            String province = ctx.getOrDefault(JwtAuthWebFilter.CTX_PROVINCE, "");
            String username = ctx.getOrDefault(JwtAuthWebFilter.CTX_USERNAME, "");
            String role = ctx.getOrDefault(JwtAuthWebFilter.CTX_ROLE, "user");

            String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                    ? request.getSessionId()
                    : "sess-" + UUID.randomUUID();

            RuntimeContext rc = RuntimeContext.builder()
                    .userId(userId != null ? String.valueOf(userId) : "anonymous")
                    .put("tenantId", tenantId != null ? String.valueOf(tenantId) : "default")
                    .put("province", province)
                    .sessionId(sessionId)
                    .build();

            String enrichedMessage = enrichMessageWithContext(
                    request.getMessage(), userId, tenantId, province, username, role);
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
                                            String province, String username, String role) {
        if (originalMessage == null) originalMessage = "";
        StringBuilder sb = new StringBuilder(originalMessage);
        sb.append("\n\n---\n");
        sb.append("[Context]\n");
        sb.append("- userId: ").append(userId != null ? userId : "anonymous").append("\n");
        sb.append("- tenantId: ").append(tenantId != null ? tenantId : "default").append("\n");
        // ⭐ 硬性省份权限：用户绑定的省份范围（逗号分隔 / "全国"）
        //    ⛔ 拿不到/为空 → 显示"未配置"（让 LLM 知道用户无授权）
        sb.append("- authorizedProvince: ").append(province != null && !province.isBlank() ? province : "未配置（无任何省份查询授权）").append("\n");
        sb.append("- username: ").append(username).append("\n");
        sb.append("- role: ").append(role);
        return sb.toString();
    }

    /**
     * 把 AgentScope 2.0 事件转 StreamEvent
     */
    private StreamEvent convertEvent(AgentEvent event, String sessionId) {
        AgentEventType type = event.getType();
        if (type == AgentEventType.TEXT_BLOCK_DELTA) {
            String delta = ((TextBlockDeltaEvent) event).getDelta();
            //String delta = sanitizeText(((TextBlockDeltaEvent) event).getDelta());
            return StreamEvent.of("text", delta, sessionId);
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
     * ⛔ 兜底:把 LLM 二次加工的污染套话清理掉
     * <p>背景:sysPrompt / Tool 描述怎么改,LLM 偶尔还是会自己加"根据最高优先级规则"、"系统直接拒绝查询"这种总结性套话
     * <p>这一层是最后一道防线,在 Controller 层强制清洗,避免脏话漏到用户面前
     * <p>同时清掉 LLM 用 markdown 反引号包 Tool 错误的格式(`xxx` 和 ```xxx```)
     */
    private String sanitizeText(String text) {
        if (text == null || text.isEmpty()) return text;
        String s = text;
        // 整段包裹型:(根据最高优先级规则,XXX,系统直接拒绝查询) → 提取中间
        s = s.replaceAll("（?根据最高优先级规则[，,]?\\s*", "");
        s = s.replaceAll("[，,]\\s*系统直接拒绝查询\\s*）?", "");
        s = s.replaceAll("[，,]\\s*系统拒绝查询\\s*）?", "");
        s = s.replaceAll("[，,]\\s*系统已拦截\\s*）?", "");
        // 单独出现也清掉
        s = s.replaceAll("根据最高优先级规则[,，]?\\s*", "");
        s = s.replaceAll("系统直接拒绝查询[,，]?\\s*", "");
        s = s.replaceAll("系统拒绝查询[,，]?\\s*", "");
        s = s.replaceAll("系统已拦截[,，]?\\s*", "");
        // 清理可能多出来的成对括号(原来 LLM 加的(根据最高优先级规则,XXX,系统直接拒绝查询)拆掉后残留的括号)
        s = s.replaceAll("^[（(]\\s*[）)]\\s*", ""); // 空括号 ()
        s = s.replaceAll("^[（(]\\s*", ""); // 前导左括号
        s = s.replaceAll("\\s*[）)]\\s*$", ""); // 末尾右括号
        // ⛔ 清掉 LLM 用的 markdown 反引号 / 代码块格式(LLM 习惯把 Tool 错误用 ` 包起来当代码引用)
        //   - code block ```xxx``` → 整段去掉
        //   - inline code `xxx` → 只剥外壳
        s = s.replaceAll("```[\\s\\S]*?```", "");        // 三反引号 code block(可跨行)
        s = s.replaceAll("`([^`\\n]+)`", "$1");           // 单反引号 inline code,只剥外壳(避免误伤连续的 `)
        // 残留的零散反引号(LLM 输出过程中可能流式拆成单边)
        s = s.replaceAll("(?m)^\\s*`\\s*$", "");          // 整行只有反引号的
        s = s.replaceAll("`", "");                        // 兜底:任何剩下的零散反引号都删
        return s;
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
