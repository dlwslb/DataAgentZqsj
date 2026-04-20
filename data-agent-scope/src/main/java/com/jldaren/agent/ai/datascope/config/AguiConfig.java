package com.jldaren.agent.ai.datascope.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AG-UI 协议配置
 * 
 * <p>功能：
 * <ul>
 *   <li>注册智能体到 AG-UI 注册表</li>
 *   <li>暴露 /agui/run 端点供前端调用</li>
 *   <li>支持流式事件推送 (SSE)</li>
 * </ul>
 *
 * AguiConfig.java - AG-UI 配置
 * 注册智能体到 AguiAgentRegistry
 * 暴露 /agui/run SSE 端点
 * 使用方式：
 * 前端通过 SSE 连接 ws://localhost:58064/agui/run，发送消息格式：
 * json
 * {
 *   "agentId": "businessAssistant",
 *   "messages": [{"role": "user", "content": "你好"}]
 * }
 */
@Configuration
public class AguiConfig {

    @Bean
    public AguiAgentRegistry aguiAgentRegistry(ReActAgent businessAssistant) {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        
        // 注册智能体，前端可通过 agentId="businessAssistant" 调用
        registry.register("businessAssistant", businessAssistant);
        
        return registry;
    }

}
