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

import com.jldaren.agent.ai.datascope.entity.ChatSession;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * ChatSession Mapper - 会话管理
 */
@Mapper
public interface ChatSessionMapper {

    @Select("SELECT * FROM agent_scope_chat_session WHERE agent_id = #{agentId} ORDER BY update_time DESC")
    List<ChatSession> findByAgentId(@Param("agentId") Long agentId);

    @Select("SELECT * FROM agent_scope_chat_session WHERE agent_id = #{agentId} AND user_id = #{userId} ORDER BY update_time DESC")
    List<ChatSession> findByAgentIdAndUserId(@Param("agentId") Long agentId, @Param("userId") Long userId);

    @Select("SELECT * FROM agent_scope_chat_session WHERE id = #{id}")
    ChatSession findById(@Param("id") String id);

    @Insert("""
            INSERT INTO agent_scope_chat_session 
            (id,agent_id, user_id, title, status, create_time, update_time)
            VALUES (#{id},#{agentId}, #{userId}, #{title}, 'active', NOW(), NOW())
            """)
    int insert(ChatSession session);

    @Update("""
            UPDATE agent_scope_chat_session 
            SET session_name = #{title}, status = #{status}, update_time = NOW()
            WHERE id = #{id}
            """)
    int updateById(ChatSession session);

    @Delete("DELETE FROM agent_scope_chat_session WHERE id = #{id}")
    int deleteById(@Param("id") String id);

    @Update("UPDATE agent_scope_chat_session SET update_time = NOW() WHERE id = #{id}")
    int updateTime(@Param("id") String id);

}
