package com.jldaren.agent.ai.datascope.controller.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamResponseDTO {
    private String content;      // 片段内容
    private String messageType;  // text/markdown/markdown-report/html-report
    private String messageId;    // 唯一消息ID（建议前端生成或后端预生成）
    private String eventType;    // reasoning/acting/summary/done/error（可选，用于区分流类型）
}
