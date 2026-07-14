/*
 * Copyright 2026 the original author or authors.
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
package com.jldaren.agent.ai.datascope2.dto;

import lombok.Data;

/**
 * Chat 请求 DTO
 */
@Data
public class ChatRequest {
    /**
     * 用户消息
     */
    private String message;

    /**
     * 会话 ID（不传则自动生成）
     */
    private String sessionId;

    /**
     * 用户 ID（必填，用于多租户隔离）
     */
    private String userId;

    /**
     * 租户 ID（可选）
     */
    private String tenantId;

    /**
     * Agent 名称（多 agent 场景用）
     */
    private String agentName;
}
