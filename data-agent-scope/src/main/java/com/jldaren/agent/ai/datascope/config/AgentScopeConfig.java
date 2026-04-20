package com.jldaren.agent.ai.datascope.config;

import com.jldaren.agent.ai.datascope.compression.CompressingHttpTransport;
import com.jldaren.agent.ai.datascope.compression.CompressionConfig;
import com.jldaren.agent.ai.datascope.entity.ModelConfig;
import com.jldaren.agent.ai.datascope.mapper.ModelConfigMapper;
import com.jldaren.agent.ai.datascope.tool.WeatherTool;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

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

    public AgentScopeConfig(ModelConfigMapper modelConfigMapper) {
        this.modelConfigMapper = modelConfigMapper;
    }

    /**
     * 获取 ChatModel 实例
     * 优先从数据库读取启用的模型配置，如果为空则使用默认配置
     */
    public DashScopeChatModel getChatModel() {
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
            log.info("✅使用数据库模型配置: provider={}, model={}", dbConfig.getProvider(), modelName);
        } else {
            log.info("✅使用默认模型配置: model={}", modelName);
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
     * 获取 Toolkit 实例
     * 供 AgentScopeRegistry 动态创建 Agent 使用
     */
    public Toolkit getToolkit() {
        Toolkit toolkit = new Toolkit();
        // 注册默认工具
        toolkit.registerTool(new WeatherTool());
        return toolkit;
    }

}