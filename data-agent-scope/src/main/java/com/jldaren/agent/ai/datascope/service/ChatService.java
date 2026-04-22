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
package com.jldaren.agent.ai.datascope.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jldaren.agent.ai.datascope.controller.bean.ChatRequest;
import com.jldaren.agent.ai.datascope.registry.AgentScopeRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AgentScopeRegistry registry;
    private final RagService ragService;
    private final ObjectMapper objectMapper;

    private static final int RAG_TOP_K = 3; // 检索相关知识的数量

    public Mono<Msg> chat(ChatRequest request) {
        Long agentId = request.getAgentId();
        String userId = request.getUserId();
        String tenantId = request.getTenantId();
        
        // 如果提供了 userId 和 tenantId，使用带长期记忆的 Agent
        ReActAgent agent;
        if (userId != null && tenantId != null) {
            agent = registry.getAgentWithLongTermMemory(agentId, userId, tenantId);
            if (agent == null) {
                return Mono.error(new IllegalArgumentException("ReActAgent not found: " + agentId));
            }
            log.debug("Using ReActAgent with long-term memory: agentId={}, userId={}, tenantId={}", agentId, userId, tenantId);
        } else {
            // 降级：使用普通 Agent（不带长期记忆）
            agent = registry.getAgent(agentId);
            if (agent == null) {
                return Mono.error(new IllegalArgumentException("ReActAgent not found: " + agentId));
            }
            log.debug("Using ReActAgent without long-term memory: agentId={}", agentId);
        }

        // RAG 增强（检索相关知识）
        String enhancedMessage = enhanceWithRag(agentId, request.getMessage());

        Msg userMsg = Msg.builder().textContent(enhancedMessage).build();
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

    /**
     * 使用 RAG 增强用户消息
     * 检索相关知识并注入到上下文中
     * 注意：长期记忆增强已由 ReActAgent 的 longTermMemoryMode=BOTH 自动处理，
     * 框架会在 agent.call() 时自动调用 retrieve() 和 record()，无需在此手动增强
     */
    private String enhanceWithRag(Long agentId, String userMessage) {
        try {
            // 检索相关知识
            List<Document> relatedKnowledge = ragService.searchRelatedKnowledge(agentId, userMessage, RAG_TOP_K);

            if (relatedKnowledge.isEmpty()) {
                log.debug("🔍 未检索到相关知识，使用原始消息");
                return userMessage;
            }

            // 构建知识上下文
            String knowledgeContext = relatedKnowledge.stream()
                    .map(doc -> {
                        Object title = doc.getMetadata().get("title");
                        return String.format("- [%s] %s",
                                title != null ? title : "未知",
                                doc.getText());
                    })
                    .collect(Collectors.joining("\n"));

            // 构建增强后的消息
            String enhancedMessage = String.format(
                    """
                    【相关知识参考】
                    %s

                    【用户问题】
                    %s

                    请结合上述相关知识回答问题。如果知识与问题无关，请忽略知识直接回答。
                    """,
                    knowledgeContext,
                    userMessage
            );

            log.debug("RAG enhanced: agentId={}, retrieved {} knowledge docs", agentId, relatedKnowledge.size());
            return enhancedMessage;

        } catch (Exception e) {
            log.error("❌ RAG 增强失败: agentId={}, error={}", agentId, e.getMessage(), e);
            // 降级：返回原始消息
            return userMessage;
        }
    }

}
