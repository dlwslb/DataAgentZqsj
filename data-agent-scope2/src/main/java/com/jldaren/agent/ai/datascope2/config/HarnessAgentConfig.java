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
package com.jldaren.agent.ai.datascope2.config;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * AgentScope 2.0 HarnessAgent 配置
 *
 * <p>关键变化（对比 1.0）：
 * <ul>
 *   <li>1.0: ReActAgent + 自实现 Hook + 自实现 Memory/Plan</li>
 *   <li>2.0: HarnessAgent + Middleware + 内置 Workspace/Memory/Plan Mode</li>
 *   <li>1.0: Tool 是 @Tool 注解的 Java 方法</li>
 *   <li>2.0: Tool 仍是 Java 方法，但 Skill（Markdown 模板）告诉 agent 怎么组合</li>
 * </ul>
 */
@Slf4j
@Configuration
public class HarnessAgentConfig {

    @Value("${agentscope.model.default:dashscope:qwen-plus}")
    private String defaultModel;

    @Value("${agentscope.workspace.base-path}")
    private String workspaceBasePath;

    @Value("${agentscope.state-store.type:file}")
    private String stateStoreType;

    @Value("${agentscope.state-store.base-path}")
    private String stateStoreBasePath;

    @Value("${agentscope.skill.type:classpath}")
    private String skillRepoType;

    @Value("${agentscope.skill.classpath-paths:skills}")
    private String skillClasspathPaths;

    @Value("${agentscope.skill.mysql.database:data_agent_v2}")
    private String skillMysqlDatabase;

    @Value("${agentscope.skill.mysql.table:agentscope_skill}")
    private String skillMysqlTable;

    @Value("${agentscope.skill.mysql.create-if-not-exist:true}")
    private boolean skillMysqlCreateIfNotExist;

    @Value("${agentscope.skill.mysql.writeable:true}")
    private boolean skillMysqlWriteable;

    @Value("${agentscope.skill.project-global-dir:}")
    private String projectGlobalSkillsDir;

    @Value("${agentscope.skill.enable-self-learning:false}")
    private boolean enableSelfLearning;

    /**
     * Workspace 路径（每个 agentName 一份）
     */
    @Bean
    public Path workspacePath() {
        Path path = Paths.get(workspaceBasePath);
        log.info("📂 AgentScope 2.0 workspace 路径: {}", path.toAbsolutePath());
        return path;
    }

    /**
     * Skill 仓库
     * <p>dev: ClasspathSkillRepository（resources/skills/）
     * <p>prod: MysqlSkillRepository（在线编辑、版本管理）
     */
    @Bean
    public AgentSkillRepository skillRepository(@Qualifier("skillDataSource") DataSource dataSource) {
        if ("mysql".equalsIgnoreCase(skillRepoType)) {
            log.info("📚 使用 MySQL Skill 仓库: db={}, table={}", skillMysqlDatabase, skillMysqlTable);
            return MysqlSkillRepository.builder(dataSource)
                    .databaseName(skillMysqlDatabase)
                    .skillsTableName(skillMysqlTable)
                    .createIfNotExist(skillMysqlCreateIfNotExist)
                    .writeable(skillMysqlWriteable)
                    .build();
        }
        // classpath 模式：单路径，CommaSeparated 字段取第一个
        String[] paths = skillClasspathPaths.split(",");
        log.info("📚 使用 Classpath Skill 仓库: {}", Arrays.toString(paths));
        try {
            return new ClasspathSkillRepository(paths[0].trim());
        } catch (java.io.IOException e) {
            throw new RuntimeException("初始化 ClasspathSkillRepository 失败: " + paths[0].trim(), e);
        }
    }

