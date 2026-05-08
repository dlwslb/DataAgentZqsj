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
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

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
    private final com.jldaren.agent.ai.datascope.service.VectorStoreService vectorStoreService;  // ⭐ 统一的向量存储服务

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
            LongTermMemoryConfig config,
            com.jldaren.agent.ai.datascope.service.VectorStoreService vectorStoreService) {
        this.memoryMapper = memoryMapper;
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.dataSource = dataSource;
        this.config = config;
        this.vectorStoreService = vectorStoreService;
        log.info("✅LongTermMemoryService initialized: vectorStore={}, embeddingModel={}, chatModel={}, dataSource={}, vectorStoreService={}",
                vectorStore != null, embeddingModel != null, chatModel != null, dataSource != null, vectorStoreService != null);
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
            // ⭐ 智能格式化记忆内容，提高检索相似度
            String formattedContent = standardizeMemoryContent(content, userId);
            
            String vectorId = storeVector(formattedContent, metadata, agentName, userId, sessionId, tenantId, memoryId);

            MemoryRecord record = MemoryRecord.builder()
                    .id(memoryId)
                    .tenantId(tenantId)
                    .agentName(agentName)
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(config.getMemoryType())
                    .content(formattedContent)  // ⭐ 使用格式化后的内容
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
        } catch (RuntimeException e) {
            // ⭐ 重新抛出 RuntimeException，触发 @Transactional 回滚
            log.error("❌ [长期记忆] 记录失败（将回滚）: agent={}, user={}, error={}", agentName, userId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ [长期记忆] 记录失败（将回滚）: agent={}, user={}", agentName, userId, e);
            throw new RuntimeException("记录长期记忆失败", e);
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
     * ⭐ 使用统一的 VectorStoreService 更新向量
     */
    private void updateVector(String memoryId, String newContent, MemoryRecord existing) {
        if (vectorStoreService == null) {
            log.warn("⚠️ [长期记忆更新] VectorStoreService 为空，跳过向量更新");
            return;
        }

        try {
            // 1. 构建 metadata
            Map<String, Object> docMetadata = new HashMap<>();
            if (existing.getMetadata() != null) {
                docMetadata.putAll(existing.getMetadata());
            }
            docMetadata.put("agentName", existing.getAgentName());
            docMetadata.put("userId", existing.getUserId());
            docMetadata.put("tenantId", existing.getTenantId());

            // 2. 获取最大长度
            int maxLength = config != null ? config.getMaxContentLength() : 2000;

            // 3. 使用统一的 VectorStoreService 更新向量
            log.debug("💾 [长期记忆更新] 开始更新向量: id={}, contentLength={}", memoryId, newContent.length());
            boolean success = vectorStoreService.updateVector(memoryId, newContent, docMetadata, maxLength);
            
            if (success) {
                log.info("✅ [长期记忆更新] 向量更新成功: id={}", memoryId);
            } else {
                log.error("❌ [长期记忆更新] 向量更新失败: id={}", memoryId);
            }
        } catch (Exception e) {
            log.error("❌ [长期记忆更新] 向量更新异常: id={}, error={}", memoryId, e.getMessage(), e);
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
     * ⭐ 使用统一的 VectorStoreService 检索相关记忆
     */
    private List<MemorySearchResult> retrieveViaVectorStore(String agentName, String userId,
                                                            String query, int limit, double threshold,
                                                            MemoryConfig config, String sessionId) throws ExecutionException, InterruptedException, TimeoutException {
        if (vectorStoreService == null) {
            log.warn("⚠️ [长期记忆查询] VectorStoreService 为空，无法执行查询");
            return Collections.emptyList();
        }

        log.debug("🔍 [长期记忆SQL搜索] agentName={}, userId={}, query={}, limit={}, threshold={}, sessionId={}",
                agentName, userId, query, limit, threshold, sessionId);

        try {
            // 1. 调用统一的 VectorStoreService 进行查询
            List<Document> documents = vectorStoreService.searchLongTermMemories(
                    query,
                    agentName,
                    userId,
                    sessionId,
                    config.getMemoryType(),
                    limit,
                    threshold
            );

            if (documents.isEmpty()) {
                log.info("✅ [长期记忆查询] 未找到相关记忆");
                return Collections.emptyList();
            }

            // 2. 转换为 MemorySearchResult
            List<MemorySearchResult> results = new ArrayList<>();
            List<String> accessedIds = new ArrayList<>();

            for (Document doc : documents) {
                String memoryId = doc.getId();
                String content = doc.getText();
                Map<String, Object> metadata = doc.getMetadata();
                Double similarity = metadata != null ? (Double) metadata.get("similarity") : null;

                if (similarity != null && similarity >= threshold) {
                    MemorySearchResult result = MemorySearchResult.builder()
                            .id(memoryId)
                            .content(content)
                            .similarityScore(similarity)
                            .metadata(metadata)
                            .build();
                    results.add(result);
                    accessedIds.add(memoryId);

                    log.debug("  [记忆] id={}, similarity={}, contentLength={}",
                            memoryId, similarity, content != null ? content.length() : 0);
                }
            }

            // 3. 批量更新访问统计
            if (!accessedIds.isEmpty()) {
                batchUpdateAccessInfo(accessedIds);
            }
            
            log.debug("✅ [长期记忆查询] 检索到 {} 条相关结果 (threshold={}, 共扫描 {} 条, purpose={})",
                    results.size(), threshold, documents.size(), sessionId != null ? "session_retrieve" : "dedup_check");
            return results;

        } catch (Exception e) {
            log.error("❌ [长期记忆查询] 搜索失败: {}", e.getMessage(), e);
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
     * ⭐ 同时检查原始内容和格式化后的内容，确保去重逻辑有效
     */
    public boolean isDuplicateContent(String agentName, String userId, String tenantId, String content) {
        if (memoryMapper == null || content == null) return false;
        
        // 检查原始内容
        boolean originalExists = memoryMapper.existsByContent(agentName, userId, tenantId, content.trim()) > 0;
        if (originalExists) {
            return true;
        }
        
        // 检查格式化后的内容（以防数据库中已存格式化内容）
        String formattedContent = standardizeMemoryContent(content, userId);
        if (!content.equals(formattedContent)) {  // 只有在内容确实被格式化时才检查
            return memoryMapper.existsByContent(agentName, userId, tenantId, formattedContent.trim()) > 0;
        }
        
        return false;
    }

    // ========== 向量存储 ==========

    /**
     * ⭐ 使用统一的 VectorStoreService 存储向量
     */
    private String storeVector(String content, Map<String, Object> metadata,
                                String agentName, String userId, String sessionId,
                                String tenantId, String memoryId) {
        if (vectorStoreService == null) {
            log.warn("⚠️ [长期记忆向量存储] VectorStoreService 为空，跳过向量存储");
            return null;
        }

        try {
            // 1. 构建 metadata
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

            // 2. 截断内容
            int maxLength = config != null ? config.getMaxContentLength() : 2000;
            
            // 3. 使用统一的 VectorStoreService 存储向量
            log.debug("💾 [长期记忆向量存储] 开始存储向量: id={}, contentLength={}", memoryId, content.length());
            boolean success = vectorStoreService.storeVector(memoryId, content, docMetadata, maxLength);
            
            if (!success) {
                log.error("❌ [长期记忆向量存储] 存储失败，将回滚事务: id={}", memoryId);
                throw new RuntimeException("向量存储失败: " + memoryId);
            }
            
            log.debug("✅ [长期记忆向量存储] 存储成功: id={}", memoryId);
            return memoryId;
        } catch (RuntimeException e) {
            // ⭐ 重新抛出 RuntimeException，触发事务回滚
            throw e;
        } catch (Exception e) {
            log.error("❌ [长期记忆向量存储] 异常，将回滚事务: id={}, error={}", memoryId, e.getMessage(), e);
            throw new RuntimeException("向量存储异常: " + memoryId, e);
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

        // ⭐ 检测矛盾的关键词模式（新信息覆盖旧信息）
// 格式：{"新输入中的触发词", "已有记忆中的检索词"}
// 若第二个元素为 null，代表通用纠正/否定词，需结合上下文判断
        String[][] contradictionPatterns = {

                // ================= 1. 身份与称呼 =================
                {"我改名叫", "我叫"},
                {"我现在叫", "我叫"},
                {"我的名字是", "我叫"},
                {"以后叫我", "我叫"},
                {"别叫我", "我叫"},  // 纠正称呼
                {"请叫我", "我叫"},
                {"我改了网名", "我的网名"},
                {"我换了昵称", "我的昵称"},
                {"我是", "我是"},    // ⚠️ 注意：需配合后缀值比对（如"我是张三"覆盖"我是李四"）

                // ================= 2. 联系方式 =================
                {"我现在的电话", "我的电话"},
                {"我新手机号", "我的手机号"},
                {"我换了手机", "我的手机"},
                {"联系我请打", "我的电话"},
                {"我微信换了", "我的微信"},
                {"我换了邮箱", "我的邮箱"},
                {"我的新邮箱", "我的邮箱"},
                {"我微信号改了", "我的微信"},

                // ================= 3. 居住地与位置 =================
                {"我搬到了", "我住在"},
                {"我现在住在", "我住在"},
                {"我搬家了", "我的地址"},
                {"我的新地址", "我的地址"},
                {"我回老家了", "我住在"},  // 状态变更
                {"我去了", "我在"},       // 动态位置变更（如"我去了北京" vs "我在上海"）

                // ================= 4. 工作与职业 =================
                {"我换工作了", "我的工作"},
                {"我跳槽了", "我的公司"},
                {"我现在在", "我在"},       // ⚠️ 需过滤非地名（如"我现在在阿里"）
                {"我入职了", "我的公司"},
                {"我离职了", "我的公司"},
                {"我辞职了", "我的工作"},
                {"我换部门了", "我的部门"},
                {"我升职了", "我的职位"},
                {"我不干了", "我的工作"},

                // ================= 5. 人生状态与关系 =================
                {"我结婚了", "我单身"},
                {"我离婚了", "我已婚"},
                {"我分手了", "我的对象"},
                {"我有对象了", "我单身"},
                {"我毕业了", "我是学生"},
                {"我退学了", "我是学生"},
                {"我退休了", "我的工作"},
                {"我怀孕了", "我没孩子"},
                {"我生二胎了", "我有一个孩子"},

                // ================= 6. 偏好与习惯（软性覆盖） =================
                {"我现在不爱吃", "我爱吃"},
                {"我开始喜欢", "我喜欢"},
                {"我戒了", "我抽烟"},  // 或 "我喝酒"
                {"我改喝", "我喝"},    // 如 "我改喝冰美式" 覆盖 "我喝拿铁"
                {"我不怎么玩了", "我喜欢玩"},

                // ================= 7. 资产与物品 =================
                {"我换手机了", "我的手机"},  // 如"我换iPhone了"覆盖"我用华为"
                {"我买车了", "我的车"},
                {"我换车了", "我的车"},
                {"我换了电脑", "我的电脑"},

                // ================= 8. 宠物相关 =================
                {"我的狗走了", "我的狗"},
                {"我送人了", "我的猫"},  // ⚠️ 需结合上下文指代
                {"我又养了", "我的宠物"},

                // ================= 9. 通用否定与纠正词 =================
                // 这些没有特定的匹配目标，一旦出现，应触发对整句实体的检索与比对
                {"之前说错", null},
                {"我记错了", null},
                {"不是", null},           // ⚠️ 极高误判率，建议只在前缀出现时触发，如"不是我之前说的..."
                {"忘了我之前说的", null},
                {"以这次为准", null},
                {"我改了", null},
                {"我换了", null},
                {"不再是", null},
                {"以前是", null},         // 暗示现在不是了
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

        if (oldPattern != null) {
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
        }
        
        // ⭐ 增强：即使没有明确的矛盾关键词，也检查是否有相同类型的格式化内容
        // 例如：新内容是【用户信息-姓名】，旧内容也是【用户信息-姓名】，则认为可能矛盾
        String newFormatted = standardizeMemoryContent(content, userId);
        if (newFormatted.startsWith("【用户信息-姓名】")) {
            // 查找已有的姓名信息
            try {
                MemoryRecord existingName = memoryMapper.selectFirstByContentLike(
                        agentName, userId, tenantId, "【用户信息-姓名】");
                if (existingName != null && !existingName.getContent().equals(newFormatted)) {
                    log.info("⚠️ [矛盾检测] 发现同名类型记忆，可能需更新: id={}", existingName.getId());
                    return existingName.getId();
                }
            } catch (Exception e) {
                log.warn("Failed to find same-type memory: {}", e.getMessage());
            }
        }

        return null;
    }

    /**
     * 批量更新访问统计信息
     */
    private void batchUpdateAccessInfo(List<String> memoryIds) {
        if (memoryMapper == null || memoryIds == null || memoryIds.isEmpty()) {
            return;
        }

        try {
            memoryMapper.batchUpdateAccessInfo(memoryIds);
            log.debug("✅ [长期记忆] 批量更新访问统计: count={}", memoryIds.size());
        } catch (Exception e) {
            log.warn("⚠️ [长期记忆] 批量更新访问统计失败: {}", e.getMessage());
        }
    }

    /**
     * ⭐ 智能格式化记忆内容，提高检索相似度
     * 将用户口语化的表达转换为标准化的格式，便于向量检索
     * 
     * @param originalContent 原始记忆内容
     * @param userId 用户 ID
     * @return 格式化后的记忆内容
     */
    private String standardizeMemoryContent(String originalContent, String userId) {
        if (originalContent == null || originalContent.trim().isEmpty()) {
            return originalContent;
        }

        String content = originalContent.trim();
        
        // 1. 检测并标准化姓名信息
        if (containsNamePattern(content)) {
            return standardizeNameInfo(content, userId);
        }
        
        // 2. 检测并标准化喜好信息
        if (containsPreferencePattern(content)) {
            return standardizePreferenceInfo(content, userId);
        }
        
        // 3. 检测并标准化位置信息
        if (containsLocationPattern(content)) {
            return standardizeLocationInfo(content, userId);
        }
        
        // 4. 检测并标准化工作信息
        if (containsWorkPattern(content)) {
            return standardizeWorkInfo(content, userId);
        }
        
        // 5. 其他情况：添加统一前缀
        return addStandardPrefix(content, userId);
    }

    /**
     * 检测是否包含姓名模式
     */
    private boolean containsNamePattern(String content) {
        return content.contains("我叫") 
            || content.contains("我的名字") 
            || content.contains("我姓")
            || content.contains("请叫我")  // ⭐ 新增：支持“请叫我xxx”
            || content.matches(".*我是[\u4e00-\u9fa5]{2,4}.*");
    }

    /**
     * 标准化姓名信息
     */
    private String standardizeNameInfo(String content, String userId) {
        // 提取姓名
        String name = extractName(content);
        if (name != null && !name.isEmpty()) {
            return String.format(
                "【用户信息-姓名】用户的名字是%s。当用户问'我是谁'、'我叫什么'、'我的名字'时，应该回答：你叫%s。",
                name, name
            );
        }
        return addStandardPrefix(content, userId);
    }

    /**
     * 从内容中提取姓名
     */
    private String extractName(String content) {
        // "我叫猫哥" -> "猫哥"
        if (content.contains("我叫")) {
            int idx = content.indexOf("我叫") + 2;
            return content.substring(idx).trim();
        }
        // "我的名字是猫哥" -> "猫哥"
        if (content.contains("我的名字")) {
            int idx = content.indexOf("我的名字");
            // 查找"是"或"叫"
            for (int i = idx; i < content.length(); i++) {
                if (content.charAt(i) == '是' || content.charAt(i) == '叫') {
                    return content.substring(i + 1).trim();
                }
            }
        }
        // "我是猫哥" -> "猫哥"
        if (content.matches("^我是[\u4e00-\u9fa5]{2,4}$")) {
            return content.substring(2).trim();
        }
        return null;
    }

    /**
     * 检测是否包含喜好模式
     */
    private boolean containsPreferencePattern(String content) {
        return content.contains("我喜欢") 
            || content.contains("我爱吃") 
            || content.contains("爱吃")
            || content.contains("喜欢吃")
            || content.contains("我不喜欢");
    }

    /**
     * 标准化喜好信息
     */
    private String standardizePreferenceInfo(String content, String userId) {
        return String.format(
            "【用户偏好】%s。当用户询问相关喜好时，可以参考这条信息。",
            content
        );
    }

    /**
     * 检测是否包含位置模式
     */
    private boolean containsLocationPattern(String content) {
        return content.contains("我住") 
            || content.contains("我家在") 
            || content.contains("我来自")
            || content.contains("我在");
    }

    /**
     * 标准化位置信息
     */
    private String standardizeLocationInfo(String content, String userId) {
        return String.format(
            "【用户信息-位置】%s。当用户询问居住地或位置时，可以参考这条信息。",
            content
        );
    }

    /**
     * 检测是否包含工作模式
     */
    private boolean containsWorkPattern(String content) {
        return content.contains("我的工作") 
            || content.contains("我从事") 
            || content.contains("我是做")
            || content.contains("我的职业");
    }

    /**
     * 标准化工作信息
     */
    private String standardizeWorkInfo(String content, String userId) {
        return String.format(
            "【用户信息-工作】%s。当用户询问工作或职业时，可以参考这条信息。",
            content
        );
    }

    /**
     * 添加标准前缀
     */
    private String addStandardPrefix(String content, String userId) {
        // 对于无法分类的内容，添加通用前缀
        return String.format("【用户记忆】%s", content);
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
