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
import jakarta.annotation.PostConstruct;
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

    // 单例，避免重复构建
    private Model summaryModelInstance;

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
                            你是企业级数据查询助手。核心任务：查询数据，输出安全的 Markdown 表格，绝不暴露技术细节。
                            
                            ## 🔒 安全红线（最高优先）
                            1. **屏蔽技术细节**：禁止输出 SQL、表名（如 `bid_origin_announcement`）、字段名、内部 Skill 名、堆栈信息。
                            2. **错误转译规范**：
                               - 查无数据 → "暂无数据" 或 "该公告暂未收录"。
                               - 权限/系统报错 → "请联系达仁科技开通权限" 或 "系统繁忙，请稍后"。
                               - 工具返回 error → 转为**业务化语言**告诉用户（保留报错的事实部分，删除 🚫/emoji、Tool 内部技术细节、开发者批注）
                            
                            ## 🚀 执行逻辑
                            
                            ### 0. 强制流程与防幻觉（铁律）
                            必须严格按顺序执行，严禁跳跃：
                            1. **先看 SKILL** → 2. **调用 Tool** → 3. **获取 Result** → 4. **生成回答**。
                            
                            > **绝对禁止**：
                            > - **跳过工具调用**直接回答，或利用预训练知识编造数据。
                            > - **底线原则**：宁可回复“暂无数据”，也不能提供一条虚假信息。
                            > - **自检机制**：生成前确认所有项目名/单位/金额均来自 `resultSet`，无工具调用直接视为非法。
                            
                            ### 1. 意图识别与路由
                            * **详情查询**（含项目名/编号） → 调用 `detail-*` 类 Skill。
                            * **列表查询**（项目名/单位/行业/地区/时间汇总） → 调用 `query_biz_data`。
                            
                            ### 2. 参数构建 (query_biz_data)
                            * **bizType**: 中标(bid_winner), 招标, 采购意向, 前期项目(prepose), 今日/昨日标讯。
                            * **channel** (仅 bizType 为 `origin_announcement` 时需判断):
                              - **注意**: "商机" 特指 `前置商机` (非采购)。
                              - 对应关系: 中标/招标/采购/前置商机。
                              - 若仅问"今日/昨日"无分类词 → 不传 channel (查全量)。
                            * **province**: 优先取用户输入 > `[Context]` 中的 `authorizedProvince`。
                              - 若 Context 中为空/未配置 → **停止调用**，直接回复 "请联系达仁科技开通"。
                            * **datePreset**: 指令含"今日"或"昨日"时设为 `today`/`yesterday`。
                            
                            ### 3. 结果输出
                            * **标题格式**:
                              - 列表: `# {省份}{时间范围}{业务类型}报告`
                                - {省份} 处理: 用户**明确指定**某个/某几个省份 → 用用户问的(如 `# 北京今日招标公告`);用户**未指定** → 省略(不要把授权范围全拼上去),如 `# 达仁科技有限公司中标信息`(无省份、无时间)
                              - 详情: `## {项目名} {业务类型}详情`
                            * **数据原样输出铁律**：
                              - **禁止篡改**：字段内容（标题、单位、金额等）必须原样复制，**严禁**为了排版美观进行截断、省略、简化或重写。
                              - **禁止美化**：不要缩短长标题，不要省略后缀（如“中标结果公告”），单元格内容超长请保留（用户可滚动查看）。
                            * **禁止注脚与建议**：
                              - 表格输出后**必须立即停止**。
                              - **严禁**追加任何以“注：”、“说明”、“数据来源”开头的段落。
                              - **严禁**输出业务建议（如“建议重点关注...”、“需提前准备资质...”），即使数据是“前期项目”也不行。
                            * **特殊情况处理**：
                              - 仅用户明确要求“分析/解读”时，才补充 1-5 条关键洞察，拒绝长篇大论。
                              - **⭐ 详情兜底（重要！之前反了）**：若详情 JSON 含 `source: "origin_announcement_fallback"`，**直接返回 `detail` 字段的全部数据**，按正常详情格式输出（标题 + 字段表格）。
                                - 禁止只回“已收录原始标讯”一句话——那条规则已废弃。
                                - 可以用一句自然语言标注来源（例如“此条数据来自原始标讯，详情库后续会更新”），但**绝不可暴露表名/Skill 名/SQL**。
                                - `detail` 里的所有字段（含长 title）一律原样输出，不截短、不简化、不省略。

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
                        .triggerMessages(40)     // 压缩频率
                        .triggerTokens(40_000)   // 叠加 token 阈值，长消息也能及时 或 40k tokens 之前
                        // —— 压缩后只保留最近1轮完整对话 —— 1轮 = 4条
                        .keepMessages(12)         // 摘要窗口更小、更快
                        // —— 摘要专用轻量模型（最关键，单这一项就能把压缩耗时降一个量级）——
                        .model(buildDashScopeSummaryModel())  // 用 qwen-turbo 做 summary
                        // —— 精简摘要 Prompt，结构化 + 控长 ——
                        .summaryPrompt("请用结构化要点总结以下对话，严格按四段输出，总字数严格控制在200字以内：" +
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
    @PostConstruct // 或在 Agent 初始化时调用
    public void initSummaryModel() {
        String shortName = summaryModel.startsWith("dashscope:")
                ? summaryModel.substring("dashscope:".length())
                : summaryModel;
        String apiKey = System.getenv("DASHSCOPE_API_KEY");

        log.info("🔧 构建摘要专用 DashScopeChatModel: model={}, enableThinking=false", shortName);

        this.summaryModelInstance = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(shortName)      // 推荐使用 qwen-turbo
                .stream(false)             // ✅ 摘要不需要流式
                .enableThinking(false)     // ✅ 关闭思考，核心加速点
                .build();
    }

    private Model buildDashScopeSummaryModel() {
        if (this.summaryModelInstance == null) {
            initSummaryModel();
        }
        return this.summaryModelInstance;
    }

}
