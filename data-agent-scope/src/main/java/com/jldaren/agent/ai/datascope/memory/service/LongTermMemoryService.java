/*
 * Copyright 2024-2026 the original author or authors.
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
package com.jldaren.agent.ai.datascope.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jldaren.agent.ai.datascope.memory.config.LongTermMemoryConfig;
import com.jldaren.agent.ai.datascope.memory.entity.MemoryConfig;
import com.jldaren.agent.ai.datascope.memory.entity.MemoryRecord;
import com.jldaren.agent.ai.datascope.memory.entity.MemorySearchResult;
import com.jldaren.agent.ai.datascope.memory.mapper.LongTermMemoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 长期记忆服务类  [长期记忆] 分析内容: importance=0.65, threshold=0.60数据库配置 大于这个才能保存
 * 提供记忆的存储、检索和管理功能
 */
@Slf4j
@Service
public class LongTermMemoryService {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String VECTOR_TABLE = "agent_scope_vector_store";
    private static final String MEMORY_TABLE = "long_term_memory";

    private final LongTermMemoryMapper memoryMapper;
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final LongTermMemoryConfig config;
    private final ChatModel chatModel;
    private final DataSource dataSource;  // ⭐ 手写 SQL 用

    // ⭐ 阻塞线程池：用于在响应式线程中执行阻塞的 Embedding 调用
    private static final java.util.concurrent.ExecutorService blockingThreadPool =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Runtime.getRuntime().availableProcessors(),
                    r -> {
                        Thread t = new Thread(r, "embedding-blocking");
                        t.setDaemon(true);
                        return t;
                    });

    /**
     * 配置缓存 (agentName:tenantId -> MemoryConfig)
     * 带 TTL 过期机制，避免数据库配置更新后缓存不刷新
     */
    private final Map<String, CachedConfig> configCache = new ConcurrentHashMap<>();

    private static class CachedConfig {
        final MemoryConfig config;
        final long expireAt;

        CachedConfig(MemoryConfig config) {
            this.config = config;
            this.expireAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    public LongTermMemoryService(
            @Autowired(required = false) LongTermMemoryMapper memoryMapper,
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) EmbeddingModel embeddingModel,
            @Autowired(required = false) ChatModel chatModel,
            @Autowired @Qualifier("oceanbaseDataSource") DataSource dataSource,
            LongTermMemoryConfig config) {
        this.memoryMapper = memoryMapper;
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.dataSource = dataSource;
        this.config = config;
        log.info("✅LongTermMemoryService initialized: vectorStore={}, embeddingModel={}, chatModel={}, dataSource={}",
                vectorStore != null, embeddingModel != null, chatModel != null, dataSource != null);
    }

    // ========== 记忆 CRUD ==========

    @Transactional(rollbackFor = Exception.class)
    public String recordMemory(String agentName, String userId, String sessionId,
                               String content, Double importance, Map<String, Object> metadata,
                               String tenantId) {
        return recordMemory(agentName, userId, sessionId, content, importance, metadata, tenantId, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public String recordMemory(String agentName, String userId, String sessionId,
                               String content, Double importance, Map<String, Object> metadata,
                               String tenantId, boolean isAuto) {
        if (!isEnabled(agentName, tenantId)) {
            log.debug("LongTermMemory is disabled for agent: {}", agentName);
            return null;
        }

        MemoryConfig config = getConfig(agentName, tenantId);
        String memoryId = UUID.randomUUID().toString();

        LocalDateTime expireTime = null;
        if (config.getRetentionDays() != null && config.getRetentionDays() > 0) {
            expireTime = LocalDateTime.now().plusDays(config.getRetentionDays());
        }

        try {
            String vectorId = storeVector(content, metadata, agentName, userId, sessionId, tenantId, memoryId);

            MemoryRecord record = MemoryRecord.builder()
                    .id(memoryId)
                    .tenantId(tenantId)
                    .agentName(agentName)
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(config.getMemoryType())
                    .content(content)
                    .contentVectorId(vectorId)
                    .metadata(metadata)
                    .importanceScore(importance != null ? importance : config.getAutoRecordThreshold().doubleValue())
                    .isAuto(isAuto ? 1 : 0)
                    .expireTime(expireTime)
                    .accessCount(0)
                    .lastAccessTime(LocalDateTime.now())
                    .isDeleted(0)
                    .build();

            if (memoryMapper != null) {
                memoryMapper.insert(record);
                log.debug("Recorded memory: id={}, agent={}, user={}, importance={}",
                        memoryId, agentName, userId, String.format("%.2f", record.getImportanceScore()));
            } else {
                log.warn("MemoryMapper is null, memory not persisted to database");
            }

            return memoryId;
        } catch (Exception e) {
            log.error("Failed to record memory: agent={}, user={}", agentName, userId, e);
            return null;
        }
    }

    /**
     * 更新已有记忆的内容（Mem0 式记忆更新）
     * 当用户说了矛盾信息（如"我改名叫李四"），更新而非新增
     *
     * @param memoryId       原记忆 ID
     * @param newContent     新内容
     * @param importanceScore 新重要性评分
     * @return 是否更新成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean updateMemoryContent(String memoryId, String newContent, Double importanceScore) {
        if (memoryMapper == null) return false;

        try {
            MemoryRecord existing = memoryMapper.selectById(memoryId);
            if (existing == null) return false;

            // 更新 MySQL 内容
            int rows = memoryMapper.updateContent(memoryId, newContent,
                    importanceScore != null ? importanceScore : existing.getImportanceScore());
            if (rows <= 0) return false;

            // ⭐ 更新向量（先删后插，使用手写 SQL）
            if (existing.getContentVectorId() != null) {
                // 1. 删除旧向量
                deleteVectorById(existing.getContentVectorId());

                // 2. 生成新向量
                if (embeddingModel != null && dataSource != null) {
                    updateVector(memoryId, newContent, existing);
                }
            } else if (dataSource != null && embeddingModel != null) {
                // 如果之前没有向量，直接创建新的
                updateVector(memoryId, newContent, existing);
            }

            log.info("✅ [长期记忆更新] 记忆更新成功: id={}, oldLen={}, newLen={}", memoryId,
                    existing.getContent() != null ? existing.getContent().length() : 0, newContent.length());
            return true;
        } catch (Exception e) {
            log.error("❌ [长期记忆更新] 记忆更新失败: id={}, error={}", memoryId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * ⭐ 手写 SQL 更新向量
     */
    private void updateVector(String memoryId, String newContent, MemoryRecord existing) {
        try {
            // 1. 生成向量（使用阻塞线程池，避免在 WebFlux 响应式线程中阻塞）
            CompletableFuture<List<float[]>> future = CompletableFuture.supplyAsync(
                () -> embeddingModel.embed(List.of(newContent)),
                blockingThreadPool
            );
            List<float[]> embeddings = future.get(30, TimeUnit.SECONDS);
            if (embeddings == null || embeddings.isEmpty()) {
                log.error("❌ [长期记忆更新] 无法生成向量: id={}", memoryId);
                return;
            }

            float[] vector = embeddings.get(0);
            String vectorStr = arrayToString(vector);

            // 2. 构建 metadata JSON
            Map<String, Object> docMetadata = new HashMap<>();
            if (existing.getMetadata() != null) {
                docMetadata.putAll(existing.getMetadata());
            }
            docMetadata.put("agentName", existing.getAgentName());
            docMetadata.put("userId", existing.getUserId());
            docMetadata.put("tenantId", existing.getTenantId());
            String metadataJson = objectMapper.writeValueAsString(docMetadata);

            // 3. 截断内容
            int maxLength = config != null ? config.getMaxContentLength() : 2000;
            String truncatedContent = newContent.length() > maxLength
                    ? newContent.substring(0, maxLength) + "..." : newContent;

            // 4. 插入新向量
            String sql = String.format(
                    "INSERT INTO %s (id, description, metadata, vector) VALUES (?, ?, ?, '%s')",
                    VECTOR_TABLE, vectorStr);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, memoryId);
                ps.setString(2, truncatedContent);
                ps.setString(3, metadataJson);

                int rows = ps.executeUpdate();
                log.info("✅ [长期记忆更新] 向量更新成功: id={}, 影响行数={}", memoryId, rows);
            }
        } catch (Exception e) {
            log.error("❌ [长期记忆更新] 向量更新失败: id={}, error={}", memoryId, e.getMessage(), e);
        }
    }

    /**
     * ⭐ 手写 SQL 删除向量
     */
    private void deleteVectorById(String vectorId) {
        if (vectorId == null || dataSource == null) return;

        try {
            String sql = "DELETE FROM " + VECTOR_TABLE + " WHERE id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, vectorId);
                int rows = ps.executeUpdate();
                log.info("🗑️ [长期记忆] 删除向量: id={}, 影响行数={}", vectorId, rows);
            }
        } catch (Exception e) {
            log.warn("⚠️ [长期记忆] 删除向量失败: id={}, error={}", vectorId, e.getMessage());
        }
    }

    // ========== 检索 ==========

    public List<MemorySearchResult> retrieveMemories(String agentName, String userId,
                                                     String query, Integer topK, String tenantId) {
        return retrieveMemories(agentName, userId, query, topK, tenantId, null);
    }

    /**
     * 检索相关记忆（带会话隔离）
     * 当 sessionId 不为空时，只检索该会话的记忆；
     * 当 sessionId 为空时，检索该用户所有会话的记忆
     */
    public List<MemorySearchResult> retrieveMemories(String agentName, String userId,
                                                     String query, Integer topK, String tenantId,
                                                     String sessionId) {
        if (!isEnabled(agentName, tenantId)) {
            return Collections.emptyList();
        }

        MemoryConfig config = getConfig(agentName, tenantId);
        int limit = topK != null ? Math.min(topK, config.getMaxTopk()) : config.getDefaultTopk();
        double threshold = config.getSimilarityThreshold().doubleValue();

        try {
            if (vectorStore != null && embeddingModel != null) {
                return retrieveViaVectorStore(agentName, userId, query, limit, threshold, config, sessionId);
            } else if (memoryMapper != null) {
                if (sessionId != null && !sessionId.isEmpty()) {
                    return memoryMapper.searchByVectorWithSession(agentName, userId, tenantId, query, threshold, limit, config.getMemoryType(), sessionId);
                } else {
                    return memoryMapper.searchByVector(agentName, userId, tenantId, query, threshold, limit, config.getMemoryType());
                }
            } else {
                log.warn("Both vectorStore and memoryMapper are unavailable");
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("Failed to retrieve memories: agent={}, user={}, session={}", agentName, userId, sessionId, e);
            return Collections.emptyList();
        }
    }

    /**
     * ⭐ 手写 SQL 检索相关记忆（支持 similarityThreshold 阈值过滤）
     * ⭐ OceanBase 向量参数必须直接拼接在 SQL 中，不能用 ? 占位符
     */
    private List<MemorySearchResult> retrieveViaVectorStore(String agentName, String userId,
                                                            String query, int limit, double threshold,
                                                            MemoryConfig config, String sessionId) throws ExecutionException, InterruptedException, TimeoutException {
        log.debug("🔍 [长期记忆SQL搜索] agentName={}, userId={}, query={}, limit={}, threshold={}, sessionId={}",
                agentName, userId, query, limit, threshold, sessionId);

        // 1. 生成查询向量（使用阻塞线程池，避免在 WebFlux 响应式线程中阻塞）
        CompletableFuture<List<float[]>> future = CompletableFuture.supplyAsync(
            () -> embeddingModel.embed(List.of(query)),
            blockingThreadPool
        );
        List<float[]> embeddings = future.get(30, TimeUnit.SECONDS);
        if (embeddings == null || embeddings.isEmpty()) {
            log.warn("⚠️ [长期记忆SQL搜索] 无法生成查询向量");
            return Collections.emptyList();
        }

        float[] queryVector = embeddings.get(0);
        String vectorStr = arrayToString(queryVector);

        // 2. 构建 SQL（使用子查询结构：先从长期记忆表筛选ID，再关联向量表）
        StringBuilder sql = new StringBuilder();

        // 主查询：从向量表查询
        sql.append(String.format(
                "SELECT k.id, k.description, k.metadata, " +
                "cosine_distance(k.vector, '%s') AS distance " +
                "FROM %s k " +
                "INNER JOIN (",
                vectorStr, VECTOR_TABLE));

        // 子查询：从长期记忆表筛选符合条件的 ID
        sql.append("SELECT content_vector_id FROM ").append(MEMORY_TABLE).append(" WHERE is_deleted = 0 ");
        sql.append("AND agent_name = ? ");
        List<Object> params = new ArrayList<>();
        params.add(agentName);

        // 用户过滤
        if (userId != null && !userId.isEmpty()) {
            sql.append("AND (user_id = ? OR user_id IS NULL)");
            params.add(userId);
        }

        // 会话过滤（可选）
        if (sessionId != null && !sessionId.isEmpty()) {
            sql.append("AND session_id = ?");
            params.add(sessionId);
        }

        // 记忆类型过滤
        if (config != null && config.getMemoryType() != null) {
            sql.append("AND memory_type = ?");
            params.add(config.getMemoryType());
        }

        sql.append(") v ON k.id = v.content_vector_id ");
        sql.append(String.format("ORDER BY distance ASC APPROXIMATE LIMIT ? ", limit));
        params.add(limit);

        // ⭐ 构建完整可执行的 SQL
//        String fullSql = sql.toString();
//        log.debug("📝 [长期记忆SQL搜索] 可执行SQL:\n{}", fullSql);
//        log.debug("📝 [长期记忆SQL搜索] 参数: {}", params);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            // 设置参数
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                List<MemorySearchResult> results = new ArrayList<>();
                List<String> accessedIds = new ArrayList<>();
                int count = 0;

                while (rs.next()) {
                    String memoryId = rs.getString("id");
                    String content = rs.getString("description");
                    String metadataJson = rs.getString("metadata");
                    double distance = rs.getDouble("distance");

                    // ⭐ 计算相似度 = 1 - cosine_distance（distance 越小，相似度越高）
                    double similarity = 1.0 - distance;

                    // ⭐ 应用 similarityThreshold 阈值过滤
                    if (similarity >= threshold) {
                        count++;
                        log.debug("  [{}] id={}, distance={}, similarity={}, contentLength={}",
                                count, memoryId, distance, similarity, content != null ? content.length() : 0);

                        // 解析 metadata
                        Map<String, Object> metadata = parseMetadata(metadataJson);

                        // 从数据库查询完整的记忆记录
                        MemoryRecord record = null;
                        if (memoryMapper != null) {
                            record = memoryMapper.selectById(memoryId);
                            if (record != null) {
                                accessedIds.add(memoryId);
                            }
                        }

                        results.add(MemorySearchResult.builder()
                                .id(memoryId)
                                .content(content)
                                .memoryType(record != null ? record.getMemoryType() : "semantic")
                                .similarityScore(similarity)
                                .importanceScore(record != null ? record.getImportanceScore() : 0.5)
                                .metadata(metadata)
                                .sessionId(record != null ? record.getSessionId() : null)
                                .createTime(record != null && record.getCreateTime() != null ?
                                        record.getCreateTime().toString() : null)
                                .isAuto(record != null ? record.getIsAuto() == 1 : true)
                                .build());
                    }
                }

                log.debug("✅ [长期记忆SQL搜索] 检索到 {} 条相关记忆 (threshold={}, 共扫描 {} 条)",
                        results.size(), threshold, count);

                // 批量更新访问信息
                if (memoryMapper != null && !accessedIds.isEmpty()) {
                    try {
                        memoryMapper.batchUpdateAccessInfo(accessedIds);
                    } catch (Exception e) {
                        log.warn("⚠️ 批量更新访问信息失败: {}", e.getMessage());
                    }
                }

                return results;
            }
        } catch (Exception e) {
            log.error("❌ [长期记忆SQL搜索] 执行失败: {}", e.getMessage(), e);
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

    private double computeSimilarity(Map<String, Object> metadata) {
        Object distanceObj = metadata.getOrDefault("distance", 0.0);
        double distance;
        if (distanceObj instanceof Number) {
            distance = ((Number) distanceObj).doubleValue();
        } else if (distanceObj instanceof String) {
            try {
                distance = Double.parseDouble((String) distanceObj);
            } catch (NumberFormatException e) {
                distance = 0.0;
            }
        } else {
            distance = 0.0;
        }
        double similarityScore = 1.0 - distance / 2.0;
        return Math.max(0.0, Math.min(1.0, similarityScore));
    }

    public List<MemorySearchResult> retrieveMemories(String agentName, String userId, String query) {
        return retrieveMemories(agentName, userId, query, null, null);
    }

    public List<MemoryRecord> getMemoriesByUser(String agentName, String userId, String tenantId, Integer limit) {
        if (memoryMapper == null) return Collections.emptyList();
        return memoryMapper.selectByUser(agentName, userId, tenantId, null, limit);
    }

    public List<MemoryRecord> getMemoriesBySession(String agentName, String userId, String sessionId, String tenantId) {
        if (memoryMapper == null) return Collections.emptyList();
        return memoryMapper.selectBySession(agentName, userId, sessionId, tenantId);
    }

    // ========== 删除 ==========

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteMemory(String memoryId) {
        if (memoryMapper == null) return false;

        try {
            MemoryRecord record = memoryMapper.selectById(memoryId);
            if (record == null) return false;

            int result = memoryMapper.delete(memoryId);
            if (result > 0 && record.getContentVectorId() != null) {
                deleteVectorById(record.getContentVectorId());
            }

            log.info("Deleted memory: id={}", memoryId);
            return result > 0;
        } catch (Exception e) {
            log.error("Failed to delete memory: id={}", memoryId, e);
            return false;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int clearMemories(String agentName, String userId, String tenantId) {
        if (memoryMapper == null) return 0;

        try {
            List<MemoryRecord> records = memoryMapper.selectByUser(agentName, userId, tenantId, null, null);
            if (records.isEmpty()) return 0;

            List<String> vectorIds = new ArrayList<>();
            List<String> memoryIds = new ArrayList<>();

            for (MemoryRecord record : records) {
                memoryIds.add(record.getId());
                if (record.getContentVectorId() != null) {
                    vectorIds.add(record.getContentVectorId());
                }
            }

            int deleted = memoryMapper.batchDelete(memoryIds);
            for (String vectorId : vectorIds) {
                deleteVectorById(vectorId);
            }

            log.info("Cleared {} memories for agent={}, user={}", deleted, agentName, userId);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to clear memories: agent={}, user={}", agentName, userId, e);
            return 0;
        }
    }

    public int getMemoryCount(String agentName, String userId, String tenantId) {
        if (memoryMapper == null) return 0;
        return memoryMapper.countByUser(agentName, userId, tenantId);
    }

    /**
     * 检查是否已存在相同内容的记忆（DB 精确匹配，避免全量加载）
     */
    public boolean isDuplicateContent(String agentName, String userId, String tenantId, String content) {
        if (memoryMapper == null || content == null) return false;
        return memoryMapper.existsByContent(agentName, userId, tenantId, content.trim()) > 0;
    }

    // ========== 向量存储 ==========

    /**
     * ⭐ 手写 SQL 存储向量到 agent_scope_vector_store 表
     */
    private String storeVector(String content, Map<String, Object> metadata,
                                String agentName, String userId, String sessionId,
                                String tenantId, String memoryId) {
        if (embeddingModel == null || dataSource == null) {
            log.warn("⚠️ [长期记忆向量存储] EmbeddingModel 或 DataSource 为空，跳过向量存储");
            return null;
        }

        try {
            // 1. 生成向量（使用阻塞线程池，避免在 WebFlux 响应式线程中阻塞）
            log.debug("🔄 [长期记忆向量存储] 生成向量: id={}, contentLength={}，content", memoryId, content.length(),content);
            
            CompletableFuture<List<float[]>> future = CompletableFuture.supplyAsync(
                () -> embeddingModel.embed(List.of(content)),
                blockingThreadPool
            );
            List<float[]> embeddings = future.get(30, TimeUnit.SECONDS);
            if (embeddings == null || embeddings.isEmpty()) {
                log.error("❌ [长期记忆向量存储] 无法生成向量: id={}", memoryId);
                return null;
            }

            float[] vector = embeddings.get(0);
            String vectorStr = arrayToString(vector);
            log.debug("✅ [长期记忆向量存储] 向量生成成功: id={}, dimension={}", memoryId, vector.length);

            // 2. 构建 metadata JSON
            Map<String, Object> docMetadata = new HashMap<>();
            docMetadata.put("agentName", agentName);
            docMetadata.put("userId", userId);
            docMetadata.put("tenantId", tenantId);
            if (sessionId != null && !sessionId.isEmpty()) {
                docMetadata.put("sessionId", sessionId);
            }
            if (metadata != null) {
                docMetadata.putAll(metadata);
            }
            String metadataJson = objectMapper.writeValueAsString(docMetadata);

            // 3. 截断内容
            int maxLength = config != null ? config.getMaxContentLength() : 2000;
            String truncatedContent = content;
            if (content.length() > maxLength) {
                truncatedContent = content.substring(0, maxLength) + "...";
            }

            // 4. 手写 SQL 插入向量
            log.debug("💾 [长期记忆向量存储] 插入向量: id={}, contentLength={}", memoryId, truncatedContent.length());
            String sql = String.format(
                    "INSERT INTO %s (id, description, metadata, vector) VALUES (?, ?, ?, '%s')",
                    VECTOR_TABLE, vectorStr);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, memoryId);  // 使用 memoryId 作为向量 ID
                ps.setString(2, truncatedContent);  // 存储内容
                ps.setString(3, metadataJson);  // 存储 metadata

                int rows = ps.executeUpdate();
                log.debug("✅ [长期记忆向量存储] 插入成功: id={}, 影响行数={}", memoryId, rows);
                return memoryId;
            }
        } catch (Exception e) {
            log.error("❌ [长期记忆向量存储] 存储失败: id={}, error={}", memoryId, e.getMessage(), e);
            return null;
        }
    }

    // ========== 配置 ==========

    public boolean isEnabled(String agentName, String tenantId) {
        MemoryConfig config = getConfig(agentName, tenantId);
        return config != null && config.isEnabled();
    }

    public MemoryConfig getConfig(String agentName, String tenantId) {
        String cacheKey = agentName + ":" + tenantId;

        return configCache.compute(cacheKey, (k, cached) -> {
            if (cached != null && !cached.isExpired()) {
                return cached;
            }

            MemoryConfig config = null;
            if (memoryMapper != null) {
                // 1. 先查智能体+租户级别配置
                if (agentName != null) {
                    config = memoryMapper.selectAgentConfig(agentName, tenantId);
                }
                // 2. 再查全局配置（agent_name IS NULL AND tenant_id IS NULL）
                if (config == null) {
                    config = memoryMapper.selectGlobalConfig();
                }
            }

            if (config == null) {
                // 兜底：使用 yml 配置构建 MemoryConfig
                config = MemoryConfig.builder()
                        .enabled(1)
                        .similarityThreshold(java.math.BigDecimal.valueOf(
                                this.config != null ? this.config.getSimilarityThreshold() : 0.4))
                        .defaultTopk(this.config != null ? this.config.getDefaultTopk() : 10)
                        .maxTopk(this.config != null ? this.config.getMaxTopk() : 50)
                        .retentionDays(this.config != null ? this.config.getRetentionDays() : 365)
                        .autoRecordThreshold(java.math.BigDecimal.valueOf(
                                this.config != null ? this.config.getAutoRecordThreshold() : 0.7))
                        .autoRecordEnabled(this.config != null && this.config.isAutoRecordEnabled() ? 1 : 1)
                        .memoryType(this.config != null ? this.config.getMemoryType() : "semantic")
                        .build();
            }

            return new CachedConfig(config);
        }).config;
    }

    // ========== 定时任务 ==========

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void cleanExpiredMemories() {
        if (memoryMapper == null) return;
        try {
            int cleaned = memoryMapper.cleanExpired();
            if (cleaned > 0) {
                log.info("Cleaned {} expired memories", cleaned);
            }
        } catch (Exception e) {
            log.error("Failed to clean expired memories", e);
        }
    }

    // ========== 重要性评估 ==========

    /**
     * 分析内容重要性
     * 根据 llmImportanceEnabled 配置选择评估方式：
     * - false（默认）：规则引擎，基于关键词加权快速评估
     * - true：调用 LLM 进行语义评估，更准确但有延迟和 token 开销
     */
    public double analyzeImportance(String content) {
        if (content == null || content.isEmpty()) {
            return 0.0;
        }

        // 如果开启了 LLM 评估，优先使用
        if (config != null && config.isLlmImportanceEnabled() && chatModel != null) {
            try {
                return analyzeImportanceWithLLM(content);
            } catch (Exception e) {
                log.warn("LLM importance analysis failed, falling back to rules: {}", e.getMessage());
                // 降级到规则引擎
            }
        }

        return analyzeImportanceWithRules(content);
    }

    /**
     * LLM 评估内容重要性
     * 让 LLM 判断内容是否包含值得长期记忆的个人信息
     *
     * 返回值含义：
     * - 0.9-1.0：明确的记忆请求（"请记住我的名字叫张三"）
     * - 0.7-0.9：包含重要个人信息（"我住在北京"、"我是程序员"）
     * - 0.4-0.7：可能有用但不确定
     * - 0.0-0.4：闲聊或无关内容（"你好"、"今天天气不错"）
     */
    private double analyzeImportanceWithLLM(String content) {
        String prompt = """
                你是一个记忆重要性评估器。请评估以下用户消息是否包含值得长期记忆的信息。
                
                评分标准：
                - 0.9-1.0：明确的记忆请求（如"请记住"、"提醒我"）
                - 0.7-0.9：包含重要个人信息（姓名、住址、工作、偏好、家庭等）
                - 0.4-0.7：可能有用但不确定的信息
                - 0.0-0.4：闲聊、问候或无关内容
                
                只返回一个0到1之间的数字，不要返回任何其他文字。
                
                用户消息：%s
                """.formatted(content.length() > 500 ? content.substring(0, 500) : content);

        try {
            String response = ChatClient.create(chatModel)
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response != null) {
                String trimmed = response.trim().replaceAll("[^0-9.]", "");
                double score = Double.parseDouble(trimmed);
                return Math.max(0.0, Math.min(1.0, score));
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse LLM importance score: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("LLM importance analysis error: {}", e.getMessage());
        }

        // LLM 调用失败，降级到规则引擎
        return analyzeImportanceWithRules(content);
    }

    /**
     * 规则引擎评估内容重要性（默认方式）
     */
    private double analyzeImportanceWithRules(String content) {
        double importance = 0.3;
        String lowerContent = content.toLowerCase();

        importance += matchKeywordScore(lowerContent, STRONG_KEYWORDS, 0.25);  // ⭐ 提高"记住"类关键词权重
        importance += matchKeywordScore(lowerContent, PERSONAL_INFO_KEYWORDS, 0.15);
        importance -= matchKeywordScore(lowerContent, CASUAL_KEYWORDS, 0.15);

        if (content.length() > 20) importance += 0.05;
        if (content.length() > 50) importance += 0.05;

        return Math.max(0.0, Math.min(1.0, importance));
    }

    private double matchKeywordScore(String content, String[] keywords, double scorePerMatch) {
        double score = 0;
        int matchCount = 0;
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                score += scorePerMatch;
                matchCount++;
                if (matchCount >= 3) break;
            }
        }
        return score;
    }

    /**
     * 检测新内容是否与已有记忆矛盾，需要更新
     * Mem0 核心能力：用户说"我改名叫李四"会更新已有"我叫张三"的记忆
     *
     * @param agentName  Agent 名称
     * @param userId     用户 ID
     * @param content    新内容
     * @param tenantId   租户 ID
     * @return 需要更新的已有记忆 ID，如果不需要更新返回 null
     */
    public String findContradictingMemory(String agentName, String userId, String content, String tenantId) {
        if (memoryMapper == null) return null;

        // 检测矛盾的关键词模式（新信息覆盖旧信息）
        String[][] contradictionPatterns = {
                // {"新模式", "旧模式"} — 新信息包含前者时，检查是否有包含后者的已有记忆
                {"我改名叫", "我叫"},
                {"我现在叫", "我叫"},
                {"我现在住在", "我住在"},
                {"我搬到了", "我住在"},
                {"我换工作了", "我的工作"},
                {"我现在的电话", "我的电话"},
                {"我换了", null},  // 通用变更模式
                {"不再是", null},  // 否定旧信息
        };

        String lowerContent = content.toLowerCase();

        // 检查新内容是否包含矛盾信号
        String oldPattern = null;
        for (String[] pattern : contradictionPatterns) {
            if (lowerContent.contains(pattern[0])) {
                oldPattern = pattern[1] != null ? pattern[1] : pattern[0];
                break;
            }
        }

        if (oldPattern == null) {
            return null;
        }

        // 使用 DB LIKE 查询直接查找矛盾记忆（替代全量加载+内存遍历）
        try {
            MemoryRecord contradicting = memoryMapper.selectFirstByContentLike(
                    agentName, userId, tenantId, oldPattern);
            if (contradicting != null) {
                log.debug("Found contradicting memory: id={}, content={}", contradicting.getId(),
                        contradicting.getContent().length() > 50
                                ? contradicting.getContent().substring(0, 50) + "..."
                                : contradicting.getContent());
                return contradicting.getId();
            }
        } catch (Exception e) {
            log.warn("Failed to find contradicting memory: {}", e.getMessage());
        }

        return null;
    }

    // ========== 关键词常量 ==========

    private static final String[] STRONG_KEYWORDS = {
            "记住", "请记住", "请帮我记住", "记得", "请记得",
            "提醒我", "下次提醒", "以后记住", "别忘了",
            "重要", "必须", "需要记住"
    };

    private static final String[] PERSONAL_INFO_KEYWORDS = {
            "我叫", "我是", "我的名字", "我姓",
            "我喜欢", "我不喜欢", "爱吃", "喜欢吃",
            "我的生日", "出生日期", "生日是",
            "我住", "我家在", "我来自", "我在",
            "我的工作", "我从事", "我是做",
            "我的电话", "我的邮箱", "联系方式",
            "我老婆", "我老公", "我孩子", "我儿子", "我女儿"
    };

    private static final String[] CASUAL_KEYWORDS = {
            "你好", "在吗", "好的", "嗯", "哦",
            "哈哈", "谢谢", "拜拜", "再见", "行", "可以"
    };
}
