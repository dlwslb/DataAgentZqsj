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
package com.jldaren.agent.ai.datascope.memory.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 记忆搜索结果类
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class MemorySearchResult {

    /**
     * 记忆ID
     */
    private String id;

    /**
     * 记忆内容
     */
    private String content;

    /**
     * 记忆类型
     */
    private String memoryType;

    /**
     * 相似度分数
     */
    private Double similarityScore;

    /**
     * 重要性评分
     */
    private Double importanceScore;

    /**
     * 元数据
     */
    private Map<String, Object> metadata;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 创建时间
     */
    private String createTime;

    /**
     * 是否自动记录
     */
    private Boolean isAuto;

    /**
     * 创建 MemoryRecord 实体
     */
    public MemoryRecord toMemoryRecord() {
        return MemoryRecord.builder()
                .id(this.id)
                .content(this.content)
                .memoryType(this.memoryType)
                .importanceScore(this.importanceScore)
                .sessionId(this.sessionId)
                .isAuto(this.isAuto ? 1 : 0)
                .metadata(this.metadata)
                .build();
    }
}
