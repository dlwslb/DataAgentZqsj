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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 向量存储服务
 * 统一处理向量的生成和存储，供知识库（RAG）和长期记忆使用
 */
@Slf4j
@Service
public class VectorStoreService {

    private final EmbeddingModel embeddingModel;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    
    @Value("${spring.ai.vectorstore.oceanbase.table-name:agent_scope_vector_store}")
    private String tableName;

    // 阻塞线程池：用于在响应式线程中执行阻塞的 Embedding 调用
    private static final java.util.concurrent.ExecutorService blockingThreadPool =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors()),
                    r -> {
                        Thread t = new Thread(r, "vector-embedding-" + System.currentTimeMillis());
                        t.setDaemon(true);
                        return t;
                    }
            );

    @Autowired
    public VectorStoreService(
            EmbeddingModel embeddingModel,
            @Qualifier("oceanbaseDataSource") DataSource dataSource,
            ObjectMapper objectMapper) {
        this.embeddingModel = embeddingModel;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        log.info("✅ VectorStoreService initialized");
    }

    /**
     * 存储向量到数据库（手写 SQL 方式）
     * 
     * @param id 向量 ID
     * @param content 原始内容
     * @param metadata 元数据
     * @return 是否成功
     */
    public boolean storeVector(String id, String content, Map<String, Object> metadata) {
        return storeVector(id, content, metadata, 2000);
    }

    /**
     * 存储向量到数据库（手写 SQL 方式）
     * 
     * @param id 向量 ID
     * @param content 原始内容
     * @param metadata 元数据
     * @param maxLength 内容最大长度（超过会被截断）
     * @return 是否成功
     */
    public boolean storeVector(String id, String content, Map<String, Object> metadata, int maxLength) {
        if (embeddingModel == null || dataSource == null) {
            log.error("❌ [向量存储] EmbeddingModel 或 DataSource 为空");
            return false;
        }

        try {
            log.debug("🔄 [向量存储] 开始生成向量: id={}, contentLength={}", id, 
                    content != null ? content.length() : 0);
            
            // 1. 生成向量（使用阻塞线程池，避免在 WebFlux 响应式线程中阻塞）
            CompletableFuture<List<float[]>> future = CompletableFuture.supplyAsync(
                () -> embeddingModel.embed(List.of(content)),
                blockingThreadPool
            );
            List<float[]> embeddings = future.get(30, TimeUnit.SECONDS);
            
            if (embeddings == null || embeddings.isEmpty()) {
                log.error("❌ [向量存储] 无法生成向量: id={}", id);
                return false;
            }

            float[] vector = embeddings.get(0);
            String vectorStr = arrayToString(vector);
            log.debug("✅ [向量存储] 向量生成成功: id={}, dimension={}", id, vector.length);

            // 2. 构建 metadata JSON
            String metadataJson;
            try {
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (Exception e) {
                log.error("❌ [向量存储] metadata 转 JSON 失败: {}", e.getMessage());
                metadataJson = "{}";
            }

            // 3. 截断内容
            String truncatedContent = content;
            if (content != null && content.length() > maxLength) {
                truncatedContent = content.substring(0, maxLength) + "...";
            }

            // 4. 手写 SQL 插入向量
            log.debug("💾 [向量存储] 插入向量: id={}, contentLength={}", id, truncatedContent.length());
            String sql = String.format(
                    "INSERT INTO %s (id, description, metadata, vector) VALUES (?, ?, ?, '%s')",
                    tableName, vectorStr);

            try (java.sql.Connection conn = dataSource.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setString(2, truncatedContent);
                ps.setString(3, metadataJson);

                int rows = ps.executeUpdate();
                log.debug("✅ [向量存储] 插入成功: id={}, 影响行数={}", id, rows);
                return rows > 0;
            }
        } catch (Exception e) {
            log.error("❌ [向量存储] 存储失败: id={}, error={}", id, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 更新向量（先删除再插入）
     * 
     * @param id 向量 ID
     * @param content 原始内容
     * @param metadata 元数据
     * @return 是否成功
     */
    public boolean updateVector(String id, String content, Map<String, Object> metadata) {
        return updateVector(id, content, metadata, 2000);
    }

    /**
     * 更新向量（先删除再插入）
     * 
     * @param id 向量 ID
     * @param content 原始内容
     * @param metadata 元数据
     * @param maxLength 内容最大长度（超过会被截断）
     * @return 是否成功
     */
    public boolean updateVector(String id, String content, Map<String, Object> metadata, int maxLength) {
        log.debug("🔄 [向量更新] 开始更新向量: id={}", id);
        
        // 1. 先删除旧向量
        deleteVector(id);
        
        // 2. 再插入新向量
        boolean success = storeVector(id, content, metadata, maxLength);
        
        if (success) {
            log.debug("✅ [向量更新] 更新成功: id={}", id);
        } else {
            log.error("❌ [向量更新] 更新失败: id={}", id);
        }
        
        return success;
    }

    /**
     * 删除向量
     * 
     * @param id 向量 ID
     * @return 是否成功
     */
    public boolean deleteVector(String id) {
        if (dataSource == null) {
            log.error("❌ [向量删除] DataSource 为空");
            return false;
        }

        try {
            String sql = "DELETE FROM " + tableName + " WHERE id = ?";
            try (java.sql.Connection conn = dataSource.getConnection();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, id);
                int rows = ps.executeUpdate();
                log.debug("✅ [向量删除] 删除成功: id={}, 影响行数={}", id, rows);
                return rows > 0;
            }
        } catch (Exception e) {
            log.error("❌ [向量删除] 删除失败: id={}, error={}", id, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 将 float[] 转换为字符串格式
     * 
     * @param array float 数组
     * @return 字符串格式，如 "[0.1, 0.2, 0.3]"
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
     * 
     * @param metadataJson JSON 字符串
     * @return Map 对象
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("⚠️ [向量查询] 解析 metadata 失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 向量相似度搜索（使用余弦距离）
     * 
     * @param query 查询文本
     * @param topK 返回结果数量
     * @param threshold 相似度阈值（0-1），低于此值的结果将被过滤
     * @return 文档列表（按相似度降序排列）
     */
    public List<Document> searchBySimilarity(String query, int topK, double threshold) {
        return searchBySimilarity(query, null, null, null, topK, threshold);
    }

    /**
     * 向量相似度搜索（支持租户和用户过滤）
     * 
     * @param query 查询文本
     * @param agentId Agent ID（可选）
     * @param tenantId 租户 ID（可选）
     * @param userId 用户 ID（可选）
     * @param topK 返回结果数量
     * @param threshold 相似度阈值（0-1），低于此值的结果将被过滤
     * @return 文档列表（按相似度降序排列）
     */
    public List<Document> searchBySimilarity(String query, Long agentId, Long tenantId, Long userId, int topK, double threshold) {
        return searchBySimilarity(query, agentId, tenantId, userId, null, null, topK, threshold);
    }

    /**
     * 向量相似度搜索（完整版本，支持长期记忆的所有过滤条件）
     * 
     * @param query 查询文本
     * @param agentId Agent ID（可选）
     * @param tenantId 租户 ID（可选）
     * @param userId 用户 ID（可选）
     * @param sessionId 会话 ID（可选，用于长期记忆的会话隔离）
     * @param memoryType 记忆类型（可选，如 "semantic"、"episodic"）
     * @param topK 返回结果数量
     * @param threshold 相似度阈值（0-1），低于此值的结果将被过滤
     * @return 文档列表（按相似度降序排列）
     */
    public List<Document> searchBySimilarity(
            String query, 
            Long agentId, 
            Long tenantId, 
            Long userId,
            String sessionId,
            String memoryType,
            int topK, 
            double threshold) {
        if (embeddingModel == null || dataSource == null) {
            log.error("❌ [向量查询] EmbeddingModel 或 DataSource 为空");
            return Collections.emptyList();
        }

        try {
            log.debug("🔍 [向量查询] 开始搜索: query={}, agentId={}, tenantId={}, userId={}, topK={}, threshold={}",
                    query, agentId, tenantId, userId, topK, threshold);

            // 1. 生成查询向量
            CompletableFuture<List<float[]>> future = CompletableFuture.supplyAsync(
                () -> embeddingModel.embed(List.of(query)),
                blockingThreadPool
            );
            List<float[]> embeddings = future.get(30, TimeUnit.SECONDS);
            
            if (embeddings == null || embeddings.isEmpty()) {
                log.warn("⚠️ [向量查询] 无法生成查询向量");
                return Collections.emptyList();
            }

            float[] queryVector = embeddings.get(0);
            String vectorStr = arrayToString(queryVector);

            // 2. 构建 SQL
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT id, description, metadata, ");
            sql.append(String.format("cosine_distance(vector, '%s') AS distance ", vectorStr));
            sql.append("FROM ").append(tableName).append(" WHERE 1=1 ");

            List<Object> params = new ArrayList<>();

            // 添加过滤条件
            if (agentId != null) {
                // 从 metadata 中过滤 agentId
                sql.append(" AND JSON_EXTRACT(metadata, '$.agentId') = ? ");
                params.add(String.valueOf(agentId));
            }

            if (tenantId != null) {
                sql.append(" AND (JSON_EXTRACT(metadata, '$.tenantId') = ? OR JSON_EXTRACT(metadata, '$.tenantId') IS NULL) ");
                params.add(String.valueOf(tenantId));
            }

            if (userId != null) {
                sql.append(" AND (JSON_EXTRACT(metadata, '$.userId') = ? OR JSON_EXTRACT(metadata, '$.userId') IS NULL) ");
                params.add(String.valueOf(userId));
            }

            sql.append(" ORDER BY distance ASC APPROXIMATE LIMIT ?");
            params.add(topK);
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {

                // 设置参数
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    List<Document> documents = new ArrayList<>();
                    int count = 0;
                    
                    while (rs.next()) {
                        String id = rs.getString("id");
                        String content = rs.getString("description");
                        String metadataJson = rs.getString("metadata");
                        double distance = rs.getDouble("distance");

                        // 计算相似度 = 1 - distance
                        double similarity = 1.0 - distance;

                        // 应用阈值过滤
                        if (similarity >= threshold) {
                            count++;
                            log.debug("  [{}] id={}, distance={}, similarity={}",
                                    count, id, distance, similarity);

                            // 解析 metadata
                            Map<String, Object> metadata = parseMetadata(metadataJson);
                            metadata.put("similarity", similarity);

                            Document doc = new Document(id, content, metadata);
                            documents.add(doc);
                        }
                    }
                    
                    log.info("✅ [向量查询] 检索到 {} 条相关结果 (threshold={}, 共扫描 {} 条)",
                            documents.size(), threshold, count);
                    return documents;
                }
            }
        } catch (Exception e) {
            log.error("❌ [向量查询] 搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 长期记忆向量相似度搜索（通过 long_term_memory 表关联查询）
     * 
     * @param query 查询文本
     * @param agentName Agent 名称
     * @param userId 用户 ID
     * @param sessionId 会话 ID（可选，用于会话隔离）
     * @param memoryType 记忆类型（可选，如 "semantic"、"episodic"）
     * @param topK 返回结果数量
     * @param threshold 相似度阈值（0-1），低于此值的结果将被过滤
     * @return 文档列表（按相似度降序排列）
     */
    public List<Document> searchLongTermMemories(
            String query,
            String agentName,
            String userId,
            String sessionId,
            String memoryType,
            int topK,
            double threshold) {
        
        if (embeddingModel == null || dataSource == null) {
            log.error("❌ [长期记忆查询] EmbeddingModel 或 DataSource 为空");
            return Collections.emptyList();
        }

        try {
            log.debug("🔍 [长期记忆查询] 开始搜索: query={}, agentName={}, userId={}, sessionId={}, memoryType={}, topK={}, threshold={}",
                    query, agentName, userId, sessionId, memoryType, topK, threshold);

            // 1. 生成查询向量
            CompletableFuture<List<float[]>> future = CompletableFuture.supplyAsync(
                () -> embeddingModel.embed(List.of(query)),
                blockingThreadPool
            );
            List<float[]> embeddings = future.get(30, TimeUnit.SECONDS);
            
            if (embeddings == null || embeddings.isEmpty()) {
                log.warn("⚠️ [长期记忆查询] 无法生成查询向量");
                return Collections.emptyList();
            }

            float[] queryVector = embeddings.get(0);
            String vectorStr = arrayToString(queryVector);

            // 2. 构建 SQL（通过 long_term_memory 表关联）
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT k.id, k.description, k.metadata, ");
            sql.append(String.format("cosine_distance(k.vector, '%s') AS distance ", vectorStr));
            sql.append("FROM ").append(tableName).append(" k ");
            sql.append("INNER JOIN (");
            sql.append("  SELECT content_vector_id ");
            sql.append("  FROM long_term_memory ");
            sql.append("  WHERE is_deleted = 0 ");

            List<Object> params = new ArrayList<>();

            // Agent 名称过滤
            if (agentName != null && !agentName.isEmpty()) {
                sql.append("    AND agent_name = ? ");
                params.add(agentName);
            }

            // 用户 ID 过滤（支持 NULL）
            if (userId != null && !userId.isEmpty()) {
                sql.append("    AND (user_id = ? OR user_id IS NULL) ");
                params.add(userId);
            }

            // 会话 ID 过滤（可选）
            if (sessionId != null && !sessionId.isEmpty()) {
                sql.append("    AND session_id = ? ");
                params.add(sessionId);
            }

            // 记忆类型过滤
            if (memoryType != null && !memoryType.isEmpty()) {
                sql.append("    AND memory_type = ? ");
                params.add(memoryType);
            }

            sql.append(") v ON k.id = v.content_vector_id ");
            sql.append(" ORDER BY distance ASC APPROXIMATE LIMIT ?");
            params.add(topK);

            // 3. 执行查询
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {

                // 设置参数
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    List<Document> documents = new ArrayList<>();
                    int count = 0;
                    
                    while (rs.next()) {
                        String id = rs.getString("id");
                        String content = rs.getString("description");
                        String metadataJson = rs.getString("metadata");
                        double distance = rs.getDouble("distance");

                        // 计算相似度 = 1 - distance
                        double similarity = 1.0 - distance;

                        // 应用阈值过滤
                        if (similarity >= threshold) {
                            count++;
                            log.debug("  [{}] id={}, distance={}, similarity={}",
                                    count, id, distance, similarity);

                            // 解析 metadata
                            Map<String, Object> metadata = parseMetadata(metadataJson);
                            metadata.put("similarity", similarity);

                            Document doc = new Document(id, content, metadata);
                            documents.add(doc);
                        }
                    }
                    
                    String purpose = (sessionId != null && !sessionId.isEmpty()) ? "session_retrieve" : "dedup_check";
                    log.info("✅ [长期记忆查询] 检索到 {} 条相关结果 (threshold={}, 共扫描 {} 条, purpose={})",
                            documents.size(), threshold, count, purpose);
                    return documents;
                }
            }
        } catch (Exception e) {
            log.error("❌ [长期记忆查询] 搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * RAG 知识库向量相似度搜索（通过 agent_scope_knowledge 表关联查询）
     * 
     * @param query 查询文本
     * @param agentId Agent ID
     * @param tenantId 租户 ID（可选）
     * @param userId 用户 ID（可选）
     * @param topK 返回结果数量
     * @param threshold 相似度阈值（0-1），低于此值的结果将被过滤
     * @return 文档列表（按相似度降序排列）
     */
    public List<Document> searchKnowledgeBase(
            String query,
            Long agentId,
            Long tenantId,
            Long userId,
            int topK,
            double threshold) {
        
        if (embeddingModel == null || dataSource == null) {
            log.error("❌ [知识库查询] EmbeddingModel 或 DataSource 为空");
            return Collections.emptyList();
        }

        try {
            log.debug("🔍 [知识库查询] 开始搜索: query={}, agentId={}, tenantId={}, userId={}, topK={}, threshold={}",
                    query, agentId, tenantId, userId, topK, threshold);

            // 1. 生成查询向量
            CompletableFuture<List<float[]>> future = CompletableFuture.supplyAsync(
                () -> embeddingModel.embed(List.of(query)),
                blockingThreadPool
            );
            List<float[]> embeddings = future.get(30, TimeUnit.SECONDS);
            
            if (embeddings == null || embeddings.isEmpty()) {
                log.warn("⚠️ [知识库查询] 无法生成查询向量");
                return Collections.emptyList();
            }

            float[] queryVector = embeddings.get(0);
            String vectorStr = arrayToString(queryVector);

            // 2. 构建 SQL（通过 agent_scope_knowledge 表关联）
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT k.id, k.description, k.metadata, ");
            sql.append(String.format("cosine_distance(k.vector, '%s') AS distance ", vectorStr));
            sql.append("FROM ").append(tableName).append(" k ");
            sql.append("INNER JOIN (");
            sql.append("  SELECT id FROM agent_scope_knowledge WHERE is_deleted = 0 AND agent_id = ? ");

            List<Object> params = new ArrayList<>();
            params.add(String.valueOf(agentId));

            // 租户过滤
            if (tenantId != null) {
                sql.append("    AND (tenant_id = ? OR tenant_id IS NULL) ");
                params.add(String.valueOf(tenantId));
            }

            // 用户过滤
            if (userId != null) {
                sql.append("    AND (user_id = ? OR user_id IS NULL) ");
                params.add(String.valueOf(userId));
            }

            sql.append(") v ON k.id = v.id ");
            sql.append(" ORDER BY distance ASC APPROXIMATE LIMIT ?");
            params.add(topK);

            // 3. 执行查询
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql.toString())) {

                // 设置参数
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    List<Document> documents = new ArrayList<>();
                    int count = 0;
                    
                    while (rs.next()) {
                        String id = rs.getString("id");
                        String content = rs.getString("description");
                        String metadataJson = rs.getString("metadata");
                        double distance = rs.getDouble("distance");

                        // 计算相似度 = 1 - distance
                        double similarity = 1.0 - distance;

                        // 应用阈值过滤
                        if (similarity >= threshold) {
                            count++;
                            log.debug("  [{}] id={}, distance={}, similarity={}",
                                    count, id, distance, similarity);

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
                                    log.warn("⚠️ [知识库查询] 解析嵌套 metadata 失败: {}", e.getMessage());
                                }
                            }

                            metadata.put("knowledgeId", id);
                            metadata.put("similarity", similarity);

                            Document doc = new Document(id, content, metadata);
                            documents.add(doc);
                        }
                    }
                    
                    log.info("✅ [知识库查询] 检索到 {} 条相关知识 (threshold={}, 共扫描 {} 条)",
                            documents.size(), threshold, count);
                    return documents;
                }
            }
        } catch (Exception e) {
            log.error("❌ [知识库查询] 搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
