package com.jldaren.agent.ai.datascope.multiagent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 多智能体 - 报告生成器
 */
@Slf4j
@Component
public class ReportGeneratorAgent {

    private final ReActAgent agent;

    public ReportGeneratorAgent(
            @Value("${agentscope.api-key}") String apiKey,
            @Value("${agentscope.model-name}") String modelName,
            @Value("${agentscope.base-url}") String baseUrl) {
        
        this.agent = ReActAgent.builder()
                .name("ReportGenerator")
                .sysPrompt("""
                        你是专业的报告生成专家。
                        - 将数据分析结果转化为结构化报告
                        - 包含：摘要、关键发现、建议
                        - 语言简洁专业，适合管理层阅读
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

    public Msg generate(String analysisResult) {
        log.info("📝 ReportGenerator 生成报告");
        String prompt = "基于以下分析结果生成报告：\n" + analysisResult;
        Msg request = Msg.builder().textContent(prompt).build();
        return agent.call(request).block();
    }
}
