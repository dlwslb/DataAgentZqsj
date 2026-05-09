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
package com.jldaren.agent.ai.datascope.hook;

import com.jldaren.agent.ai.datascope.util.MessageContentCleaner;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * RAG 内容清理 Hook - 在 LLM 回答完成后异步清理 Redis 中的 RAG 增强内容
 * 
 * <p>工作流程：
 * <ol>
 *   <li>监听 PostCallEvent（Agent 调用完成）</li>
 *   <li>从 Redis 中读取当前会话的最后一条 USER 消息</li>
 *   <li>提取原始问题（去除 RAG 知识、长期记忆等噪声）</li>
 *   <li>更新 Redis，只保留简洁的原始问题</li>
 * </ol>
 * 
 * <p>优点：
 * <ul>
 *   <li>✅ LLM 已经看到完整的 RAG 上下文并完成推理</li>
 *   <li>✅ Redis 中存储的是简洁的原始问题，节省空间</li>
 *   <li>✅ 对话历史更易读，便于人工审查</li>
 *   <li>✅ 不影响当前对话，只影响后续从 Redis 读取历史</li>
 * </ul>
 */
@Slf4j
public class RagCleanupHook implements Hook {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final String agentId;
    private final String tenantId;
    private final String userId;
    private final String sessionId;
    
    private static final String KEY_PREFIX = "memory";

    public RagCleanupHook(ReactiveStringRedisTemplate redisTemplate, 
                         String agentId, String tenantId, String userId, String sessionId) {
        this.redisTemplate = redisTemplate;
        this.agentId = agentId != null ? agentId : "unknown";
        this.tenantId = tenantId != null ? tenantId : "default";
        this.userId = userId != null ? userId : "anonymous";
        this.sessionId = sessionId;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.fromCallable(() -> {
            if (event instanceof PostCallEvent postCallEvent) {
                log.info("🔔 [RAG清理] PostCallEvent 触发，开始清理 RAG 内容: sessionId={}", sessionId);
                // ⭐ LLM 已经完成推理，现在可以安全地清理 Redis 中的 RAG 内容
                cleanupRagFromRedis();
            } else {
                log.debug("🔍 [RAG清理] 收到其他事件: {}", event.getClass().getSimpleName());
            }
            return event;
        });
    }

