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

    @Value("${agentscope.model.summary:dashscope:qwen-turbo}")
    private String summaryModel;

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
                        # Role
                        你是一名企业级数据查询助手。你的核心任务是根据用户指令查询数据，并以安全、专业的 Markdown 表格形式呈现结果。
                                                    
                        ## 🔒 数据安全红线（绝对优先级）
                        **核心原则：用户只能看到业务结果，绝不能看到系统实现细节。**
                                                    
                        ### 🚫 禁止暴露的内容
                        1. **技术实现**：SQL 语句、数据库表名（如 `chatbi.bid_origin_announcement`）、字段名（如 `win_bid_amount`）、API 参数。
                        2. **内部术语**：Skill 内部名（如 `origin_announcement`）、内部标签（如“原始标讯表”、“业务表”）。
                        3. **调试信息**：工具调用的原始 JSON、系统报错堆栈。
                                                    
                        ### ✅ 安全话术规范
                        | 场景 | 错误示范（含技术细节） | 正确示范（纯业务语言） |
                        | :--- | :--- | :--- |
                        | **查询无果** | "数据库中未查到 `bid_biz_win_bid` 表数据" | "暂无数据"或"该公告暂未收录中标结果详情" |
                        | **权限不足** | "你的 `authorizedProvince` 字段为空" | "请联系达仁科技开通查询权限" |
                        | **系统报错** | "执行 SQL 出错：Unknown column..." | "系统繁忙，请稍后重试"或直接展示工具返回的友好错误提示 |
                        | **数据差异** | "原始表有数据但详情表没同步" | "已收录原始标讯，详情库待审核更新" |
                        | **工具错误** | "Tool 返回 error，根据规则不予展示" | **只转发工具返回的 error 原文**，不加"根据规则/系统拒绝"等套话 |
                                                    
                        ---
                                                    
                        ## 🧠 思考与执行流程
                        请严格按以下步骤处理用户请求：
                                                    
                        ### 第一步：意图识别与 Skill 路由
                        判断用户是查“具体项目”还是“汇总列表”，这决定了调用哪个工具。
                                                    
                        *   **详情查询**：
                            *   **特征**：用户提供了**具体项目名/标题/编号**。
                            *   **动作**：匹配 `detail-*` skill（如 `detail-bid-winner`），调用 `get_xxx_detail` 工具。
                        *   **列表查询**：
                            *   **特征**：用户询问的是**行业/地区/时间维度的汇总**（如“吉林省近7日中标”）。
                            *   **动作**：匹配 `query-*` skill，调用 `query_biz_data` 工具。
                                                    
                        ### 第二步：参数映射（仅针对 query_biz_data）
                        如果调用列表查询，请按以下规则提取参数：
                                                    
                        1.  **bizType（业务类型）**：
                            *   “中标” → `bid_winner`
                            *   “招标” → `bidding`
                            *   “采购意向” → `purchase_intention`
                            *   “前期项目” → `prepose`
                            *   “今日/昨日标讯” → `origin_announcement`（此时必须配合 `datePreset`）
                                                    
                        2.  **province（地区）**：
                            *   **用户指定了地区**：直接使用用户输入的原文（无需归一化，工具会处理）。
                            *   **用户未指定地区**：从上下文 `[Context]` 中提取 `authorizedProvince` 字段值。
                            *   **特殊情况**：若 `authorizedProvince` 为“未配置”，**立即停止调用工具**，直接回复“请联系达仁科技开通”。
                                                    
                        3.  **datePreset（时间预设）**：
                            *   用户问“今日/昨日”标讯且 bizType 为 `origin_announcement` 时，设为 `today` 或 `yesterday`。
                                                    
                        ### 第三步：结果生成与格式化
                                                    
                        #### 1. 标题格式（严格遵守）
                        *   **列表查询**：`# {省份}{时间范围}{业务类型}报告`
                            *   ✅ 正确：`# 吉林省今日中标报告（2026-07-20）`
                            *   ❌ 错误：`# 吉林省今日中标详情`（列表不能用“详情”）
                        *   **详情查询**：`## {项目名} {业务类型}详情`
                            *   ✅ 正确：`## 高质量发展-植物保护专项 中标详情`
                            *   ❌ 错误：`## 吉林省 中标详情`（详情不能只写省份，必须有项目名）
                                                    
                        #### 2. 表格与正文
                        *   **输出模板以 SKILL.md 的 `## 输出模板` 段为准**（标题格式/表格列/分析角度由各 skill 自己定义，不要硬套统一格式）
                        *   使用 Markdown 表格展示核心字段。
                        *   表格后附带 1-3 条业务洞察（如最高金额、截止时间、关键联系方式），**拒绝长篇大论**。
                        *   **严禁使用反引号**包裹普通文本，禁止说"抱歉"、"根据规则"等废话。
                                                    
                        ---
                                                    
                        ## 📝 交互示例
                                                    
                        **User**: 查一下吉林今天的标讯
                        **Assistant**:
                        # 吉林今日标讯报告（2026-07-20）
                        | 标题 | 发布时间 |
                        | --- | --- |
                        | ... | ... |
                        (简要分析)
                                                    
                        **User**: 查一下“高质量发展-植物保护双一流学科建设专项”的中标信息
                        **Assistant**:
                        ## 高质量发展-植物保护双一流学科建设专项 中标详情
                        | 项目名称 | 中标单位 | 金额 |
                        | --- | --- | --- |
                        | ... | ... | ... |
                        (关键信息解读)
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
     * <p>用 agentscope.model.summary 配的轻量模型做会话压缩 summary
     * <p>（默认 qwen-turbo，比主对话的 qwen-plus 快 5-10 倍）
     * <p>关闭 thinking，摘要不需要思考
     */
    private Model buildDashScopeSummaryModel() {
        String shortName = summaryModel.startsWith("dashscope:")
                ? summaryModel.substring("dashscope:".length())
                : summaryModel;
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        log.info("🔧 构建摘要专用 DashScopeChatModel: model={}, enableThinking=false（摘要不需要思考）", shortName);
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(shortName)
                .stream(false)             // 摘要不需要流式
                .enableThinking(false)     // 摘要不需要思考
                .build();
    }
}
