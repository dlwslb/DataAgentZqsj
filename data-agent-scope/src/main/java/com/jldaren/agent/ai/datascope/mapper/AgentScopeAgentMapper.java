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

import com.jldaren.agent.ai.datascope.entity.AgentScopeAgent;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * AgentScope Agent Mapper
 */
@Mapper
public interface AgentScopeAgentMapper {

    @Select("SELECT * FROM agent_scope_agent ORDER BY create_time DESC")
    List<AgentScopeAgent> findAll();

    @Select("SELECT * FROM agent_scope_agent WHERE id = #{id}")
    AgentScopeAgent findById(@Param("id") Long id);

    @Select("SELECT * FROM agent_scope_agent WHERE status = #{status} ORDER BY create_time DESC")
    List<AgentScopeAgent> findByStatus(@Param("status") String status);

    @Select("""
            SELECT * FROM agent_scope_agent 
            WHERE (name LIKE CONCAT('%', #{keyword}, '%') 
               OR description LIKE CONCAT('%', #{keyword}, '%') 
               OR tags LIKE CONCAT('%', #{keyword}, '%'))
            ORDER BY create_time DESC
            """)
    List<AgentScopeAgent> searchByKeyword(@Param("keyword") String keyword);

    @Select("SELECT * FROM agent_scope_agent WHERE status = 'published' ORDER BY create_time DESC")
    List<AgentScopeAgent> findPublishedAgents();

    @Insert("""
            INSERT INTO agent_scope_agent 
            (name, description, avatar, status, api_key, api_key_enabled, a2a_enabled, prompt, category, admin_id, tags, create_time, update_time)
            VALUES (#{name}, #{description}, #{avatar}, #{status}, #{apiKey}, #{apiKeyEnabled}, #{a2aEnabled}, #{prompt}, #{category}, #{adminId}, #{tags}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AgentScopeAgent agent);

    @Update("""
            <script>
                UPDATE agent_scope_agent
                <trim prefix="SET" suffixOverrides=",">
                    <if test="name != null">name = #{name},</if>
                    <if test="description != null">description = #{description},</if>
                    <if test="avatar != null">avatar = #{avatar},</if>
                    <if test="status != null">status = #{status},</if>
                    <if test="apiKey != null">api_key = #{apiKey},</if>
                    <if test="apiKeyEnabled != null">api_key_enabled = #{apiKeyEnabled},</if>
                    <if test="a2aEnabled != null">a2a_enabled = #{a2aEnabled},</if>
                    <if test="prompt != null">prompt = #{prompt},</if>
                    <if test="category != null">category = #{category},</if>
                    <if test="adminId != null">admin_id = #{adminId},</if>
                    <if test="tags != null">tags = #{tags},</if>
                    update_time = NOW()
                </trim>
                WHERE id = #{id}
            </script>
            """)
    int updateById(AgentScopeAgent agent);

    @Delete("DELETE FROM agent_scope_agent WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Update("""
            UPDATE agent_scope_agent 
            SET status = #{status}, update_time = NOW() 
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Select("SELECT COUNT(*) FROM agent_scope_agent WHERE status = 'published'")
    int countPublished();

}