    /**
     * 异步清理 Redis 中的 RAG 增强内容
     */
    private void cleanupRagFromRedis() {
        if (redisTemplate == null || sessionId == null) {
            log.warn("⚠️ [RAG清理] RedisTemplate 或 sessionId 为空，跳过清理");
            return;
        }

        try {
            // ⭐ 构建 Redis key（与 BoundedRedisMemory.buildKey() 保持一致）
            String key = KEY_PREFIX + ":" + agentId + ":" + tenantId + ":" + userId + ":" + sessionId + ":messages";
            log.debug("🔍 [RAG清理] 开始读取 Redis: key={}", key);
            
            // ⭐ 优化：只读取最后 5 条消息（通常用户消息就在其中），避免读取全部历史
            redisTemplate.opsForZSet()
                    .reverseRange(key, org.springframework.data.domain.Range.from(
                            org.springframework.data.domain.Range.Bound.inclusive(0L))
                            .to(org.springframework.data.domain.Range.Bound.inclusive(4L)))  // 最后 5 条
                    .collectList()
                    .subscribe(members -> {
                        log.debug("🔍 [RAG清理] 从 Redis 读取到 {} 条消息", members != null ? members.size() : 0);
                        
                        if (members == null || members.isEmpty()) {
                            log.warn("⚠️ [RAG清理] Redis 中没有消息，sessionId={}", sessionId);
                            return;
                        }

                        // 找到最后一条 USER 消息
                        String lastUserMember = null;
                        for (int i = members.size() - 1; i >= 0; i--) {
                            String member = members.get(i);
                            if (member.contains("\"role\":\"USER\"")) {
                                lastUserMember = member;
                                log.debug("✅ [RAG清理] 找到 USER 消息，长度={}", member.length());
                                break;
                            }
                        }

                        if (lastUserMember == null) {
                            log.warn("⚠️ [RAG清理] 没有找到 USER 消息，sessionId={}", sessionId);
                            return;
                        }

                        // 解析 member 格式：{id}|||{json}
                        int separatorIndex = lastUserMember.indexOf("|||");
                        if (separatorIndex < 0) {
                            log.warn("⚠️ [RAG清理] Member 格式错误: {}", lastUserMember);
                            return;
                        }

                        String memberId = lastUserMember.substring(0, separatorIndex);
                        String json = lastUserMember.substring(separatorIndex + 3);

                        // ⭐ 快速检测：只有包含 RAG 或长期记忆标记时才执行清理
                        if (!json.contains("【相关知识参考】") && 
                            !json.contains("【用户历史记忆】") &&
                            !json.contains("<long_term_memory>") &&
                            !json.contains("请基于上述相关知识") &&
                            !json.contains("请结合上述")) {
                            log.debug("✅ [RAG清理] 无需清理（无 RAG/记忆增强标记），sessionId={}", sessionId);
                            return;
                        }

                        // 提取原始问题
                        String originalText = extractTextFromJson(json);
                        log.debug("🔍 [RAG清理] 提取到的原始文本长度: {}", originalText != null ? originalText.length() : 0);
                        
                        if (originalText == null || originalText.isEmpty()) {
                            log.warn("⚠️ [RAG清理] 无法提取文本，sessionId={}", sessionId);
                            return;
                        }

                        String cleanText = MessageContentCleaner.extractOriginalQuestion(originalText);
                        log.debug("🔍 [RAG清理] 清理后的文本长度: {}", cleanText.length());
                        
                        // 如果内容没有变化，无需更新
                        if (cleanText.equals(originalText)) {
                            log.info("✅ [RAG清理] 内容无需清理（已经是原始问题），sessionId={}", sessionId);
                            return;
                        }

                        // 构建新的 JSON（替换 content 数组中的 text 字段）
                        String escapedOriginal = escapeJson(originalText);
                        String escapedClean = escapeJson(cleanText);
                        
                        // 替换第一个 TextBlock 的 text 值
                        String newJson = json.replace(
                                "\"text\":\"" + escapedOriginal + "\"",
                                "\"text\":\"" + escapedClean + "\""
                        );

                        // ⭐ 关键：使用 Lua 脚本原子性地更新，保持 score 不变
                        // Lua 脚本：先获取 score，再删除旧 member，最后添加新 member（使用原 score）
                        String luaScript = 
                                "local score = redis.call('ZSCORE', KEYS[1], ARGV[1]) " +
                                "if score then " +
                                "  redis.call('ZREM', KEYS[1], ARGV[1]) " +
                                "  redis.call('ZADD', KEYS[1], score, ARGV[2]) " +
                                "  return 1 " +
                                "else " +
                                "  return 0 " +
                                "end";
                        
                        String newMember = memberId + "|||" + newJson;
                        
                        // ⭐ 创建 Lua 脚本
                        org.springframework.data.redis.core.script.DefaultRedisScript<Long> script = 
                                new org.springframework.data.redis.core.script.DefaultRedisScript<>();
                        script.setScriptText(luaScript);
                        script.setResultType(Long.class);
                        
                        redisTemplate.execute(
                                script,
                                java.util.Collections.singletonList(key),
                                lastUserMember,  // ARGV[1]: 旧 member
                                newMember        // ARGV[2]: 新 member
                        ).subscribe(
                                result -> {
                                    if (result != null && result == 1) {
                                        log.info("✅ [RAG清理] 成功清理 RAG 内容（Lua 原子操作）: sessionId={}, 原始长度={}, 清理后长度={}",
                                                sessionId, originalText.length(), cleanText.length());
                                    } else {
                                        log.warn("⚠️ [RAG清理] 未找到旧 member，可能已被删除: sessionId={}", sessionId);
                                    }
                                },
                                error -> log.error("❌ [RAG清理] Lua 脚本执行失败: sessionId={}, error={}",
                                        sessionId, error.getMessage())
                        );

                    }, error -> {
                        log.error("❌ [RAG清理] 读取 Redis 失败: sessionId={}, error={}",
                                sessionId, error.getMessage());
                    });

        } catch (Exception e) {
            log.error("❌ [RAG清理] 异常: sessionId={}, error={}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * 从 JSON 中提取 textContent
     * 注意：AgentScope 的 Msg 序列化后，文本内容在 content 数组中
     */
    private String extractTextFromJson(String json) {
        try {
            // ⭐ AgentScope Msg 格式：content 是一个数组，包含 TextBlock
            // {"content":[{"type":"text","text":"..."}], ...}
            
            int contentStart = json.indexOf("\"content\":[");
            if (contentStart < 0) return null;
            
            // 找到第一个 TextBlock 的 text 字段
            int textStart = json.indexOf("\"text\":\"", contentStart);
            if (textStart < 0) return null;
            
            textStart += "\"text\":\"".length();
            
            // 找到对应的结束引号（需要处理转义）
            int end = textStart;
            while (end < json.length()) {
                if (json.charAt(end) == '\\' && end + 1 < json.length()) {
                    end += 2; // 跳过转义字符
                    continue;
                }
                if (json.charAt(end) == '"') {
                    break;
                }
                end++;
            }
            
            if (end >= json.length()) return null;
            
            String text = json.substring(textStart, end);
            // 处理转义字符
            return text.replace("\\n", "\n")
                      .replace("\\\"", "\"")
                      .replace("\\\\", "\\");
        } catch (Exception e) {
            log.warn("⚠️ [RAG清理] 提取 textContent 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 转义 JSON 字符串
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
