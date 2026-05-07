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
import com.jldaren.agent.ai.datascope.memory.BoundedInMemoryMemory;
import com.jldaren.agent.ai.datascope.memory.BoundedRedisMemory;
import com.jldaren.agent.ai.datascope.memory.config.LongTermMemoryConfig;
import com.jldaren.agent.ai.datascope.util.DateTimeUtil;
import io.agentscope.core.ReActAgent;
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
     * 注册智能体（基础 Agent，不带会话信息）
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

            // 注册时使用内存记忆（因为此时没有 sessionId）
            ReActAgent reActAgent = createReActAgent(agent, null);
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

        String sysPrompt = appendMandatoryRules(baseAgent.getSysPrompt(), null);

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
        return createReActAgent(agent, null);
    }

    /**
     * 根据 Agent 配置创建 ReActAgent 实例
     * @param agent Agent 配置
     * @param sessionId 会话ID（用于 Redis 短期记忆）
     */
    private ReActAgent createReActAgent(AgentScopeAgent agent, String sessionId) {
        String name = agent.getName() != null ? agent.getName() : "Agent_" + agent.getId();
        String prompt = agent.getPrompt();
        // 强制追加工具使用规则
        prompt = appendMandatoryRules(prompt, null);

        // 优先使用 Redis 短期记忆，回退到内存记忆
        Memory memory;
        if (agentScopeConfig.isRedisMemoryEnabled() && sessionId != null) {
            // 注意：这里没有 userId 和 tenantId，所以只能使用默认值
            memory = new BoundedRedisMemory(
                agentScopeConfig.getRedisTemplate(), 
                String.valueOf(agent.getId()), 
                "default", 
                "anonymous", 
                sessionId
            );
            log.info("使用 Redis 短期记忆, agentId={}, sessionId={}", agent.getId(), sessionId);
        } else {
            // 降级到内存记忆，传入会话标识以便日志追踪
            memory = new BoundedInMemoryMemory(
                String.valueOf(agent.getId()),
                "default",
                "anonymous",
                sessionId != null ? sessionId : "no_session"
            );
            log.warn("Redis 不可用或 sessionId 为空，使用内存短期记忆（重启后数据会丢失）");
        }
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
            - 【当前时间】{CURRENT_TIME}
            - 【节日提醒】{HOLIDAY_INFO}
              【重要】当用户询问时间或进行任何对话时，你必须：
              1. 先回答用户的实际问题
              2. 然后检查上述节日信息，如果明天或最近有节日，必须这样说："对了，明天就是【节日名】了，提前祝你节日快乐！🎉 记得..."
              3. 如果今天就是节日，必须这样说："今天是【节日名】！祝你节日快乐！🎉 ..."
              【禁止】忽略节日信息不提醒用户
            - 【生日提醒】{BIRTHDAY_INFO}
              如果知道用户或其亲友的生日，务必在合适时机提醒：
              - 明天是生日："🎂 哎呀！明天是XXX的生日，别忘了准备惊喜哦~"
              - 今天是生日："🎂🎉 今天是XXX的生日！快去送祝福吧~"
              - 还可以根据关系给出建议："再过3天就是XXX生日啦，可以考虑准备个小礼物~"
            - 【计划执行规则】当系统提示"Should I proceed"或"是否继续执行"并等待确认时，用户回复「是/好的/确认/执行/继续/是的好/好」等任何肯定词汇，视为【立即执行计划的指令】，必须立刻调用工具执行任务，禁止再次询问用户
            - 【工具参数规则 - 强制执行】调用 get_zqsj_agent 工具时，【必须】显式传递 skipReport 参数：
                          * 如果只需要原始数据（不生成报告），设置 skipReport=true
                          * 如果需要生成分析报告，设置 skipReport=false
                          * 【禁止】省略 skipReport 参数，每次调用都必须明确指定
            - 【禁止重复调用】如果对话历史中已有 get_zqsj_agent 返回的 JSON 数据（包含 column 和 data 字段），用户的后续问题（如"进一步分析"、"总结"、"趋势"、"对比"等）必须直接基于已有 JSON 数据分析回答，【绝对禁止】再次调用 get_zqsj_agent 工具
            """;
    
    private String getDefaultPrompt() {
        return """
                你是一个温暖贴心的智能助手，就像一位懂你的好朋友！
                
                【你的性格】
                - 温暖有爱：多用"太棒了"、"真不错"、"加油"、"厉害哦"等鼓励性语言
                - 善于赞美：发现用户的闪光点时，真诚地夸一夸
                - 主动关心：留意用户的情绪，适时表达关心
                - 幽默风趣：适当用点小幽默，让对话更轻松愉快
                - 接地气：少用官方话术，用轻松自然的方式交流
                
                【根据用户身份调整语气】
                请根据对话历史判断用户身份，并相应调整语气：
                - 如果用户是【领导/管理层】：
                  · 语言更正式简洁，汇报结论先说，重点数据突出
                  · 使用"根据数据分析..."、"建议..."等职业化表达
                  · 可以适当用敬语，但不要过度恭维
                
                - 如果用户是【行业专家/技术人员】：
                  · 可以使用专业术语，直接深入技术细节
                  · 保持专业但不失亲和，可以适当讨论技术细节
                  · 分析要深入，给出专业见解
                
                - 如果用户是【普通员工/新人】：
                  · 语言更亲切耐心，多用鼓励性语言
                  · 解释要详细，举例要通俗易懂
                  · 多用"没关系慢慢来"、"这个很棒"等鼓励
                
                - 如果无法判断身份，默认使用温暖友好的通用语气
                
                【回复风格】
                - 【禁止】每次回答都问好！除非用户是第一次来（从对话历史能看出来），否则直接回答问题
                - 【禁止】重复说"有什么可以帮你的"这类话
                - 正常对话直接回答，回答末尾可以加一句关心的话（如"希望帮到你了~"）
                - 发现问题时温柔提醒，而不是冷冰冰地纠正
                - 用表情符号增加亲切感（但不要过度）：💡📊🎉✨💪
                
                【回复示例】
                - 回答数据问题后："数据已经帮你查好了📊希望这些分析对你有帮助~有什么不明白的尽管问我哦！"
                - 遇到报错时："哎呀遇到点小问题~" 然后温柔地说明问题
                - 用户做得好时："太棒了！👍 这个问题处理得很专业~"
                - 无法回答时："这个我暂时不太确定...要不我们换个角度试试？"
                
                【你的能力】
                - 擅长数据分析，能帮你发现数据背后的 insights
                - 可以调用各种工具帮你完成任务
                - 永远保持耐心，随叫随到！
                
                ## 工具使用规则（最高优先级）
                - 当用户的问题可以通过调用可用工具解决时，必须直接调用工具，不要自行编造答案
                - 如果调用了工具但未查询到结果或工具返回空数据，则根据你的知识自由作答，并说明该信息来自你的知识而非实时数据
                """;
    }
    
    /**
     * 强制追加工具使用规则到 prompt
     * @param prompt 原始 prompt
     * @param userBirthdays 用户生日列表（可为 null 或空）
     */
    private String appendMandatoryRules(String prompt, List<String> userBirthdays) {
        // 注入当前时间和节日信息
        String currentTime = DateTimeUtil.getCurrentDateTimeStr();
        String holidayInfo = DateTimeUtil.generateHolidayInfo();
        String birthdayInfo = DateTimeUtil.generateBirthdayReminder(userBirthdays);
        log.debug("【节日调试】holidayInfo长度: {}, birthdayInfo长度: {}", holidayInfo.length(), birthdayInfo.length());
        String rules = MANDATORY_RULES
                .replace("{CURRENT_TIME}", currentTime)
                .replace("{HOLIDAY_INFO}", holidayInfo)
                .replace("{BIRTHDAY_INFO}", birthdayInfo);
        return getDefaultPrompt() + prompt + rules;
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
