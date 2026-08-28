/*
 * Copyright 2024-2026 the original author or authors.
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
package com.alibaba.cloud.ai.dataagent.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * service_period 智能解析器（用于 search_expiring_projects）。
 *
 * 现实数据五花八门（用户截图样本）：
 *   A  "2025年09月30日 至 2026年07月31日"                              → 2026-07-31
 *   B  "签订合同之日起至2025年12月31日"                                  → 2025-12-31
 *   B  "自合同签订之日起（含当日）至2027年12月31日止"                     → 2027-12-31
 *   C  "一年" / "两年" / "三年" / "三年"                                → pub + 1/2/3 年
 *   C  "6个月" / "12个月" / "21个月" / "26个月" / "27个月" / "36个月" → pub + N 个月
 *   C  "30天" / "45天" / "90天" / "365天" / "730天" / "1075天" / "1825天" → pub + N 天
 *   C  "50个工作日" / "30个工作日"                                       → pub + N*7/5 天（约）
 *   D  "自合同签订之日起服务期限24个月"                                   → pub + 24个月
 *   D  "前置机运维服务1年，软件运维服务3年"                              → 取最短（pub + 1年）
 *   E  解析失败 → 由调用方选 fallback（如 publish_time）
 *
 * 设计：单纯函数，无副作用，返回 ParseResult。
 */
public final class ServicePeriodParser {

    private ServicePeriodParser() {}

    public enum Source { DOUBLE_DATE, END_DATE, RELATIVE, JSON_END, JSON_DAYS, NONE }

    public static final class ParseResult {
        /** 解析得到的"项目到期日"（expire_date）。可能为 null（兜底场景）。 */
        public final LocalDate expireDate;
        /** 用了哪种规则？ */
        public final Source source;
        /** 解析是否成功。false = 走 fallback。 */
        public final boolean parsed;

        public ParseResult(LocalDate expireDate, Source source, boolean parsed) {
            this.expireDate = expireDate;
            this.source = source;
            this.parsed = parsed;
        }

        public static ParseResult empty() {
            return new ParseResult(null, Source.NONE, false);
        }
    }

    /** A 档：YYYY年MM月DD日 至 YYYY年MM月DD日 */
    private static final Pattern P_DOUBLE = Pattern.compile(
            "(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*[至到~—\\-]\\s*(\\d{4})年(\\d{1,2})月(\\d{1,2})日");

    /** B 档：单日期（"至" / "止" / "前" 等结尾词前面的日期） */
    private static final Pattern P_SINGLE_END = Pattern.compile(
            "至|止(?:之日|当日|当日为止)?|前|(?:签订|签署|生效)(?:合同)?(?:之)?日(?:起)?(?:之)?(?:日)?(?:后)?[，,：:]?\\s*(?:含当日)?(?:至)?\\s*(\\d{4})年(\\d{1,2})月(\\d{1,2})日");

    /** 兜底匹配：单日期出现在字符串后面 */
    private static final Pattern P_ANY_DATE = Pattern.compile(
            "(\\d{4})年(\\d{1,2})月(\\d{1,2})日");

    /** C 档：N 年/月/天/工作日（被"自...起"/"签订合同"/"服务期"等词围着） */
    private static final Pattern P_N_YEAR = Pattern.compile("(\\d+)\\s*年(?:服务期|期限|内)?");
    private static final Pattern P_N_MONTH = Pattern.compile("(\\d+)\\s*个?月(?:服务期|期限|内)?");
    private static final Pattern P_N_DAY = Pattern.compile("(\\d+)\\s*个?天");
    private static final Pattern P_N_WORK_DAY = Pattern.compile("(\\d+)\\s*个?工作日");

