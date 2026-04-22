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
package com.jldaren.agent.ai.datascope.controller;

import com.jldaren.agent.ai.datascope.tool.ToolRegistry;
import com.jldaren.agent.ai.datascope.vo.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 工具管理接口
 * 提供已注册工具列表，供前端 Agent 配置页面选择
 */
@RestController
@RequestMapping("/api/scope/tool")
@RequiredArgsConstructor
@Tag(name = "Tool API", description = "工具管理接口")
public class ToolController {

    private final ToolRegistry toolRegistry;

    @GetMapping("/list")
    @Operation(summary = "获取工具列表", description = "获取所有已注册的工具及其参数信息")
    public ApiResponse<List<ToolRegistry.ToolMeta>> listTools() {
        return ApiResponse.success("success", toolRegistry.getAllTools());
    }
}
