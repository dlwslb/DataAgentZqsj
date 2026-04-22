package com.jldaren.agent.ai.datascope.tool;

import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.util.Map;

/**
 * 远程调用商机智能体工具
 * 通过 HTTP 调用 data-agent-management 的 /api/stream/search 接口
 */
@Slf4j
@Component
public class A2ARemoteCallTool {

    @Value("${agentscope.a2a.remote-agent-url:http://192.168.3.177:58065}")
    private String remoteAgentUrl;

    @Value("${agentscope.a2a.remote-agent-id:5}")
    private String remoteAgentId;

    private WebClient webClient;

    private static final ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    @PostConstruct
    public void init() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.webClient = WebClient.builder()
                .baseUrl(remoteAgentUrl)
                .exchangeStrategies(strategies)
                .build();
        log.info("✅Remote agent HTTP client initialized, url={}, agentId={}", remoteAgentUrl, remoteAgentId);
    }

    @Tool(name = "get_zqsj_agent", description = "商机查询智能体：查询中标数据、招标信息、采购信息、企业商机、项目动态等商业信息。当用户询问中标、招标、采购、商机、项目数据等相关问题时，应调用此工具获取实时数据。工具返回的即是最终分析结果，请直接整理后回复用户，不要重复分析。")
    public Msg callRemoteAgent(
            @ToolParam(name = "question", description = "问题内容", required = true) String question) {

        log.info("🔧 [远程智能体调用] get_zqsj_agent 被调用, question={}", question);

        try {
            // 使用 SSE 专用解码器，直接接收 ServerSentEvent 对象
            String fullResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/stream/search")
                            .queryParam("agentId", remoteAgentId)
                            .queryParam("query", question)
                            .build())
                    .retrieve()
                    .bodyToFlux(SSE_TYPE)
                    .filter(sse -> sse.data() != null)
                    .mapNotNull(sse -> {
                        Object textObj = sse.data().get("text");
                        return textObj != null ? textObj.toString() : null;
                    })
                    .filter(text -> !text.isBlank())
                    .collectList()
                    .map(chunks -> String.join("", chunks))
                    .block();

            if (fullResponse == null || fullResponse.isBlank()) {
                log.warn("⚠️ [远程智能体调用] 返回为空, question={}", question);
                return Msg.builder().textContent("远程商机智能体无响应").build();
            }

            // 提取最终报告部分，过滤掉中间处理步骤（意图识别、SQL生成等）
            String finalResult = extractFinalReport(fullResponse);
            log.info("✅ [远程智能体调用] 返回成功, 原始长度={}, 提取后长度={}", fullResponse.length(), finalResult.length());
            return Msg.builder().textContent(finalResult).build();

        } catch (Exception e) {
            log.error("❌ [远程智能体调用] 调用失败, question={}, error={}", question, e.getMessage(), e);
            return Msg.builder().textContent("远程商机智能体调用失败: " + e.getMessage()).build();
        }
    }

    /**
     * 从远程智能体完整输出中提取最终报告
     * 远程智能体输出格式：中间过程（意图识别、SQL等）+ 最终报告（以 # 开头的 markdown）
     */
    private String extractFinalReport(String fullResponse) {
        // 尝试找到报告开头的 markdown 标题（如 "# 吉林省2026年4月内中标项目分析报告"）
        int reportStart = fullResponse.indexOf("\n# ");
        if (reportStart >= 0) {
            String report = fullResponse.substring(reportStart + 1).trim();
            if (!report.isBlank()) {
                return report;
            }
        }
        // 如果没有找到 markdown 报告，尝试找 SQL 查询结果之后的内容
        int sqlResultEnd = fullResponse.indexOf("报告生成完成");
        if (sqlResultEnd >= 0) {
            return fullResponse.substring(0, sqlResultEnd).trim();
        }
        // 都没找到则返回原始内容（兜底）
        return fullResponse;
    }
}
