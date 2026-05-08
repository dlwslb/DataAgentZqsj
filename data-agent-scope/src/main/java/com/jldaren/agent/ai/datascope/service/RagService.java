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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jldaren.agent.ai.datascope.entity.AgentScopeKnowledge;
import com.jldaren.agent.ai.datascope.mapper.AgentScopeKnowledgeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * RAG 检索增强生成服务
 */
@Slf4j
@Service
public class RagService {

    private final AgentScopeKnowledgeMapper knowledgeMapper;
    private final VectorStore vectorStore;  // ⭐ 用于检索（保留兼容性）
    private final EmbeddingModel embeddingModel;  // ⭐ 用于生成查询向量
    private final DataSource dataSource;  // ⭐ 用于手写 SQL 查询
    private final VectorStoreService vectorStoreService;  // ⭐ 统一的向量存储服务

    // ⭐ 阻塞线程池：用于在响应式线程中执行阻塞的 Embedding 调用
    private static final java.util.concurrent.ExecutorService blockingThreadPool =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors(),
                    r -> {
                        Thread t = new Thread(r, "rag-embedding-blocking");
                        t.setDaemon(true);
                        return t;
                    });

    // ⭐ 构造函数注入 DataSource（使用 @Qualifier 指定特定 Bean）
    @Autowired
    public RagService(
            AgentScopeKnowledgeMapper knowledgeMapper,
            VectorStore vectorStore,
            EmbeddingModel embeddingModel,
            @Qualifier("oceanbaseDataSource") DataSource dataSource,
            VectorStoreService vectorStoreService) {
        this.knowledgeMapper = knowledgeMapper;
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.dataSource = dataSource;
        this.vectorStoreService = vectorStoreService;
        log.info(" ✅RagService initialized");
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String tableName = "agent_scope_vector_store";  // ⭐ 向量表名
    /**
     * 向量化并存储知识
     */
    public void embedAndStoreKnowledge(AgentScopeKnowledge knowledge) {
        try {
            log.info("📊 [向量化] 开始处理: id={}, title={}, agentId={}, tenantId={}, userId={}", 
                    knowledge.getId(), knowledge.getTitle(), knowledge.getAgentId(), 
                    knowledge.getTenantId(), knowledge.getUserId());
            
            // 更新状态为处理中
            knowledgeMapper.updateEmbeddingStatus(knowledge.getId(), "PROCESSING");
            log.info("✅ [向量化] 状态更新为 PROCESSING: id={}", knowledge.getId());

            // 构建 metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("knowledgeId", knowledge.getId());
            metadata.put("agentId", knowledge.getAgentId());
            metadata.put("tenantId", knowledge.getTenantId() != null ? String.valueOf(knowledge.getTenantId()) : "null");
            metadata.put("userId", knowledge.getUserId() != null ? String.valueOf(knowledge.getUserId()) : "null");
            metadata.put("type", knowledge.getType());
            metadata.put("title", knowledge.getTitle());
            
            log.info("📝 [向量化] 准备元数据: id={}, contentLength={}, metadataKeys={}", 
                    knowledge.getId(), 
                    knowledge.getContent() != null ? knowledge.getContent().length() : 0,
                    metadata.keySet());

            // ⭐ 修改时：使用 updateVector（先删除再插入）
            log.info("💾 [向量化] 开始更新向量: id={}", knowledge.getId());
            boolean success = vectorStoreService.updateVector(
                    String.valueOf(knowledge.getId()),
                    knowledge.getContent(),
                    metadata,
                    2000  // 最大长度
            );
            
            if (!success) {
                throw new RuntimeException("向量更新失败");
            }
            
            log.info("✅ [向量化] 向量更新完成: id={}", knowledge.getId());

            // 更新状态为完成
            knowledgeMapper.updateEmbeddingStatus(knowledge.getId(), "COMPLETED");
            log.info("✅ 知识向量化成功: id={}, title={}, tenantId={}, userId={}", 
                    knowledge.getId(), knowledge.getTitle(), knowledge.getTenantId(), knowledge.getUserId());
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // 截取错误消息，避免过长
            if (errorMsg.length() > 500) {
                errorMsg = errorMsg.substring(0, 500);
            }
            knowledgeMapper.updateEmbeddingStatusWithError(knowledge.getId(), "FAILED", errorMsg);
            log.error("❌ 知识向量化失败: id={}, error={}", knowledge.getId(), e.getMessage(), e);
        }
    }

    private static final int DEFAULT_TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = 0.7; // 相似度阈值，低于此值的结果将被过滤


    /**
     * ⭐ 使用统一的 VectorStoreService 检索相关知识
     */
    private List<Document> searchWithSql(Long agentId, Long tenantId, Long userId, String query, int topK, double threshold) throws ExecutionException, InterruptedException, TimeoutException {
        if (vectorStoreService == null) {
            log.warn("⚠️ [知识库查询] VectorStoreService 为空，无法执行查询");
            return Collections.emptyList();
        }

        log.debug("🔍 [知识库SQL搜索] agentId={}, tenantId={}, userId={}, query={}, topK={}, threshold={}",
                agentId, tenantId, userId, query, topK, threshold);

        try {
            // 1. 调用统一的 VectorStoreService 进行查询
            List<Document> documents = vectorStoreService.searchKnowledgeBase(
                    query,
                    agentId,
                    tenantId,
                    userId,
                    topK,
                    threshold
            );

            if (documents.isEmpty()) {
                return Collections.emptyList();
            }

            log.info("✅ [知识库查询] 检索到 {} 条相关知识", documents.size());
            return documents;

        } catch (Exception e) {
            log.error("❌ [知识库查询] 搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 将 float[] 数组转换为 SQL 字符串格式
     */
    private String arrayToString(float[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(array[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 解析 metadata JSON
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("⚠️ 解析 metadata 失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 检索相关知识
     * 使用手写 SQL，支持 similarityThreshold 阈值过滤
     */
    public List<Document> searchRelatedKnowledge(Long agentId, String query, int topK) {
        return searchRelatedKnowledge(agentId, null, null, query, topK);
    }

    /**
     * 检索相关知识（支持租户和用户隔离）
     * ⭐ 使用手写 SQL，完全支持 similarityThreshold 阈值过滤
     */
    public List<Document> searchRelatedKnowledge(Long agentId, Long tenantId, Long userId, String query, int topK) {
        log.debug("🔍 [RAG] 搜索请求: agentId={}, tenantId={}, userId={}, query={}, topK={}, threshold={}",
                agentId, tenantId, userId, query, topK, SIMILARITY_THRESHOLD);

        try {
            // ⭐ 使用手写 SQL 搜索（支持 similarityThreshold）
            return searchWithSql(agentId, tenantId, userId, query, topK, SIMILARITY_THRESHOLD);
        } catch (Exception e) {
            log.error("❌ [RAG] SQL搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
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
                    Object score = doc.getMetadata().get("similarity");  // ⭐ 从 metadata 获取分数
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

                    请基于上述相关知识回答问题。如果知识库内容与问题相关，必须引用；不要说"未提及"。
                    """, knowledgeContext, userMessage);


            log.debug("📚 [RAG] 知识库检索增强: agentId={}, 检索到 {} 条知识", agentId, relatedKnowledge.size());
            return enhancedMessage;

        } catch (Exception e) {
            log.warn("⚠️ [RAG] 增强失败，使用原始消息: {}", e.getMessage());
            return userMessage;
        }
    }

    /**
     * 删除知识的向量数据（使用统一的 VectorStoreService）
     */
    public void deleteKnowledgeVectors(Long knowledgeId) {
        if (vectorStoreService == null) {
            log.warn("⚠️ [删除向量] VectorStoreService 为空");
            return;
        }

        try {
            String vectorId = String.valueOf(knowledgeId);
            boolean success = vectorStoreService.deleteVector(vectorId);
            log.info("🗑️ 删除知识向量: knowledgeId={}, vectorId={}, success={}", knowledgeId, vectorId, success);
        } catch (Exception e) {
            log.error("❌ 删除知识向量失败: knowledgeId={}, error={}", knowledgeId, e.getMessage(), e);
        }
    }

    /**
     * 删除知识向量（使用统一的 VectorStoreService，供内部调用）
     */
    private void deleteVectorById(String vectorId) {
        if (vectorStoreService == null) {
            log.warn("⚠️ [删除向量] VectorStoreService 为空");
            return;
        }

        try {
            boolean success = vectorStoreService.deleteVector(vectorId);
            log.info("🗑️ 删除向量: vectorId={}, success={}", vectorId, success);
        } catch (Exception e) {
            log.warn("⚠️ 删除向量失败（或记录不存在）: vectorId={}, error={}", vectorId, e.getMessage());
        }
    }
}
