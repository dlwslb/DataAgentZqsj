package com.jldaren.agent.ai.datascope.controller.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;

/**
 * SSE 流式聊天请求参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamChatRequest {

    @NotBlank(message = "message is required")
    private String message;          // 🔑 必填：用户消息

    private String sessionId;        // 可选：会话 ID（用于多轮对话）

    @Builder.Default
    private boolean enableRag = true; // 可选：是否启用 RAG 增强（默认开启）

    // 预留扩展字段（未来可加 temperature/maxTokens 等）
    private Double temperature;
    private Integer maxTokens;
}
