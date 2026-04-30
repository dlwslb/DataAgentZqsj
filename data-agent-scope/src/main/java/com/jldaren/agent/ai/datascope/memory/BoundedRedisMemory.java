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
 *
 * Author: dlw
 * Created: 2026-04-27
 */
package com.jldaren.agent.ai.datascope.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于 Redis Sorted Set (ZSET) 的短期记忆实现。
 *
 * <p>特性：
 * <ol>
 *   <li>持久化：消息存储在 Redis ZSET 中，服务重启不会丢失</li>
 *   <li>自动去重：相同内容的消息只会保留一条（通过 member hash 实现）</li>
 *   <li>限流：始终 ≤ maxMessages 条，超出自动淘汰最旧的</li>
 *   <li>TTL：默认 15 天过期，避免 Redis 空间无限增长</li>
 *   <li>多租户隔离：按 agentId:tenantId:userId:sessionId 四级隔离</li>
 *   <li>高性能：使用 ZSET 原子操作，无需全量读写</li>
 * </ol>
 *
 * <p>ZSET 设计：
 * <ul>
 *   <li>Score = 时间戳（保证消息顺序）</li>
 *   <li>Member = {hash}:{json}（hash 用于去重，json 为完整消息）</li>
 *   <li>ZREMRANGEBYRANK 删除最旧消息（限流）</li>
 * </ul>
 *
 * <p>Redis Key 格式：memory:{agentId}:{tenantId}:{userId}:{sessionId}:messages
 */
@Slf4j
public class BoundedRedisMemory implements Memory {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final int maxMessages;
    private final Duration ttl;
    private final ObjectMapper objectMapper;
    private final String agentId;
    private final String tenantId;
    private final String userId;
    private final String sessionId;

    private static final int DEFAULT_MAX_MESSAGES = 120;
    private static final Duration DEFAULT_TTL = Duration.ofDays(15);
    private static final String KEY_PREFIX = "memory";

    public BoundedRedisMemory(ReactiveStringRedisTemplate redisTemplate, String agentId, 
                              String tenantId, String userId, String sessionId) {
        this(redisTemplate, agentId, tenantId, userId, sessionId, DEFAULT_MAX_MESSAGES, DEFAULT_TTL);
    }

    public BoundedRedisMemory(ReactiveStringRedisTemplate redisTemplate, String agentId,
                              String tenantId, String userId, String sessionId, int maxMessages) {
        this(redisTemplate, agentId, tenantId, userId, sessionId, maxMessages, DEFAULT_TTL);
    }

