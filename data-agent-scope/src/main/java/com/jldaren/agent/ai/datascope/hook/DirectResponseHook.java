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
                log.info("🔍 [DirectResponse] PostActingEvent触发: toolName={}", toolName);
                
                if ("get_zqsj_agent".equals(toolName)) {
                    // 从 ToolResultBlock.output 中提取实际文本
                    String resultText = extractToolResultText(postActingEvent);
                    
                    // 用纯文本 Msg 替换 toolResultMsg，确保 getTextContent() 可用
                    Msg directMsg = Msg.builder()
                            .name(postActingEvent.getAgent().getName())
                            .role(MsgRole.ASSISTANT)
                            .textContent(resultText)
                            .build();
                    postActingEvent.setToolResultMsg(directMsg);
                    
                    log.info("🚀 [DirectResponse] get_zqsj_agent 返回完成，文本长度={}，新Msg.role={}，请求终止循环，直接输出结果",
                            resultText != null ? resultText.length() : 0, directMsg.getRole());
                    postActingEvent.stopAgent();
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
