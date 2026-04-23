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
package com.jldaren.agent.ai.datascope.tool;

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

    @Tool(name = "get_zqsj_agent", description = "商机查询智能体：当用户询问中标、招标、采购、商机、项目数据等相关问题时，应调用此工具获取实时数据。")
    public String callRemoteAgent(
            @ToolParam(name = "question", description = "问题内容", required = true) String question) {

        // 解析消息中的参数标记 [OPTIONS: userRole=admin, showSqlResults=true]
        String actualQuestion = question;
        String userRole = "user";
        Boolean showSqlResults = false;
        Boolean nl2sqlOnly = false;
        
        int optionsStart = question.lastIndexOf("[OPTIONS:");
        if (optionsStart >= 0) {
            int optionsEnd = question.indexOf("]", optionsStart);
            if (optionsEnd > optionsStart) {
                String optionsStr = question.substring(optionsStart + 9, optionsEnd); // 跳过 "[OPTIONS: " 
                actualQuestion = question.substring(0, optionsStart).trim();
                
                // 解析各个参数
                for (String opt : optionsStr.split(",")) {
                    String[] kv = opt.trim().split("=");
                    if (kv.length == 2) {
                        String key = kv[0].trim();
                        String value = kv[1].trim();
                        switch (key) {
                            case "userRole" -> userRole = value;
                            case "showSqlResults" -> showSqlResults = "true".equalsIgnoreCase(value);
                            case "nl2sqlOnly" -> nl2sqlOnly = "true".equalsIgnoreCase(value);
                        }
                    }
                }
            }
        }

        log.info("🔧 [远程智能体调用] get_zqsj_agent 被调用, question={}, userRole={}, showSqlResults={}", 
                actualQuestion, userRole, showSqlResults);

        // 保存为 final 变量供 lambda 使用
        final String finalQuestion = actualQuestion;
        final String finalUserRole = userRole;
        final Boolean finalNl2sqlOnly = nl2sqlOnly;
        final Boolean finalShowSqlResults = showSqlResults;

        try {
            // 使用 SSE 专用解码器，直接接收 ServerSentEvent 对象
            String fullResponse = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/stream/search")
                            .queryParam("agentId", remoteAgentId)
                            .queryParam("query", finalQuestion)
                            .queryParam("userRole", finalUserRole)
                            .queryParam("nl2sqlOnly", finalNl2sqlOnly)
                            .queryParam("showSqlResults", finalShowSqlResults)
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
                log.warn("⚠️ [远程智能体调用] 返回为空, question={}", actualQuestion);
                return "远程商机智能体无响应";
            }

            log.debug("🔍 [远程智能体调用] 原始拼接结果前100字符: [{}]", fullResponse.substring(0, Math.min(100, fullResponse.length())));
            log.debug("🔍 [远程智能体调用] 原始拼接结果首字符=[{}], 尾字符=[{}]]",
                    fullResponse.isEmpty() ? "空" : (int) fullResponse.charAt(0),
                    fullResponse.isEmpty() ? "空" : (int) fullResponse.charAt(fullResponse.length() - 1));

            // 提取最终报告部分，过滤掉中间处理步骤（意图识别、SQL生成等）
            String finalResult = extractFinalReport(fullResponse);
            log.info("✅ [远程智能体调用] 返回成功, 原始长度={}, 提取后长度={}", fullResponse.length(), finalResult.length());
            return finalResult;

        } catch (Exception e) {
            log.error("❌ [远程智能体调用] 调用失败, question={}, error={}", actualQuestion, e.getMessage(), e);
            return "远程商机智能体调用失败: " + e.getMessage();
        }
    }

    /**
     * 从远程智能体完整输出中提取最终报告
     * 远程端 SSE 输出格式：
     *   - 中间过程（意图识别、SQL等）
     *   - $$$markdown-report 报告内容 $$$/markdown-report
     *   - $$$/markdown-report（结束标记）
     *   - 报告生成完成！
     */
    private String extractFinalReport(String fullResponse) {
        String report = fullResponse;

        // 清理：先去掉整体被双引号包裹的情况（远程端可能把整个报告序列化为JSON字符串）
        if (report.startsWith("\"") && report.endsWith("\"")) {
            try {
                // 尝试作为JSON字符串反序列化，正确处理转义字符（\n, \", \\等）
                report = new com.fasterxml.jackson.databind.ObjectMapper().readValue(report, String.class);
            } catch (Exception e) {
                // JSON解析失败则手动去掉首尾引号
                log.warn("⚠️ [extractFinalReport] JSON反序列化失败，手动去引号: {}", e.getMessage());
                while (report.startsWith("\"") && report.endsWith("\"") && report.length() > 1) {
                    report = report.substring(1, report.length() - 1);
                }
            }
        }

        // 优先提取 $$$markdown-report ... $$$/markdown-report 之间的内容
        int startTag = report.indexOf("$$$markdown-report");
        int endTag = report.indexOf("$$$/markdown-report");
        if (startTag >= 0 && endTag > startTag) {
            report = report.substring(startTag + "$$$markdown-report".length(), endTag).trim();
        } else if (startTag >= 0) {
            // 有开始标记但没结束标记，取开始标记之后的内容
            report = report.substring(startTag + "$$$markdown-report".length()).trim();
        } else {
            // 没有 $$$ 标记，回退到找 markdown 标题
            int reportStart = report.indexOf("\n# ");
            if (reportStart >= 0) {
                report = report.substring(reportStart + 1).trim();
            }
        }

        // 清理：去掉结尾的"报告生成完成！"
        report = report.replaceAll("报告生成完成！?\\s*$", "");
        // 清理：确保 echarts 代码块有 markdown 围栏
        report = fixEchartsCodeBlock(report);

        return report;
    }

    /**
     * 修复 echarts 代码块格式
     * 远程端可能输出裸 echarts JSON 而没有 ```echarts ``` 围栏
     */
    private String fixEchartsCodeBlock(String report) {
        // 匹配：换行 + echarts + 换行 + { ... }（到字符串结尾）
        // 替换为：换行 + ```echarts + 换行 + { ... } + 换行 + ```
        String pattern = "(\\n)echarts\\s*(\\n\\s*\\{)";
        if (java.util.regex.Pattern.compile(pattern).matcher(report).find()) {
            report = report.replaceAll(pattern, "$1```echarts$2");
            // 在 echarts JSON 结尾的 } 后补 ```（如果还没有的话）
            // 找最后一个 ```echarts 之后到结尾，确保闭合
            int echartsStart = report.lastIndexOf("```echarts");
            if (echartsStart >= 0 && !report.trim().endsWith("```")) {
                report = report.stripTrailing() + "\n```";
            }
        }
        return report;
    }
}
