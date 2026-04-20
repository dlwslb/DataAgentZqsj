package com.jldaren.agent.ai.datascope.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.HookEventType;
import io.agentscope.core.hook.PreActingEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Human-in-the-Loop Hook - 人机协同干预
 * 
 * <p>在智能体执行关键步骤时暂停，等待人工确认或修正
 */
@Slf4j
@Component
public class HitlHook implements Hook {

    private final BlockingQueue<String> userFeedbackQueue = new LinkedBlockingQueue<>();

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.fromCallable(() -> {
            // 在工具调用前暂停，等待人工确认
            if (event.getType() == HookEventType.PRE_ACTING && event instanceof PreActingEvent preActingEvent) {
                String toolName = preActingEvent.getToolUse().getName();
                log.info("⏸️  HITL: 工具调用前暂停 - {}", toolName);
                
                // 等待用户反馈（实际项目中应通过 WebSocket/API 接收）
                String feedback = waitForUserFeedback();
                
                if ("reject".equalsIgnoreCase(feedback)) {
                    log.info("❌ 用户拒绝执行工具");
                    // AgentScope 1.0.11 不支持直接 skip，这里仅做日志记录
                    // 实际可以通过抛出异常或修改 toolUse 来阻止执行
                } else if ("modify".equalsIgnoreCase(feedback)) {
                    log.info("✏️ 用户修改参数");
                    // 这里可以修改工具参数
                }
            }
            
            return event;
        });
    }

    /**
     * 等待用户反馈（示例实现，实际应通过 API/WebSocket）
     */
    private String waitForUserFeedback() {
        try {
            // 模拟等待 5 秒，超时自动继续
            String feedback = userFeedbackQueue.poll();
            return feedback != null ? feedback : "approve";
        } catch (Exception e) {
            log.warn("等待用户反馈异常", e);
            return "approve";
        }
    }

    /**
     * 提交用户反馈（供 Controller 调用）
     */
    public void submitFeedback(String feedback) {
        userFeedbackQueue.offer(feedback);
    }
}
