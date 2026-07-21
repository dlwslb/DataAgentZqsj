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
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
// import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;  // 暂未用,先注释
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
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
     * ⭐ LLM 思考 token 上限（enableThinking=true 时生效）
     * <p>百炼官方文档：
     *   - 不设：默认 32768（默认会让 LLM 想很久，20-60s）
     *   - 设小（如 1024）：限制思考 token，强制 LLM 快速决定（推荐 2048-4096）
     *   - 0：禁用 thinking（等于 enableThinking=false）
     * <p>实测 20s 慢是因为没限制思考 budget，LLM 一直在"想"才发 tool_call
     */
    @Value("${agentscope.model.thinking-budget:2048}")
    private int thinkingBudget;

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
    @Bean("dataAgent")
    public HarnessAgent dataAgentHarnessAgent(Toolkit toolkit,
                                              AgentSkillRepository skillRepository,
                                              AgentStateStore agentStateStore,
                                              Path workspacePath) {
        log.info("🚀 初始化 HarnessAgent: model={}, workspace={}", defaultModel, workspacePath);

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("zqsj-data-agent")
                .sysPrompt("""
                            你是企业级数据查询助手。用户问什么，你查什么，结果用 Markdown 表格呈现。
                                                
                            ## 工具调用规则
                            - query_biz_data：查业务表
                            - bizType：bid_winner(中标) / bidding(招标) / purchase_intention(采购意向) / prepose(前期项目) / origin_announcement(今日/昨日标讯)
                            - 用户问"今日/昨日" → bizType 必须用 origin_announcement，datePreset=today/yesterday
                            - province：从用户消息 [Context] 里的 authorizedProvince 取值，用户原文直接传（工具自动归一化）
                            - 用户没指定省份 → authorizedProvince 整体传
                            - authorizedProvince = "未配置" → 不调工具，回复"请联系达仁科技开通"
                            - 用户输入的任何写法都直接传给工具，工具负责校验，你不管格式
                                                
                            ## 输出格式
                            - 查询结果用 Markdown 呈现：先给一个 # 标题（如"# XX省今日中标报告（日期）"），再给表格
                            - 表格后可加 1-3 条要点分析（最高金额、行业分布等），不要长篇大论
                            - 工具返回 error → 只转发 error 原文，不加"根据XX规则/系统拒绝"之类的话
                            - 查不到 → "暂无数据"，不换表重查
                            - 不用反引号，不说"抱歉"，不说"根据XX规则"
                                                
                            ## Skill 路由
                            - 先看 <available_skills> 有没有匹配的 skill
                            - 有就 load_skill_through_path，按 skill 步骤走
                            - 选定 skill 后不跳转其他 skill
                            """)
                // 模型（直接传 Model 实例，强制 enableThinking=true）
                // ⭐ 必须用 Model 实例，不能用字符串 + modelResolver —— 字符串路径走 ModelRegistry，
                //    不会把 model 字段赋值（导致 ReActAgent 内部 model=null 的 NPE）
                // ⭐ enableThinking=true 让 DashScope 把 LLM chain-of-thought 推到 reasoning_content 字段，
                //    框架 emit 成 ThinkingBlockDeltaEvent，前端 filter 直接吞掉，不再混进 TEXT_BLOCK_DELTA
                .model(buildDashScopeModel(defaultModel))
                // Workspace（自动管理 AGENTS.md / skills/ / plans/ / sessions/） 这合并时间4分+关闭先不用记忆，需要在新建个开启的
                .workspace(workspacePath)
                .disableMemoryHooks()
                // Skill 仓库（自动合并 classpath + mysql 等多源）
                .skillRepository(skillRepository)
                // 状态存储（file / redis，由 AgentStateStoreConfig 提供）
                .stateStore(agentStateStore)
                // 上下文压缩（用更激进的配置，专治慢）
                .compaction(CompactionConfig.builder()
                        // —— 触发策略：放宽阈值，少触发 = 少调用摘要 LLM ——
                        .triggerMessages(12)     //，压缩频率
                        .triggerTokens(30_000)   // 叠加 token 阈值，长消息也能及时 或 30k tokens 之前
                        // —— 压缩后只保留最近1轮完整对话 —— 1轮 = 4条
                        .keepMessages(4)         // 摘要窗口更小、更快
                        // —— 摘要专用轻量模型（最关键，单这一项就能把压缩耗时降一个量级）——
                        .model(buildDashScopeSummaryModel())  // 用 qwen-turbo 做 summary
                        // —— 精简摘要 Prompt，结构化 + 控长 ——
                        .summaryPrompt("请用结构化要点总结以下对话，严格按四段输出，每段不超过3条：" +
                                "SESSION INTENT / SUMMARY / ARTIFACTS / NEXT STEPS。\n对话内容：{messages}")
                        // —— 预压缩参数截断：write_file/edit_file 的大入参先裁掉 ——
                        .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                                .maxArgLength(1500)
                                .build())
                        // —— 关掉压缩前的两项副作用，省掉两次额外 LLM/IO ——
                        .flushBeforeCompact(false)  // ← 跳过记忆提取，省一次 LLM 调用 (如不需要记忆提取则关闭)
                        .offloadBeforeCompact(false) // 保留原始消息落盘（纯 IO，很快）
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
        log.info("🔧 构建 DashScopeChatModel: model={}, enableThinking=true（LLM 内部思考 + thinking_budget={} 限 token）",
                shortName, thinkingBudget);
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(shortName)
                .stream(true)
                .enableThinking(true)  // ⭐ 关键：让 LLM 思考，思考内容进 reasoning_content 字段
                .defaultOptions(io.agentscope.core.model.GenerateOptions.builder()
                        .thinkingBudget(thinkingBudget)  // ⭐ 限制思考 token 上限，避免慢
                        .build())
                .build();
    }

    /**
     * ⭐ 摘要专用轻量模型
     * <p>用 qwen-turbo 做会话压缩 summary（比主对话的 qwen-plus 快 5-10 倍）
     * <p>关闭 thinking,摘要不需要思考
     */
    private Model buildDashScopeSummaryModel() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        log.info("🔧 构建摘要专用 DashScopeChatModel: model=qwen-turbo, enableThinking=false（摘要不需要思考）");
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-turbo")
                .stream(false)             // 摘要不需要流式
                .enableThinking(false)     // 摘要不需要思考
                .build();
    }
}
