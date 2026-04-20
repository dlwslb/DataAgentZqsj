package com.jldaren.agent.ai.datascope.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
public class WeatherTool {

    @Tool(name = "get_weather", description = "查询城市天气信息（示例工具）")
    public Mono<String> getWeather(
            @ToolParam(name = "city", description = "城市名称，如: 北京", required = true) String city) {

        log.info("查询天气: {}", city);

        // 模拟异步调用外部天气 API
        return Mono.fromCallable(() -> {
            // TODO: 替换为真实 API 调用，如: 高德/和风天气
            return switch (city) {
                case "北京" -> "北京: 晴, 25°C, 空气质量优";
                case "上海" -> "上海: 多云, 22°C, 湿度 65%";
                case "深圳" -> "深圳: 小雨, 28°C, 建议带伞";
                default -> String.format("%s: 暂无天气数据，请尝试其他城市", city);
            };
        }).subscribeOn(Schedulers.boundedElastic());  // 避免阻塞反应式线程
    }
}