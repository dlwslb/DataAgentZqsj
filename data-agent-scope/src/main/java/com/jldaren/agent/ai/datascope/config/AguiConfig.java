package com.jldaren.agent.ai.datascope.config;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import com.jldaren.agent.ai.datascope.registry.AgentScopeRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AG-UI 协议配置
 */
@Configuration
public class AguiConfig {

    @Bean
    public AguiAgentRegistry aguiAgentRegistry(AgentScopeRegistry registry) {
        AguiAgentRegistry aguiRegistry = new AguiAgentRegistry();
        
        // 将 Registry 中的所有 Agent 注册到 AG-UI
        registry.getAllAgents().forEach((agentId, agent) -> {
            aguiRegistry.register(agentId.toString(), agent);
        });
        
        return aguiRegistry;
    }

}
