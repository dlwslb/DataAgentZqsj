package com.jldaren.agent.ai.datascope.controller;

import com.jldaren.agent.ai.datascope.controller.bean.ChatRequest;
import com.jldaren.agent.ai.datascope.controller.bean.ChatResponse;
import com.jldaren.agent.ai.datascope.controller.bean.StreamEvent;
import com.jldaren.agent.ai.datascope.dto.WeatherReport;
import com.jldaren.agent.ai.datascope.hook.HitlHook;
import com.jldaren.agent.ai.datascope.multiagent.DataAnalystAgent;
import com.jldaren.agent.ai.datascope.multiagent.ReportGeneratorAgent;
import com.jldaren.agent.ai.datascope.service.ChatService;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Tag(name = "Chat API", description = "AgentScope 聊天接口")
public class ChatController {

    private final ChatService chatService;
    private final HitlHook hitlHook;
    private final DataAnalystAgent dataAnalystAgent;
    private final ReportGeneratorAgent reportGeneratorAgent;

    //http://localhost:58064/api/v1/chat/quick?agentId=1&message=你好
    @GetMapping("/quick")
    @Operation(summary = "快速测试", description = "GET 请求直接返回回复")
    public Mono<String> quickChat(@RequestParam Long agentId, @RequestParam(defaultValue = "你好") String message) {
        ChatRequest request = new ChatRequest();
        request.setAgentId(agentId);
        request.setMessage(message);
        return chatService.chat(request)
                .map(Msg::getTextContent);
    }

    @PostMapping("/structured")
    @Operation(summary = "结构化输出", description = "返回 JSON 格式的天气报告")
    public Mono<WeatherReport> structuredChat(@RequestBody ChatRequest request) {
        return chatService.chatStructured(request, WeatherReport.class);
    }

    @PostMapping
    @Operation(summary = "非流式聊天", description = "一次性返回完整回复")
    public Mono<String> chat(@RequestBody ChatRequest request) {
        return chatService.chat(request)
                .map(this::extractText);
    }

    /**
     * 从 Msg 中提取文本内容
     * 优先取顶层 TextBlock，若为空则从 ToolResultBlock.output 中提取
     */
    private String extractText(Msg msg) {
        String text = msg.getTextContent();
        if (text != null && !text.isBlank()) {
            return text;
        }
        // 从 ToolResultBlock.output 中提取
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolResultBlock toolResult) {
                for (ContentBlock out : toolResult.getOutput()) {
                    if (out instanceof TextBlock tb) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(tb.getText());
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * HITL 反馈：
     * curl -X POST "http://localhost:58064/api/v1/chat/hitl/feedback?feedback=approve"
     */
    @PostMapping("/hitl/feedback")
    @Operation(summary = "HITL 提交反馈", description = "人机协同 - 提交人工反馈")
    public Mono<String> submitHitlFeedback(@RequestParam String feedback) {
        hitlHook.submitFeedback(feedback);
        return Mono.just("反馈已提交: " + feedback);
    }

    //curl -X POST http://localhost:58064/api/v1/chat/multiagent/report \
    //  -H 'Content-Type: application/json' \
    //  -d '{"message": "分析上月销售数据"}'
    @PostMapping("/multiagent/report")
    @Operation(summary = "多智能体报告", description = "数据分析 + 报告生成")
    public Mono<String> multiAgentReport(@RequestBody ChatRequest request) {
        return Mono.fromCallable(() -> {
            // Step 1: 数据分析师分析
            var analysisResult = dataAnalystAgent.analyze(request.getMessage());
            
            // Step 2: 报告生成器生成报告
            var report = reportGeneratorAgent.generate(analysisResult.getTextContent());
            
            return report.getTextContent();
        });
    }

}
