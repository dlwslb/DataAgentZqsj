package com.jldaren.agent.ai.datascope.dto;

import lombok.Data;

/**
 * 结构化输出示例 - 天气报告
 */
@Data
public class WeatherReport {
    private String city;
    private String weather;
    private Integer temperature;
    private String humidity;
    private String recommendation;
}
