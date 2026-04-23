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
package com.jldaren.agent.ai.datascope.registry;

import com.jldaren.agent.ai.datascope.config.AgentScopeConfig;
import com.jldaren.agent.ai.datascope.entity.AgentScopeAgent;
import com.jldaren.agent.ai.datascope.hook.DirectResponseHook;
import com.jldaren.agent.ai.datascope.memory.config.LongTermMemoryConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;

import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AgentScope Agent 注册表
 * 负责动态注册和注销 Agent 实例
 */
@Slf4j
@Component
public class AgentScopeRegistry {

    private final Map<Long, ReActAgent> agentMap = new ConcurrentHashMap<>();

    /**
     * 缓存带长期记忆的 Agent: key = "agentId_userId_tenantId"
     * 带 TTL 过期机制：长期不活跃的 Agent 自动淘汰释放内存
     */
    private final Map<String, CachedAgent> longTermMemoryAgentCache = new ConcurrentHashMap<>();

    private final AgentScopeConfig agentScopeConfig;
    private final LongTermMemoryConfig memoryConfig;
    private final DirectResponseHook directResponseHook;

    /** 缓存条目：记录最后访问时间用于 TTL 淘汰 */
    private static class CachedAgent {
        final ReActAgent agent;
        volatile long lastAccessTime;

