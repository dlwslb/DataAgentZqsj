package com.jldaren.agent.ai.datascope.controller.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreamEvent {
    private String type;         // "text_chunk" | "tool_call" | "error" | "done"
    private String content;
    private Object metadata;     // 额外信息，如工具参数
    private long timestamp;
}