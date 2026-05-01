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

import com.alibaba.cloud.ai.vectorstore.oceanbase.OceanBaseVectorStore;
import com.jldaren.agent.ai.datascope.entity.AgentScopeKnowledge;
import com.jldaren.agent.ai.datascope.mapper.AgentScopeKnowledgeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索增强生成服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final AgentScopeKnowledgeMapper knowledgeMapper;
    private final OceanBaseVectorStore vectorStore;
    /**
     * 向量化并存储知识
     */
    public void embedAndStoreKnowledge(AgentScopeKnowledge knowledge) {
        try {
            // 更新状态为处理中
            knowledgeMapper.updateEmbeddingStatus(knowledge.getId(), "PROCESSING");

            // 创建 Document
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("knowledgeId", knowledge.getId());
            metadata.put("agentId", knowledge.getAgentId());
            metadata.put("tenantId", knowledge.getTenantId() != null ? String.valueOf(knowledge.getTenantId()) : "null");
            metadata.put("userId", knowledge.getUserId() != null ? String.valueOf(knowledge.getUserId()) : "null");
            metadata.put("type", knowledge.getType());
            metadata.put("title", knowledge.getTitle());

            Document document = new Document(
                    knowledge.getContent(),
                    metadata
            );

            // 存储到向量数据库
            vectorStore.add(List.of(document));

            // 更新状态为完成
            knowledgeMapper.updateEmbeddingStatus(knowledge.getId(), "COMPLETED");
            log.info("✅ 知识向量化成功: id={}, title={}, tenantId={}, userId={}", 
                    knowledge.getId(), knowledge.getTitle(), knowledge.getTenantId(), knowledge.getUserId());
        } catch (Exception e) {
            knowledgeMapper.updateEmbeddingStatus(knowledge.getId(), "FAILED");
            log.error("❌ 知识向量化失败: id={}, error={}", knowledge.getId(), e.getMessage(), e);
        }
    }

    private static final int DEFAULT_TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = 0.7; // 相似度阈值，低于此值的结果将被过滤


    /**
     * 检索相关知识
     * 使用 filterExpression 在向量库层面过滤 agentId、tenantId、userId
     * - agentId: 必填，必须匹配
     * - tenantId: 可选，如果有值则匹配 (tenant_id = ? OR tenant_id IS NULL)
     * - userId: 可选，如果有值则匹配 (user_id = ? OR user_id IS NULL)
     */
    public List<Document> searchRelatedKnowledge(Long agentId, String query, int topK) {
        return searchRelatedKnowledge(agentId, null, null, query, topK);
    }

    /**
     * 检索相关知识（支持租户和用户隔离）
     */
    public List<Document> searchRelatedKnowledge(Long agentId, Long tenantId, Long userId, String query, int topK) {
        // 构建过滤表达式
        StringBuilder filterBuilder = new StringBuilder();
        filterBuilder.append(String.format("agentId == '%s'", agentId));
        
        // 如果提供了 tenantId，添加租户过滤：匹配指定租户或公共知识(tenant_id IS NULL)
        if (tenantId != null) {
            filterBuilder.append(String.format(" AND (tenantId == '%s' OR tenantId == 'null')", tenantId));
        }
        
        // 如果提供了 userId，添加用户过滤：匹配指定用户或公共知识(user_id IS NULL)
        if (userId != null) {
            filterBuilder.append(String.format(" AND (userId == '%s' OR userId == 'null')", userId));
        }
        
        String filterExpression = filterBuilder.toString();
        log.debug("🔍 [RAG] 搜索过滤条件: {}", filterExpression);
        
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(SIMILARITY_THRESHOLD)  // 设置相似度阈值
                .filterExpression(filterExpression)
                .build();

        try {
            return vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.warn("Vector search with filter failed, falling back to post-filter: {}", e.getMessage());
            // 降级：不过滤搜索后手动过滤
            SearchRequest fallbackRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK * 3)
                    .build();
            List<Document> documents = vectorStore.similaritySearch(fallbackRequest);
            return documents.stream()
                    .filter(doc -> {
                        Object docAgentId = doc.getMetadata().get("agentId");
                        if (docAgentId == null || !docAgentId.equals(agentId)) {
                            return false;
                        }
                        
                        // 检查租户隔离
                        if (tenantId != null) {
                            Object docTenantId = doc.getMetadata().get("tenantId");
                            if (docTenantId != null && !docTenantId.equals(tenantId) && !"null".equals(String.valueOf(docTenantId))) {
                                return false;
                            }
                        }
                        
                        // 检查用户隔离
                        if (userId != null) {
                            Object docUserId = doc.getMetadata().get("userId");
                            if (docUserId != null && !docUserId.equals(userId) && !"null".equals(String.valueOf(docUserId))) {
                                return false;
                            }
                        }
                        
                        return true;
                    })
                    .limit(topK)
                    .collect(Collectors.toList());
        }
    }


    /**
     * 使用 RAG 增强用户消息（检索相关知识并注入到上下文中）
     * 提供统一的 RAG 增强逻辑，供所有 Controller 使用
     */
    public String enhanceWithRag(Long agentId, String userMessage) {
        return enhanceWithRag(agentId, null, null, userMessage, DEFAULT_TOP_K);
    }

    public String enhanceWithRag(Long agentId, Long tenantId, Long userId, String userMessage, int topK) {
        try {
            List<Document> relatedKnowledge = searchRelatedKnowledge(agentId, tenantId, userId, userMessage, topK);

            // 打印检索结果（调试用）
            if (!relatedKnowledge.isEmpty()) {
                log.info("📚 [RAG] 检索到 {} 条相关知识:", relatedKnowledge.size());
                for (int i = 0; i < relatedKnowledge.size(); i++) {
                    Document doc = relatedKnowledge.get(i);
                    Object title = doc.getMetadata().get("title");
                    Double score = doc.getScore();
                    log.info("  [{}] title={}, score={}, contentPreview={}", 
                            i+1, title, score, 
                            doc.getText().length() > 50 ? doc.getText().substring(0, 50) + "..." : doc.getText());
                }
            }

            if (relatedKnowledge.isEmpty()) {
                log.debug("🔍 [RAG] 未检索到相关知识，使用原始消息");
                return userMessage;
            }

            String knowledgeContext = relatedKnowledge.stream()
                    .map(doc -> {
                        Object title = doc.getMetadata().get("title");
                        return String.format("- [%s] %s", title != null ? title : "未知", doc.getText());
                    })
                    .collect(Collectors.joining("\n"));

            String enhancedMessage = String.format("""
                    【相关知识参考】
                    %s

                    【用户问题】
                    %s

                    请结合上述相关知识回答问题。如果知识与问题无关，请忽略知识直接回答。
                    """, knowledgeContext, userMessage);


            log.debug("📚 [RAG] 知识库检索增强: agentId={}, 检索到 {} 条知识", agentId, relatedKnowledge.size());
            return enhancedMessage;

        } catch (Exception e) {
            log.warn("⚠️ [RAG] 增强失败，使用原始消息: {}", e.getMessage());
            return userMessage;
        }
    }

    /**
     * 删除知识的向量数据
     */
    public void deleteKnowledgeVectors(Long knowledgeId) {
        try {
            String filterExpression = String.format("knowledgeId == '%d'", knowledgeId);
            SearchRequest searchRequest = SearchRequest.builder()
                    .query("")
                    .topK(100)
                    .filterExpression(filterExpression)
                    .build();

            List<Document> documents = vectorStore.similaritySearch(searchRequest);
            if (!documents.isEmpty()) {
                List<String> ids = documents.stream()
                        .map(Document::getId)
                        .collect(Collectors.toList());
                vectorStore.delete(ids);
                log.info("🗑️ 删除知识向量: knowledgeId={}, count={}", knowledgeId, ids.size());
            }
        } catch (Exception e) {
            log.error("❌ 删除知识向量失败: knowledgeId={}, error={}", knowledgeId, e.getMessage(), e);
        }
    }
}