        CachedAgent(ReActAgent agent) {
            this.agent = agent;
            this.lastAccessTime = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    public AgentScopeRegistry(AgentScopeConfig agentScopeConfig,
                              LongTermMemoryConfig memoryConfig,
                              DirectResponseHook directResponseHook) {
        this.agentScopeConfig = agentScopeConfig;
        this.memoryConfig = memoryConfig;
        this.directResponseHook = directResponseHook;
    }

    /**
     * 注册智能体
     */
    public void register(AgentScopeAgent agent) {
        if (agent == null || agent.getId() == null) {
            log.warn("注册 Agent 失败: agent 或 agent.id 为空");
            return;
        }

        try {
            if (agentMap.containsKey(agent.getId())) {
                log.info("Agent {} 已存在，先注销", agent.getId());
                unregister(agent.getId());
            }

            ReActAgent reActAgent = createReActAgent(agent);
            agentMap.put(agent.getId(), reActAgent);

            log.info("Agent 注册成功: id={}, name={}", agent.getId(), agent.getName());
        } catch (Exception e) {
            log.error("Agent 注册失败: id={}, error={}", agent.getId(), e.getMessage(), e);
        }
    }

    /**
     * 注销智能体
     */
    public void unregister(Long agentId) {
        ReActAgent agent = agentMap.remove(agentId);
        if (agent != null) {
            longTermMemoryAgentCache.entrySet().removeIf(entry -> entry.getKey().startsWith(agentId + "_"));
            log.info("Agent 注销成功: id={}", agentId);
        } else {
            log.warn("Agent 不存在或已注销: id={}", agentId);
        }
    }

    /**
     * 获取智能体
     */
    public ReActAgent getAgent(Long agentId) {
        return agentMap.get(agentId);
    }

    /**
     * 获取带长期记忆的智能体 (用于聊天场景)
     * 带 TTL 缓存：超过 agentCacheTtlMinutes 不活跃的 Agent 自动淘汰
     */
    public synchronized ReActAgent getAgentWithLongTermMemory(Long agentId, String userId, String sessionId, String tenantId) {
        String cacheKey = agentId + "_" + (userId != null ? userId : "anonymous")
                         + "_" + (sessionId != null ? sessionId : "no_session")
                         + "_" + (tenantId != null ? tenantId : "default");

        // 优先从缓存获取
        CachedAgent cached = longTermMemoryAgentCache.get(cacheKey);
        if (cached != null) {
            cached.touch(); // 刷新最后访问时间
            log.debug("Using cached Agent with long-term memory: key={}", cacheKey);
            return cached.agent;
        }

        ReActAgent baseAgent = agentMap.get(agentId);
        if (baseAgent == null) {
            log.warn("Agent not found: id={}", agentId);
            return null;
        }

        String sysPrompt = appendMandatoryRules(baseAgent.getSysPrompt());

        ReActAgent agentWithMemory = agentScopeConfig.createAgentWithLongTermMemory(
                baseAgent.getName(),
                userId != null ? userId : "anonymous",
                sessionId,
                tenantId != null ? tenantId : "default",
                sysPrompt
        );

        if (agentWithMemory != null) {
            longTermMemoryAgentCache.put(cacheKey, new CachedAgent(agentWithMemory));
            log.info("Created and cached Agent with long-term memory: key={}", cacheKey);
        }

        return agentWithMemory;
    }

    /**
     * 定时淘汰 TTL 过期的缓存 Agent
     * 每分钟检查一次，超过 agentCacheTtlMinutes 不活跃的 Agent 被淘汰
     */
    @Scheduled(fixedRate = 60_000)
    public void evictExpiredCache() {
        if (longTermMemoryAgentCache.isEmpty()) return;

        long ttlMillis = memoryConfig != null
                ? TimeUnit.MINUTES.toMillis(memoryConfig.getAgentCacheTtlMinutes())
                : TimeUnit.MINUTES.toMillis(30); // 默认 30 分钟
        long now = System.currentTimeMillis();

        longTermMemoryAgentCache.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue().lastAccessTime) > ttlMillis;
            if (expired) {
                log.info("TTL evicting cached Agent: key={}, idleMinutes={}",
                        entry.getKey(),
                        (now - entry.getValue().lastAccessTime) / 60_000);
            }
            return expired;
        });
    }

    /**
     * 检查智能体是否已注册
     */
    public boolean isRegistered(Long agentId) {
        return agentMap.containsKey(agentId);
    }

    /**
     * 获取已注册智能体数量
     */
    public int getRegisteredCount() {
        return agentMap.size();
    }

    /**
     * 根据 Agent 配置创建 ReActAgent 实例
     */
    private ReActAgent createReActAgent(AgentScopeAgent agent) {
        String name = agent.getName() != null ? agent.getName() : "Agent_" + agent.getId();
        String prompt = agent.getPrompt();
        // 强制追加工具使用规则
        prompt = appendMandatoryRules(prompt);

        Memory memory = new InMemoryMemory();
        PlanNotebook planNotebook = PlanNotebook.builder()
               .storage(agentScopeConfig.getPlanStorage())
               .maxSubtasks(5)
               .needUserConfirm(false)
               .build();

        // 按 Agent 配置的工具名称构建 Toolkit
        Toolkit toolkit = agentScopeConfig.getToolkit(agent.getToolNames());
        log.info("🔧 Created ReActAgent: name={}, toolNames config='{}', toolkit tools={}", 
                name, agent.getToolNames(), toolkit.getToolNames());

        ReActAgent.Builder builder = ReActAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(agentScopeConfig.getChatModel())
                .memory(memory)
                .toolkit(toolkit)
                .planNotebook(planNotebook)
                .maxIters(5)  // DirectResponseHook 会在工具返回后直接终止循环
                .checkRunning(true)
                .hooks(List.of(directResponseHook));

        log.info("Created ReActAgent: name={}, tools={}", name,
                agent.getToolNames() != null ? agent.getToolNames() : "ALL");

        return builder.build();
    }

    private static final String MANDATORY_RULES = """
                
                ## 强制规则（最高优先级）
                - 【计划执行规则】当系统提示"Should I proceed"或"是否继续执行"并等待确认时，用户回复「是/好的/确认/执行/继续/是的好/好」等任何肯定词汇，视为【立即执行计划的指令】，必须立刻调用工具执行任务，禁止再次询问用户
                """;
    
    private String getDefaultPrompt() {
        return """
                你是一个专业的企业智能助手。
                - 回答简洁专业，不确定时主动澄清
                - 善于分析数据，提供有价值的建议
                
                ## 工具使用规则（最高优先级）
                - 当用户的问题可以通过调用可用工具解决时，必须直接调用工具，不要自行编造答案
                - 如果调用了工具但未查询到结果或工具返回空数据，则根据你的知识自由作答，并说明该信息来自你的知识而非实时数据
                """;
    }
    
    /**
     * 强制追加工具使用规则到 prompt
     */
    private String appendMandatoryRules(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return getDefaultPrompt();
        }
        // 如果 prompt 中已包含 mandatory rules，跳过
        if (prompt.contains("get_zqsj")) {
            return prompt;
        }
        return prompt + MANDATORY_RULES;
    }

    public void clear() {
        agentMap.clear();
        longTermMemoryAgentCache.clear();
        log.info("AgentScopeRegistry 已清空");
    }

    public Map<Long, ReActAgent> getAllAgents() {
        return new ConcurrentHashMap<>(agentMap);
    }
}
