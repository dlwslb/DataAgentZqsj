package com.jldaren.agent.ai.datascope.core.classifier;

import com.jldaren.agent.ai.datascope.enums.Intent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 轻量级意图分类器 - 规则驱动，支持平滑升级到模型
 *
 * 设计原则：
 * 1. 无状态，线程安全，可单例
 * 2. 匹配顺序：排除词 → 精确规则 → 关键词兜底
 * 3. 所有规则外部可配置（后续可改从 Nacos/DB 加载）
 *
 *   // 1. 分类意图----调用方法
 *   Intent intent = IntentClassifier.classify(actualQuestion);
 *   // 2. 根据意图决策（清晰！可测试！易扩展！）
 *   if (intent.needReport()) {
 *       skipReport = false;
 *       log.info("📊 [远程智能体] 意图[{}] 触发报告生成: {}", intent.getCode(), actualQuestion);
 *   } else {
 *       log.debug("🔄 [远程智能体] 意图[{}] 跳过报告: {}", intent.getCode(), actualQuestion);
 *   }
 */
@Slf4j
public class IntentClassifier {

    // ========== 排除词：用户「问报告」但「不是要生成」的场景 ==========
    private static final Set<String> EXCLUDE_PHRASES = Set.of(
            "report字段", "report参数", "什么是report", "report是什么意思",
            "报告模板", "报告格式", "报告怎么填", "报告怎么写",
            "怎么生成报告", "报告生成流程", "报告权限"  // 问方法/权限，不是要执行
    );

    // ========== 意图匹配规则：按优先级排序 ==========
    // 结构：Intent -> [规则列表]，规则支持「正则」或「关键词」
    private static final Map<Intent, List<IntentRule>> INTENT_RULES = new LinkedHashMap<>();

    static {
        // 📊 REPORT_GENERATE：报告生成类
        INTENT_RULES.put(Intent.REPORT_GENERATE, Arrays.asList(
                // 正则规则：动词+报告/分析/总结
                IntentRule.regex("(生成|出|写|制作|创建|导出|输出|产成|整理|给我|帮我).*?(报告|分析|总结|报表|report)", true),
                // 正则规则：英文表达
                IntentRule.regex("(generate|create|make|export).*report", true),
                // 关键词兜底：防止正则漏掉口语
                IntentRule.keyword("生成报告", "出报告", "写报告", "制作报告", "创建报告",
                        "导出报告", "输出报告", "报告生成", "生成分析",
                        "出一份", "写一份", "整理报告", "report", "generate report")
        ));

        // 🔍 DATA_QUERY：数据查询类
        INTENT_RULES.put(Intent.DATA_QUERY, Arrays.asList(
                IntentRule.regex("(查|查询|搜索|找|看看|看下|拉取).*?(数据|信息|记录|日志|结果)", true),
                IntentRule.regex("(query|search|find|get).*data", true),
                IntentRule.keyword("查数据", "查询信息", "搜索记录", "拉取日志", "看看结果")
        ));

        // ⚙️ CONFIG_UPDATE：配置管理类
        INTENT_RULES.put(Intent.CONFIG_UPDATE, Arrays.asList(
                IntentRule.regex("(配置|设置|修改|调整|更新|关闭|开启).*?(参数|任务|策略|规则)", true),
                IntentRule.keyword("配置任务", "修改参数", "调整策略", "更新规则")
        ));

        // 💬 CHAT：闲聊类（最后匹配，避免抢其他意图）
        INTENT_RULES.put(Intent.CHAT, Arrays.asList(
                IntentRule.keyword("你好", "您好", "hello", "hi", "在吗", "谢谢", "再见", "哈哈", "😄")
        ));
    }

    /**
     * 核心分类方法 - 线程安全，无状态
     * @param question 用户问题
     * @return 识别到的意图，无法识别返回 UNKNOWN
     */
    public static Intent classify(String question) {
        // 1. 空指针/空串防护
        if (question == null || (question = question.trim()).isEmpty()) {
            return Intent.UNKNOWN;
        }

        String lowerQ = question.toLowerCase();

        // 2. 【优先】排除词过滤：问报告但不是要生成
        for (String exclude : EXCLUDE_PHRASES) {
            if (lowerQ.contains(exclude.toLowerCase())) {
                log.debug("🔍 [意图排除] 命中排除词[{}]: {}", exclude, question);
                return Intent.UNKNOWN;
            }
        }

        // 3. 【核心】按优先级匹配意图规则
        for (Map.Entry<Intent, List<IntentRule>> entry : INTENT_RULES.entrySet()) {
            Intent intent = entry.getKey();
            for (IntentRule rule : entry.getValue()) {
                if (rule.matches(lowerQ)) {
                    log.debug("🎯 [意图识别] 命中[{}]: {} -> {}", intent.getCode(), question, rule);
                    return intent;
                }
            }
        }

        // 4. 【兜底】无法识别
        log.debug("❓ [意图未知] 无法识别: {}", question);
        return Intent.UNKNOWN;
    }

    /**
     * 内部规则类 - 封装正则/关键词匹配逻辑
     */
    @AllArgsConstructor
    private static class IntentRule {
        private final RuleType type;
        private final Object pattern;  // Pattern 或 Set<String>
        private final String desc;     // 用于日志

        /** 创建正则规则 */
        static IntentRule regex(String regex, boolean caseInsensitive) {
            int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
            return new IntentRule(RuleType.REGEX, Pattern.compile(regex, flags), regex);
        }

        /** 创建关键词规则（支持多个关键词，任一命中即匹配） */
        static IntentRule keyword(String... keywords) {
            return new IntentRule(RuleType.KEYWORD, new HashSet<>(Arrays.asList(keywords)),
                    "keywords:" + Arrays.toString(keywords));
        }

        boolean matches(String question) {
            if (type == RuleType.REGEX) {
                return ((Pattern) pattern).matcher(question).find();
            } else {
                Set<String> keywords = (Set<String>) pattern;
                return keywords.stream().anyMatch(k -> question.contains(k.toLowerCase()));
            }
        }

        @Override
        public String toString() {
            return desc;
        }
    }

    private enum RuleType { REGEX, KEYWORD }
}