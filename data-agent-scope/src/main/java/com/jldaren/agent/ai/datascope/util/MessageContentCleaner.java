package com.jldaren.agent.ai.datascope.util;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 消息内容清理工具类
 * 用于从 RAG/长期记忆增强的消息中提取原始用户问题
 * dlw
 */
@Slf4j
public class MessageContentCleaner {

    // ---- 去噪正则（预编译避免重复编译）----
    private static final Pattern LONG_TERM_MEMORY_TAG = Pattern.compile("<long_term_memory>[\\s\\S]*?</long_term_memory>");
    private static final Pattern USER_HISTORY_MEMORY = Pattern.compile("【用户历史记忆】[\\s\\S]*?(?=【(?:当前问题|用户问题)】)");
    private static final Pattern RAG_KNOWLEDGE = Pattern.compile("【相关知识参考】[\\s\\S]*?(?=【(?:用户问题|当前问题)】)");
    private static final Pattern QUESTION_TAG = Pattern.compile("【(?:当前问题|用户问题)】");
    private static final Pattern HINT_PHRASE_1 = Pattern.compile("请结合上述(?:历史记忆|相关知识).*?回答.*?。");
    private static final Pattern HINT_PHRASE_2 = Pattern.compile("如果(?:历史记忆|知识与问题).*?直接回答。");
    private static final Pattern SEPARATOR = Pattern.compile("\\n\\s*\\n\\s*\\n");
    private static final Pattern EXTRACT_QUESTION = Pattern.compile("【(?:用户问题|当前问题)】\\s*([\\s\\S]+)");
    private static final Pattern TRAILING_HINT = Pattern.compile("请结合上述.*$");

    /**
     * 清除文本中被注入的噪声内容
     * RAG/记忆增强时注入的上下文不应作为记忆内容存储
     *
     * @param text 原始文本（可能包含 RAG 知识、长期记忆等噪声）
     * @return 清理后的文本（只保留用户原始问题）
     */
    public static String cleanNoiseFromText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String cleaned = text;
        cleaned = LONG_TERM_MEMORY_TAG.matcher(cleaned).replaceAll("");
        cleaned = USER_HISTORY_MEMORY.matcher(cleaned).replaceAll("");
        cleaned = RAG_KNOWLEDGE.matcher(cleaned).replaceAll("");
        cleaned = QUESTION_TAG.matcher(cleaned).replaceAll("");
        cleaned = HINT_PHRASE_1.matcher(cleaned).replaceAll("");
        cleaned = HINT_PHRASE_2.matcher(cleaned).replaceAll("");
        cleaned = SEPARATOR.matcher(cleaned).replaceAll("\n");

        return cleaned.trim();
    }

    /**
     * 从被增强的消息中提取用户的原始问题
     * 优先使用【用户问题】标签后的内容，如果没有则使用去噪后的全文
     *
     * @param text 增强后的消息文本
     * @return 用户原始问题
     */
    public static String extractOriginalQuestion(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // 尝试提取【用户问题】或【当前问题】之后的内容
        java.util.regex.Matcher userQuestionMatcher = EXTRACT_QUESTION.matcher(text);
        if (userQuestionMatcher.find()) {
            String question = userQuestionMatcher.group(1).trim();
            question = TRAILING_HINT.matcher(question).replaceAll("").trim();
            if (!question.isEmpty()) {
                return question;
            }
        }

        // 如果没有匹配到标记，使用 cleanNoiseFromText 去噪
        String cleaned = cleanNoiseFromText(text);

        // 过长的 query 影响向量匹配质量，截断
        if (cleaned.length() > 200) {
            cleaned = cleaned.substring(0, 200);
        }

        return cleaned;
    }
}
