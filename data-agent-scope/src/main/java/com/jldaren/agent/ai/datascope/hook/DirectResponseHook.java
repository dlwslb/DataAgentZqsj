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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                    
                    boolean shouldStop = false; // 不终止，让 ReAct 继续处理
                    
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
                                    if (Boolean.parseBoolean(String.valueOf(skipReport))==true) {
                                        // skipReport=true：只有原始数据，提取 resultSet 让 ReAct 继续处理
                                        resultText = OBJECT_MAPPER.writeValueAsString(resultSetNode);
                                        log.info("📊 [DirectResponse] skipReport=true，已提取 resultSet 数据，长度={}", resultText.length());
                                    } else {
                                        shouldStop = true;
                                        //resultText = "未找到相关数据,请等待数据更新。";
                                        // skipReport=false：有完整报告，直接返回
                                        log.info("📊 [DirectResponse] skipReport=false，检测到完整报告，直接返回，长度={}", resultText.length());
                                    }
                                }
                            } else {
                                shouldStop = true;
                                resultText = "未找到相关数据,请等待数据更新。";
                                // 不是 JSON 格式，说明已经是报告内容（Markdown 等）
                                log.info("📊 [DirectResponse] 检测到非 JSON 格式内容（可能是 Markdown 报告），直接返回，长度={}", resultText.length());
                            }
                        } catch (Exception e) {
                            resultText = "未找到相关数据,请等待数据更新。";
                            log.warn("⚠️ [DirectResponse] 解析 SQL 查询结果失败: {}", e.getMessage());
                        }
                    }

                    if((!resultText.contains("resultSet") && resultText.contains("未找到相关数据。") && resultText.endsWith("结果分析完成。"))
                            ||resultText.contains("SQL次数生成超限，最大尝试次数")
                            ||resultText.contains("正在进行意图识别... { \"classification\": \"《闲聊或无关指令》\" } 意图识别完成！\n")){
                        resultText = "未找到相关数据,请等待数据更新。";
                        shouldStop = true;
                    }
                    if(Boolean.parseBoolean(String.valueOf(skipReport))==false){
                        shouldStop = true;
                    }
                    //兜底：处理澄清需求，去掉技术细节（表名等）
                    if(resultText.startsWith("正在进行意图识别")){
                        // ⭐ 提取澄清问题中的自然语言部分，去掉技术细节
                        String cleanText = extractClarificationQuestion(resultText);
                        if (cleanText != null && !cleanText.isEmpty()) {
                            resultText = cleanText;
                            log.info("✅ [DirectResponse] 澄清问题已清理技术细节");
                        } else {
                            // 如果无法提取，才使用默认提示
                            resultText = "未找到相关数据,请等待数据更新。";
                            log.warn("⚠️ 远程调用结果可能有错误，未解析到结果或报告！");
                        }
                        shouldStop = true;
                    }

                    // 需求确认提取
                    String regex = "【需求内容】：\\s*(.*?)\\s*可行性评估完成！";
                    Pattern pattern = Pattern.compile(regex);
                    Matcher matcher = pattern.matcher(resultText);
                    if (matcher.find()) {
                        // group(1) 获取第一个括号内匹配的内容
                        resultText = matcher.group(1);
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
                log.debug("Failed to unwrap JSON string", e);
            }
        }
        return text;
    }

    /**
     * ⭐ 提取澄清问题中的自然语言部分，去掉技术细节（表名、字段名等）
     * 
     * 示例输入：
     * "正在进行意图识别...请问您想查询的“XXX项目”是指哪个表中的具体项目？是中标信息（bid_biz_win_bid）、招标信息（bid_biz_bidding）还是采购意向（bid_biz_purchase_intention）中的记录？另外，您希望获取哪些明细字段？例如项目名称、预算金额、中标单位或产品信息等？"
     * 
     * 示例输出：
     * "请问您想查询的“XXX项目”是指哪个表中的具体项目？是中标信息、招标信息还是采购意向中的记录？另外，您希望获取哪些明细字段？例如项目名称、预算金额、中标单位或产品信息等？"
     */
    private String extractClarificationQuestion(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // 1. 去掉开头的“正在进行意图识别...”
        String cleaned = text.replaceFirst("^正在进行意图识别[^。]*。\\s*", "");
        
        // 2. 去掉括号内的技术细节（如表名、字段名）
        // 匹配模式：中文+（英文或下划线） -> 只保留中文部分
        cleaned = cleaned.replaceAll("([\u4e00-\u9fa5]+)（[a-zA-Z0-9_]+）", "$1");
        
        // 3. 去掉单独的括号内容（如 (bid_biz_win_bid)）
        cleaned = cleaned.replaceAll("\\([a-zA-Z0-9_]+\\)", "");
        
        // 4. 清理多余的空格
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        
        return cleaned.isEmpty() ? null : cleaned;
    }
}
