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
package com.jldaren.agent.ai.datascope.memory.controller;

import com.jldaren.agent.ai.datascope.memory.OceanBaseLongTermMemory;
import com.jldaren.agent.ai.datascope.memory.config.LongTermMemoryConfig;
import com.jldaren.agent.ai.datascope.memory.entity.MemoryConfig;
import com.jldaren.agent.ai.datascope.memory.entity.MemoryRecord;
import com.jldaren.agent.ai.datascope.memory.entity.MemorySearchResult;
import com.jldaren.agent.ai.datascope.memory.service.LongTermMemoryService;
import com.jldaren.agent.ai.datascope.vo.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆控制器
 * 提供长期记忆的调试和管理接口
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
@Tag(name = "长期记忆管理", description = "长期记忆的记录、检索和管理接口")
public class LongTermMemoryController {

    @Autowired(required = false)
    private LongTermMemoryService memoryService;

    @Autowired
    private LongTermMemoryConfig memoryConfig;

    @PostMapping("/record")
    @Operation(summary = "记录记忆", description = "记录新的长期记忆")
    public ApiResponse<String> recordMemory(
            @Parameter(description = "智能体名称") @RequestParam String agentName,
            @Parameter(description = "用户ID") @RequestParam String userId,
            @Parameter(description = "记忆内容") @RequestParam String content,
            @Parameter(description = "重要性评分 (0.0-1.0)") @RequestParam(required = false) Double importance,
            @Parameter(description = "会话ID") @RequestParam(required = false) String sessionId,
            @Parameter(description = "租户ID") @RequestParam(required = false) String tenantId) {

        if (memoryService == null) {
            return ApiResponse.error("长期记忆服务未启用");
        }

        try {
            String memoryId = memoryService.recordMemory(
                    agentName, userId, sessionId, content, importance, null, tenantId);

            if (memoryId != null) {
                return ApiResponse.success("记忆记录成功", memoryId);
            } else {
                return ApiResponse.error("记忆记录失败");
            }
        } catch (Exception e) {
            log.error("Failed to record memory", e);
            return ApiResponse.error("记忆记录失败: " + e.getMessage());
        }
    }

