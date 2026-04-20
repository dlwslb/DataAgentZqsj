package com.jldaren.agent.ai.datascope.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jldaren.agent.ai.datascope.controller.bean.ChatRequest;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ReActAgent agent;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Mono<Msg> chat(ChatRequest request) {
        Msg userMsg = Msg.builder().textContent(request.getMessage()).build();
        return Mono.fromFuture(agent.call(userMsg).toFuture());  // ✅ 转 Mono
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
