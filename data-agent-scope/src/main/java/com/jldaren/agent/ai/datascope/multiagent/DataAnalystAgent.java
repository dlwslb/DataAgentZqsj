package com.jldaren.agent.ai.datascope.multiagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 多智能体 - 数据分析师
 *
 * 多智能体报告：
 *
 */
@Slf4j
@Component
public class DataAnalystAgent {

    private final ReActAgent agent;

    public DataAnalystAgent(
            @Value("${agentscope.api-key}") String apiKey,
            @Value("${agentscope.model-name}") String modelName,
            @Value("${agentscope.base-url}") String baseUrl) {
        
        this.agent = ReActAgent.builder()
                .name("DataAnalyst")
                .sysPrompt("""
                        你是专业的数据分析师。
                        - 擅长 SQL 查询和数据统计
                        - 能生成清晰的数据分析报告
                        - 回答简洁，聚焦关键指标
                        """)
                .model(DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .baseUrl(baseUrl)
                        .build())
                .toolkit(new Toolkit())
                .maxIters(10)
                .build();
    }

    public Msg analyze(String question) {
        log.info("📊 DataAnalyst 分析: {}", question);
        Msg request = Msg.builder().textContent(question).build();
        return agent.call(request).block();
    }
}
