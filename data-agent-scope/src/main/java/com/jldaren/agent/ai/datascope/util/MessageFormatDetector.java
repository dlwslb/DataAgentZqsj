package com.jldaren.agent.ai.datascope.util;

import lombok.extern.slf4j.Slf4j;
import java.util.regex.Pattern;

/**
 * 消息格式检测工具类
 * 用于识别用户/助手消息的内容类型（text/markdown/html-report 等）
 */
@Slf4j
public class MessageFormatDetector {

    private static final Pattern MARKDOWN_LIST_PATTERN = Pattern.compile("(?m)^\\s*[-*+]\\s+");
    private static final Pattern MARKDOWN_ORDERED_LIST_PATTERN = Pattern.compile("(?m)^\\s*\\d+\\.\\s+");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile(".*\\[.+\\]\\(.+\\).*");
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile(".*!\\[.+\\]\\(.+\\).*");

    /**
     * 检测消息内容类型
     * @param content 消息内容
     * @return 类型标识: text | markdown | markdown-report | html-report
     */
    public String detect(String content) {
        if (content == null || content.isEmpty()) return "text";

        // HTML 报告优先检测（避免被 markdown 规则误判）
        if (containsAny(content, "$$$html-report", "<html", "</div>", "</p>", "<table", "<br")) {
            return "html-report";
        }
        // Markdown 报告标识
        if (containsAny(content, "$$$markdown-report", "report content:")) {
            return "markdown-report";
        }
        // 代码块
        if (content.contains("```")) return "markdown";
        // 标题（从大到小匹配）
        if (containsAny(content, "######", "#####", "####", "###", "##", "# ")) return "markdown";
        // 强调符号
        if (containsAny(content, "**", "__", "* ", "_ ")) return "markdown";
        // 无序/有序列表
        if (MARKDOWN_LIST_PATTERN.matcher(content).find() ||
                MARKDOWN_ORDERED_LIST_PATTERN.matcher(content).find()) {
            return "markdown";
        }
        // 链接或图片
        if (MARKDOWN_LINK_PATTERN.matcher(content).find() ||
                MARKDOWN_IMAGE_PATTERN.matcher(content).find()) {
            return "markdown";
        }
        return "text";
    }

    /**
     * 辅助方法：判断文本是否包含任意关键词
     */
    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}