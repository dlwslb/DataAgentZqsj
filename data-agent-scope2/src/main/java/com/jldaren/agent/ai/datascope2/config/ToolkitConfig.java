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

import com.jldaren.agent.ai.datascope2.memory.BusinessKnowledgeRagTool;
import com.jldaren.agent.ai.datascope2.memory.LongTermMemoryTool;
import com.jldaren.agent.ai.datascope2.tool.FormatReportTool;
import com.jldaren.agent.ai.datascope2.tool.QueryBizDataTool;
import com.jldaren.agent.ai.datascope2.tool.RunPythonTool;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Toolkit 配置：把原子工具注册到 agent
 *
 * <p>Tool 是 Skill 调用的基础。在 AgentScope 2.0 里：
 * <ul>
 *   <li>Tool = Java 方法（@Tool 注解），是原子能力</li>
 *   <li>Skill = Markdown 模板，告诉 agent 什么场景用哪个 tool、怎么用、结果怎么处理</li>
 * </ul>
 */
@Slf4j
@Configuration
public class ToolkitConfig {

    @Bean
    public Toolkit toolkit(QueryBizDataTool queryBizDataTool,
                           RunPythonTool runPythonTool,
                           FormatReportTool formatReportTool,
                           LongTermMemoryTool longTermMemoryTool,
                           BusinessKnowledgeRagTool businessKnowledgeRagTool) {
        log.info("🔧 注册原子工具到 Toolkit");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(queryBizDataTool);
        toolkit.registerTool(runPythonTool);
        toolkit.registerTool(formatReportTool);
        toolkit.registerTool(longTermMemoryTool);
        toolkit.registerTool(businessKnowledgeRagTool);
        log.info("✅ Toolkit 工具注册完成: query_biz_data, run_python, format_report, " +
                "record_memory, recall_memories, list_recent_memories, search_business_knowledge");
        return toolkit;
    }
}
