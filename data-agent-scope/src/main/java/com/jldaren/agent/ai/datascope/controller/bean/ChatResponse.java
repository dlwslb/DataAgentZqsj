package com.jldaren.agent.ai.datascope.controller.bean;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String sessionId;
    private String content;
    private long timestamp;
    private String traceId;      // 用于链路追踪
}
