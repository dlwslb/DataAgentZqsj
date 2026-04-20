package com.jldaren.agent.ai.datascope.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jldaren.agent.ai.datascope.controller.bean.ChatRequest;
import com.jldaren.agent.ai.datascope.registry.AgentScopeRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AgentScopeRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Mono<Msg> chat(ChatRequest request) {
        Long agentId = request.getAgentId();
        ReActAgent agent = registry.getAgent(agentId);
        if (agent == null) {
            return Mono.error(new IllegalArgumentException("Agent not found: " + agentId));
        }
        Msg userMsg = Msg.builder().textContent(request.getMessage()).build();
        return Mono.fromFuture(agent.call(userMsg).toFuture());
    }

    public <T> Mono<T> chatStructured(ChatRequest request, Class<T> responseType) {
        return chat(request)
                .map(msg -> {
                    try {
                        String json = msg.getTextContent();
                        return objectMapper.readValue(json, responseType);
                    } catch (Exception e) {
                        throw new RuntimeException("解析结构化输出失败", e);
                    }
                });
    }
}
