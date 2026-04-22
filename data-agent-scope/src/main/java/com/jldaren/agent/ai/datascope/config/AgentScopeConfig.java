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
package com.jldaren.agent.ai.datascope.config;

import com.jldaren.agent.ai.datascope.compression.CompressingHttpTransport;
import com.jldaren.agent.ai.datascope.compression.CompressionConfig;
import com.jldaren.agent.ai.datascope.entity.ModelConfig;
import com.jldaren.agent.ai.datascope.mapper.ModelConfigMapper;
import com.jldaren.agent.ai.datascope.memory.OceanBaseLongTermMemory;
import com.jldaren.agent.ai.datascope.memory.config.LongTermMemoryConfig;
import com.jldaren.agent.ai.datascope.memory.entity.MemoryConfig;
import com.jldaren.agent.ai.datascope.memory.service.LongTermMemoryService;
import com.jldaren.agent.ai.datascope.plan.DatabasePlanStorage;
import com.jldaren.agent.ai.datascope.tool.ToolRegistry;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.plan.storage.PlanStorage;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.TimeUnit;

/**
 * AgentScope 基础配置
 * 提供 ChatModel、Toolkit 等公共组件
 */
@Slf4j
@Configuration
public class AgentScopeConfig {

    @Value("${agentscope.api-key:}")
    private String defaultApiKey;

    @Value("${agentscope.model-name:qwen-plus}")
    private String defaultModelName;

    @Value("${agentscope.base-url:}")
    private String defaultBaseUrl;

    @Value("${agentscope.compression.enabled:false}")
    private boolean compressionEnabled;

    private final ModelConfigMapper modelConfigMapper;
    private final JdbcTemplate jdbcTemplate;
    private final LongTermMemoryService longTermMemoryService;
    private final LongTermMemoryConfig memoryConfig;
    private final ToolRegistry toolRegistry;

    /** 缓存 ChatModel 实例，避免每次调用都查数据库+new Builder */
    private volatile DashScopeChatModel cachedChatModel;
    private volatile long chatModelCacheTime = 0;
    private static final long CHAT_MODEL_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    public AgentScopeConfig(ModelConfigMapper modelConfigMapper,
                           JdbcTemplate jdbcTemplate,
                           @Autowired(required = false) LongTermMemoryService longTermMemoryService,
                           @Autowired(required = false) LongTermMemoryConfig memoryConfig,
                           ToolRegistry toolRegistry) {
        this.modelConfigMapper = modelConfigMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.longTermMemoryService = longTermMemoryService;
        this.memoryConfig = memoryConfig;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 获取 ChatModel 实例
     * 优先从数据库读取启用的模型配置，如果为空则使用默认配置
     * 带 5 分钟缓存避免每次调用都查数据库
     */
    public DashScopeChatModel getChatModel() {
        long now = System.currentTimeMillis();
        if (cachedChatModel != null && (now - chatModelCacheTime) < CHAT_MODEL_CACHE_TTL_MS) {
            return cachedChatModel;
        }

        synchronized (this) {
            // DCL: 双重检查
            if (cachedChatModel != null && (System.currentTimeMillis() - chatModelCacheTime) < CHAT_MODEL_CACHE_TTL_MS) {
                return cachedChatModel;
            }

            DashScopeChatModel model = buildChatModel();
            cachedChatModel = model;
            chatModelCacheTime = System.currentTimeMillis();
            return model;
        }
    }

    /**
     * 强制刷新 ChatModel 缓存（配置变更时调用）
     */
    public void refreshChatModel() {
        cachedChatModel = null;
        chatModelCacheTime = 0;
    }

    private DashScopeChatModel buildChatModel() {
        ModelConfig dbConfig = null;
        try {
            dbConfig = modelConfigMapper.selectActiveByType("CHAT");
        } catch (Exception e) {
            log.warn("从数据库读取模型配置失败，使用默认配置: {}", e.getMessage());
        }

        String apiKey = (dbConfig != null && dbConfig.getApiKey() != null) ? dbConfig.getApiKey() : defaultApiKey;
        String modelName = (dbConfig != null && dbConfig.getModelName() != null) ? dbConfig.getModelName() : defaultModelName;
        String baseUrl = (dbConfig != null && dbConfig.getBaseUrl() != null) ? dbConfig.getBaseUrl() : defaultBaseUrl;

        if (dbConfig != null) {
            log.info("Using database model config: provider={}, model={}", dbConfig.getProvider(), modelName);
        } else {
            log.info("Using default model config: model={}", modelName);
        }

        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName);

        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        if (compressionEnabled) {
            CompressionConfig compressionConfig = CompressionConfig.enableGzip();
            CompressingHttpTransport transport = new CompressingHttpTransport(
                    null,
                    compressionConfig
            );
            builder.httpTransport((HttpTransport) transport);
        }

        return builder.build();
    }

