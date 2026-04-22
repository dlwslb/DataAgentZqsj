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
package com.jldaren.agent.ai.datascope.memory;

import com.jldaren.agent.ai.datascope.memory.entity.MemoryRecord;
import com.jldaren.agent.ai.datascope.memory.entity.MemorySearchResult;
import com.jldaren.agent.ai.datascope.memory.service.LongTermMemoryService;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OceanBase 长期记忆实现
 * 实现 AgentScope io.agentscope.core.memory.LongTermMemory 接口
 * 可直接集成到 ReActAgent.builder().longTermMemory() 中
 *
 * 对比 Mem0LongTermMemory 的核心设计：
 * 1. record(): 提取 USER 消息，去噪后按重要性评分决定是否存储（Mem0 核心理念）
 * 2. retrieve(): 语义搜索相关记忆，注入到 Agent 上下文
 * 3. 多租户隔离: agentName + userId + tenantId
 * 4. 双存储: MySQL 关系数据 + OceanBase 向量数据
 */
@Slf4j
public class OceanBaseLongTermMemory implements LongTermMemory {

    private static final int MAX_CONTENT_LENGTH = 2000;

    // ---- 去噪正则（预编译避免重复编译）----
    private static final Pattern LONG_TERM_MEMORY_TAG = Pattern.compile("<long_term_memory>[\\s\\S]*?</long_term_memory>");
    private static final Pattern USER_HISTORY_MEMORY = Pattern.compile("【用户历史记忆】[\\s\\S]*?(?=【(?:当前问题|用户问题)】)");
    private static final Pattern RAG_KNOWLEDGE = Pattern.compile("【相关知识参考】[\\s\\S]*?(?=【(?:用户问题|当前问题)】)");
    private static final Pattern QUESTION_TAG = Pattern.compile("【(?:当前问题|用户问题)】");
    private static final Pattern HINT_PHRASE_1 = Pattern.compile("请结合上述(?:历史记忆|相关知识).*?回答.*?。");
    private static final Pattern HINT_PHRASE_2 = Pattern.compile("如果(?:历史记忆|知识与问题).*?直接回答。");
    private static final Pattern SEPARATOR = Pattern.compile("\\n---\\n");
    private static final Pattern EXTRACT_QUESTION = Pattern.compile("【(?:用户问题|当前问题)】\\s*([\\s\\S]+)");
    private static final Pattern TRAILING_HINT = Pattern.compile("请结合上述.*$");

    private LongTermMemoryService memoryService;
    private String agentName = "default";
    private String userId;
    private String tenantId;
    private int retrieveCount = 5;
    private double similarityThreshold = 0.4;
    private boolean enabled = true;
    private String memoryType = "semantic";

    // ---- 构造方式 ----

    private OceanBaseLongTermMemory() {
        this.memoryService = null;
    }

