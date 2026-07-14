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
 * 报告格式化工具
 */
@Slf4j
@Component
public class FormatReportTool {

    /**
     * 把数据格式化成 Markdown 报告
     *
     * @param title    报告标题
     * @param data     数据
     * @param template 模板: table(表格) / list(列表) / summary(摘要)
     * @return Markdown 字符串
     */
    @Tool(name = "format_report",
          description = "把数据格式化成 Markdown 报告。template 支持: table(表格) / list(列表) / summary(摘要)。" +
                  "data 是 List<Map<String,Object>>，每个 map 是一行。",
          readOnly = true,
          concurrencySafe = true)
    public reactor.core.publisher.Mono<String> formatReport(
            @ToolParam(name = "title", description = "报告标题") String title,
            @ToolParam(name = "data", description = "数据 List<Map>") List<Map<String, Object>> data,
            @ToolParam(name = "template", description = "模板: table / list / summary") String template) {
        log.info("📝 [format_report] title={}, data.size={}, template={}",
                title, data == null ? 0 : data.size(), template);
        return reactor.core.publisher.Mono.fromCallable(() -> doFormatReport(title, data, template))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private String doFormatReport(String title, List<Map<String, Object>> data, String template) {
        if (data == null || data.isEmpty()) {
            return "## " + (title == null ? "数据报告" : title) + "\n\n> 暂无数据\n";
        }

        String tpl = template == null ? "table" : template.toLowerCase();
        StringBuilder md = new StringBuilder();

        switch (tpl) {
            case "table" -> renderTable(md, title, data);
            case "list" -> renderList(md, title, data);
            case "summary" -> renderSummary(md, title, data);
            default -> renderTable(md, title, data);
        }

        String result = md.toString();
        log.info("✅ [format_report] 生成报告, 长度={}", result.length());
        return result;
    }

    private void renderTable(StringBuilder md, String title, List<Map<String, Object>> data) {
        md.append("## ").append(title == null ? "数据报告" : title).append("\n\n");
        // 表头
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> row : data) {
            keys.addAll(row.keySet());
        }
        List<String> keyList = new ArrayList<>(keys);
        md.append("| ").append(String.join(" | ", keyList)).append(" |\n");
        md.append("|").append(keyList.stream().map(k -> "---").collect(Collectors.joining("|"))).append("|\n");
        // 数据行
        for (Map<String, Object> row : data) {
            List<String> values = keyList.stream()
                    .map(k -> String.valueOf(row.getOrDefault(k, "")))
                    .collect(Collectors.toList());
            md.append("| ").append(String.join(" | ", values)).append(" |\n");
        }
        md.append("\n> 共 ").append(data.size()).append(" 条数据\n");
    }

    private void renderList(StringBuilder md, String title, List<Map<String, Object>> data) {
        md.append("## ").append(title == null ? "数据报告" : title).append("\n\n");
        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = data.get(i);
            md.append(i + 1).append(". ");
            row.forEach((k, v) -> md.append("**").append(k).append("**: ").append(v).append("; "));
            md.append("\n");
        }
    }

    private void renderSummary(StringBuilder md, String title, List<Map<String, Object>> data) {
        md.append("## ").append(title == null ? "数据摘要" : title).append("\n\n");
        md.append("- 数据条数: **").append(data.size()).append("**\n");
        if (!data.isEmpty()) {
            Map<String, Object> first = data.get(0);
            md.append("- 字段数: **").append(first.size()).append("**\n");
            md.append("- 关键字段: ").append(String.join(", ", first.keySet())).append("\n");
        }
    }
}
