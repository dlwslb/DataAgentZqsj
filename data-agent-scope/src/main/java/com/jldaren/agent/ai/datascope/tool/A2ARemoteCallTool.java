package com.jldaren.agent.ai.datascope.tool;

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.a2a.agent.card.AgentCardResolver;
import io.agentscope.core.a2a.agent.card.WellKnownAgentCardResolver;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * A2A 远程调用工具
 */
@Slf4j
@Component
public class A2ARemoteCallTool {

    @Value("${agentscope.a2a.remote-agent-url:http://localhost:8080}")
    private String remoteAgentUrl;

    @Tool(name = "call_remote_agent", description = "调用远程智能体")
    public Mono<String> callRemoteAgent(
            @ToolParam(name = "question", description = "问题内容", required = true) String question) {
        
        return Mono.fromCallable(() -> {
            // 创建 AgentCard 解析器
            AgentCardResolver agentCardResolver = WellKnownAgentCardResolver.builder()
                    .baseUrl(remoteAgentUrl)
                    .build();
            
            // 创建 A2A 客户端
            A2aAgent remoteAgent = A2aAgent.builder()
                    .name("remote-agent")
                    .agentCardResolver(agentCardResolver)
                    .build();
            
            // 调用远程智能体
            Msg request = Msg.builder().textContent(question).build();
            Msg response = remoteAgent.call(request).block();
            
            return response != null ? response.getTextContent() : "无响应";
        });
    }
}