    public OceanBaseLongTermMemory(LongTermMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- LongTermMemory 接口核心实现 ----

    /**
     * 记录新记忆 (AgentScope LongTermMemory 接口)
     *
     * 核心逻辑（对齐 Mem0 理念）：
     * 1. 只提取 USER 角色消息
     * 2. 去除被注入的噪声内容（RAG/记忆增强标签）
     * 3. 按重要性评分决定是否存储，不再用硬编码关键词过滤
     * 4. 检测矛盾记忆并更新（Mem0 核心能力：用户说"我改名叫李四"会更新而非新增）
     * 5. 去重检查，避免存储完全相同的内容
     */
    @Override
    public Mono<Void> record(List<Msg> messages) {
        if (!enabled || memoryService == null || messages == null || messages.isEmpty()) {
            return Mono.empty();
        }

        List<String> userContents = new ArrayList<>();

        for (Msg msg : messages) {
            if (msg.getRole() != io.agentscope.core.message.MsgRole.USER) {
                continue;
            }
            String text = msg.getTextContent();
            if (text == null || text.isEmpty()) {
                continue;
            }
            String cleanedText = cleanNoiseFromText(text);
            if (!cleanedText.isEmpty()) {
                userContents.add(cleanedText);
            }
        }

        if (userContents.isEmpty()) {
            log.debug("No user messages to record as memory");
            return Mono.empty();
        }

        for (String content : userContents) {
            if (content.length() > MAX_CONTENT_LENGTH) {
                log.debug("Memory content too long ({}), truncating to {}", content.length(), MAX_CONTENT_LENGTH);
                content = content.substring(0, MAX_CONTENT_LENGTH);
            }

            double importance = memoryService.analyzeImportance(content);
            double threshold = getAutoRecordThreshold();

            if (importance < threshold) {
                log.debug("Memory importance {} below threshold {}, skipping: {}",
                        String.format("%.2f", importance), String.format("%.2f", threshold),
                        content.length() > 50 ? content.substring(0, 50) + "..." : content);
                continue;
            }

            // Mem0 式记忆更新：检测矛盾并更新已有记忆
            String contradictingId = memoryService.findContradictingMemory(agentName, userId, content, tenantId);
            if (contradictingId != null) {
                boolean updated = memoryService.updateMemoryContent(contradictingId, content, importance);
                if (updated) {
                    log.debug("Updated contradicting memory: id={}, new content len={}", contradictingId, content.length());
                    continue; // 已更新，不再新增
                }
                // 更新失败则继续走新增流程
            }

            // 去重：检查是否已有相同内容
            if (isDuplicateMemory(content)) {
                log.debug("Duplicate memory skipped: {}", content.length() > 50 ? content.substring(0, 50) + "..." : content);
                continue;
            }

            memoryService.recordMemory(
                    agentName, userId, null, content, importance, null, tenantId, true
            );
            log.debug("Recorded memory: agent={}, user={}, importance={}, len={}",
                    agentName, userId, String.format("%.2f", importance), content.length());
        }

        return Mono.empty();
    }

    /**
     * 检索相关记忆 (AgentScope LongTermMemory 接口)
     * 框架在 longTermMemoryMode=BOTH 时自动调用
     */
    @Override
    public Mono<String> retrieve(Msg msg) {
        if (!enabled || memoryService == null) {
            return Mono.just("");
        }

        String rawQuery = msg != null ? msg.getTextContent() : "";
        String query = extractCleanQuery(rawQuery);

        if (query.isEmpty()) {
            return Mono.just("");
        }

        log.debug("Retrieving memories: agent={}, user={}, queryLen={}", agentName, userId, query.length());

        List<MemorySearchResult> results = memoryService.retrieveMemories(
                agentName, userId, query, retrieveCount, tenantId
        );

        String retrievedContent = results.stream()
                .filter(r -> r.getSimilarityScore() >= similarityThreshold)
                .map(MemorySearchResult::getContent)
                .collect(Collectors.joining("\n---\n"));

        long passedCount = results.stream().filter(r -> r.getSimilarityScore() >= similarityThreshold).count();
        log.debug("Retrieved {} memories, {} passed threshold ({})",
                results.size(), passedCount, similarityThreshold);

        return Mono.just(retrievedContent);
    }

    // ---- 辅助方法 ----

    /**
     * 检查是否已存在相似记忆（基于精确内容匹配）
     * 使用 DB 查询避免全量加载用户记忆
     */
    private boolean isDuplicateMemory(String content) {
        try {
            return memoryService.isDuplicateContent(agentName, userId, tenantId, content);
        } catch (Exception e) {
            log.warn("Failed to check duplicate memory: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 清除文本中被注入的噪声内容
     * RAG/记忆增强时注入的上下文不应作为记忆内容存储
     */
    private String cleanNoiseFromText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String cleaned = text;
        cleaned = LONG_TERM_MEMORY_TAG.matcher(cleaned).replaceAll("");
        cleaned = USER_HISTORY_MEMORY.matcher(cleaned).replaceAll("");
        cleaned = RAG_KNOWLEDGE.matcher(cleaned).replaceAll("");
        cleaned = QUESTION_TAG.matcher(cleaned).replaceAll("");
        cleaned = HINT_PHRASE_1.matcher(cleaned).replaceAll("");
        cleaned = HINT_PHRASE_2.matcher(cleaned).replaceAll("");
        cleaned = SEPARATOR.matcher(cleaned).replaceAll("\n");

        return cleaned.trim();
    }

    /**
     * 从被增强的消息中提取用户的原始问题，用作向量搜索的 query
     * 避免用含噪声的长文本（RAG/记忆增强标签）作为搜索 query
     */
    private String extractCleanQuery(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // 尝试提取【用户问题】或【当前问题】之后的内容
        java.util.regex.Matcher userQuestionMatcher = EXTRACT_QUESTION.matcher(text);
        if (userQuestionMatcher.find()) {
            String question = userQuestionMatcher.group(1).trim();
            question = TRAILING_HINT.matcher(question).replaceAll("").trim();
            if (!question.isEmpty()) {
                return question;
            }
        }

        // 如果没有匹配到标记，使用 cleanNoiseFromText 去噪
        String cleaned = cleanNoiseFromText(text);

        // 过长的 query 影响向量匹配质量，截断
        if (cleaned.length() > 200) {
            cleaned = cleaned.substring(0, 200);
        }

        return cleaned;
    }

    /**
     * 获取自动记录阈值
     */
    private double getAutoRecordThreshold() {
        if (memoryService != null) {
            var config = memoryService.getConfig(agentName, tenantId);
            if (config != null && config.getAutoRecordThreshold() != null) {
                return config.getAutoRecordThreshold().doubleValue();
            }
        }
        return 0.7;
    }

    // ---- 公开的辅助方法 ----

    public List<MemorySearchResult> retrieveDetailed(String query, int count) {
        if (!enabled || memoryService == null) {
            return Collections.emptyList();
        }
        return memoryService.retrieveMemories(agentName, userId, query, count, tenantId);
    }

    public void clear() {
        if (!enabled || memoryService == null) return;
        memoryService.clearMemories(agentName, userId, tenantId);
        log.info("Cleared all memories for agent={}, user={}", agentName, userId);
    }

    public void clear(String memoryId) {
        if (!enabled || memoryService == null) return;
        memoryService.deleteMemory(memoryId);
        log.info("Cleared memory: id={}", memoryId);
    }

    public int size() {
        if (!enabled || memoryService == null) return 0;
        return memoryService.getMemoryCount(agentName, userId, tenantId);
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public List<MemoryRecord> getAllMemories() {
        if (!enabled || memoryService == null) return Collections.emptyList();
        return memoryService.getMemoriesByUser(agentName, userId, tenantId, null);
    }

    // ---- 链式 setter ----

    public OceanBaseLongTermMemory agentName(String agentName) { this.agentName = agentName; return this; }
    public OceanBaseLongTermMemory userId(String userId) { this.userId = userId; return this; }
    public OceanBaseLongTermMemory tenantId(String tenantId) { this.tenantId = tenantId; return this; }
    public OceanBaseLongTermMemory retrieveCount(int retrieveCount) { this.retrieveCount = retrieveCount; return this; }
    public OceanBaseLongTermMemory similarityThreshold(double threshold) { this.similarityThreshold = threshold; return this; }
    public OceanBaseLongTermMemory memoryType(String memoryType) { this.memoryType = memoryType; return this; }
    public OceanBaseLongTermMemory enabled(boolean enabled) { this.enabled = enabled; return this; }

    public LongTermMemoryService getMemoryService() { return memoryService; }
    public String getName() { return "OceanBaseLongTermMemory"; }

    public Map<String, String> getMetadata() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("agentName", agentName);
        metadata.put("userId", userId);
        metadata.put("tenantId", tenantId);
        metadata.put("memoryType", memoryType);
        metadata.put("enabled", String.valueOf(enabled));
        metadata.put("retrieveCount", String.valueOf(retrieveCount));
        metadata.put("similarityThreshold", String.valueOf(similarityThreshold));
        return metadata;
    }

    public LongTermMemory fork() {
        OceanBaseLongTermMemory forked = new OceanBaseLongTermMemory(this.memoryService);
        forked.agentName = this.agentName;
        forked.userId = this.userId;
        forked.tenantId = this.tenantId;
        forked.retrieveCount = this.retrieveCount;
        forked.similarityThreshold = this.similarityThreshold;
        forked.enabled = this.enabled;
        forked.memoryType = this.memoryType;
        return forked;
    }

    public List<String> retrieve(Msg msg, int count) {
        if (!enabled || memoryService == null) return Collections.emptyList();

        String rawQuery = msg != null ? msg.getTextContent() : "";
        String query = extractCleanQuery(rawQuery);

        List<MemorySearchResult> results = memoryService.retrieveMemories(agentName, userId, query, count, tenantId);
        return results.stream()
                .filter(r -> r.getSimilarityScore() >= similarityThreshold)
                .map(MemorySearchResult::getContent)
                .collect(Collectors.toList());
    }

    public void setUserId(String userId) { this.userId = userId; }
    public void setSessionId(String sessionId) { /* 长期记忆不绑定会话 */ }
    public String getSessionId() { return null; }

    // ---- Builder ----

    public static class Builder {
        private final OceanBaseLongTermMemory instance;

        public Builder() {
            this.instance = new OceanBaseLongTermMemory();
        }

        public Builder agentName(String agentName) { instance.agentName(agentName); return this; }
        public Builder userId(String userId) { instance.userId(userId); return this; }
        public Builder tenantId(String tenantId) { instance.tenantId(tenantId); return this; }
        public Builder retrieveCount(int count) { instance.retrieveCount(count); return this; }
        public Builder similarityThreshold(double threshold) { instance.similarityThreshold(threshold); return this; }
        public Builder memoryType(String memoryType) { instance.memoryType(memoryType); return this; }
        public Builder enabled(boolean enabled) { instance.enabled(enabled); return this; }

        public Builder memoryService(LongTermMemoryService memoryService) {
            instance.memoryService = memoryService; // field access within same package class
            return this;
        }

        public OceanBaseLongTermMemory build() {
            if (instance.agentName == null) {
                instance.agentName = "default";
            }
            return instance;
        }
    }
}
