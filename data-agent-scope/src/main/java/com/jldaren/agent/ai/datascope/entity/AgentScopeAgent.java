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
package com.jldaren.agent.ai.datascope.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AgentScope Agent 实体类
 * 对应数据库表: agent_scope_agent
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class AgentScopeAgent {

    private Long id;

    private String name;

    private String description;

    private String avatar;

    // draft-草稿, published-已发布, offline-已下线
    private String status;

    @JsonIgnore
    private String apiKey;

    private Integer apiKeyEnabled;

    private Integer a2aEnabled;  // A2A 协议是否启用

    private String prompt;

    private String category;

    private Long adminId;

    private String tags;

    /**
     * 启用的工具名称（逗号分隔）
     * 对应 @Tool 注解的 name 属性
     * 为空时使用默认工具集
     */
    private String toolNames;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

}
