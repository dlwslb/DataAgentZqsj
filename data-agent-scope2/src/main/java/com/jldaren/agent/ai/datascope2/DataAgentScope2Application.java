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
package com.jldaren.agent.ai.datascope2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DataAgent Scope 2.0 - 基于 AgentScope 2.0 + HarnessAgent 的数据查询 Agent
 *
 * <p>核心特性：使用 Skill 机制让 agent 自动根据 skill 描述匹配并执行数据查询。
 * <ul>
 *   <li>HarnessAgent 提供工作区、长期记忆、会话持久化、子 agent、沙箱等工程底座</li>
 *   <li>Skill 仓库（classpath / MySQL）管理可复用的"工作流模板"</li>
 *   <li>Tool 提供原子能力（query_biz_data / run_python / format_report）</li>
 *   <li>RuntimeContext 透传 userId/sessionId/tenantId 实现多租户隔离</li>
 * </ul>
 */
@SpringBootApplication
public class DataAgentScope2Application {

    public static void main(String[] args) {
        SpringApplication.run(DataAgentScope2Application.class, args);
    }
}
