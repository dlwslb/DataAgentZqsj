package com.jldaren.agent.ai.datascope.controller.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String sessionId;    // 会话ID (用于多轮对话)
    private String message;      // 用户消息
    private boolean stream;      // 是否流式响应
    private String userId;       // 用户ID (用于权限/审计)
}