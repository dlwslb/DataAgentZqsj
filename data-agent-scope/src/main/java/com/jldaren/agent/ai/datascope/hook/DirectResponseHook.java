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
package com.jldaren.agent.ai.datascope.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 工具返回直出 Hook - 防止 ReActAgent 二次总结
 * 
 * <p>当 get_zqsj_agent 工具返回后：
 * <ol>
 *   <li>从 ToolResultBlock 中提取实际文本内容</li>
 *   <li>将 toolResultMsg 替换为仅包含文本的 Msg（确保 getTextContent() 可用）</li>
 *   <li>调用 stopAgent() 终止 ReAct 循环</li>
 * </ol>
 * 这样 agent.call() 返回的 Msg 可以通过 getTextContent() 直接获取文本。
 */
@Slf4j
@Component
public class DirectResponseHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.fromCallable(() -> {
            if (event instanceof PostActingEvent postActingEvent) {
                String toolName = postActingEvent.getToolUse().getName();
                Map<String, Object> toolArgs = postActingEvent.getToolUse().getInput();
                Object skipReport = toolArgs != null ? toolArgs.get("skipReport") : null;
                log.info("🔍 [DirectResponse] PostActingEvent触发: toolName={}，skipReport={}", toolName,skipReport);

                if ("get_zqsj_agent".equals(toolName)) {
                    // 从 ToolResultBlock.output 中提取实际文本
                    String resultText = extractToolResultText(postActingEvent);
                    
                    boolean shouldStop = true; // 默认终止循环
                    
                    // 判断是否包含 SQL 查询结果标记
                    int sqlResultIndex = resultText.indexOf("SQL查询结果：");
                    if (sqlResultIndex >= 0) {
                        // 有 SQL 查询结果，需要进一步判断是原始数据还是完整报告
                        try {
                            // 提取 JSON 部分
                            String jsonText = resultText.substring(sqlResultIndex + "SQL查询结果：".length()).trim();
                            
                            // 先检查是否是有效的 JSON（以 { 或 [ 开头）
                            if (jsonText.startsWith("{") || jsonText.startsWith("[")) {
                                JsonNode rootNode = OBJECT_MAPPER.readTree(jsonText);
                                JsonNode resultSetNode = rootNode.get("resultSet");
                                
                                if (resultSetNode != null) {
                                    // 检查是否有报告生成的迹象（Markdown 标题、报告内容等）
                                    boolean hasReportContent = resultText.contains("# ") || 
                                                              resultText.contains("## ") ||
                                                              resultText.contains("### ") ||
                                                              (resultText.length() > 200); // 报告通常较长
                                    
                                    if (!hasReportContent) {
                                        // skipReport=true：只有原始数据，提取 resultSet 让 ReAct 继续处理
                                        resultText = OBJECT_MAPPER.writeValueAsString(resultSetNode);
                                        log.info("📊 [DirectResponse] skipReport=true，已提取 resultSet 数据，长度={}", resultText.length());
                                        shouldStop = false; // 不终止，让 ReAct 继续处理
                                    } else {
                                        // skipReport=false：有完整报告，直接返回
                                        log.info("📊 [DirectResponse] skipReport=false，检测到完整报告，直接返回，长度={}", resultText.length());
                                    }
                                }
                            } else {
                                // 不是 JSON 格式，说明已经是报告内容（Markdown 等）
                                log.info("📊 [DirectResponse] 检测到非 JSON 格式内容（可能是 Markdown 报告），直接返回，长度={}", resultText.length());
                            }
                        } catch (Exception e) {
                            log.warn("⚠️ [DirectResponse] 解析 SQL 查询结果失败: {}", e.getMessage());
                        }
                    }
                    
                    // 用纯文本 Msg 替换 toolResultMsg
                    Msg directMsg = Msg.builder()
                            .name(postActingEvent.getAgent().getName())
                            .role(MsgRole.ASSISTANT)
                            .textContent(resultText)
                            .build();
                    postActingEvent.setToolResultMsg(directMsg);
                    
                    if (shouldStop) {
                        log.info("🚀 [DirectResponse] get_zqsj_agent 返回完成，终止循环，直接输出结果");
                        postActingEvent.stopAgent();
                    } else {
                        log.debug("🔄 [DirectResponse] get_zqsj_agent 返回，让 ReAct 继续处理截取后的结果");
                    }
                }
            }
            return event;
        });
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 从 PostActingEvent 的 ToolResultBlock.output 中提取文本内容
     * 注意：框架将 String 返回值放入 TextBlock 时可能做了 JSON 序列化（加引号+转义），
     * 需要反序列化还原为原始文本。
     */
    private String extractToolResultText(PostActingEvent event) {
        ToolResultBlock toolResult = event.getToolResult();
        if (toolResult == null || toolResult.getOutput() == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : toolResult.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                if (sb.length() > 0) sb.append("\n");
                String blockText = textBlock.getText();
                // 框架可能将 String 工具返回值 JSON 序列化后存入 TextBlock，
                // 导致文本被双引号包裹且换行变为 \n，需要反序列化还原
                blockText = unwrapJsonString(blockText);
                sb.append(blockText);
            }
        }

        String text = sb.toString();
        if (text.isBlank()) {
            // 兜底：尝试从 toolResultMsg 的 getTextContent() 获取
            Msg toolResultMsg = event.getToolResultMsg();
            if (toolResultMsg != null) {
                String msgText = toolResultMsg.getTextContent();
                if (msgText != null && !msgText.isBlank()) {
                    return msgText;
                }
            }
        }
        return text;
    }

    /**
     * 如果文本被 JSON 序列化（首尾有引号），反序列化还原为原始字符串
     */
    private String unwrapJsonString(String text) {
        if (text != null && text.startsWith("\"") && text.endsWith("\"")) {
            try {
                return OBJECT_MAPPER.readValue(text, String.class);
            } catch (Exception e) {
                log.warn("⚠️ [DirectResponse] JSON反序列化TextBlock失败，保持原文: {}", e.getMessage());
            }
        }
        return text;
    }
}
