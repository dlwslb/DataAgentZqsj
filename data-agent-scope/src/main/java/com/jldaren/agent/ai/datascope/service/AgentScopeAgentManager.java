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
package com.jldaren.agent.ai.datascope.service;

import com.jldaren.agent.ai.datascope.entity.AgentScopeAgent;
import com.jldaren.agent.ai.datascope.mapper.AgentScopeAgentMapper;
import com.jldaren.agent.ai.datascope.registry.AgentScopeRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AgentScope Agent 生命周期管理器
 * 负责 Agent 的启动加载、发布、下线等生命周期管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentScopeAgentManager {

    private final AgentScopeAgentMapper agentMapper;

    private final AgentScopeRegistry agentRegistry;

    /**
     * 应用启动完成后加载所有已发布的 Agent
     * 使用 ApplicationReadyEvent 确保所有 Bean（包括 ToolRegistry 扫描的工具）都已初始化
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("=== ✅初始化 AgentScope Agent ===");
        try {
            List<AgentScopeAgent> publishedAgents = agentMapper.findPublishedAgents();
            log.info("✅ 发现 {} 个已发布的 Agent", publishedAgents.size());

            for (AgentScopeAgent agent : publishedAgents) {
                log.info("✅注册 Agent: {} (ID: {})", agent.getName(), agent.getId());
                agentRegistry.register(agent);
            }

            log.info("✅ AgentScope 初始化完成，共注册 {} 个 Agent", agentRegistry.getRegisteredCount());
        } catch (Exception e) {
            log.error("❌ AgentScope 初始化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 关闭时清理资源
     */
    @PreDestroy
    public void destroy() {
        log.info("=== 关闭 AgentScope Agent ===");
        agentRegistry.clear();
        log.info("✅ AgentScope 已关闭");
    }

    /**
     * 发布 Agent（从草稿变为已发布）
     */
    public void publish(Long agentId) {
        AgentScopeAgent agent = agentMapper.findById(agentId);
        if (agent == null) {
            log.warn("⚠️ Agent 不存在: {}", agentId);
            return;
        }

        if (!"draft".equals(agent.getStatus())) {
            log.warn("⚠️ Agent 状态不是草稿，无法发布: id={}, status={}", agentId, agent.getStatus());
            return;
        }

        // 更新状态
        agentMapper.updateStatus(agentId, "published");

        // 注册到 AgentScope
        agent.setStatus("published");
        agentRegistry.register(agent);

        log.info("✅ Agent 发布成功: id={}, name={}", agentId, agent.getName());
    }

    /**
     * 下线 Agent（从已发布变为下线）
     */
    public void offline(Long agentId) {
        AgentScopeAgent agent = agentMapper.findById(agentId);
        if (agent == null) {
            log.warn("⚠️ Agent 不存在: {}", agentId);
            return;
        }

        // 从 AgentScope 注销
        agentRegistry.unregister(agentId);

        // 更新状态
        agentMapper.updateStatus(agentId, "offline");

        log.info("✅ Agent 下线成功: id={}, name={}", agentId, agent.getName());
    }

    /**
     * 重新上线 Agent（从下线变为已发布）
     */
    public void republish(Long agentId) {
        AgentScopeAgent agent = agentMapper.findById(agentId);
        if (agent == null) {
            log.warn("⚠️ Agent 不存在: {}", agentId);
            return;
        }

        if (!"offline".equals(agent.getStatus())) {
            log.warn("⚠️ Agent 状态不是下线，无法重新发布: id={}, status={}", agentId, agent.getStatus());
            return;
        }

        // 更新状态
        agentMapper.updateStatus(agentId, "published");

        // 重新注册到 AgentScope
        agent.setStatus("published");
        agentRegistry.register(agent);

        log.info("✅ Agent 重新发布成功: id={}, name={}", agentId, agent.getName());
    }

    /**
     * 重新加载 Agent（用于配置更新后刷新）
     */
    public void reload(Long agentId) {
        AgentScopeAgent agent = agentMapper.findById(agentId);
        if (agent == null) {
            log.warn("⚠️ Agent 不存在: {}", agentId);
            return;
        }

        // 先注销
        agentRegistry.unregister(agentId);

        // 如果是已发布状态，重新注册
        if ("published".equals(agent.getStatus())) {
            agentRegistry.register(agent);
            log.info("✅ Agent 重新加载成功: id={}, name={}", agentId, agent.getName());
        }
    }

    /**
     * 刷新所有已发布的 Agent
     */
    public void refreshAll() {
        log.info("=== 刷新所有 Agent ===");
        agentRegistry.clear();

        List<AgentScopeAgent> publishedAgents = agentMapper.findPublishedAgents();
        for (AgentScopeAgent agent : publishedAgents) {
            agentRegistry.register(agent);
        }

        log.info("✅ Agent 刷新完成，共注册 {} 个 Agent", agentRegistry.getRegisteredCount());
    }

}
