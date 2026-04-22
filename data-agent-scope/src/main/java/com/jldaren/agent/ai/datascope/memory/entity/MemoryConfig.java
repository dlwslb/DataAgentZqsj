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

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 长期记忆配置实体类
 * 对应数据库表: long_term_memory_config
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class MemoryConfig {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 智能体名称 (NULL表示全局配置)
     */
    private String agentName;

    /**
     * 租户ID
     */
    private String tenantId;

    /**
     * 是否启用: 0-禁用, 1-启用
     */
    private Integer enabled;

    /**
     * 相似度阈值
     */
    private BigDecimal similarityThreshold;

    /**
     * 默认检索数量
     */
    private Integer defaultTopk;

    /**
     * 最大检索数量
     */
    private Integer maxTopk;

    /**
     * 默认保留天数
     */
    private Integer retentionDays;

    /**
     * 自动记录重要性阈值
     */
    private BigDecimal autoRecordThreshold;

    /**
     * 是否启用自动记录: 0-禁用, 1-启用
     */
    private Integer autoRecordEnabled;

    /**
     * 默认记忆类型
     */
    private String memoryType;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /**
     * 是否启用
     */
    public boolean isEnabled() {
        return enabled != null && enabled == 1;
    }

    /**
     * 是否启用自动记录
     */
    public boolean isAutoRecordEnabled() {
        return autoRecordEnabled != null && autoRecordEnabled == 1;
    }
}
