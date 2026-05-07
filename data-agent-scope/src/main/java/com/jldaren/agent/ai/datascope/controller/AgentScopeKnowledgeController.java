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
package com.jldaren.agent.ai.datascope.controller;

import com.jldaren.agent.ai.datascope.dto.TenantDTO;
import com.jldaren.agent.ai.datascope.dto.UserDTO;
import com.jldaren.agent.ai.datascope.entity.AgentScopeKnowledge;
import com.jldaren.agent.ai.datascope.mapper.AgentScopeKnowledgeMapper;
import com.jldaren.agent.ai.datascope.mapper.SystemTenantMapper;
import com.jldaren.agent.ai.datascope.mapper.SystemUserMapper;
import com.jldaren.agent.ai.datascope.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * AgentScope Knowledge Controller
 * 提供知识库的 CRUD 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/scope/knowledge")
@Tag(name = "AgentScope Knowledge", description = "AgentScope 知识库管理接口")
public class AgentScopeKnowledgeController {

    private final AgentScopeKnowledgeMapper knowledgeMapper;
    private final SystemTenantMapper tenantMapper;
    private final SystemUserMapper userMapper;
    private final RagService ragService;
    private final ExecutorService ragExecutor;

    public AgentScopeKnowledgeController(
            AgentScopeKnowledgeMapper knowledgeMapper,
            SystemTenantMapper tenantMapper,
            SystemUserMapper userMapper,
            RagService ragService,
            @Qualifier("ragExecutor") ExecutorService ragExecutor) {
        this.knowledgeMapper = knowledgeMapper;
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
        this.ragService = ragService;
        this.ragExecutor = ragExecutor;
    }

    /**
     * 获取知识列表（按 Agent）
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
     * 获取知识列表（按租户）
     */
    @GetMapping("/tenant/{tenantId}/list")
    @Operation(summary = "按租户获取知识列表", description = "获取指定租户的知识列表，支持按用户过滤")
    public List<AgentScopeKnowledge> listByTenant(@PathVariable Long tenantId,
                                                   @RequestParam(required = false) Long userId,
                                                   @RequestParam(required = false) String type,
                                                   @RequestParam(required = false) String embeddingStatus) {
        return knowledgeMapper.findByTenantId(tenantId, userId, type, embeddingStatus);
    }

    /**
     * 获取公共知识列表（未绑定租户）
     */
    @GetMapping("/public/list")
    @Operation(summary = "获取公共知识列表", description = "获取未绑定租户的公共知识")
    public List<AgentScopeKnowledge> listPublicKnowledge(@RequestParam(required = false) String type,
                                                          @RequestParam(required = false) String embeddingStatus) {
        return knowledgeMapper.findPublicKnowledge(type, embeddingStatus);
    }

