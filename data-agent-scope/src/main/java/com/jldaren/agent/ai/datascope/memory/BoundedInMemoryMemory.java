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

import io.agentscope.core.message.Msg;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 带限制的短期记忆：去重 + 数量限制 + 会话标识。
 *
 * <p>特性：
 * <ol>
 *   <li>去重：连续相同的 role+textContent 不重复添加</li>
 *   <li>限流：内存中始终 ≤ maxMessages 条，超出自动淘汰最旧的</li>
 *   <li>会话标识：支持 agentId、tenantId、userId、sessionId 四级隔离（仅用于日志和调试）</li>
 * </ol>
 *
 * <p>注意：内存版本不支持持久化，服务重启后数据丢失。生产环境建议使用 BoundedRedisMemory。
 */
@Slf4j
public class BoundedInMemoryMemory implements Memory {

    private final List<Msg> messages = new CopyOnWriteArrayList<>();
    private final int maxMessages;
    private final String agentId;
    private final String tenantId;
    private final String userId;
    private final String sessionId;

    private static final String KEY_PREFIX = "memory";
    private static final int DEFAULT_MAX_MESSAGES = 120;

    public BoundedInMemoryMemory() {
        this(null, null, null, null, DEFAULT_MAX_MESSAGES);
    }

    public BoundedInMemoryMemory(int maxMessages) {
        this(null, null, null, null, maxMessages);
    }

    public BoundedInMemoryMemory(String agentId, String tenantId, String userId, String sessionId) {
        this(agentId, tenantId, userId, sessionId, DEFAULT_MAX_MESSAGES);
    }

    public BoundedInMemoryMemory(String agentId, String tenantId, String userId, String sessionId, int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be > 0, got: " + maxMessages);
        }
        this.agentId = agentId != null ? agentId : "unknown";
        this.tenantId = tenantId != null ? tenantId : "default";
        this.userId = userId != null ? userId : "anonymous";
        this.sessionId = sessionId != null ? sessionId : "no_session";
        this.maxMessages = maxMessages;
        log.debug("BoundedInMemoryMemory 初始化完成, agentId={}, tenantId={}, userId={}, sessionId={}, maxMessages={}",
                  this.agentId, this.tenantId, this.userId, this.sessionId, maxMessages);
    }

    // ==================== StateModule ====================

    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        session.save(sessionKey, KEY_PREFIX + "_messages", new ArrayList<>(messages));
    }

    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        List<Msg> loaded = session.getList(sessionKey, KEY_PREFIX + "_messages", Msg.class);
        messages.clear();
        messages.addAll(loaded);
        trimToSize();
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
        
        // 如果是 USER 消息，清理 RAG/长期记忆增强内容
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
                    log.debug("USER 消息已清理 RAG 内容: 原始长度={}, 清理后长度={}", 
                            originalText.length(), cleanText.length());
                }
            }
        }
        
        if (isDuplicate(messageToSave)) {
            log.debug("消息重复，跳过添加: agentId={}, sessionId={}", agentId, sessionId);
            return;
        }
        messages.add(messageToSave);
        trimToSize();
        log.debug("消息已添加, agentId={}, sessionId={}, 当前消息数={}", agentId, sessionId, messages.size());
    }

    @Override
    public List<Msg> getMessages() {
        return messages.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteMessage(int index) {
        if (index >= 0 && index < messages.size()) {
            messages.remove(index);
        }
    }

    @Override
    public void clear() {
        messages.clear();
        log.info("会话已清空, agentId={}, tenantId={}, userId={}, sessionId={}", 
                 agentId, tenantId, userId, sessionId);
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    // ==================== 内部方法 ====================

    private void trimToSize() {
        while (messages.size() > maxMessages) {
            messages.remove(0);
        }
    }

    /**
     * 连续相同的 role+textContent 视为重复，跳过
     */
    private boolean isDuplicate(Msg message) {
        if (messages.isEmpty()) return false;
        Msg last = messages.get(messages.size() - 1);
        if (last == null) return false;
        if (message.getRole() != last.getRole()) return false;
        String newText = message.getTextContent();
        String lastText = last.getTextContent();
        if (newText == null || lastText == null) return false;
        return newText.equals(lastText);
    }
}
