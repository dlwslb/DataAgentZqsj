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
package com.jldaren.agent.ai.datascope.memory.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 长期记忆配置类
 * 从 application.yml 读取 agentscope.memory 配置
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "agentscope.memory")
public class LongTermMemoryConfig {

    /** 是否启用长期记忆 */
    private boolean enabled = true;

    /** 相似度阈值 */
    private double similarityThreshold = 0.4;

    /** 默认检索数量 */
    private int defaultTopk = 10;

    /** 最大检索数量 */
    private int maxTopk = 50;

    /** 默认保留天数 */
    private int retentionDays = 365;

    /** 自动记录阈值 */
    private double autoRecordThreshold = 0.7;

    /** 是否启用自动记录 */
    private boolean autoRecordEnabled = true;

    /** 默认记忆类型 */
    private String memoryType = "semantic";

    /** 清理过期任务 cron 表达式 */
    private String cleanupCron = "0 0 2 * * ?";

    /** 是否启用定时清理 */
    private boolean cleanupEnabled = true;

    /** 向量存储时最大内容长度(避免token超限) */
    private int maxContentLength = 2000;

    /** 是否启用 LLM 评估重要性（默认关闭，用规则引擎；开启后调用 LLM 判断内容是否值得记忆） */
    private boolean llmImportanceEnabled = false;

    /** Agent 缓存 TTL（分钟），超过此时间不活跃的缓存 Agent 将被淘汰释放内存 */
    private int agentCacheTtlMinutes = 30;
}