    /** JSON 档：精确解析 {"service_end":"YYYY-MM-DD","service_start":"YYYY-MM-DD","service_days":N} */
    private static final Pattern P_JSON_KEY = Pattern.compile(
            "\"(service_end|service_start|service_days)\"\\s*:\\s*(\"?[^\",}\\s]*\"?\\d*)");
    private static final DateTimeFormatter JSON_DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * 解析 service_period 字符串，结合 publish_time 做相对计算的锚点。
     *
     * @param raw service_time 原始字符串（null/blank → empty）
     * @param publishTime 项目发布日期（C 档相对单位的起点；可为空，空则空结果）
     */
    public static ParseResult parse(String raw, LocalDate publishTime) {
        if (raw == null) return ParseResult.empty();
        String s = raw.trim();
        if (s.isEmpty()) return ParseResult.empty();

        // ---------- JSON 档（winner 表 service_period 是 JSON 字符串）----------
        // 形如 {"service_end":"2026-07-31","service_start":"2025-09-30","service_days":840}
        if (s.startsWith("{") && s.endsWith("}")) {
            ParseResult jr = parseJson(s);
            if (jr != null && jr.parsed) return jr;
            // JSON 但字段全空 → 走 fallback
            if (jr != null) return ParseResult.empty();
        }

        // ---------- A 档：双日期 ----------
        Matcher m = P_DOUBLE.matcher(s);
        if (m.find()) {
            LocalDate end = dateOf(m.group(4), m.group(5), m.group(6));
            if (end != null) return new ParseResult(end, Source.DOUBLE_DATE, true);
        }

        // ---------- B 档：单日期 ----------
        // 优先匹配"至/止/前"后面的日期
        Matcher m2 = P_SINGLE_END.matcher(s);
        if (m2.find()) {
            int gi = m2.groupCount();
            // group 1,2,3 是日期
            // 但 P_SINGLE_END 用了一个非捕获组前置 + 1 个捕获组，所以日期在 group(1/2/3)
            LocalDate end = dateOf(m2.group(1), m2.group(2), m2.group(3));
            if (end != null) return new ParseResult(end, Source.END_DATE, true);
        }
        // 兜底：取最后一个 YYYY年MM月DD日 作为截止日
        Matcher mTail = P_ANY_DATE.matcher(s);
        LocalDate last = null;
        while (mTail.find()) {
            LocalDate d = dateOf(mTail.group(1), mTail.group(2), mTail.group(3));
            if (d != null) last = d;
        }
        // 但要排除"自 YYYY 至 YYYY"——只有 1 个日期时才信任
        if (last != null && !m.find()) {
            // 已被 P_DOUBLE 排除过一次，重新看
            Matcher onlyOne = P_DOUBLE.matcher(s);
            if (!onlyOne.find() && s.indexOf("至") < 0 && s.indexOf("止") < 0) {
                return new ParseResult(last, Source.END_DATE, true);
            }
            if (!onlyOne.find()) {
                return new ParseResult(last, Source.END_DATE, true);
            }
        }

        // ---------- C/D 档：N 年 / 月 / 天 ----------
        if (publishTime == null) {
            return ParseResult.empty();
        }

        // 收集所有候选期限，取最小的（最严格 = 最先到期）
        List<Integer> candidateDays = new ArrayList<>();

        Matcher my = P_N_YEAR.matcher(s);
        while (my.find()) {
            int n = Integer.parseInt(my.group(1));
            if (n > 0 && n <= 20) { // 上限 20 年防止脏数据
                candidateDays.add((int) Math.round(n * 365.25));
            }
        }
        Matcher mm = P_N_MONTH.matcher(s);
        while (mm.find()) {
            int n = Integer.parseInt(mm.group(1));
            if (n > 0 && n <= 240) { // 上限 20 年
                candidateDays.add((int) Math.round(n * 30.44));
            }
        }
        Matcher md = P_N_DAY.matcher(s);
        while (md.find()) {
            int n = Integer.parseInt(md.group(1));
            if (n > 0 && n <= 3650) { // 上限 10 年
                candidateDays.add(n);
            }
        }
        Matcher mwd = P_N_WORK_DAY.matcher(s);
        while (mwd.find()) {
            int n = Integer.parseInt(mwd.group(1));
            if (n > 0 && n <= 720) { // 上限 3 年
                // 工作日 ≈ 实际天数 * 7 / 5（含周末）
                candidateDays.add((int) Math.round(n * 1.4));
            }
        }

        if (!candidateDays.isEmpty()) {
            // 取最小天数 = 最先到期（D 档多段时取最短）
            int minDays = candidateDays.stream().mapToInt(Integer::intValue).min().orElse(0);
            if (minDays > 0 && minDays <= 3650) {
                return new ParseResult(publishTime.plusDays(minDays), Source.RELATIVE, true);
            }
        }

        // ---------- 没解析出来 ----------
        return ParseResult.empty();
    }

    private static LocalDate dateOf(String y, String mo, String d) {
        try {
            int year = Integer.parseInt(y);
            int month = Integer.parseInt(mo);
            int day = Integer.parseInt(d);
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * JSON 档解析：从 {"service_end":"...","service_start":"...","service_days":N} 提取 expire_date。
     *
     * 优先级：
     *   1) service_end 有效 → 直接用它（精确日期）
     *   2) service_end 无 → service_start + service_days（兜底精确计算）
     *   3) 两个都空 → 返回 null（让外层走 FALLBACK）
     *
     * 返回 null 表示字段缺失；返回 parsed=false 表示虽然识别到 JSON 但无有效日期。
     */
    private static ParseResult parseJson(String s) {
        LocalDate endDate = null;
        LocalDate startDate = null;
        Integer days = null;

        Matcher m = P_JSON_KEY.matcher(s);
        while (m.find()) {
            String key = m.group(1);
            String rawVal = m.group(2).replace("\"", "").trim();
            if (rawVal.isEmpty()) continue;
            try {
                if ("service_end".equals(key)) {
                    endDate = LocalDate.parse(rawVal, JSON_DATE_FMT);
                } else if ("service_start".equals(key)) {
                    startDate = LocalDate.parse(rawVal, JSON_DATE_FMT);
                } else if ("service_days".equals(key)) {
                    days = Integer.parseInt(rawVal);
                }
            } catch (Exception ignore) {
                // 字段值不合法，跳过
            }
        }

        if (endDate != null) {
            return new ParseResult(endDate, Source.JSON_END, true);
        }
        if (startDate != null && days != null && days > 0) {
            return new ParseResult(startDate.plusDays(days), Source.JSON_DAYS, true);
        }
        if (startDate != null || endDate != null || days != null) {
            // 至少识别到部分字段但不可用
            return new ParseResult(null, Source.JSON_DAYS, false);
        }
        // 完全没识别到任何字段
        return null;
    }
}
