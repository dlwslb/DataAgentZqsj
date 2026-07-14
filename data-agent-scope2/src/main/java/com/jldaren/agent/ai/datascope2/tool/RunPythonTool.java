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
package com.jldaren.agent.ai.datascope2.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Python 数据分析工具（mock 实现）
 *
 * <p>生产环境方案：
 * <ul>
 *   <li>调 HarnessAgent 自带的 Sandbox（Docker / K8s / 本地子进程）</li>
 *   <li>复用 1.0 的 Python 执行器（PythonExecutionManager）</li>
 *   <li>沙箱隔离执行，避免 LLM 生成的代码影响宿主机</li>
 * </ul>
 */
@Slf4j
@Component
public class RunPythonTool {

    /**
     * 执行 Python 代码做数据分析
     *
     * @param code Python 代码（pandas / numpy / matplotlib 风格）
     * @param data 输入数据（List<Map>，会被转成 pandas DataFrame 注入到 df 变量）
     * @return 执行结果摘要
     */
    @Tool(name = "run_python",
          description = "在沙箱中执行 Python 代码做数据分析、统计、趋势计算。data 参数是 List<Map>，" +
                  "会自动转成 pandas DataFrame 注入到变量 df。code 中可使用 df，支持 pandas/numpy/matplotlib 语法。" +
                  "返回 dict，包含 'result'(计算结果)、'summary'(文字摘要)、'chart'(图表 base64，可选)",
          readOnly = false,
          concurrencySafe = true)
    public reactor.core.publisher.Mono<Map<String, Object>> runPython(
            @ToolParam(name = "code", description = "Python 代码，df 是输入数据") String code,
            @ToolParam(name = "data", description = "输入数据 List<Map<String,Object>>") List<Map<String, Object>> data) {
        log.info("🐍 [run_python] data.size={}, code.len={}", data == null ? 0 : data.size(), code == null ? 0 : code.length());
        return reactor.core.publisher.Mono.fromCallable(() -> doRunPython(code, data))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private Map<String, Object> doRunPython(String code, List<Map<String, Object>> data) {
        if (code == null || code.isBlank()) {
            return Map.of("error", "code 不能为空");
        }

        // mock：根据 code 关键字猜测返回类型
        Map<String, Object> result = new LinkedHashMap<>();
        String codeLower = code.toLowerCase();

        if (codeLower.contains("groupby") || codeLower.contains("agg") || codeLower.contains("sum")
                || codeLower.contains("count") || codeLower.contains("mean")) {
            // 聚合
            result.put("type", "aggregation");
            result.put("result", mockAggregation(data, codeLower));
        } else if (codeLower.contains("sort") || codeLower.contains("rank") || codeLower.contains("top")) {
            // 排序
            result.put("type", "ranking");
            result.put("result", mockRanking(data, codeLower));
        } else if (codeLower.contains("plot") || codeLower.contains("chart") || codeLower.contains("hist")) {
            // 图表
            result.put("type", "chart");
            result.put("result", mockChartSummary(data));
            result.put("chart", "[base64-png-placeholder]");
        } else {
            // 默认：直接返回数据摘要
            result.put("type", "summary");
            result.put("result", data == null ? List.of() : data.stream().limit(5).collect(Collectors.toList()));
        }

        result.put("summary", "已对 " + (data == null ? 0 : data.size()) + " 条数据执行 Python 分析");
        log.info("✅ [run_python] 完成: type={}", result.get("type"));
        return result;
    }

    private Map<String, Object> mockAggregation(List<Map<String, Object>> data, String code) {
        // 简化：按 region 分组统计 amount
        Map<String, Double> agg = new LinkedHashMap<>();
        if (data != null) {
            for (Map<String, Object> row : data) {
                String region = String.valueOf(row.getOrDefault("region", "未知"));
                Double amount = ((Number) row.getOrDefault("amount", 0)).doubleValue();
                agg.merge(region, amount, Double::sum);
            }
        }
        return new LinkedHashMap<>(agg);
    }

    private List<Map<String, Object>> mockRanking(List<Map<String, Object>> data, String code) {
        if (data == null) return List.of();
        return data.stream()
                .sorted((a, b) -> {
                    Double av = ((Number) a.getOrDefault("amount", 0)).doubleValue();
                    Double bv = ((Number) b.getOrDefault("amount", 0)).doubleValue();
                    return bv.compareTo(av);
                })
                .limit(10)
                .collect(Collectors.toList());
    }

    private Map<String, Object> mockChartSummary(List<Map<String, Object>> data) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("chart_type", "bar");
        summary.put("x_axis", "region");
        summary.put("y_axis", "amount");
        summary.put("data_points", data == null ? 0 : data.size());
        return summary;
    }
}