    /**
     * 核心：HarnessAgent
     *
     * <p>对比 1.0 的 ReActAgent.builder()：
     * <pre>
     * // 1.0
     * ReActAgent agent = ReActAgent.builder()
     *     .name("data-agent")
     *     .model(chatModel)
     *     .memory(shortTermMemory)
     *     .longTermMemory(longTermMemory)
     *     .longTermMemoryMode(LongTermMemoryMode.BOTH)
     *     .toolkit(toolkit)
     *     .hooks(List.of(directResponseHook, tokenUsageHook))
     *     .build();
     *
     * // 2.0
     * HarnessAgent agent = HarnessAgent.builder()
     *     .name("data-agent")
     *     .model("dashscope:qwen-plus")          // 字符串由 ModelRegistry 解析
     *     .workspace(workspacePath)               // 自动管理 AGENTS.md / skills/ / plans/
     *     .skillRepository(skillRepository)       // 自动注入到 system prompt
     *     .compaction(CompactionConfig...)        // 自动上下文压缩
     *     .stateStore(agentStateStore)            // 自动会话恢复
     *     .toolkit(toolkit)                       // 原子能力
     *     .build();
     * </pre>
     */
    @Bean
    public HarnessAgent dataAgentHarnessAgent(Toolkit toolkit,
                                              AgentSkillRepository skillRepository,
                                              AgentStateStore agentStateStore,
                                              Path workspacePath) {
        log.info("🚀 初始化 HarnessAgent: model={}, workspace={}", defaultModel, workspacePath);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("data-agent")
                .sysPrompt("""
                        你是一个企业级数据查询助手，专注于帮业务人员从数据库里查数据、出报表、做趋势分析。

                        ## 当前用户上下文（每次请求自动注入）
                        - userId / tenantId：会作为系统提示附在用户消息末尾
                        - **tenantId 由框架自动注入到 query_biz_data 等业务 Tool**，**你调用时不要传 tenantId 字段**

                        ## 工作原则
                        1. **看清问题再动手**：用户的问题可能模糊，先看 <available_skills> 里有没有匹配的 skill；
                           有就加载 skill 详情（load_skill_through_path），按 skill 的步骤走。
                        2. **拆解 + 工具**：复杂任务拆成子步骤，每步用对应工具。
                        3. **结果结构化**：用 Markdown 表格 / 列表 / 数字呈现，不要纯叙述。
                        4. **数据诚实**：查不到就说查不到，不编数据。
                        5. **必要时澄清**：表名/字段名不确定时反问用户，别瞎猜。
                        6. **🔑 严格按 skill 路由**：选定的 skill 查到了什么就汇报什么。
                           - 用了 query-daily-announcement（今日/昨日），返回 0 条就告诉用户"暂无数据"，**不要自动跳到 query-bid-winner 等老 skill 重查**
                           - 工具返回的 message 字段是权威信息："查询成功"就是成功，不要自己脑补"参数验证失败"

                        ## 工具说明
                        - **tenantId 由框架自动注入**（从 [System Context] 取），你**不要在调用参数里传 tenantId**。
                        - query_biz_data: 查业务表。
                          - bizType 取值：bid_winner(中标) / bidding(招标) / purchase_intention(采购意向) / prepose(前期项目) / origin_announcement(原始每日标讯)。
                          - 用户问"今日/昨日"标讯时，**bizType 必须用 origin_announcement**，datePreset=today 或 yesterday，channel 可选（招标/中标/采购/前置商机）。
                        - run_python: 跑 Python 做统计、画图、算趋势
                        - format_report: 把数据格式化成 Markdown 报告
                        """)
                // 模型（直接传 Model 实例，强制 enableThinking=true）
                // ⭐ 必须用 Model 实例，不能用字符串 + modelResolver —— 字符串路径走 ModelRegistry，
                //    不会把 model 字段赋值（导致 ReActAgent 内部 model=null 的 NPE）
                // ⭐ enableThinking=true 让 DashScope 把 LLM chain-of-thought 推到 reasoning_content 字段，
                //    框架 emit 成 ThinkingBlockDeltaEvent，前端 filter 直接吞掉，不再混进 TEXT_BLOCK_DELTA
                .model(buildDashScopeModel(defaultModel))
                // Workspace（自动管理 AGENTS.md / skills/ / plans/ / sessions/）
                .workspace(workspacePath)
                // Skill 仓库（自动合并 classpath + mysql 等多源）
                .skillRepository(skillRepository)
                // 状态存储（file / redis，由 AgentStateStoreConfig 提供）
                .stateStore(agentStateStore)
                // 上下文压缩（30 轮触发，保留 10 轮）
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                // 原子工具
                .toolkit(toolkit);

        // 可选：项目全局 skill 目录
        if (projectGlobalSkillsDir != null && !projectGlobalSkillsDir.isBlank()) {
            builder.projectGlobalSkillsDir(Paths.get(projectGlobalSkillsDir));
        }

        HarnessAgent agent = builder.build();
        log.info("✅ HarnessAgent 初始化完成, stateStore={}", agentStateStore.getClass().getSimpleName());
        return agent;
    }

    /**
     * 构建 DashScopeChatModel 实例，强制启用 thinking mode
     *
     * <p>百炼官方文档（https://help.aliyun.com/zh/model-studio/qwq）：
     * <pre>
     * 通过 enable_thinking 参数控制思考开关：
     * - 设为 true：模型先思考再回复（思考内容进 reasoning_content 字段，框架 emit 为 ThinkingBlockDeltaEvent）
     * - 设为 false：模型直接回复（不思考）
     * </pre>
     *
     * <p>业务诉求：**让 LLM 思考（答案质量好），但思考内容不显示给用户**。
     * <p>做法：enableThinking=true 让 LLM 内部思考 → 框架 emit ThinkingBlockDeltaEvent → 后端 filter 丢
     *
     * <p>为什么必须直接传 Model 实例：
     * <ul>
     *   <li>.model(String) 走 ModelRegistry.resolve()，解析过程是黑盒</li>
     *   <li>.modelResolver(Function) 不会让 ReActAgent.model 字段被赋值，build() 时报 NPE</li>
     *   <li>只有 .model(Model) 才能保证 model 字段有值</li>
     * </ul>
     */
    private Model buildDashScopeModel(String modelName) {
        String shortName = modelName.startsWith("dashscope:")
                ? modelName.substring("dashscope:".length())
                : modelName;
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("⚠️ DASHSCOPE_API_KEY 未配置，LLM 调用会失败");
        }
        log.info("🔧 构建 DashScopeChatModel: model={}, enableThinking=true（LLM 内部思考，思考内容后端 filter 屏蔽）", shortName);
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(shortName)
                .stream(true)
                .enableThinking(true)  // ⭐ 关键：让 LLM 思考，思考内容进 reasoning_content 字段
                .build();
    }
}