    @GetMapping("/retrieve")
    @Operation(summary = "检索记忆", description = "通过语义搜索检索相关记忆")
    public ApiResponse<List<MemorySearchResult>> retrieveMemories(
            @Parameter(description = "智能体名称") @RequestParam String agentName,
            @Parameter(description = "用户ID") @RequestParam String userId,
            @Parameter(description = "查询内容") @RequestParam String query,
            @Parameter(description = "返回数量") @RequestParam(required = false, defaultValue = "10") Integer topK,
            @Parameter(description = "租户ID") @RequestParam(required = false) String tenantId) {

        if (memoryService == null) {
            return ApiResponse.error("长期记忆服务未启用");
        }

        try {
            List<MemorySearchResult> results = memoryService.retrieveMemories(
                    agentName, userId, query, topK, tenantId);
            return ApiResponse.success("检索成功", results);
        } catch (Exception e) {
            log.error("Failed to retrieve memories", e);
            return ApiResponse.error("记忆检索失败: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    @Operation(summary = "获取记忆列表", description = "获取用户的所有长期记忆")
    public ApiResponse<List<MemoryRecord>> getMemories(
            @Parameter(description = "智能体名称") @RequestParam String agentName,
            @Parameter(description = "用户ID") @RequestParam String userId,
            @Parameter(description = "返回数量") @RequestParam(required = false, defaultValue = "100") Integer limit,
            @Parameter(description = "租户ID") @RequestParam(required = false) String tenantId) {

        if (memoryService == null) {
            return ApiResponse.error("长期记忆服务未启用");
        }

        try {
            List<MemoryRecord> records = memoryService.getMemoriesByUser(
                    agentName, userId, tenantId, limit);
            return ApiResponse.success("获取成功", records);
        } catch (Exception e) {
            log.error("Failed to get memories", e);
            return ApiResponse.error("获取记忆列表失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{memoryId}")
    @Operation(summary = "删除记忆", description = "删除指定的长期记忆")
    public ApiResponse<Boolean> deleteMemory(
            @Parameter(description = "记忆ID") @PathVariable String memoryId) {

        if (memoryService == null) {
            return ApiResponse.error("长期记忆服务未启用");
        }

        try {
            boolean result = memoryService.deleteMemory(memoryId);
            return result ? ApiResponse.success("删除成功", true) : ApiResponse.error("删除失败");
        } catch (Exception e) {
            log.error("Failed to delete memory: {}", memoryId, e);
            return ApiResponse.error("删除记忆失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/clear")
    @Operation(summary = "清除记忆", description = "清除用户的所有长期记忆")
    public ApiResponse<Integer> clearMemories(
            @Parameter(description = "智能体名称") @RequestParam String agentName,
            @Parameter(description = "用户ID") @RequestParam String userId,
            @Parameter(description = "租户ID") @RequestParam(required = false) String tenantId) {

        if (memoryService == null) {
            return ApiResponse.error("长期记忆服务未启用");
        }

        try {
            int count = memoryService.clearMemories(agentName, userId, tenantId);
            return ApiResponse.success("已清除 " + count + " 条记忆", count);
        } catch (Exception e) {
            log.error("Failed to clear memories", e);
            return ApiResponse.error("清除记忆失败: " + e.getMessage());
        }
    }

    @GetMapping("/count")
    @Operation(summary = "获取记忆数量", description = "获取用户的长期记忆数量")
    public ApiResponse<Integer> getMemoryCount(
            @Parameter(description = "智能体名称") @RequestParam String agentName,
            @Parameter(description = "用户ID") @RequestParam String userId,
            @Parameter(description = "租户ID") @RequestParam(required = false) String tenantId) {

        if (memoryService == null) {
            return ApiResponse.error("长期记忆服务未启用");
        }

        try {
            int count = memoryService.getMemoryCount(agentName, userId, tenantId);
            return ApiResponse.success("获取成功", count);
        } catch (Exception e) {
            log.error("Failed to get memory count", e);
            return ApiResponse.error("获取记忆数量失败: " + e.getMessage());
        }
    }

    @GetMapping("/config")
    @Operation(summary = "获取配置", description = "获取长期记忆的当前配置，支持按智能体和租户查询")
    public ApiResponse<MemoryConfig> getConfig(
            @Parameter(description = "智能体名称") @RequestParam(required = false) String agentName,
            @Parameter(description = "租户ID") @RequestParam(required = false) String tenantId) {
        if (memoryService == null) {
            return ApiResponse.error("长期记忆服务未启用");
        }
        MemoryConfig config = memoryService.getConfig(agentName, tenantId);
        return ApiResponse.success("获取成功", config);
    }

    @PostMapping("/agent")
    @Operation(summary = "创建带长期记忆的Agent", description = "创建一个集成长期记忆的Agent实例")
    public ApiResponse<OceanBaseLongTermMemory> createAgentMemory(
            @Parameter(description = "智能体名称") @RequestParam String agentName,
            @Parameter(description = "用户ID") @RequestParam(required = false) String userId,
            @Parameter(description = "租户ID") @RequestParam(required = false) String tenantId,
            @Parameter(description = "检索数量") @RequestParam(required = false, defaultValue = "5") Integer retrieveCount,
            @Parameter(description = "相似度阈值") @RequestParam(required = false, defaultValue = "0.4") Double threshold) {

        if (memoryService == null) {
            return ApiResponse.error("长期记忆服务未启用");
        }

        try {
            OceanBaseLongTermMemory memory = OceanBaseLongTermMemory.builder()
                    .agentName(agentName)
                    .userId(userId)
                    .tenantId(tenantId)
                    .retrieveCount(retrieveCount)
                    .similarityThreshold(threshold)
                    .memoryService(memoryService)
                    .build();

            return ApiResponse.success("创建成功", memory);
        } catch (Exception e) {
            log.error("Failed to create agent memory", e);
            return ApiResponse.error("创建Agent记忆失败: " + e.getMessage());
        }
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查长期记忆服务是否正常")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("serviceEnabled", memoryService != null);
        health.put("configEnabled", memoryConfig.isEnabled());
        health.put("status", (memoryService != null && memoryConfig.isEnabled()) ? "UP" : "DOWN");

        return ApiResponse.success("健康检查完成", health);
    }
}
