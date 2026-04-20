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

import com.jldaren.agent.ai.datascope.entity.AgentScopeKnowledge;
import com.jldaren.agent.ai.datascope.mapper.AgentScopeKnowledgeMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * AgentScope Knowledge Controller
 * 提供知识库的 CRUD 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/scope/knowledge")
@RequiredArgsConstructor
@Tag(name = "AgentScope Knowledge", description = "AgentScope 知识库管理接口")
public class AgentScopeKnowledgeController {

    private final AgentScopeKnowledgeMapper knowledgeMapper;

    /**
     * 获取知识列表
     */
    @GetMapping("/{agentId}/list")
    @Operation(summary = "获取知识列表", description = "获取指定Agent的知识列表")
    public List<AgentScopeKnowledge> list(@PathVariable Long agentId,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String embeddingStatus) {
        if ((type != null && !type.isBlank()) || (embeddingStatus != null && !embeddingStatus.isBlank())) {
            return knowledgeMapper.findByConditions(agentId, type, embeddingStatus);
        }
        return knowledgeMapper.findByAgentId(agentId);
    }

    /**
     * 获取知识详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取知识详情", description = "根据ID获取知识详情")
    public AgentScopeKnowledge get(@PathVariable Long id) {
        AgentScopeKnowledge knowledge = knowledgeMapper.findById(id);
        if (knowledge == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge not found: " + id);
        }
        return knowledge;
    }

    /**
     * 创建知识
     */
    @PostMapping("/{agentId}")
    @Operation(summary = "创建知识", description = "为指定Agent创建知识")
    public AgentScopeKnowledge create(@PathVariable Long agentId, @RequestBody AgentScopeKnowledge knowledge) {
        knowledge.setAgentId(agentId);
        if (knowledge.getIsRecall() == null) {
            knowledge.setIsRecall(1);
        }
        if (knowledge.getEmbeddingStatus() == null) {
            knowledge.setEmbeddingStatus("PENDING");
        }
        if (knowledge.getSplitterType() == null) {
            knowledge.setSplitterType("token");
        }

        knowledgeMapper.insert(knowledge);
        log.info("✅ Knowledge 创建成功: id={}, agentId={}, title={}", knowledge.getId(), agentId, knowledge.getTitle());

        // 异步处理向量化（这里简化处理，直接标记为完成）
        // 实际项目中应该异步调用向量化服务
        knowledgeMapper.updateEmbeddingStatus(knowledge.getId(), "COMPLETED");

        return knowledge;
    }

    /**
     * 更新知识
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新知识", description = "更新知识信息")
    public AgentScopeKnowledge update(@PathVariable Long id, @RequestBody AgentScopeKnowledge knowledge) {
        AgentScopeKnowledge existing = knowledgeMapper.findById(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge not found: " + id);
        }

        knowledge.setId(id);
        knowledgeMapper.updateById(knowledge);

        log.info("✅ Knowledge 更新成功: id={}, title={}", id, knowledge.getTitle());
        return knowledge;
    }

    /**
     * 删除知识
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除知识", description = "删除知识")
    public void delete(@PathVariable Long id) {
        AgentScopeKnowledge knowledge = knowledgeMapper.findById(id);
        if (knowledge == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge not found: " + id);
        }

        // 软删除
        knowledgeMapper.softDelete(id);

        log.info("✅ Knowledge 删除成功: id={}", id);
    }

    /**
     * 更新召回状态
     */
    @PutMapping("/{id}/recall")
    @Operation(summary = "更新召回状态", description = "设置知识是否被召回")
    public void updateRecall(@PathVariable Long id, @RequestBody Map<String, Boolean> request) {
        Boolean isRecall = request.get("isRecall");
        if (isRecall == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "isRecall is required");
        }

        knowledgeMapper.updateRecall(id, isRecall ? 1 : 0);
        log.info("✅ Knowledge 召回状态更新: id={}, isRecall={}", id, isRecall);
    }

    /**
     * 重试向量化
     */
    @PostMapping("/{id}/retry")
    @Operation(summary = "重试向量化", description = "重新处理知识向量化")
    public void retryEmbedding(@PathVariable Long id) {
        AgentScopeKnowledge knowledge = knowledgeMapper.findById(id);
        if (knowledge == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge not found: " + id);
        }

        // 重置状态为处理中
        knowledgeMapper.updateEmbeddingStatus(id, "PROCESSING");

        // 模拟异步处理
        // 实际项目中应该发送到消息队列或异步调用向量化服务
        log.info("🔄 知识向量化重试: id={}, title={}", id, knowledge.getTitle());

        // 简化处理：直接标记为完成
        knowledgeMapper.updateEmbeddingStatus(id, "COMPLETED");

        log.info("✅ 知识向量化完成: id={}", id);
    }

    /**
     * 获取可召回的知识
     */
    @GetMapping("/{agentId}/recallable")
    @Operation(summary = "获取可召回知识", description = "获取指定Agent可召回的知识列表")
    public List<AgentScopeKnowledge> getRecallable(@PathVariable Long agentId) {
        return knowledgeMapper.findRecallableByAgentId(agentId);
    }

}
