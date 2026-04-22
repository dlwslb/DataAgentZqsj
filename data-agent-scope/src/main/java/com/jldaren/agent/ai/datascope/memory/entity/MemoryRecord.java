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

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 长期记忆记录实体类
 * 对应数据库表: long_term_memory
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class MemoryRecord {

    /**
     * 记忆ID (UUID)
     */
    private String id;

    /**
     * 租户ID (多租户隔离)
     */
    private String tenantId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 记忆类型: semantic-语义, episodic-情景, procedural-程序性
     */
    private String memoryType;

    /**
     * 记忆内容
     */
    private String content;

    /**
     * 关联的向量ID (agent_scope_vector_store表)
     */
    private String contentVectorId;

    /**
     * 元数据JSON
     */
    private Map<String, Object> metadata;

    /**
     * 重要性评分 (0.00-1.00)
     */
    private Double importanceScore;

    /**
     * 是否自动记录: 0-手动, 1-自动
     */
    private Integer isAuto;

    /**
     * 过期时间 (NULL表示永久)
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime expireTime;

    /**
     * 访问次数
     */
    private Integer accessCount;

    /**
     * 最后访问时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime lastAccessTime;

    /**
     * 是否删除: 0-未删除, 1-已删除
     */
    private Integer isDeleted;

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
     * 记忆类型枚举
     */
    public enum MemoryType {
        SEMANTIC("semantic", "语义记忆"),
        EPISODIC("episodic", "情景记忆"),
        PROCEDURAL("procedural", "程序性记忆");

        private final String code;
        private final String description;

        MemoryType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }

        public static MemoryType fromCode(String code) {
            for (MemoryType type : values()) {
                if (type.code.equals(code)) {
                    return type;
                }
            }
            return SEMANTIC;
        }
    }
}

