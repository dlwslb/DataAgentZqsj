package com.jldaren.agent.ai.datascope.config;

import com.jldaren.agent.ai.datascope.compression.CompressingHttpTransport;
import com.jldaren.agent.ai.datascope.compression.CompressionConfig;
import com.jldaren.agent.ai.datascope.hook.HitlHook;
import com.jldaren.agent.ai.datascope.tool.WeatherTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.tool.Toolkit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AgentScopeConfig {

    @Value("${agentscope.api-key:}")
    private String apiKey;

    @Value("${agentscope.model-name:qwen-plus}")
    private String modelName;

    // baseUrl 可选，默认使用阿里云官方端点
    @Value("${agentscope.base-url:}")
    private String baseUrl;

    @Value("${agentscope.compression.enabled:false}")
    private boolean compressionEnabled;

    @Bean
    public Memory memory() {
        return new InMemoryMemory();
    }

    @Bean
    public DashScopeChatModel chatModel() {
        DashScopeChatModel.Builder builder = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName);

        // 如果配置了 baseUrl，则设置
        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        // 启用 HTTP 压缩
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

    @Bean
    public Toolkit toolkit(WeatherTool weatherTool) {
        // ✅ 真实：new + registerTool
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(weatherTool);
        return toolkit;
    }

    @Bean
    public ReActAgent businessAssistant(Memory memory, Toolkit toolkit, HitlHook hitlHook) {
        return ReActAgent.builder()
                .name("BusinessAssistant")
                .sysPrompt("""
                        你是一个专业的企业智能助手。
                        - 回答简洁专业，不确定时主动澄清
                        """)
                .model(chatModel())
                .memory(memory)
                .toolkit(toolkit)
                .hooks(List.of(hitlHook))  // 注册 HITL Hook
                .maxIters(10)
                .checkRunning(true)
                .build();
    }
}