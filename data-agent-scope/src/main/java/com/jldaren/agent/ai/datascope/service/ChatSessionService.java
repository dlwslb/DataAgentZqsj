package com.jldaren.agent.ai.datascope.service;

import com.jldaren.agent.ai.datascope.entity.ChatMessage;
import com.jldaren.agent.ai.datascope.entity.ChatSession;
import com.jldaren.agent.ai.datascope.mapper.ChatMessageMapper;
import com.jldaren.agent.ai.datascope.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    /**
     * 创建会话并保存用户消息（保证事务）
     */
    @Transactional(rollbackFor = Exception.class)
    public String createSessionAndSaveUserMsg(Long agentId, String userId, String tenantId,
                                               String sessionId, String message) {
        Long userIdLong = parseLongSafe(userId, null);
        Long tenantIdLong = parseLongSafe(tenantId, null);
        String finalSessionId = sessionId;

        if (sessionId == null || sessionId.isBlank()) {
            finalSessionId = UUID.randomUUID().toString();
            ChatSession session = ChatSession.builder()
                    .id(finalSessionId)
                    .agentId(agentId)
                    .userId(userIdLong)
                    .tenantId(tenantIdLong)
                    .title(message.length() > 20 ? message.substring(0, 20) + "..." : message)
                    .status("active")
                    .build();
            chatSessionMapper.insert(session);
        }

        ChatMessage userMsg = ChatMessage.builder()
                .sessionId(finalSessionId)
                .agentId(agentId)
                .userId(userIdLong)
                .tenantId(tenantIdLong)
                .role("user")
                .content(message)
                .messageType("text")
                .build();
        chatMessageMapper.insert(userMsg);
        chatSessionMapper.updateTime(finalSessionId);

        return finalSessionId;
    }

    private Long parseLongSafe(String str, Long defaultValue) {
        if (str == null || str.isBlank()) return defaultValue;
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse Long from '{}'", str);
            return defaultValue;
        }
    }
}