    /**
     * 获取 Toolkit 实例（全量工具）
     * 供无 toolNames 配置的 Agent 使用
     * 不缓存 Toolkit，因为工具可能动态变化
     */
    public Toolkit getToolkit() {
        return toolRegistry.buildFullToolkit();
    }

    /**
     * 按 Agent 粒度构建 Toolkit
     * 只注册 Agent 配置中指定的工具
     *
     * @param toolNames 工具名称（逗号分隔），为空则使用全量工具
     */
    public Toolkit getToolkit(String toolNames) {
        if (toolNames == null || toolNames.isBlank()) {
            return getToolkit();
        }
        return toolRegistry.buildToolkit(toolNames);
    }

    /**
     * 获取计划存储实例
     * 使用数据库持久化存储
     */
    public PlanStorage getPlanStorage() {
        return new DatabasePlanStorage(jdbcTemplate);
    }

    /**
     * 检查长期记忆是否启用
     */
    public boolean isLongTermMemoryEnabled() {
        return longTermMemoryService != null && memoryConfig != null && memoryConfig.isEnabled();
    }

    /**
     * 获取长期记忆服务
     */
    public LongTermMemoryService getLongTermMemoryService() {
        return longTermMemoryService;
    }

    /**
     * 创建长期记忆实例 (供 AgentScopeRegistry 使用)
     * 优先从 DB long_term_memory_config 表读取配置，yml 配置作为兜底
     */
    public LongTermMemory createLongTermMemory(String agentName, String userId, String tenantId) {
        if (!isLongTermMemoryEnabled() || longTermMemoryService == null) {
            return null;
        }

        MemoryConfig dbConfig = longTermMemoryService.getConfig(agentName, tenantId);

        return OceanBaseLongTermMemory.builder()
                .agentName(agentName)
                .userId(userId)
                .tenantId(tenantId)
                .retrieveCount(dbConfig.getDefaultTopk())
                .similarityThreshold(dbConfig.getSimilarityThreshold().doubleValue())
                .memoryType(dbConfig.getMemoryType())
                .enabled(true)
                .memoryService(longTermMemoryService)
                .build();
    }

    /**
     * 创建带长期记忆的 Agent
     * 集成 OceanBase 长期记忆支持跨会话的持久化存储和语义检索
     *
     * @param agentName   Agent名称
     * @param userId      用户ID (用于多租户隔离)
     * @param tenantId    租户ID
     * @param sysPrompt   系统提示词
     * @return ReActAgent 实例
     */
    public ReActAgent createAgentWithLongTermMemory(String agentName, String userId, 
                                                   String tenantId, String sysPrompt) {
        // 获取基础组件
        DashScopeChatModel chatModel = getChatModel();
        Toolkit toolkit = getToolkit();
        InMemoryMemory shortTermMemory = new InMemoryMemory();

        // 构建 Agent
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(agentName)
                .model(chatModel)
                .memory(shortTermMemory)
                .toolkit(toolkit);

        if (sysPrompt != null && !sysPrompt.isEmpty()) {
            builder.sysPrompt(sysPrompt);
        }

        // 集成长期记忆
        if (isLongTermMemoryEnabled() && longTermMemoryService != null) {
            MemoryConfig dbConfig = longTermMemoryService.getConfig(agentName, tenantId);

            OceanBaseLongTermMemory longTermMemory = OceanBaseLongTermMemory.builder()
                    .agentName(agentName)
                    .userId(userId)
                    .tenantId(tenantId)
                    .retrieveCount(dbConfig.getDefaultTopk())
                    .similarityThreshold(dbConfig.getSimilarityThreshold().doubleValue())
                    .memoryType(dbConfig.getMemoryType())
                    .enabled(true)
                    .memoryService(longTermMemoryService)
                    .build();

            builder.longTermMemory((LongTermMemory) longTermMemory)
                   .longTermMemoryMode(LongTermMemoryMode.BOTH);

            log.info("Agent {} integrated with LongTermMemory(BOTH mode): userId={}, tenantId={}, threshold={}",
                    agentName, userId, tenantId, dbConfig.getSimilarityThreshold());
        } else {
            log.warn("LongTermMemory is disabled, creating Agent without long-term memory");
        }

        return builder.build();
    }

    /**
     * 为现有 Agent 添加长期记忆
     * 用于动态给已创建的 Agent 添加长期记忆能力
     *
     * @param agent      已创建的 Agent
     * @param userId     用户ID
     * @param tenantId   租户ID
     * @return 带有长期记忆的 Agent (实际上是原 Agent，会自动使用 longTermMemoryMode 配置)
     */
    public ReActAgent addLongTermMemoryToAgent(ReActAgent agent, String userId, String tenantId) {
        if (!isLongTermMemoryEnabled()) {
            log.warn("Cannot add LongTermMemory: not enabled");
            return agent;
        }

        // 由于 AgentScope 的设计，需要在创建时配置长期记忆
        // 这里主要用于日志记录和配置检查
        log.info("Agent configured with LongTermMemory: userId={}, tenantId={}", userId, tenantId);
        return agent;
    }

}