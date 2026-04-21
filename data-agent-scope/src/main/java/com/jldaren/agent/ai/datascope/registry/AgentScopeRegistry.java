package com.jldaren.agent.ai.datascope.registry;

import com.jldaren.agent.ai.datascope.config.AgentScopeConfig;
import com.jldaren.agent.ai.datascope.entity.AgentScopeAgent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.plan.PlanNotebook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentScope Agent 注册表
 * 负责动态注册和注销 Agent 实例
 */
@Slf4j
@Component
public class AgentScopeRegistry {

    private final Map<Long, ReActAgent> agentMap = new ConcurrentHashMap<>();

    private final AgentScopeConfig agentScopeConfig;

    public AgentScopeRegistry(AgentScopeConfig agentScopeConfig) {
        this.agentScopeConfig = agentScopeConfig;
    }

    /**
     * 注册智能体
     */
    public void register(AgentScopeAgent agent) {
        if (agent == null || agent.getId() == null) {
            log.warn("✅注册 Agent 失败: agent 或 agent.id 为空");
            return;
        }

        try {
            // 如果已存在，先注销
            if (agentMap.containsKey(agent.getId())) {
                log.info("Agent {} 已存在，先注销", agent.getId());
                unregister(agent.getId());
            }

            // 创建新的 Agent 实例
            ReActAgent reActAgent = createReActAgent(agent);
            agentMap.put(agent.getId(), reActAgent);

            log.info("✅ Agent 注册成功: id={}, name={}", agent.getId(), agent.getName());
        } catch (Exception e) {
            log.error("❌ Agent 注册失败: id={}, error={}", agent.getId(), e.getMessage(), e);
        }
    }

    /**
     * 注销智能体
     */
    public void unregister(Long agentId) {
        ReActAgent agent = agentMap.remove(agentId);
        if (agent != null) {
            log.info("✅ Agent 注销成功: id={}", agentId);
        } else {
            log.warn("⚠️ Agent 不存在或已注销: id={}", agentId);
        }
    }

    /**
     * 获取智能体
     */
    public ReActAgent getAgent(Long agentId) {
        return agentMap.get(agentId);
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
        if (prompt == null || prompt.isBlank()) {
            prompt = getDefaultPrompt();
        }

        Memory memory = new InMemoryMemory();// 短期记忆（当前会话）
        PlanNotebook planNotebook = PlanNotebook.builder()
                .storage(agentScopeConfig.getPlanStorage())
                .maxSubtasks(15)// 限制最大子任务数，防止无限递归
                .build();

        return ReActAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(agentScopeConfig.getChatModel())
                .memory(memory)
                .toolkit(agentScopeConfig.getToolkit())
                .planNotebook(planNotebook)
                .maxIters(10)
                .checkRunning(true)
                .build();
    }

    /**
     * 获取默认 Prompt
     */
    private String getDefaultPrompt() {
        return """
                你是一个专业的企业智能助手。
                - 回答简洁专业，不确定时主动澄清
                - 善于分析数据，提供有价值的建议
                """;
    }

    /**
     * 清空所有注册
     */
    public void clear() {
        agentMap.clear();
        log.info("🗑️ AgentScopeRegistry 已清空");
    }

    /**
     * 获取所有已注册的 Agent ID
     */
    public Map<Long, ReActAgent> getAllAgents() {
        return new ConcurrentHashMap<>(agentMap);
    }

}
