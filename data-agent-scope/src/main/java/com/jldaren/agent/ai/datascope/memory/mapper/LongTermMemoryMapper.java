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
package com.jldaren.agent.ai.datascope.memory.mapper;

import com.jldaren.agent.ai.datascope.memory.entity.MemoryConfig;
import com.jldaren.agent.ai.datascope.memory.entity.MemoryRecord;
import com.jldaren.agent.ai.datascope.memory.entity.MemorySearchResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 长期记忆 Mapper 接口
 */
@Mapper
public interface LongTermMemoryMapper {

    /**
     * 插入记忆记录
     */
    int insert(MemoryRecord record);

    /**
     * 根据ID查询记忆
     */
    MemoryRecord selectById(@Param("id") String id);

    /**
     * 根据向量ID查询记忆
     */
    MemoryRecord selectByVectorId(@Param("vectorId") String vectorId);

    /**
     * 通过向量相似度搜索记忆
     *
     * @param agentName   智能体名称
     * @param userId      用户ID
     * @param tenantId    租户ID
     * @param query       查询内容
     * @param threshold   相似度阈值
     * @param topK        返回数量
     * @param memoryType  记忆类型
     * @return 搜索结果列表
     */
    List<MemorySearchResult> searchByVector(
            @Param("agentName") String agentName,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("query") String query,
            @Param("threshold") Double threshold,
            @Param("topK") Integer topK,
            @Param("memoryType") String memoryType
    );

    /**
     * 关键词搜索记忆
     */
    List<MemoryRecord> searchByKeyword(
            @Param("agentName") String agentName,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            @Param("memoryType") String memoryType,
            @Param("limit") Integer limit
    );

    /**
     * 获取用户的记忆列表
     */
    List<MemoryRecord> selectByUser(
            @Param("agentName") String agentName,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("memoryType") String memoryType,
            @Param("limit") Integer limit
    );

    /**
     * 获取会话相关的记忆
     */
    List<MemoryRecord> selectBySession(
            @Param("agentName") String agentName,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("tenantId") String tenantId
    );

    /**
     * 更新访问信息
     */
    int updateAccessInfo(@Param("id") String id);

    /**
     * 批量更新访问信息
     */
    int batchUpdateAccessInfo(@Param("ids") List<String> ids);

    /**
     * 更新记忆内容（Mem0 式记忆更新：用户说"我改名叫李四"会更新而非新增）
     *
     * @param id             记忆ID
     * @param content        新内容
     * @param importanceScore 新重要性评分
     * @return 影响行数
     */
    int updateContent(@Param("id") String id, @Param("content") String content,
                      @Param("importanceScore") Double importanceScore);

    /**
     * 软删除记忆
     */
    int delete(@Param("id") String id);

    /**
     * 批量软删除记忆
     */
    int batchDelete(@Param("ids") List<String> ids);

    /**
     * 清理过期记忆
     */
    int cleanExpired();

    /**
     * 统计用户记忆数量
     */
    int countByUser(
            @Param("agentName") String agentName,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId
    );

    /**
     * 检查是否已存在相同内容的记忆（去重检查）
     */
    int existsByContent(
            @Param("agentName") String agentName,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("content") String content
    );

    /**
     * 查找包含指定关键词的第一条记忆（用于矛盾检测/记忆更新）
     */
    MemoryRecord selectFirstByContentLike(
            @Param("agentName") String agentName,
            @Param("userId") String userId,
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword
    );

    /**
     * 获取全局配置
     */
    MemoryConfig selectGlobalConfig();

    /**
     * 获取智能体配置
     */
    MemoryConfig selectAgentConfig(@Param("agentName") String agentName, @Param("tenantId") String tenantId);

    /**
     * 更新配置
     */
    int updateConfig(MemoryConfig config);

    /**
     * 插入配置
     */
    int insertConfig(MemoryConfig config);
}
