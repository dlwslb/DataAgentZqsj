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
package com.jldaren.agent.ai.datascope.mapper;

import com.jldaren.agent.ai.datascope.entity.ChatMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * ChatMessage Mapper - 消息管理
 */
@Mapper
public interface ChatMessageMapper {

    @Select("SELECT * FROM agent_scope_chat_message WHERE session_id = #{sessionId} ORDER BY create_time ASC")
    List<ChatMessage> findBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM agent_scope_chat_message WHERE id = #{id}")
    ChatMessage findById(@Param("id") Long id);

    @Insert("""
            INSERT INTO agent_scope_chat_message 
            (session_id, agent_id, user_id, tenant_id, role, content, message_type, create_time)
            VALUES (#{sessionId}, #{agentId}, #{userId}, #{tenantId}, #{role}, #{content}, #{messageType}, NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ChatMessage message);

    @Delete("DELETE FROM agent_scope_chat_message WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);

}
