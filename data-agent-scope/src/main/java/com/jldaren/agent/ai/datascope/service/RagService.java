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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * RAG 检索增强生成服务
 */
@Slf4j
@Service
public class RagService {

    private final AgentScopeKnowledgeMapper knowledgeMapper;
    private final VectorStore vectorStore;  // ⭐ 用于存储向量
    private final EmbeddingModel embeddingModel;  // ⭐ 用于生成查询向量
    private final DataSource dataSource;  // ⭐ 用于手写 SQL 查询

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
            @Qualifier("oceanbaseDataSource") DataSource dataSource) {
        this.knowledgeMapper = knowledgeMapper;
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.dataSource = dataSource;
        log.info(" ✅RagService initialized");
        //log.info("RagService initialized: dataSource={}", dataSource);
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

            // 创建 Document
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("knowledgeId", knowledge.getId());
            metadata.put("agentId", knowledge.getAgentId());
            metadata.put("tenantId", knowledge.getTenantId() != null ? String.valueOf(knowledge.getTenantId()) : "null");
            metadata.put("userId", knowledge.getUserId() != null ? String.valueOf(knowledge.getUserId()) : "null");
            metadata.put("type", knowledge.getType());
            metadata.put("title", knowledge.getTitle());
            
            log.info("📝 [向量化] 创建 Document: id={}, contentLength={}, metadataKeys={}", 
                    knowledge.getId(), 
                    knowledge.getContent() != null ? knowledge.getContent().length() : 0,
                    metadata.keySet());

            // ⭐ 关键：使用 knowledgeId 作为向量 ID，确保与知识库数据一致
            Document document = new Document(
                    String.valueOf(knowledge.getId()),  // 使用 knowledgeId 作为向量 ID
                    knowledge.getContent(),
                    metadata
            );

            // ⭐ 修改时：先删除旧记录，再插入新记录（手写 SQL 删除，避免 Spring AI bug）
            log.info("💾 [向量化] 先删除旧向量: id={}", knowledge.getId());
            deleteVectorById(String.valueOf(knowledge.getId()));
            log.info("✅ [向量化] 旧向量删除完成: id={}", knowledge.getId());

            // 存储到向量数据库
            log.info("💾 [向量化] 调用 vectorStore.add(): id={}", knowledge.getId());
            vectorStore.add(List.of(document));
            log.info("✅ [向量化] vectorStore.add() 完成: id={}", knowledge.getId());

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
     * ⭐ 手写 SQL 检索相关知识（支持 similarityThreshold 阈值过滤）
     * ⭐ OceanBase 向量参数必须直接拼接在 SQL 中，不能用 ? 占位符
     */
    private List<Document> searchWithSql(Long agentId, Long tenantId, Long userId, String query, int topK, double threshold) throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("🔍 [知识库SQL搜索] agentId={}, tenantId={}, userId={}, query={}, topK={}, threshold={}",
                agentId, tenantId, userId, query, topK, threshold);

        // 1. 生成查询向量（使用阻塞线程池，避免在 WebFlux 响应式线程中阻塞）
        CompletableFuture<List<float[]>> future = CompletableFuture.supplyAsync(
            () -> embeddingModel.embed(List.of(query)),
            blockingThreadPool
        );
        List<float[]> embeddings = future.get(30, TimeUnit.SECONDS);
        if (embeddings == null || embeddings.isEmpty()) {
            log.warn("⚠️ [SQL搜索] 无法生成查询向量");
            return Collections.emptyList();
        }

        float[] queryVector = embeddings.get(0);
        String vectorStr = arrayToString(queryVector);

        // 2. 构建 SQL（使用子查询结构：先从知识表筛选ID，再关联向量表）
        // ⭐ 关键：向量参数必须用 String.format 拼接，不能用 PreparedStatement 的 ? 占位符
        StringBuilder sql = new StringBuilder();
        String knowledgeTable = "agent_scope_knowledge";  // 知识表名

        // 子查询：从知识表筛选符合条件的 ID
        sql.append(String.format(
                "SELECT k.id, k.description, k.metadata, " +
                "cosine_distance(k.vector, '%s') AS similarity " +
                "FROM %s k " +
                "INNER JOIN (",
                vectorStr, tableName));

        // 子查询部分
        sql.append("SELECT id FROM ").append(knowledgeTable).append(" WHERE is_deleted = 0 and agent_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(String.valueOf(agentId));

        // 租户过滤
        if (tenantId != null) {
            sql.append(" AND (tenant_id = ? OR tenant_id is null)");
            params.add(String.valueOf(tenantId));
        }

        // 用户过滤
        if (userId != null) {
            sql.append(" AND (user_id = ? OR user_id is null)");
            params.add(String.valueOf(userId));
        }

        sql.append(") v ON k.id = v.id ");
        sql.append(String.format("ORDER BY similarity desc APPROXIMATE LIMIT ? ", topK));
        params.add(topK);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            // 设置参数
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            // ⭐ 构建完整可执行的 SQL（向量已直接拼接）
            String fullSql = sql.toString();
            // 将 ? 替换为实际参数值（LIMIT 参数不加引号）
            int paramIndex = 0;
            for (Object param : params) {
                if (paramIndex == params.size() - 1) {
                    // LIMIT 参数不加引号
                    fullSql = fullSql.replaceFirst("\\?", String.valueOf(param));
                } else {
                    fullSql = fullSql.replaceFirst("\\?", "'" + param + "'");
                }
                paramIndex++;
            }
            //log.debug("📝 [SQL搜索] 可执行SQL:\n{}", fullSql);

            try (ResultSet rs = ps.executeQuery()) {
                List<Document> documents = new ArrayList<>();
                int count = 0;
                while (rs.next()) {
                    String id = rs.getString("id");
                    String content = rs.getString("description");
                    String metadataJson = rs.getString("metadata");
                    double distance = rs.getDouble("similarity");

                    // ⭐ 计算相似度 = 1 - cosine_distance（distance 越小，相似度越高）
                    double similarity = 1.0 - distance;

                    // ⭐ 应用 similarityThreshold 阈值过滤
                    if (similarity >= threshold) {
                        count++;
                        log.debug("  [{}] id={}, distance={}, similarity={}, contentLength={}",
                                count, id, distance, similarity, content != null ? content.length() : 0);

                        // 解析 metadata
                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        
                        // ⭐ 处理嵌套的 metadata：向量表中 metadata 字段可能包含嵌套的 metadata 字符串
                        if (metadata.containsKey("metadata") && metadata.get("metadata") instanceof String) {
                            String nestedMetadataJson = (String) metadata.get("metadata");
                            try {
                                Map<String, Object> nestedMetadata = objectMapper.readValue(
                                    nestedMetadataJson, new TypeReference<Map<String, Object>>() {}
                                );
                                // 将嵌套的 metadata 合并到外层
                                metadata.putAll(nestedMetadata);
                            } catch (Exception e) {
                                log.warn("⚠️ [RAG] 解析嵌套 metadata 失败: {}", e.getMessage());
                            }
                        }

                        metadata.put("knowledgeId", id);
                        metadata.put("similarity", similarity);  // 存储相似度分数

                        Document doc = new Document(id, content, metadata);
                        documents.add(doc);
                    }
                }
                log.info("✅ [SQL搜索] 检索到 {} 条相关知识 (threshold={}, 共扫描 {} 条)",
                        documents.size(), threshold, count);
                return documents;
            }
        } catch (Exception e) {
            log.error("❌ [SQL搜索] 执行失败: {}", e.getMessage(), e);
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
     * 删除知识的向量数据（手写 SQL，避免 Spring AI Alibaba delete bug）
     */
    public void deleteKnowledgeVectors(Long knowledgeId) {
        try {
            String vectorId = String.valueOf(knowledgeId);
            String sql = "DELETE FROM " + tableName + " WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, vectorId);
                int rows = ps.executeUpdate();
                log.info("🗑️ 删除知识向量: knowledgeId={}, vectorId={}, 影响行数={}", knowledgeId, vectorId, rows);
            }
        } catch (Exception e) {
            log.error("❌ 删除知识向量失败: knowledgeId={}, error={}", knowledgeId, e.getMessage(), e);
        }
    }

    /**
     * 删除知识向量（手写 SQL 版本，供内部调用）
     */
    private void deleteVectorById(String vectorId) {
        try {
            String sql = "DELETE FROM " + tableName + " WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, vectorId);
                int rows = ps.executeUpdate();
                log.info("🗑️ 删除向量: vectorId={}, 影响行数={}", vectorId, rows);
            }
        } catch (Exception e) {
            log.warn("⚠️ 删除向量失败（或记录不存在）: vectorId={}, error={}", vectorId, e.getMessage());
        }
    }
}
