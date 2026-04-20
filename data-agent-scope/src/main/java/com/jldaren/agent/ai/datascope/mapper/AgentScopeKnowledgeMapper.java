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

import com.jldaren.agent.ai.datascope.entity.AgentScopeKnowledge;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * AgentScope Knowledge Mapper
 */
@Mapper
public interface AgentScopeKnowledgeMapper {

    @Select("SELECT * FROM agent_scope_knowledge WHERE agent_id = #{agentId} AND is_deleted = 0 ORDER BY create_time DESC")
    List<AgentScopeKnowledge> findByAgentId(@Param("agentId") Long agentId);

    @Select("SELECT * FROM agent_scope_knowledge WHERE id = #{id}")
    AgentScopeKnowledge findById(@Param("id") Long id);

    @Select("""
            SELECT * FROM agent_scope_knowledge 
            WHERE agent_id = #{agentId} 
              AND is_deleted = 0
              <if test="type != null and type != ''">
                  AND type = #{type}
              </if>
              <if test="embeddingStatus != null and embeddingStatus != ''">
                  AND embedding_status = #{embeddingStatus}
              </if>
            ORDER BY create_time DESC
            """)
    List<AgentScopeKnowledge> findByConditions(@Param("agentId") Long agentId,
                                              @Param("type") String type,
                                              @Param("embeddingStatus") String embeddingStatus);

    @Select("SELECT * FROM agent_scope_knowledge WHERE agent_id = #{agentId} AND is_recall = 1 AND is_deleted = 0")
    List<AgentScopeKnowledge> findRecallableByAgentId(@Param("agentId") Long agentId);

    @Insert("""
            INSERT INTO agent_scope_knowledge 
            (agent_id, title, type, question, content, is_recall, embedding_status, source_filename, file_path, file_size, file_type, splitter_type, create_time, update_time)
            VALUES (#{agentId}, #{title}, #{type}, #{question}, #{content}, #{isRecall}, #{embeddingStatus}, #{sourceFilename}, #{filePath}, #{fileSize}, #{fileType}, #{splitterType}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AgentScopeKnowledge knowledge);

    @Update("""
            <script>
                UPDATE agent_scope_knowledge
                <trim prefix="SET" suffixOverrides=",">
                    <if test="title != null">title = #{title},</if>
                    <if test="question != null">question = #{question},</if>
                    <if test="content != null">content = #{content},</if>
                    <if test="isRecall != null">is_recall = #{isRecall},</if>
                    <if test="embeddingStatus != null">embedding_status = #{embeddingStatus},</if>
                    <if test="errorMsg != null">error_msg = #{errorMsg},</if>
                    update_time = NOW()
                </trim>
                WHERE id = #{id}
            </script>
            """)
    int updateById(AgentScopeKnowledge knowledge);

    @Update("UPDATE agent_scope_knowledge SET is_recall = #{isRecall}, update_time = NOW() WHERE id = #{id}")
    int updateRecall(@Param("id") Long id, @Param("isRecall") Integer isRecall);

    @Update("UPDATE agent_scope_knowledge SET embedding_status = #{status}, update_time = NOW() WHERE id = #{id}")
    int updateEmbeddingStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE agent_scope_knowledge SET is_deleted = 1, update_time = NOW() WHERE id = #{id}")
    int softDelete(@Param("id") Long id);

    @Delete("DELETE FROM agent_scope_knowledge WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Delete("DELETE FROM agent_scope_knowledge WHERE agent_id = #{agentId}")
    int deleteByAgentId(@Param("agentId") Long agentId);

}