    public BoundedRedisMemory(ReactiveStringRedisTemplate redisTemplate, String agentId,
                              String tenantId, String userId, String sessionId,
                              int maxMessages, Duration ttl) {
        if (redisTemplate == null) {
            throw new IllegalArgumentException("redisTemplate cannot be null");
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId cannot be null or blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be null or blank");
        }
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be > 0, got: " + maxMessages);
        }
        this.redisTemplate = redisTemplate;
        this.agentId = agentId;
        this.tenantId = tenantId != null ? tenantId : "default";
        this.userId = userId != null ? userId : "anonymous";
        this.sessionId = sessionId;
        this.maxMessages = maxMessages;
        this.ttl = ttl;
        this.objectMapper = new ObjectMapper();
        log.debug("BoundedRedisMemory 初始化完成, agentId={}, tenantId={}, userId={}, sessionId={}, maxMessages={}, ttl={}",
                  agentId, tenantId, userId, sessionId, maxMessages, ttl);
    }

    // ==================== StateModule ====================

    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        // Redis 实现不需要额外保存，会话信息已经在 Redis 中
        log.debug("saveTo 调用完成, sessionId={}", sessionId);
    }

    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        // Redis 实现不需要额外加载，会话信息已经在构造时加载
        log.debug("loadFrom 调用完成, sessionId={}", sessionId);
    }

    // ==================== Memory Interface ====================

    @Override
    public void addMessage(Msg message) {
        if (message == null) return;
        
        // 过滤掉 SYSTEM 消息（长期记忆检索结果是临时上下文，不应保存到短期记忆）
        if (message.getRole() == io.agentscope.core.message.MsgRole.SYSTEM) {
            log.debug("跳过 SYSTEM 消息，不保存到短期记忆: agentId={}, sessionId={}", agentId, sessionId);
            return;
        }

        String key = buildKey();

        try {
            // 0. 检测并清理旧类型的 key（从 List 迁移到 ZSET）
            org.springframework.data.redis.connection.DataType type = redisTemplate.type(key)
                    .toFuture()
                    .get();
            if (type == org.springframework.data.redis.connection.DataType.LIST) {
                log.warn("检测到旧版 List 类型的 key，正在迁移到 ZSET: {}", key);
                redisTemplate.delete(key)
                        .toFuture()
                        .get();
            }
            
            // 1. 如果是 USER 消息，清理 RAG/长期记忆增强内容后再序列化
            Msg messageToSave = message;
            if (message.getRole() == io.agentscope.core.message.MsgRole.USER) {
                String originalText = message.getTextContent();
                if (originalText != null && !originalText.isEmpty()) {
                    // 提取原始用户问题（去除 RAG 知识、长期记忆等噪声）
                    String cleanText = com.jldaren.agent.ai.datascope.util.MessageContentCleaner.extractOriginalQuestion(originalText);
                    if (!cleanText.equals(originalText)) {
                        // 创建一个新的 Msg 对象，使用清理后的内容
                        messageToSave = io.agentscope.core.message.Msg.builder()
                                .id(message.getId())
                                .name(message.getName())
                                .role(message.getRole())
                                .textContent(cleanText)
                                .metadata(message.getMetadata())
                                .build();
                        log.warn("✅ USER 消息已清理 RAG 内容: 原始长度={}, 清理后长度={}, 清理后内容={}", 
                                originalText.length(), cleanText.length(), cleanText);
                    } else {
                        log.debug("USER 消息无需清理: {}", originalText.substring(0, Math.min(50, originalText.length())));
                    }
                }
            }
            
            // 2. 序列化消息
            String json = objectMapper.writeValueAsString(messageToSave);
            
            // 3. 生成唯一 member（使用消息 ID 实现去重）
            // 格式: {id}|||{json}
            String memberId = message.getId() != null ? message.getId() : generateMemberKey(json);
            String member = memberId + "|||" + json;
            
            // 4. 使用当前时间戳作为 score（保证顺序）
            double score = System.currentTimeMillis();

            // 5. 添加到 ZSET（如果 member 已存在会自动更新 score，实现去重）
            redisTemplate.opsForZSet().add(key, member, score)
                    .toFuture()
                    .get();

            // 6. 限流：删除最旧的（保留最新的 maxMessages 条）
            Long currentSize = redisTemplate.opsForZSet().size(key)
                    .toFuture()
                    .get();
            
            if (currentSize != null && currentSize > maxMessages) {
                long toRemove = currentSize - maxMessages;
                // ZREMRANGEBYRANK: 删除排名 0 到 toRemove-1 的元素（最旧的）
                redisTemplate.opsForZSet().removeRange(key, Range.from(Range.Bound.inclusive(0L)).to(Range.Bound.inclusive(toRemove - 1)))
                        .toFuture()
                        .get();
                log.debug("限流触发，删除 {} 条最旧消息", toRemove);
            }

            // 7. 设置 TTL（只在首次创建时设置）
            Boolean hasTtl = redisTemplate.hasKey(key)
                    .toFuture()
                    .get();
            if (hasTtl != null && !hasTtl) {
                redisTemplate.expire(key, ttl)
                        .toFuture()
                        .get();
            }

            log.debug("消息已添加到 Redis ZSET, sessionId={}, 当前消息数={}", sessionId, currentSize != null ? currentSize : "unknown");
        } catch (JsonProcessingException e) {
            log.error("序列化消息失败: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("保存消息到 Redis 失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public List<Msg> getMessages() {
        return getMessagesFromRedisSync();
    }

    @Override
    public void deleteMessage(int index) {
        String key = buildKey();
        try {
            // 从 ZSET 中获取指定排名的 member
            List<String> members = redisTemplate.opsForZSet().range(key, Range.from(Range.Bound.inclusive((long) index)).to(Range.Bound.inclusive((long) index)))
                    .collectList()
                    .toFuture()
                    .get();
            
            if (members != null && !members.isEmpty()) {
                String member = members.get(0);
                // 删除该 member
                redisTemplate.opsForZSet().remove(key, member)
                        .toFuture()
                        .get();
                log.debug("消息已从 Redis ZSET 删除, sessionId={}, index={}", sessionId, index);
            }
        } catch (Exception e) {
            log.error("从 Redis ZSET 删除消息失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public void clear() {
        String key = buildKey();
        try {
            // 删除整个 ZSET
            redisTemplate.delete(key)
                    .toFuture()
                    .get();
            log.debug("会话已从 Redis ZSET 清除, sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("清除 Redis ZSET 会话失败: {}", e.getMessage(), e);
        }
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public Duration getTtl() {
        return ttl;
    }

    // ==================== 内部方法 ====================

    private String buildKey() {
        return KEY_PREFIX + ":" + agentId + ":" + tenantId + ":" + userId + ":" + sessionId + ":messages";
    }

    private List<Msg> getMessagesFromRedisSync() {
        List<String> jsonList = getJsonListFromRedisSync();
        return jsonList.stream()
                .map(this::jsonToMsg)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<String> getJsonListFromRedisSync() {
        String key = buildKey();
        try {
            // 检测并清理旧类型的 key（从 List 迁移到 ZSET）
            org.springframework.data.redis.connection.DataType type = redisTemplate.type(key)
                    .toFuture()
                    .get();
            if (type == org.springframework.data.redis.connection.DataType.LIST) {
                log.warn("检测到旧版 List 类型的 key，正在清理: {}", key);
                redisTemplate.delete(key)
                        .toFuture()
                        .get();
                return new ArrayList<>();
            }
            
            // 从 ZSET 中按 score 排序获取所有 member（从小到大=从旧到新）
            List<String> members = redisTemplate.opsForZSet().range(key, Range.from(Range.Bound.inclusive(0L)).to(Range.Bound.unbounded()))
                    .collectList()
                    .toFuture()
                    .get();
            
            if (members == null || members.isEmpty()) {
                return new ArrayList<>();
            }
            
            // member 格式: "{id}|||{json}"，需要提取 json 部分
            List<String> jsonList = new ArrayList<>();
            for (String member : members) {
                int separatorIndex = member.indexOf("|||");
                if (separatorIndex > 0 && separatorIndex < member.length() - 3) {
                    String json = member.substring(separatorIndex + 3);
                    jsonList.add(json);
                }
            }
            return jsonList;
        } catch (Exception e) {
            log.error("从 Redis ZSET 获取消息列表失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 生成唯一的 member key（用于 ZSET 去重）
     * 格式: {8位hash}{json} （固定8位前缀，无需分隔符）
     */
    private String generateMemberKey(String json) {
        int hash = Math.abs(json.hashCode());
        // 格式化为8位十六进制，保证固定长度
        String hashStr = String.format("%08x", hash);
        return hashStr + json;
    }

    private String msgToJson(Msg message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("序列化 Msg 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private Msg jsonToMsg(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Msg.class);
        } catch (JsonProcessingException e) {
            log.error("反序列化 Msg 失败, json={}: {}", truncate(json, 100), e.getMessage());
            return null;
        }
    }

    private boolean isDuplicate(List<Msg> messages, Msg message) {
        if (messages == null || messages.isEmpty()) return false;
        Msg last = messages.get(messages.size() - 1);
        if (last == null) return false;
        if (message.getRole() != last.getRole()) return false;
        String newText = message.getTextContent();
        String lastText = last.getTextContent();
        if (newText == null || lastText == null) return false;
        return newText.equals(lastText);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}