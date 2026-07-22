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

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一错误响应
 *
 * <p>错误分类（type 字段）：
 * <ul>
 *   <li>UNAUTHORIZED        - token 无效 / 过期</li>
 *   <li>FORBIDDEN           - 权限不足 / 租户隔离被破坏</li>
 *   <li>BAD_REQUEST         - 参数错误（bizType 错、conditions 缺字段等）</li>
 *   <li>NOT_FOUND           - 资源不存在</li>
 *   <li>BIZ_DATA_ERROR       - 业务数据查询失败（DB 错、SQL 语法错等）</li>
 *   <li>AGENT_ERROR          - Agent 调用失败（LLM 拒绝、Tool 异常）</li>
 *   <li>INTERNAL_ERROR       - 未知错误</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {

    /** 错误码（HTTP status） */
    private int status;

    /** 错误类型 */
    private String type;

    /** 用户友好的错误信息（前端直接显示） */
    private String message;

    /** 详细错误（仅 dev 环境或服务端日志） */
    private String detail;

    /** 时间戳 */
    private Long timestamp;
}