    /**
     * 获取知识列表（按用户）
     */
    @GetMapping("/user/{userId}/list")
    @Operation(summary = "按用户获取知识列表", description = "获取指定用户的知识列表（跨租户）")
    public List<AgentScopeKnowledge> listByUser(@PathVariable Long userId,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String embeddingStatus) {
        return knowledgeMapper.findByUserId(userId, type, embeddingStatus);
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
    public AgentScopeKnowledge create(@PathVariable Long agentId,
                                      @RequestParam(required = false) Long tenantId,
                                      @RequestParam(required = false) Long userId,
                                      @RequestBody AgentScopeKnowledge knowledge) {
        knowledge.setAgentId(agentId);
        
        // 设置租户ID（可选）
        if (tenantId != null) {
            knowledge.setTenantId(tenantId);
        } else if (knowledge.getTenantId() != null) {
            knowledge.setTenantId(knowledge.getTenantId());
        }
        
        // 设置用户ID（可选）
        if (userId != null) {
            knowledge.setUserId(userId);
        } else {
            knowledge.setUserId(knowledge.getUserId()); // 保持前端传入的值
        }
        
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
        log.info("✅ Knowledge 创建成功: id={}, agentId={}, tenantId={}, userId={}, title={}",
                knowledge.getId(), agentId, knowledge.getTenantId(), knowledge.getUserId(), knowledge.getTitle());

        // ⚠️ 异步处理向量化，避免阻塞 WebFlux 响应式线程
        ragExecutor.submit(() -> ragService.embedAndStoreKnowledge(knowledge));

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

        // 如果内容发生变化，或者当前状态是 FAILED，重新向量化
        boolean contentChanged = knowledge.getContent() != null && !knowledge.getContent().equals(existing.getContent());
        boolean wasFailed = "FAILED".equals(existing.getEmbeddingStatus());
        
        log.info("🔍 检查是否需要重新向量化: id={}, contentChanged={}, wasFailed={}, currentStatus={}", 
                id, contentChanged, wasFailed, existing.getEmbeddingStatus());
        
        if (contentChanged || wasFailed) {
            String reason = contentChanged ? "内容变化" : "之前失败，重试";
            log.info("🔄 开始异步重新向量化: id={}, 原因={}", id, reason);
            ragExecutor.submit(() -> {
                try {
                    log.info("📤 [异步任务] 开始删除旧向量: id={}", id);
                    // 先删除旧的向量数据
                    ragService.deleteKnowledgeVectors(id);
                    log.info("✅ [异步任务] 旧向量删除成功: id={}", id);
                    
                    // ⭐ 关键：从数据库重新查询完整的知识记录（确保 agentId、tenantId、userId 等字段完整）
                    log.info("📥 [异步任务] 查询完整知识记录: id={}", id);
                    AgentScopeKnowledge fullKnowledge = knowledgeMapper.findById(id);
                    if (fullKnowledge != null) {
                        log.info("✅ [异步任务] 查询成功，开始向量化: id={}, agentId={}, tenantId={}, userId={}", 
                                id, fullKnowledge.getAgentId(), fullKnowledge.getTenantId(), fullKnowledge.getUserId());
                        ragService.embedAndStoreKnowledge(fullKnowledge);
                        log.info("✅ 知识重新向量化成功: id={}", id);
                    } else {
                        log.warn("⚠️ 知识记录不存在，跳过向量化: id={}", id);
                        knowledgeMapper.updateEmbeddingStatusWithError(id, "FAILED", "知识记录不存在");
                    }
                } catch (Exception e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    if (errorMsg.length() > 500) {
                        errorMsg = errorMsg.substring(0, 500);
                    }
                    knowledgeMapper.updateEmbeddingStatusWithError(id, "FAILED", errorMsg);
                    log.error("❌ 重新向量化失败: id={}, error={}", id, e.getMessage(), e);
                }
            });
        } else {
            log.info("ℹ️ 内容未变化，跳过向量化: id={}", id);
        }

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

        // ⚠️ 先异步删除向量数据，避免阻塞 WebFlux 响应式线程
        ragExecutor.submit(() -> ragService.deleteKnowledgeVectors(id));

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

        // ⚠️ 异步重试向量化，避免阻塞 WebFlux 响应式线程
        ragExecutor.submit(() -> {
            try {
                ragService.deleteKnowledgeVectors(id);
                ragService.embedAndStoreKnowledge(knowledge);
                log.info("✅ 知识重试向量化成功: id={}", id);
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (errorMsg.length() > 500) {
                    errorMsg = errorMsg.substring(0, 500);
                }
                knowledgeMapper.updateEmbeddingStatusWithError(id, "FAILED", errorMsg);
                log.error("❌ 重试向量化失败: id={}, error={}", id, e.getMessage(), e);
            }
        });

        log.info("✅ 知识向量化重试完成: id={}", id);
    }

    /**
     * 获取可召回的知识
     */
    @GetMapping("/{agentId}/recallable")
    @Operation(summary = "获取可召回知识", description = "获取指定Agent可召回的知识列表")
    public List<AgentScopeKnowledge> getRecallable(@PathVariable Long agentId) {
        return knowledgeMapper.findRecallableByAgentId(agentId);
    }

    // ==================== 租户和用户管理接口 ====================

    /**
     * 获取所有启用的租户列表
     */
    @GetMapping("/tenants")
    @Operation(summary = "获取租户列表", description = "获取所有启用的租户")
    public List<TenantDTO> getTenants() {
        return tenantMapper.findAllActiveTenants();
    }

    /**
     * 根据租户ID获取用户列表
     */
    @GetMapping("/tenants/{tenantId}/users")
    @Operation(summary = "获取租户下的用户列表", description = "获取指定租户下的所有启用用户")
    public List<UserDTO> getUsersByTenant(@PathVariable Long tenantId) {
        return userMapper.findByTenantId(tenantId);
    }

}
