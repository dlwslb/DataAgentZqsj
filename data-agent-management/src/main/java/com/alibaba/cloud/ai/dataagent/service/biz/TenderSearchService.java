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
package com.alibaba.cloud.ai.dataagent.service.biz;

import com.alibaba.cloud.ai.dataagent.entity.biz.*;
import com.alibaba.cloud.ai.dataagent.mapper.biz.*;
import com.alibaba.cloud.ai.dataagent.util.ServicePeriodParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * tender-search skill 后端实现
 *
 * <p>本服务实现 tender-search 描述的 16 个工具的查询/详情逻辑。
 * 数据来源是公司内部 4 张表（bid_biz_bidding / bid_biz_win_bid / bid_biz_prepose / bid_biz_purchase_intention），
 * 通过 MyBatis 查 DB 后做字段映射，对齐 tender-search SKILL.md（deer-flow/skills/public/tender-search/）定义的响应格式。
 *
 * <h3>⚠️ 当前实现范围（v0.1 极简版）</h3>
 * <ul>
 *   <li>✅ 16 个 endpoint 全部 stub 实现（占位返回）</li>
 *   <li>✅ {@code search_bids} / {@code get_bid_detail} 真正查 4 张表 + 字段映射</li>
 *   <li>✅ {@code search_bids} 支持：keywords 多值 OR 匹配 / 真实计数 total+total_distinct /
 *       stats=true 服务端聚合（by_date / by_city / top_winners）/ fields 字段裁剪 / page 翻页</li>
 *   <li>✅ {@code match_modes} 高级过滤：单值表锁（bidding/winner/prepose/purchaseIntention，覆盖 bid_type 选表）或
 *       单值字段路由（title/product/keywords/tenderer/win_tenderer，路由 advancedWhere 关键词匹配字段，win_tenderer 仅中标表支持）；
 *       多值 / fulltext / 未知值回退全字段 OR</li>
 *   <li>⚠️ {@code bid_process} stage 字段：由调用方传入；本服务按 bid_type 推断（bidding=4, bid_winner=7, prepose=2, purchase_intention=1）</li>
 *   <li>⚠️ 9 个企业/市场分析类工具（{@code search_company} / {@code get_company_profile} / {@code find_competitors} 等）：
 *       暂未实现，stub 返回空 list；待后端同事补全</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenderSearchService {

    private final BidBiddingMapper biddingMapper;
    private final BidWinnerMapper winnerMapper;
    private final BidPreposeMapper preposeMapper;
    private final BidPurchaseIntentionMapper purchaseIntentionMapper;

    private static final BigDecimal WAN_SCALE = new BigDecimal("10000");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** match_modes 字段路由 token（单值生效；多值/fulltext/未知值回退全字段 OR） */
    private static final Set<String> MATCH_FIELD_TOKENS = new HashSet<>(Arrays.asList(
            "title", "product", "keywords", "tenderer", "win_tenderer"));
    /** match_modes 表锁 token（兼容旧用法：覆盖 bid_type 的表选择） */
    private static final Set<String> TABLE_LOCK_TOKENS = new HashSet<>(Arrays.asList(
            "bidding", "winner", "prepose", "purchaseIntention"));

    // ============================================================
    // 1. 标讯搜索类（4 个）
    // ============================================================

    /** search_bids：常规搜索，按 keywords + bid_type 分流到 4 张表 */
    public Map<String, Object> searchBids(Map<String, Object> req) {
        String bidType = strOr(req, "bid_type", "全部");
        List<String> keywords = listOr(req, "keywords");
        List<String> matchModes = listOr(req, "match_modes");
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        String beginDate = strOr(req, "begin_date", null);
        String endDate = strOr(req, "end_date", null);
        BigDecimal minAmount = numOr(req, "min_amount");
        BigDecimal maxAmount = numOr(req, "max_amount");
        int page = Math.max(1, intOr(req, "page", 1));
        int pageSize = Math.min(Math.max(intOr(req, "page_size", 20), 1), 500);
        boolean withStats = boolOr(req, "stats", false);
        List<String> fields = listOr(req, "fields");

        List<Map<String, Object>> items = new ArrayList<>();
        long total = 0;
        long distinctTotal = 0;
        // bid_type 决定查哪几张表
        // "全部"（或 null/空）→ 四张表都查；"采购"→ purchase_intention；"商机"→ prepose；"招标"→ bidding；"中标"→ winner
        boolean wantAll = bidType == null || bidType.isEmpty() || "全部".equals(bidType);
        boolean wantPrepose = wantAll || "商机".equals(bidType);
        boolean wantPurchaseIntention = wantAll || "采购".equals(bidType);
        boolean wantBidding = wantAll || "招标".equals(bidType);
        boolean wantWinner = wantAll || "中标".equals(bidType);
        String matchField = resolveMatchField(matchModes);
        if (matchModes != null && matchModes.size() == 1 && TABLE_LOCK_TOKENS.contains(matchModes.get(0))) {
            // match_modes 单值表锁 token（bidding/winner/prepose/purchaseIntention）覆盖 bid_type 的表选择；
            // 字段路由 token（title/product/keywords/tenderer/win_tenderer）不影响选表，仅路由关键词匹配字段
            wantPrepose = "prepose".equals(matchModes.get(0));
            wantPurchaseIntention = "purchaseIntention".equals(matchModes.get(0));
            wantBidding = "bidding".equals(matchModes.get(0));
            wantWinner =  "winner".equals(matchModes.get(0));
        }

        // keywords 走 listByAdvanced 的 OR 组（组内任一关键词命中即匹配），修复旧版多关键词空格拼接不命中问题；
        // 单关键词行为与旧版等价（额外多匹配 tenderer 字段，属于拓宽不收窄）
        List<List<String>> effectiveGroups = keywords.isEmpty()
                ? Collections.emptyList()
                : Collections.singletonList(keywords);
        int offset = (page - 1) * pageSize;
        // 多表（如 bid_type=全部）时不能各自独立翻页：每表多取 offset+pageSize 条，合并后全局按时间倒序再截取当前页
        int tableCount = (wantBidding ? 1 : 0) + (wantWinner ? 1 : 0)
                + (wantPurchaseIntention ? 1 : 0) + (wantPrepose ? 1 : 0);
        boolean multiTable = tableCount > 1;
        int fetchOffset = multiTable ? 0 : offset;
        int fetchLimit = multiTable ? offset + pageSize : pageSize;

        if (wantBidding) {
            List<BidBiddingEntity> rows = biddingMapper.listByAdvanced(
                    matchField, provinces, effectiveGroups, null, minAmount, maxAmount, beginDate, endDate, fetchOffset, fetchLimit);
            Map<String, Object> c = biddingMapper.countByAdvanced(
                    matchField, provinces, effectiveGroups, null, minAmount, maxAmount, beginDate, endDate);
            total += numLong(c.get("total"));
            distinctTotal += numLong(c.get("distinct_total"));
            for (BidBiddingEntity e : rows) {
                items.add(toBidItem(e, 4, "招标"));
            }
        }

        if (wantWinner) {
            List<BidWinnerEntity> rows = winnerMapper.listByAdvanced(
                    matchField, provinces, effectiveGroups, null, minAmount, maxAmount, beginDate, endDate, fetchOffset, fetchLimit);
            Map<String, Object> c = winnerMapper.countByAdvanced(
                    matchField, provinces, effectiveGroups, null, minAmount, maxAmount, beginDate, endDate);
            total += numLong(c.get("total"));
            distinctTotal += numLong(c.get("distinct_total"));
            for (BidWinnerEntity e : rows) {
                items.add(toBidItem(e, 7, "中标"));
            }
        }

        if (wantPurchaseIntention) {
            // 采购意向表：标的物 = product（取前 5 个逗号分隔 token 已在 XML SUBSTRING_INDEX 处理）
            List<BidPurchaseIntentionEntity> rows = purchaseIntentionMapper.listByAdvanced(
                    matchField, provinces, effectiveGroups, null, minAmount, maxAmount, beginDate, endDate, fetchOffset, fetchLimit);
            Map<String, Object> c = purchaseIntentionMapper.countByAdvanced(
                    matchField, provinces, effectiveGroups, null, minAmount, maxAmount, beginDate, endDate);
            total += numLong(c.get("total"));
            distinctTotal += numLong(c.get("distinct_total"));
            for (BidPurchaseIntentionEntity e : rows) {
                items.add(toBidItem(e, 1, "采购"));
            }
        }

        if (wantPrepose) {
            // 拟在建项目表：金额在 toBidItem 里以空字符串返回（prepose 是项目储备，金额字段不严格）
            List<BidPreposeEntity> rows = preposeMapper.listByAdvanced(
                    matchField, provinces, effectiveGroups, null, minAmount, maxAmount, beginDate, endDate, fetchOffset, fetchLimit);
            Map<String, Object> c = preposeMapper.countByAdvanced(
                    matchField, provinces, effectiveGroups, null, minAmount, maxAmount, beginDate, endDate);
            total += numLong(c.get("total"));
            distinctTotal += numLong(c.get("distinct_total"));
            for (BidPreposeEntity e : rows) {
                items.add(toBidItem(e, 2, "商机"));
            }
        }

        // 多表合并时全局按发布时间倒序（否则 items 跨表分段会破坏倒序语义），再截取当前页
        if (multiTable) {
            items.sort((a, b) -> {
                String ta = a.get("pub_time") == null ? "" : a.get("pub_time").toString();
                String tb = b.get("pub_time") == null ? "" : b.get("pub_time").toString();
                return tb.compareTo(ta);
            });
            int from = Math.min(offset, items.size());
            int to = Math.min(offset + pageSize, items.size());
            items = new ArrayList<>(items.subList(from, to));
        }

        // 字段裁剪：调用方传 fields 时只保留指定字段（减小响应体积，降低 agent 上下文占用）
        if (!fields.isEmpty()) {
            Set<String> keep = new LinkedHashSet<>(fields);
            for (Map<String, Object> item : items) {
                item.keySet().retainAll(keep);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("total_distinct", distinctTotal);
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("items", items);
        if (withStats) {
            data.put("stats", buildSearchStats(matchField, provinces, effectiveGroups, minAmount, maxAmount, beginDate, endDate,
                    wantBidding, wantWinner, wantPurchaseIntention, wantPrepose));
        }
        data.put("guide",
                "total=公告条数、total_distinct=按(发布日期+项目名+地市+区县[+中标方])去重后的项目数，均为 SQL 精确计数——直接引用，"
                        + "禁止全量拉取或重复调用核验条数；"
                        + "bid_type=全部 时 items 为四表合并后按 publish_time 全局倒序，翻页传 page/page_size；"
                        + "keywords 多值为 OR 关系（任一命中即返回），"
                        + "无需按关键词逐个循环调用；"
                        + "传 stats=true 可一次拿到 by_date（按日分布）/ by_city（按地市分布）/ top_winners（中标方次数与金额聚合），"
                        + "均为服务端聚合结果，直接引用写报告，禁止用脚本对 items 重算统计或对已算过的分布做二次确认查询；"
                        + "传 fields 可只保留指定字段以减小响应体积（如 fields=[pub_time,city,county,sm_names]）。");
        return success(data);
    }

    /**
     * 服务端聚合统计（search_bids stats=true 时返回）：by_date / by_city / top_winners。
     * 让 agent 直接引用写报告，替代全量拉取后用脚本自行聚合的模式。
     */
    private Map<String, Object> buildSearchStats(String matchField, List<String> provinces, List<List<String>> keywordGroups,
                                                 BigDecimal minAmount, BigDecimal maxAmount,
                                                 String beginDate, String endDate,
                                                 boolean wantBidding, boolean wantWinner,
                                                 boolean wantPurchaseIntention, boolean wantPrepose) {
        // by_date 的按日分布条数上限：默认 90 天；显式传了 begin_date 时按实际跨度扩展，避免长区间分布被静默截断
        int dateLimit = resolveDateStatLimit(beginDate, endDate);
        Map<String, Long> byDate = new TreeMap<>(Collections.reverseOrder());
        Map<String, Long> byCity = new LinkedHashMap<>();
        if (wantBidding) {
            mergeDateStats(byDate, biddingMapper.statsByDate(matchField, provinces, keywordGroups, null, minAmount, maxAmount, beginDate, endDate, dateLimit));
            mergeCityStats(byCity, biddingMapper.statsByCity(matchField, provinces, keywordGroups, null, minAmount, maxAmount, beginDate, endDate, 50));
        }
        if (wantWinner) {
            mergeDateStats(byDate, winnerMapper.statsByDate(matchField, provinces, keywordGroups, null, minAmount, maxAmount, beginDate, endDate, dateLimit));
            mergeCityStats(byCity, winnerMapper.statsByCity(matchField, provinces, keywordGroups, null, minAmount, maxAmount, beginDate, endDate, 50));
        }
        if (wantPurchaseIntention) {
            mergeDateStats(byDate, purchaseIntentionMapper.statsByDate(matchField, provinces, keywordGroups, null, minAmount, maxAmount, beginDate, endDate, dateLimit));
            mergeCityStats(byCity, purchaseIntentionMapper.statsByCity(matchField, provinces, keywordGroups, null, minAmount, maxAmount, beginDate, endDate, 50));
        }
        if (wantPrepose) {
            mergeDateStats(byDate, preposeMapper.statsByDate(matchField, provinces, keywordGroups, null, minAmount, maxAmount, beginDate, endDate, dateLimit));
            mergeCityStats(byCity, preposeMapper.statsByCity(matchField, provinces, keywordGroups, null, minAmount, maxAmount, beginDate, endDate, 50));
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("by_date", byDate);
        stats.put("by_city", sortMapByValueDesc(byCity));
        if (wantWinner) {
            List<Map<String, Object>> topWinners = new ArrayList<>();
            for (Map<String, Object> row : winnerMapper.aggregateWinners(
                    matchField, provinces, keywordGroups, null, minAmount, maxAmount, beginDate, endDate, 20)) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("winner", strOf(row, "winner"));
                w.put("cnt", numLong(row.get("cnt")));
                BigDecimal amountYuan = toBigDecimal(row.get("amount_yuan"));
                w.put("amount_wan", amountYuan == null ? null : yuanToWan(amountYuan));
                topWinners.add(w);
            }
            stats.put("top_winners", topWinners);
        }
        stats.put("note", "by_date/by_city 为未去重公告数；top_winners 仅统计中标数据，按 win_tenderer 分组，金额单位万元");
        return stats;
    }

    /**
     * stats.by_date 的按日分布条数上限：默认 90 天；显式传了 begin_date 时按实际跨度扩展（+31 天余量），
     * 避免长区间查询时按日分布被静默截断。
     */
    private static int resolveDateStatLimit(String beginDate, String endDate) {
        if (beginDate == null || beginDate.isBlank()) {
            return 90;
        }
        try {
            LocalDate b = LocalDate.parse(beginDate.trim(), DATE_FMT);
            LocalDate e = (endDate == null || endDate.isBlank())
                    ? LocalDate.now() : LocalDate.parse(endDate.trim(), DATE_FMT);
            long span = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(b, e));
            return (int) Math.min(1000, span + 31);
        } catch (Exception ex) {
            return 90;
        }
    }

    /** 合并按日统计行（date_key/cnt）到累计 map */
    private static void mergeDateStats(Map<String, Long> byDate, List<Map<String, Object>> rows) {
        if (rows == null) return;
        for (Map<String, Object> r : rows) {
            String k = strOf(r, "date_key");
            if (k == null || k.isEmpty()) continue;
            byDate.merge(k, numLong(r.get("cnt")), Long::sum);
        }
    }

    /** 合并按地市统计行（province/city/cnt）到累计 map */
    private static void mergeCityStats(Map<String, Long> byCity, List<Map<String, Object>> rows) {
        if (rows == null) return;
        for (Map<String, Object> r : rows) {
            String p = strOf(r, "province");
            String c = strOf(r, "city");
            String k = (p == null || p.isEmpty() ? "未知" : p) + "·" + (c == null || c.isEmpty() ? "未知" : c);
            byCity.merge(k, numLong(r.get("cnt")), Long::sum);
        }
    }

    /** 按 value 降序重建 LinkedHashMap */
    private static Map<String, Long> sortMapByValueDesc(Map<String, Long> src) {
        return src.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * match_modes → advancedWhere 关键词字段路由（XML choose/when 的 matchField）。
     * 仅单值 token 生效：title/product/keywords/tenderer/win_tenderer；
     * 多值、fulltext、空值、未知值一律返回 null（全字段 OR，避免静默空结果）。
     * win_tenderer 仅中标表存在，其余表的 XML 无该 when 分支会自动走 otherwise 全字段 OR，无需按表特判。
     */
    private static String resolveMatchField(List<String> matchModes) {
        if (matchModes == null || matchModes.size() != 1) {
            return null;
        }
        String v = matchModes.get(0);
        if (v == null || v.isBlank() || "fulltext".equalsIgnoreCase(v.trim())) {
            return null;
        }
        v = v.trim();
        return MATCH_FIELD_TOKENS.contains(v) ? v : null;
    }

    /** query_bids_advanced：高级搜索（支持 keyword_groups AND 关系 / exclude_keywords 排除词 / match_modes 字段过滤） */
    public Map<String, Object> queryBidsAdvanced(Map<String, Object> req) {
        String bidType = strOr(req, "bid_type", "全部");
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        List<String> keywords = listOr(req, "keywords");
        List<String> matchModes = listOr(req, "match_modes");
        List<List<String>> keywordGroups = listOfListOr(req, "keyword_groups");
        List<String> excludeKeywords = listOr(req, "exclude_keywords");
        String beginDate = strOr(req, "begin_date", null);
        String endDate = strOr(req, "end_date", null);
        BigDecimal minAmount = numOr(req, "min_amount");
        BigDecimal maxAmount = numOr(req, "max_amount");
        int page = Math.max(1, intOr(req, "page", 1));
        int pageSize = Math.min(Math.max(intOr(req, "page_size", 20), 1), 500);
        int offset = (page - 1) * pageSize;

        // 合并顶层 keywords 进第一个 group（如未指定 group）
        List<List<String>> effectiveGroups = new ArrayList<>(keywordGroups);
        if (!keywords.isEmpty()) {
            List<String> topGroup = new ArrayList<>(keywords);
            // match_modes 字段路由 token 由 resolveMatchField 解析后随各 mapper 调用下发（单值生效，多值/未知值回退全字段 OR）
            effectiveGroups.add(0, topGroup);
        }

        boolean wantAll = bidType == null || bidType.isEmpty() || "全部".equals(bidType);
        boolean wantPrepose = wantAll || "商机".equals(bidType);
        boolean wantPurchaseIntention = wantAll || "采购".equals(bidType);
        boolean wantBidding = wantAll || "招标".equals(bidType);
        boolean wantWinner = wantAll || "中标".equals(bidType);
        String matchField = resolveMatchField(matchModes);
        if (matchModes != null && matchModes.size() == 1 && TABLE_LOCK_TOKENS.contains(matchModes.get(0))) {
            // match_modes 单值表锁 token（bidding/winner/prepose/purchaseIntention）覆盖 bid_type 的表选择；
            // 字段路由 token（title/product/keywords/tenderer/win_tenderer）不影响选表，仅路由关键词匹配字段
            wantPrepose = "prepose".equals(matchModes.get(0));
            wantPurchaseIntention = "purchaseIntention".equals(matchModes.get(0));
            wantBidding = "bidding".equals(matchModes.get(0));
            wantWinner =  "winner".equals(matchModes.get(0));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        long total = 0;
        long distinctTotal = 0;

        if (wantBidding) {
            List<BidBiddingEntity> rows = biddingMapper.listByAdvanced(
                    matchField, provinces, effectiveGroups, excludeKeywords,
                    minAmount, maxAmount, beginDate, endDate, offset, pageSize);
            Map<String, Object> c = biddingMapper.countByAdvanced(
                    matchField, provinces, effectiveGroups, excludeKeywords, minAmount, maxAmount, beginDate, endDate);
            total += numLong(c.get("total"));
            distinctTotal += numLong(c.get("distinct_total"));
            for (BidBiddingEntity e : rows) items.add(toBidItem(e, 4, "招标"));
        }
        if (wantWinner) {
            List<BidWinnerEntity> rows = winnerMapper.listByAdvanced(
                    matchField, provinces, effectiveGroups, excludeKeywords,
                    minAmount, maxAmount, beginDate, endDate, offset, pageSize);
            Map<String, Object> c = winnerMapper.countByAdvanced(
                    matchField, provinces, effectiveGroups, excludeKeywords, minAmount, maxAmount, beginDate, endDate);
            total += numLong(c.get("total"));
            distinctTotal += numLong(c.get("distinct_total"));
            for (BidWinnerEntity e : rows) items.add(toBidItem(e, 7, "中标"));
        }
        if (wantPurchaseIntention) {
            List<BidPurchaseIntentionEntity> rows = purchaseIntentionMapper.listByAdvanced(
                    matchField, provinces, effectiveGroups, excludeKeywords,
                    minAmount, maxAmount, beginDate, endDate, offset, pageSize);
            Map<String, Object> c = purchaseIntentionMapper.countByAdvanced(
                    matchField, provinces, effectiveGroups, excludeKeywords, minAmount, maxAmount, beginDate, endDate);
            total += numLong(c.get("total"));
            distinctTotal += numLong(c.get("distinct_total"));
            for (BidPurchaseIntentionEntity e : rows) items.add(toBidItem(e, 1, "采购"));
        }
        if (wantPrepose) {
            List<BidPreposeEntity> rows = preposeMapper.listByAdvanced(
                    matchField, provinces, effectiveGroups, excludeKeywords,
                    minAmount, maxAmount, beginDate, endDate, offset, pageSize);
            Map<String, Object> c = preposeMapper.countByAdvanced(
                    matchField, provinces, effectiveGroups, excludeKeywords, minAmount, maxAmount, beginDate, endDate);
            total += numLong(c.get("total"));
            distinctTotal += numLong(c.get("distinct_total"));
            for (BidPreposeEntity e : rows) items.add(toBidItem(e, 2, "商机"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("total_distinct", distinctTotal);
        data.put("items", items);
        data.put("matched_groups", effectiveGroups.size());
        data.put("exclude_keywords", excludeKeywords);
        return success(data);
    }

    /** get_bid_detail：标讯详情（按 bid_id 推断表） */
    public Map<String, Object> getBidDetail(Map<String, Object> req) {
        Long bidId = numOr(req, "bid_id") == null ? null : numOr(req, "bid_id").longValue();
        String uniqKey = strOr(req, "uniq_key", null);
        // bid_type 对齐 SKILL 的 bid_process 枚举：1=采购意向 2=预招标 4=招标 7=中标（旧版 1=招标/2=中标 与 SKILL 冲突，已废弃）
        Integer bidType = intOrNull(req, "bid_type");

        Map<String, Object> item = null;
        if (bidId != null) {
            // 按 bid_type 优先尝试对应表，未命中再按 招标→中标→商机→采购 顺序回退 4 张表
            if (Integer.valueOf(4).equals(bidType)) {
                BidBiddingEntity e = biddingMapper.selectById(bidId);
                if (e != null) item = toBidItem(e, 4, "招标");
            } else if (Integer.valueOf(7).equals(bidType)) {
                BidWinnerEntity e = winnerMapper.selectById(bidId);
                if (e != null) item = toBidItem(e, 7, "中标");
            } else if (Integer.valueOf(2).equals(bidType)) {
                BidPreposeEntity e = preposeMapper.selectById(bidId);
                if (e != null) item = toBidItem(e, 2, "商机");
            } else if (Integer.valueOf(1).equals(bidType)) {
                BidPurchaseIntentionEntity e = purchaseIntentionMapper.selectById(bidId);
                if (e != null) item = toBidItem(e, 1, "采购");
            }
            if (item == null) {
                BidBiddingEntity e1 = biddingMapper.selectById(bidId);
                if (e1 != null) { item = toBidItem(e1, 4, "招标"); }
            }
            if (item == null) {
                BidWinnerEntity e2 = winnerMapper.selectById(bidId);
                if (e2 != null) { item = toBidItem(e2, 7, "中标"); }
            }
            if (item == null) {
                BidPreposeEntity e3 = preposeMapper.selectById(bidId);
                if (e3 != null) { item = toBidItem(e3, 2, "商机"); }
            }
            if (item == null) {
                BidPurchaseIntentionEntity e4 = purchaseIntentionMapper.selectById(bidId);
                if (e4 != null) { item = toBidItem(e4, 1, "采购"); }
            }
        }

        if (item == null) {
            return success(new LinkedHashMap<>());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item", item);
        return success(data);
    }

    /**
     * search_expiring_projects：临期项目（v2 — 2026-08-05 升级 winner 表合同期解析）。
     *
     * 数据源：bid_biz_win_bid（中标合同期，比 prepose 的开标时间更准）。
     * 字段：service_period JSON / service_start DATE / service_day INT
     *
     * 解析策略（ServicePeriodParser）：
     *   JSON 档：service_end → 精确到期日；service_end 空 → service_start + service_days
     *   A 档（双日期区间文本）：取末尾日期
     *   B 档（截止日文本）：取唯一日期
     *   C/D 档（n 年/月/天相对期限）：publish_time + N（多段取最短）
     *   兜底：publish_time + 90 天
     *
     * 然后过滤出 expire_date ∈ [today, today + windowDays] 的项目，按剩余天数升序排。
     */
    public Map<String, Object> searchExpiringProjects(Map<String, Object> req) {
        List<String> keywords = listOr(req, "keywords");
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        String keyword = keywords.isEmpty() ? null : String.join(" ", keywords);
        int page = Math.max(1, intOr(req, "page", 1));
        int pageSize = Math.min(Math.max(intOr(req, "page_size", 20), 1), 500);
        // 临期窗口：默认 [0, 90] 天，agent 可调
        int windowDays = Math.max(1, Math.min(intOr(req, "window_days", 90), 365));
        LocalDate today = LocalDate.now();
        LocalDate windowEnd = today.plusDays(windowDays);
        // 拉宽 DB 查询窗口（publish_time 范围）：近 3 年中标合同（合同期最长 2-3 年，所以要拉到 3 年）
        String dbBegin = today.minusDays(1095).format(DATE_FMT);
        String dbEnd = today.format(DATE_FMT);
        // DB 取更多候选，service 层内存过滤 + 排序
        int dbLimit = Math.min(Math.max(pageSize * 8, 200), 2000);

        List<BidWinnerEntity> rows = winnerMapper.listExpiringCandidates(
                provinces, keyword, dbBegin, dbEnd, dbLimit);

        List<Map<String, Object>> items = new ArrayList<>();
        int parsedCount = 0;
        int totalScanned = rows.size();
        for (BidWinnerEntity w : rows) {
            LocalDate pub = w.getPublishTime();
            // JSON 档优先：service_period 字符串直接传进 parser（开头 { 即走 JSON 解析）
            String rawPeriod = w.getServicePeriod();
            // 退化路径：JSON 字段全空时，退到 service_start + service_day 拼出的文本形式
            if (rawPeriod == null || rawPeriod.isBlank()) {
                if (w.getServiceStart() != null && w.getServiceDay() != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"service_start\":\"")
                      .append(w.getServiceStart().format(DATE_FMT))
                      .append("\",\"service_days\":")
                      .append(w.getServiceDay())
                      .append("}");
                    rawPeriod = sb.toString();
                }
            }
            ServicePeriodParser.ParseResult pr = ServicePeriodParser.parse(rawPeriod, pub);
            LocalDate expireDate;
            String sourceTag;
            if (pr.parsed && pr.expireDate != null) {
                expireDate = pr.expireDate;
                parsedCount++;
                sourceTag = "WINNER_" + pr.source.name();  // WINNER_JSON_END / WINNER_JSON_DAYS / WINNER_DOUBLE_DATE ...
            } else {
                // 兜底：publish_time + 90 天
                expireDate = pub == null ? null : pub.plusDays(90);
                sourceTag = "FALLBACK_PUB_PLUS_90D";
            }

            // 过滤：expire_date ∈ [today, today+windowDays]
            if (expireDate == null) continue;
            if (expireDate.isBefore(today)) continue;
            if (expireDate.isAfter(windowEnd)) continue;

            Map<String, Object> item = toBidItem(w, 7, "中标");
            item.put("service_period_raw", rawPeriod);
            item.put("service_start", w.getServiceStart() == null ? null : w.getServiceStart().format(DATE_FMT));
            item.put("service_day", w.getServiceDay());
            item.put("service_end_date", expireDate.format(DATE_FMT));
            item.put("days_remaining", (int) (today.until(expireDate, java.time.temporal.ChronoUnit.DAYS)));
            item.put("expire_source", sourceTag);
            items.add(item);
        }

        // 按 days_remaining 升序（最临期在前）
        items.sort((a, b) -> Integer.compare(numInt(a.get("days_remaining")), numInt(b.get("days_remaining"))));
        // 翻页：total 为窗口内全量命中数，items 按 page/page_size 截取当前页
        int totalMatched = items.size();
        int from = Math.min((page - 1) * pageSize, totalMatched);
        int to = Math.min(from + pageSize, totalMatched);
        items = new ArrayList<>(items.subList(from, to));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", totalMatched);
        data.put("page", page);
        data.put("page_size", pageSize);
        data.put("today", today.format(DATE_FMT));
        data.put("window_days", windowDays);
        data.put("window_end", windowEnd.format(DATE_FMT));
        data.put("parsed_count", parsedCount);
        data.put("total_scanned", totalScanned);
        data.put("scan_note", "扫描范围为近 3 年内含合同期字段的中标记录按发布时间倒序的前 " + totalScanned
                + " 条；parsed_count/total_scanned 为该候选集口径，非全库口径；若窗口内临期项目多于候选集，结果可能不完整");
        data.put("source_table", "bid_biz_win_bid");
        data.put("items", items);
        return success(data);
    }

    // ============================================================
    // 2. 企业分析类（7 个）— 6 真实现 + 1 stub（contacts 无数据字段）
    // ============================================================

    /**
     * search_company：按公司名搜 tenderer + win_tenderer 两侧（去重），返回每个公司作为招标方/中标方的次数。
     * 用于自动匹配"总部+分子公司"。
     */
    public Map<String, Object> searchCompany(Map<String, Object> req) {
        // 兼容 company_name / company 两种参数名
        String companyName = strOr(req, "company_name", null);
        if (companyName == null || companyName.isBlank()) {
            companyName = strOr(req, "company", null);
        }
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        String province = provinces.isEmpty() ? null : provinces.get(0);
        String city = strOr(req, "city", null);
        int pageSize = Math.min(intOr(req, "page_size", 10), 20);

        if (companyName == null || companyName.isBlank()) {
            return success(Map.of("total", 0, "items", Collections.emptyList()));
        }

        // 两侧聚合后合并去重
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        List<Map<String, Object>> callers = biddingMapper.searchCompanyCandidates(companyName, province, city, pageSize);
        for (Map<String, Object> row : callers) {
            String name = strOf(row, "fullname");
            if (name == null) continue;
            Map<String, Object> m = merged.computeIfAbsent(name, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("fullname", name);
                x.put("name", strOf(row, "name"));
                x.put("province", strOf(row, "province"));
                x.put("city", strOf(row, "city"));
                x.put("bid_count", 0);
                x.put("win_count", 0);
                return x;
            });
            Object bc = row.get("bid_count");
            m.put("bid_count", bc instanceof Number ? ((Number) bc).intValue() : 0);
            m.put("latest_pub_time", strOf(row, "latest_pub_time"));
        }

        List<Map<String, Object>> winners = winnerMapper.searchCompanyCandidatesWinner(companyName, province, city, pageSize);
        for (Map<String, Object> row : winners) {
            String name = strOf(row, "fullname");
            if (name == null) continue;
            Map<String, Object> m = merged.computeIfAbsent(name, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("fullname", name);
                x.put("name", strOf(row, "name"));
                x.put("province", strOf(row, "province"));
                x.put("city", strOf(row, "city"));
                x.put("bid_count", 0);
                x.put("win_count", 0);
                return x;
            });
            Object wc = row.get("win_count");
            m.put("win_count", wc instanceof Number ? ((Number) wc).intValue() : 0);
            m.put("latest_pub_time", strOf(row, "latest_pub_time"));
        }

        items.addAll(merged.values());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", items.size());
        data.put("page", intOr(req, "page", 1));
        data.put("page_size", pageSize);
        data.put("items", items);
        return success(data);
    }

    /**
     * get_company_profile：合并 tenderer + win_tenderer 两侧画像，给出 caller_count + winner_count。
     */
    public Map<String, Object> getCompanyProfile(Map<String, Object> req) {
        String company = strOr(req, "company", null);
        if (company == null || company.isBlank()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            return success(empty);
        }

        Map<String, Object> asTenderer = biddingMapper.getCompanyProfileAsTenderer(company);
        Map<String, Object> asWinner = winnerMapper.getCompanyProfileAsWinner(company);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fullname", company);
        out.put("name", company);
        out.put("province", asTenderer != null ? asTenderer.get("province") : (asWinner != null ? asWinner.get("province") : null));
        out.put("city", asTenderer != null ? asTenderer.get("city") : (asWinner != null ? asWinner.get("city") : null));
        out.put("top_industry", asTenderer != null ? asTenderer.get("top_industry") : (asWinner != null ? asWinner.get("top_industry") : null));
        out.put("caller_count", numInt(asTenderer == null ? null : asTenderer.get("caller_count")));
        out.put("winner_count", numInt(asWinner == null ? null : asWinner.get("winner_count")));
        BigDecimal totalBudget = toBigDecimal(asTenderer == null ? null : asTenderer.get("total_budget"));
        BigDecimal totalRevenue = toBigDecimal(asWinner == null ? null : asWinner.get("total_revenue"));
        out.put("total_budget_yuan", totalBudget);
        out.put("total_revenue_yuan", totalRevenue);
        out.put("total_budget_wan", yuanToWan(totalBudget));
        out.put("total_revenue_wan", yuanToWan(totalRevenue));
        out.put("latest_caller_time", asTenderer == null ? null : asTenderer.get("latest_pub_time"));
        out.put("latest_winner_time", asWinner == null ? null : asWinner.get("latest_pub_time"));
        out.put("province_count", numInt(asTenderer == null ? null : asTenderer.get("province_count")));
        out.put("data_source_note", "基础工商信息（注册资本/成立日期等）不在本数据源；统计来自 bid_biz_bidding + bid_biz_win_bid 4 张表聚合");
        return success(out);
    }

    /**
     * get_company_business_keywords（v2 — 2026-08-05 升级）。
     *
     * 之前只聚合 bidding + winner 两表的 product 字段。
     * 现在升级：
     *   1. 新增聚合 bid_prepose 表（同时含 product + keywords 字段，是商机最丰富的来源）
     *   2. 对 prepose.keywords 字符串做拆分（中文逗号/分号/竖线/空格 分隔），
     *      每个 token 作为一个 keyword 累计
     *   3. 对拆分出的 token 做基本停用词过滤（项目/服务/采购 等过短通用词）
     *
     * 返回结构不变：[{ keyword, count, amount }] 按 count 倒序。
     */
    public Map<String, Object> getCompanyBusinessKeywords(Map<String, Object> req) {
        String company = strOr(req, "company", null);
        if (company == null || company.isBlank()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("company_name", company);
            data.put("keywords", Collections.emptyList());
            return success(data);
        }
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        String beginDate = strOr(req, "begin_date", null);
        String endDate = strOr(req, "end_date", null);
        int limit = Math.min(intOr(req, "limit", 20), 100);
        // 拉宽窗口：每个 mapper 多取一些，避免漏掉边角关键词
        int mapperLimit = Math.max(limit * 3, 60);

        // ========== 路径 1：bidding + winner 的 product 聚合 ==========
        List<Map<String, Object>> productRows = new ArrayList<>();
        productRows.addAll(biddingMapper.getCompanyBusinessKeywordsAsTenderer(company, beginDate, endDate, provinces, mapperLimit));
        productRows.addAll(winnerMapper.getCompanyBusinessKeywordsAsWinner(company, beginDate, endDate, provinces, mapperLimit));

        // ========== 路径 2：prepose 的 product 聚合（tenderer LIKE） ==========
        List<Map<String, Object>> preposeRows = preposeMapper.aggregateProductAsTenderer(company, provinces, beginDate, endDate, mapperLimit);

        // ========== 路径 3：prepose.keywords 拆分聚合（每条记录拆出多个 token） ==========
        //   直接 SQL 不太好做（GROUP BY 不能保证拆分），所以先 list 再 service 层拆
        List<BidPreposeEntity> preposeRowsRaw = preposeMapper.listByConditions(
                null, null, null, null, company, null, null, null, beginDate, endDate, mapperLimit * 5);
        Map<String, Integer> keywordTokensCounts = new LinkedHashMap<>();
        for (BidPreposeEntity e : preposeRowsRaw) {
            String kws = e.getKeywords();
            if (kws == null || kws.isBlank()) continue;
            for (String tk : keywordsTokenize(kws)) {
                keywordTokensCounts.merge(tk, 1, Integer::sum);
            }
        }

        // ========== 合并所有路径到 keyword-count map ==========
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        // product 聚合（bidding + winner）— 来源：招标 / 中标
        for (Map<String, Object> row : productRows) {
            String kw = strOf(row, "keyword");
            if (kw == null || kw.isBlank()) continue;
            Integer c = row.get("count") instanceof Number ? ((Number) row.get("count")).intValue() : 0;
            BigDecimal amt = toBigDecimal(row.get("amount"));
            Map<String, Object> m = merged.computeIfAbsent(kw, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("keyword", kw);
                x.put("count", 0);
                x.put("amount", BigDecimal.ZERO);
                x.put("sources", new LinkedHashSet<String>());
                return x;
            });
            m.put("count", numInt(m.get("count")) + c);
            BigDecimal cur = toBigDecimal(m.get("amount"));
            m.put("amount", cur == null ? amt : (amt == null ? cur : cur.add(amt)));
            @SuppressWarnings("unchecked")
            Set<String> src = (Set<String>) m.get("sources");
            src.add("招标/中标");
        }

        // prepose product 聚合 — 来源：预在建
        for (Map<String, Object> row : preposeRows) {
            String kw = strOf(row, "keyword");
            if (kw == null || kw.isBlank()) continue;
            Integer c = row.get("count") instanceof Number ? ((Number) row.get("count")).intValue() : 0;
            BigDecimal amt = toBigDecimal(row.get("amount"));
            Map<String, Object> m = merged.computeIfAbsent(kw, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("keyword", kw);
                x.put("count", 0);
                x.put("amount", BigDecimal.ZERO);
                x.put("sources", new LinkedHashSet<String>());
                return x;
            });
            m.put("count", numInt(m.get("count")) + c);
            BigDecimal cur = toBigDecimal(m.get("amount"));
            m.put("amount", cur == null ? amt : (amt == null ? cur : cur.add(amt)));
            @SuppressWarnings("unchecked")
            Set<String> src = (Set<String>) m.get("sources");
            src.add("预在建");
        }

        // keywords 拆分 token — 来源：预在建关键词（无 amount）
        for (Map.Entry<String, Integer> e : keywordTokensCounts.entrySet()) {
            String kw = e.getKey();
            if (kw == null || kw.isBlank()) continue;
            Map<String, Object> m = merged.computeIfAbsent(kw, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("keyword", kw);
                x.put("count", 0);
                x.put("amount", BigDecimal.ZERO);
                x.put("sources", new LinkedHashSet<String>());
                return x;
            });
            m.put("count", numInt(m.get("count")) + e.getValue());
            @SuppressWarnings("unchecked")
            Set<String> src = (Set<String>) m.get("sources");
            src.add("预在建关键词");
        }

        // 排序 + 截断
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> m : merged.values()) {
            @SuppressWarnings("unchecked")
            Set<String> src = (Set<String>) m.get("sources");
            m.put("sources", src == null ? Collections.emptyList() : new ArrayList<>(src));
            items.add(m);
        }
        items.sort((a, b) -> {
            int diff = numInt(b.get("count")) - numInt(a.get("count"));
            if (diff != 0) return diff;
            String ka = a.get("keyword") == null ? "" : a.get("keyword").toString();
            String kb = b.get("keyword") == null ? "" : b.get("keyword").toString();
            return ka.compareTo(kb);
        });
        if (items.size() > limit) items = items.subList(0, limit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("company_name", company);
        data.put("total_keywords", items.size());
        data.put("keyword_tokens_from_prepose", keywordTokensCounts.size()); // 仅做信息展示
        data.put("keywords", items);
        return success(data);
    }

    /**
     * get_company_partners：按 partner_type 选 caller（客户=合作过的招标方）还是 supplier（供应商=合作过的中标方）。
     */
    public Map<String, Object> getCompanyPartners(Map<String, Object> req) {
        // 兼容 company_name / company 两种参数名（search_company 用 company_name，agent 易混传）
        String company = strOr(req, "company", null);
        if (company == null || company.isBlank()) {
            company = strOr(req, "company_name", null);
        }
        String partnerType = strOr(req, "partner_type", "全部");
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        List<String> keywords = listOr(req, "keywords");
        BigDecimal minAmount = numOr(req, "min_amount");
        String beginDate = strOr(req, "begin_date", null);
        String endDate = strOr(req, "end_date", null);
        int limit = Math.min(intOr(req, "limit", 20), 100);

        List<Map<String, Object>> rows = new ArrayList<>();
        // 客户=合作伙伴是招标方；供应商=合作伙伴是中标方
        boolean wantCaller = "全部".equals(partnerType) || "客户".equals(partnerType);
        boolean wantSupplier = "全部".equals(partnerType) || "供应商".equals(partnerType);
        if (company == null || company.isBlank()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("company", company);
            data.put("partner_type", partnerType);
            data.put("total", 0);
            data.put("partners", Collections.emptyList());
            return success(data);
        }
        if (wantSupplier) {
            rows.addAll(winnerMapper.getCompanyPartnersAsWinner(company, provinces, keywords, minAmount, beginDate, endDate, limit));
        }
        if (wantCaller) {
            rows.addAll(biddingMapper.getCompanyPartnersAsCaller(company, provinces, keywords, minAmount, beginDate, endDate, limit));
        }

        List<Map<String, Object>> partners = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("company_name", row.get("company_name"));
            Object cc = row.get("cooperation_count");
            p.put("cooperation_count", cc instanceof Number ? ((Number) cc).intValue() : 0);
            BigDecimal amt = toBigDecimal(row.get("cooperation_amount"));
            p.put("cooperation_amount", amt);
            p.put("cooperation_amount_wan", yuanToWan(amt));
            p.put("last_cooperation_time", row.get("last_cooperation_time"));
            String products = strOf(row, "products");
            p.put("products", products == null || products.isBlank() ? Collections.emptyList() : Arrays.asList(products.split(",")));
            partners.add(p);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("company", company);
        data.put("partner_type", partnerType);
        data.put("total", partners.size());
        data.put("partners", partners);
        return success(data);
    }

    /**
     * get_company_contacts：聚合该公司作为招标方 / 中标方 / 代理机构 三种角色的联系人。
     *
     * 三条路径并行查询，按角色分组：
     *   1) 中标方 → win_bid.win_tenderer_manager / phone / email
     *   2) 招标方 → bidding.tenderer_contact / phone / address
     *   3) 代理机构 → bidding.agency_contact / phone（agency LIKE 公司名）
     *
     * 注意：联系人数据稀疏（tenderer_contact 大概率 NULL），所以返回的 contacts 列表可能为空。
     * 建议 agent 拿到结果后引导用户：如需联系人可访问招标公告详情页或致电采购方。
     */
    public Map<String, Object> getCompanyContacts(Map<String, Object> req) {
        String company = strOr(req, "company", null);
        int limit = Math.min(intOr(req, "limit", 10), 30);
        if (company == null || company.isBlank()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("company_name", company);
            data.put("total", 0);
            data.put("contacts", Collections.emptyList());
            data.put("_note", "缺少 company 参数");
            return success(data);
        }

        List<Map<String, Object>> contacts = new ArrayList<>();

        // 路径 1：作为中标方
        List<Map<String, Object>> asWinner = winnerMapper.aggregateContactsAsWinner(company, limit);
        for (Map<String, Object> row : asWinner) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", nullToDash(row.get("name")));
            c.put("phone", nullToDash(row.get("phone")));
            c.put("email", nullToDash(row.get("email")));
            c.put("role", "中标方经办人");
            c.put("contact_count", row.get("contact_count"));
            c.put("latest_contact_time", row.get("latest_contact_time"));
            String projects = strOf(row.get("related_projects") == null ? null : row.get("related_projects").toString());
            c.put("related_projects", projects.isEmpty() ? Collections.emptyList() : Arrays.asList(projects.split("\\|\\|")));
            contacts.add(c);
        }

        // 路径 2：作为招标方
        List<Map<String, Object>> asTenderer = winnerMapper.aggregateContactsAsTenderer(company, limit);
        for (Map<String, Object> row : asTenderer) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", nullToDash(row.get("name")));
            c.put("phone", nullToDash(row.get("phone")));
            c.put("address", nullToDash(row.get("address")));
            c.put("role", "招标方联系人");
            c.put("contact_count", row.get("contact_count"));
            c.put("latest_contact_time", row.get("latest_contact_time"));
            String projects = strOf(row.get("related_projects") == null ? null : row.get("related_projects").toString());
            c.put("related_projects", projects.isEmpty() ? Collections.emptyList() : Arrays.asList(projects.split("\\|\\|")));
            contacts.add(c);
        }

        // 路径 3：作为代理机构
        List<Map<String, Object>> asAgency = winnerMapper.aggregateContactsAsAgency(company, limit);
        for (Map<String, Object> row : asAgency) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", nullToDash(row.get("name")));
            c.put("phone", nullToDash(row.get("phone")));
            c.put("agency_company", nullToDash(row.get("company")));
            c.put("role", "代理机构联系人");
            c.put("contact_count", row.get("contact_count"));
            c.put("latest_contact_time", row.get("latest_contact_time"));
            String projects = strOf(row.get("related_projects") == null ? null : row.get("related_projects").toString());
            c.put("related_projects", projects.isEmpty() ? Collections.emptyList() : Arrays.asList(projects.split("\\|\\|")));
            contacts.add(c);
        }

        // 按 contact_count 倒序（数据来源更多 = 更可信）
        contacts.sort((a, b) -> Long.compare(numLong(b.get("contact_count")), numLong(a.get("contact_count"))));
        if (contacts.size() > limit) contacts = contacts.subList(0, limit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("company_name", company);
        data.put("total", contacts.size());
        data.put("contacts", contacts);
        data.put("data_freshness_note", "联系人数据来源于历史招中标公告，tenderer_contact / win_tenderer_manager 字段覆盖率较低（约 10-30%），结果可能为空");
        return success(data);
    }

    /**
     * get_price_trends：按 brand + 可选 product/model 模糊命中 product 字段，
     * 对 win_bid_price 做分布统计 + 月度趋势 + 涨跌对比。
     *
     * ⚠️ 注意：4 张表无 SKU 维度（product 是逗号分隔文本），所以本接口输出"价格分布"而非"严格 SKU 单价"。
     * 适用场景：投标报价前摸市场价格分布、做定价参考。
     */
    public Map<String, Object> getPriceTrends(Map<String, Object> req) {
        String brand = strOr(req, "brand", null);
        String product = strOr(req, "product", null);
        String model = strOr(req, "model", null);
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        if (brand == null || brand.isBlank()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("brand", brand);
            data.put("total_samples", 0);
            data.put("price_distribution", Collections.emptyMap());
            data.put("_note", "缺少 brand 参数");
            return success(data);
        }

        // 默认看近 2 年；agent 可调
        String beginDate = strOr(req, "begin_date", LocalDate.now().minusDays(730).format(DATE_FMT));
        String endDate = strOr(req, "end_date", LocalDate.now().format(DATE_FMT));
        int limit = Math.min(Math.max(intOr(req, "limit", 2000), 100), 10000);

        List<Map<String, Object>> rows = winnerMapper.listPricesByBrandProduct(
                brand, product, model, provinces, beginDate, endDate, limit);

        if (rows.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("brand", brand);
            data.put("product", product);
            data.put("model", model);
            data.put("total_samples", 0);
            data.put("price_distribution", Collections.emptyMap());
            data.put("_note", "近 2 年无匹配中标记录（brand=" + brand + " product=" + product + " model=" + model + "），可放宽关键词或扩大时间范围");
            return success(data);
        }

        // 收集所有价格
        List<BigDecimal> prices = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object p = r.get("price");
            if (p == null) continue;
            try {
                BigDecimal bd = new BigDecimal(p.toString());
                if (bd.compareTo(BigDecimal.ZERO) > 0) prices.add(bd);
            } catch (Exception ignore) {}
        }
        if (prices.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("brand", brand);
            data.put("total_samples", 0);
            data.put("price_distribution", Collections.emptyMap());
            data.put("_note", "命中记录但 win_bid_price 都为空或 0");
            return success(data);
        }

        // 价格分位数
        Collections.sort(prices);
        Map<String, Object> dist = new LinkedHashMap<>();
        dist.put("count", prices.size());
        dist.put("min_yuan", prices.get(0));
        dist.put("p25_yuan", percentile(prices, 0.25));
        dist.put("median_yuan", percentile(prices, 0.50));
        dist.put("avg_yuan", avgOf(prices));
        dist.put("p75_yuan", percentile(prices, 0.75));
        dist.put("p90_yuan", percentile(prices, 0.90));
        dist.put("max_yuan", prices.get(prices.size() - 1));

        // 月度均价
        Map<String, List<BigDecimal>> monthlyPrices = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            String month = strOf(r.get("month") == null ? null : r.get("month").toString());
            Object p = r.get("price");
            if (month.isEmpty() || p == null) continue;
            try {
                BigDecimal bd = new BigDecimal(p.toString());
                if (bd.compareTo(BigDecimal.ZERO) > 0) {
                    monthlyPrices.computeIfAbsent(month, k -> new ArrayList<>()).add(bd);
                }
            } catch (Exception ignore) {}
        }
        List<Map<String, Object>> monthly = new ArrayList<>();
        for (Map.Entry<String, List<BigDecimal>> e : monthlyPrices.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", e.getKey());
            m.put("sample_count", e.getValue().size());
            m.put("avg_yuan", avgOf(e.getValue()));
            monthly.add(m);
        }

        // 涨跌对比：近 3 个月 vs 早 3 个月 中位价（median，对极端值更稳健）
        String trendNote;
        BigDecimal recentMedian = null;
        BigDecimal earlierMedian = null;
        if (monthly.size() >= 6) {
            int n = monthly.size();
            List<BigDecimal> recent = new ArrayList<>();
            List<BigDecimal> earlier = new ArrayList<>();
            for (int i = n - 1; i >= Math.max(0, n - 3); i--) {
                recent.add((BigDecimal) monthly.get(i).get("avg_yuan"));
            }
            for (int i = Math.max(0, n - 6); i < Math.max(0, n - 3); i++) {
                earlier.add((BigDecimal) monthly.get(i).get("avg_yuan"));
            }
            Collections.sort(recent);
            Collections.sort(earlier);
            recentMedian = percentile(recent, 0.50);
            earlierMedian = percentile(earlier, 0.50);
            if (earlierMedian != null && recentMedian != null && earlierMedian.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal change = recentMedian.subtract(earlierMedian)
                        .divide(earlierMedian, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                String direction = change.compareTo(BigDecimal.ZERO) > 0 ? "上涨" : (change.compareTo(BigDecimal.ZERO) < 0 ? "下跌" : "持平");
                trendNote = String.format("近 3 个月中位价 %.2f 元 vs 早 3 个月中位价 %.2f 元，%s %s%%",
                        recentMedian.doubleValue(), earlierMedian.doubleValue(), direction, change.toPlainString());
            } else {
                trendNote = "样本不足计算涨跌";
            }
        } else {
            trendNote = "样本月份不足 6 个月，无法计算涨跌对比";
        }

        // 价格区间（< 1 万 / 1-10 万 / 10-100 万 / 100 万-1000 万 / ≥ 1000 万）
        Map<String, Integer> buckets = new LinkedHashMap<>();
        buckets.put("under_1wan", 0);
        buckets.put("1to10wan", 0);
        buckets.put("10to100wan", 0);
        buckets.put("100wan_to_1000wan", 0);
        buckets.put("above_1000wan", 0);
        BigDecimal oneWan = BigDecimal.valueOf(10000);
        BigDecimal tenWan = BigDecimal.valueOf(100000);
        BigDecimal hundredWan = BigDecimal.valueOf(1000000);
        BigDecimal thousandWan = BigDecimal.valueOf(10000000);
        for (BigDecimal p : prices) {
            if (p.compareTo(oneWan) < 0) buckets.put("under_1wan", buckets.get("under_1wan") + 1);
            else if (p.compareTo(tenWan) < 0) buckets.put("1to10wan", buckets.get("1to10wan") + 1);
            else if (p.compareTo(hundredWan) < 0) buckets.put("10to100wan", buckets.get("10to100wan") + 1);
            else if (p.compareTo(thousandWan) < 0) buckets.put("100wan_to_1000wan", buckets.get("100wan_to_1000wan") + 1);
            else buckets.put("above_1000wan", buckets.get("above_1000wan") + 1);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("brand", brand);
        data.put("product", product);
        data.put("model", model);
        data.put("date_range", Map.of("begin", beginDate, "end", endDate));
        data.put("total_samples", prices.size());
        data.put("price_distribution", dist);
        data.put("price_buckets", buckets);
        data.put("monthly_trend", monthly);
        data.put("trend_note", trendNote);
        data.put("data_freshness_note", "基于 product 字段模糊命中（逗号分隔文本），非严格 SKU 单价，仅供参考价格分布");
        return success(data);
    }

    /**
     * find_competitors：基于三个维度找对手，按权重合并去重打分：
     *   - 路径 A：同 project_name 关联（精确，权重 3）
     *   - 路径 B：共享同一招标方（中等，权重 2）
     *   - 路径 C：同省份 + 同 product 前缀 + 时间接近（宽匹配，权重 1）
     * 返回按加权 co_bid_score 排序的 Top N。
     */
    public Map<String, Object> findCompetitors(Map<String, Object> req) {
        String company = strOr(req, "company", null);
        int limit = Math.min(intOr(req, "limit", 10), 50);
        if (company == null || company.isBlank()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("company_name", company);
            data.put("total_projects", 0);
            data.put("competitors", Collections.emptyList());
            return success(data);
        }

        // 加权聚合：company_name -> {score, count_a, count_b, count_c, latest_time, products/callers/provinces}
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        int[] totalProjectsRef = {0};

        BiConsumer<List<Map<String, Object>>, Integer> absorb = (rows, weight) -> {
            for (Map<String, Object> row : rows) {
                String name = strOf(row, "company_name");
                if (name == null || name.isBlank()) continue;
                Map<String, Object> m = merged.computeIfAbsent(name, k -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("company_name", name);
                    x.put("co_bid_count", 0);
                    x.put("co_bid_score", 0);
                    x.put("count_path_a", 0);
                    x.put("count_path_b", 0);
                    x.put("count_path_c", 0);
                    return x;
                });
                Object cbc = row.get("co_bid_count");
                int cnt = cbc instanceof Number ? ((Number) cbc).intValue() : 0;
                if (weight == 3) m.put("count_path_a", numInt(m.get("count_path_a")) + cnt);
                else if (weight == 2) m.put("count_path_b", numInt(m.get("count_path_b")) + cnt);
                else m.put("count_path_c", numInt(m.get("count_path_c")) + cnt);
                m.put("co_bid_count", numInt(m.get("co_bid_count")) + cnt);
                m.put("co_bid_score", numInt(m.get("co_bid_score")) + cnt * weight);
                totalProjectsRef[0] += cnt;

                // 取最新的时间
                Object t = row.get("latest_co_bid_time");
                if (t != null) {
                    Object cur = m.get("latest_co_bid_time");
                    if (cur == null || t.toString().compareTo(cur.toString()) > 0) {
                        m.put("latest_co_bid_time", t);
                    }
                }
                // 拼接 products/callers/provinces（限制长度）
                appendUnique(m, "top_co_bid_products", strOf(row, "top_co_bid_products"), 8);
                appendUnique(m, "top_co_bid_callers", strOf(row, "top_co_bid_callers"), 8);
                appendUnique(m, "top_co_bid_provinces", strOf(row, "top_co_bid_provinces"), 5);
            }
        };

        absorb.accept(winnerMapper.findCompetitorsByCoBid(company, limit), 3);
        absorb.accept(winnerMapper.findCompetitorsBySharedCaller(company, limit), 2);
        absorb.accept(winnerMapper.findCompetitorsByRegionProduct(company, limit), 1);

        // 按加权分数排序，截 Top N
        List<Map<String, Object>> sorted = new ArrayList<>(merged.values());
        sorted.sort((a, b) -> {
            int diff = numInt(b.get("co_bid_score")) - numInt(a.get("co_bid_score"));
            return diff != 0 ? diff : numInt(b.get("co_bid_count")) - numInt(a.get("co_bid_count"));
        });
        if (sorted.size() > limit) sorted = sorted.subList(0, limit);

        // 附加 Top 竞对的年度共中标趋势（同 project_name 口径），agent 无需二次拉取即可判断对手活跃度走势
        int trendTop = Math.min(sorted.size(), 5);
        for (int i = 0; i < trendTop; i++) {
            Map<String, Object> c = sorted.get(i);
            String competitorName = strOf(c, "company_name");
            if (competitorName == null || competitorName.isBlank()) continue;
            try {
                Map<String, Integer> yearly = new LinkedHashMap<>();
                for (Map<String, Object> yr : winnerMapper.competitorYearlyCoBid(company, competitorName)) {
                    Object y = yr.get("yr");
                    Object yc = yr.get("co_bid_count");
                    yearly.put(y == null ? "未知" : y.toString(), yc instanceof Number ? ((Number) yc).intValue() : 0);
                }
                c.put("yearly_trend", yearly);
            } catch (Exception e) {
                log.warn("[find_competitors] 年度趋势查询失败 company={} competitor={}: {}", company, competitorName, e.getMessage());
            }
        }

        List<Map<String, Object>> competitors = new ArrayList<>();
        for (Map<String, Object> m : sorted) {
            competitors.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("company_name", company);
        data.put("total_projects", totalProjectsRef[0]);
        data.put("competitors", competitors);
        data.put("_match_strategy", "3条路径加权合并：A=同project_name(×3) + B=共享招标方(×2) + C=同省份+同品类+时间接近(×1)，按co_bid_score排序");
        data.put("_trend_note", "competitors 前 5 名附 yearly_trend（年份→同项目共中标次数，同 project_name 口径），用于判断对手活跃度走势");
        return success(data);
    }

    /** 把 csv 字符串按逗号拆开去重，append 到 List<String> 字段，限制总长度 */
    private static void appendUnique(Map<String, Object> m, String key, String csv, int maxSize) {
        if (csv == null || csv.isBlank()) return;
        @SuppressWarnings("unchecked")
        List<String> cur = (List<String>) m.computeIfAbsent(key, k -> new ArrayList<>());
        Set<String> seen = new HashSet<>(cur);
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !seen.contains(t)) {
                if (cur.size() >= maxSize) break;
                cur.add(t);
                seen.add(t);
            }
        }
    }

    /**
     * find_potential_bidders：找该 tenderer 历史上合作过的 win_tenderer。
     * bid_id/uniq_key/project_title 至少一个；若无项目上下文则用 company 维度。
     */
    public Map<String, Object> findPotentialBidders(Map<String, Object> req) {
        String company = strOr(req, "company", null);
        String product = strOr(req, "product", null);
        // SKILL 示例只传 bid_url：通过 bid_url 反查项目所属招标方作为 company（先查招标表，再回退中标表）
        String bidUrl = strOr(req, "bid_url", null);
        if ((company == null || company.isBlank()) && bidUrl != null && !bidUrl.isBlank()) {
            BidBiddingEntity b = biddingMapper.selectByBidUrl(bidUrl.trim());
            if (b != null && b.getTenderer() != null && !b.getTenderer().isBlank()) {
                company = b.getTenderer();
            } else {
                BidWinnerEntity w = winnerMapper.selectByBidUrl(bidUrl.trim());
                if (w != null && w.getTenderer() != null && !w.getTenderer().isBlank()) {
                    company = w.getTenderer();
                }
            }
        }
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        String province = provinces.isEmpty() ? null : provinces.get(0);
        int limit = Math.min(intOr(req, "limit", 10), 50);

        // 如果带了 bid_id / project_title，先尝试推断 project 上下文（此处简化用 company 维度）
        if (company == null || company.isBlank()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("project_title", strOr(req, "project_title", null));
            data.put("bidders", Collections.emptyList());
            data.put("_note", "需要 company 参数，或传入能在标讯库中命中项目的 bid_url");
            return success(data);
        }

        List<Map<String, Object>> rows = biddingMapper.findPotentialBidders(company, product, province, limit);
        List<Map<String, Object>> bidders = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("company_name", row.get("company_name"));
            Object chc = row.get("caller_history_count");
            b.put("caller_history_count", chc instanceof Number ? ((Number) chc).intValue() : 0);
            BigDecimal amt = toBigDecimal(row.get("caller_history_amount"));
            b.put("caller_history_amount", amt);
            b.put("last_win_time", row.get("last_win_time"));
            String mp = strOf(row, "matched_products");
            b.put("matched_products", mp == null || mp.isBlank()
                    ? Collections.emptyList()
                    : Arrays.stream(mp.split(",")).limit(5).collect(Collectors.toList()));
            b.put("source", "历史中标");
            bidders.add(b);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("project_title", strOr(req, "project_title", company + " 关联项目"));
        data.put("bidders", bidders);
        return success(data);
    }

    // ============================================================
    // 3. 市场分析类（5 个）— 全部 stub
    // ============================================================

    public Map<String, Object> getTopPurchasers(Map<String, Object> req) {
        List<String> keywords = listOr(req, "keywords");
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        // 兼容 min_price/max_price 与 min_amount/max_amount 两种参数名
        BigDecimal minAmount = numOr(req, "min_amount");
        if (minAmount == null) minAmount = numOr(req, "min_price");
        BigDecimal maxAmount = numOr(req, "max_amount");
        if (maxAmount == null) maxAmount = numOr(req, "max_price");
        String beginDate = strOr(req, "begin_date", null);
        String endDate = strOr(req, "end_date", null);
        int limit = intOr(req, "limit", 20);

        List<Map<String, Object>> rows = biddingMapper.aggregateTopPurchasers(
                keywords, provinces, minAmount, maxAmount, beginDate, endDate, limit);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("company_name", row.get("company_name"));
            Object pc = row.get("purchase_count");
            item.put("purchase_count", pc instanceof Number ? ((Number) pc).intValue() : 0);
            BigDecimal total = toBigDecimal(row.get("total_amount"));
            item.put("total_amount", total);
            item.put("total_amount_wan", yuanToWan(total));
            item.put("latest_purchase_time", row.get("latest_purchase_time"));
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", items.size());
        data.put("items", items);
        return success(data);
    }

    public Map<String, Object> getTopSuppliers(Map<String, Object> req) {
        List<String> keywords = listOr(req, "keywords");
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        // 兼容 min_price/max_price 与 min_amount/max_amount 两种参数名
        BigDecimal minAmount = numOr(req, "min_amount");
        if (minAmount == null) minAmount = numOr(req, "min_price");
        BigDecimal maxAmount = numOr(req, "max_amount");
        if (maxAmount == null) maxAmount = numOr(req, "max_price");
        String beginDate = strOr(req, "begin_date", null);
        String endDate = strOr(req, "end_date", null);
        int limit = intOr(req, "limit", 20);

        List<Map<String, Object>> rows = winnerMapper.aggregateTopSuppliers(
                keywords, provinces, minAmount, maxAmount, beginDate, endDate, limit);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("company_name", row.get("company_name"));
            Object wc = row.get("win_count");
            item.put("win_count", wc instanceof Number ? ((Number) wc).intValue() : 0);
            BigDecimal total = toBigDecimal(row.get("total_amount"));
            item.put("total_amount", total);
            item.put("total_amount_wan", yuanToWan(total));
            item.put("latest_win_time", row.get("latest_win_time"));
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", items.size());
        data.put("items", items);
        return success(data);
    }

    public Map<String, Object> getTopBrands(Map<String, Object> req) {
        // 接受 keywords（数组）或 单个 keyword 字符串 —— 多关键词 AND 命中 keywords 字段
        List<String> keywords = listOr(req, "keywords");
        String singleKeyword = strOr(req, "keyword", null);
        if (keywords.isEmpty() && singleKeyword != null && !singleKeyword.isBlank()) {
            keywords = Collections.singletonList(singleKeyword);
        }
        List<String> excludeKeywords = listOr(req, "exclude_keywords");
        if (provinceUnauthorized(req)) {
            return unauthorizedProvinceError();
        }
        List<String> provinces = resolveQueryProvinces(req);
        // 兼容 min_price/max_price 与 min_amount/max_amount 两种参数名
        BigDecimal minPrice = numOr(req, "min_price");
        if (minPrice == null) minPrice = numOr(req, "min_amount");
        BigDecimal maxPrice = numOr(req, "max_price");
        if (maxPrice == null) maxPrice = numOr(req, "max_amount");
        String beginDate = strOr(req, "begin_date", null);
        String endDate = strOr(req, "end_date", null);
        int limit = intOr(req, "limit", 20);

        // 没传 keywords 时强约束：必须至少传 1 个品类关键词，否则 keywords 字段全表 GROUP BY 会爆
        if (keywords.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("keywords", Collections.emptyList());
            data.put("total", 0);
            data.put("brands", Collections.emptyList());
            data.put("_note", "get_top_brands 需要传 keywords 参数（品类关键词列表，如[\"数字化\"]或[\"防火墙\",\"信息安全\"]），多关键词之间是 AND 关系；否则 keywords 字段全表 GROUP BY 成本过高");
            return success(data);
        }

        // 用第一个关键词传给 SQL（保持向后兼容），其他关键词在 service 层二次 AND 过滤
        String primaryKeyword = keywords.get(0);
        List<String> extraKeywords = keywords.size() > 1 ? keywords.subList(1, keywords.size()) : Collections.emptyList();

        List<Map<String, Object>> rows = winnerMapper.aggregateTopBrands(
                primaryKeyword, excludeKeywords, provinces, minPrice, maxPrice, beginDate, endDate, Math.min(limit * 20, 2000));

        // 关键词拆分 + 多关键词 AND 二次过滤 + 排除词 + 省份维度二次拆分
        // keywords 字段是 "数字化,监测,系统" 这种逗号分隔，需要按逗号拆成单个关键词再聚合
        // 每个 keyword 下再按 province 拆开（A 视角：关键词 × 省份）
        Map<String, Map<String, Object>> kwAgg = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String fullKeywords = strOf(row.get("brand") == null ? null : row.get("brand").toString());
            if (fullKeywords.isEmpty()) continue;

            // 按逗号切，拆出单个关键词（同时用于 AND 精确过滤与聚合）
            String[] parts = fullKeywords.split(",");
            Set<String> partSet = new LinkedHashSet<>();
            for (String part : parts) {
                String t = part.trim();
                if (!t.isEmpty()) partSet.add(t);
            }
            // 多关键词 AND 过滤：每个额外关键词都要作为独立 token 精确命中（子串匹配会误命中，如"防火墙"命中"云防火墙"）
            boolean allHit = true;
            for (String extra : extraKeywords) {
                if (extra == null || extra.isBlank()) continue;
                if (!partSet.contains(extra.trim())) { allHit = false; break; }
            }
            if (!allHit) continue;

            // 拆出单个关键词，分别累加命中数
            int hitCount = numInt(row.get("win_count"));   // 本条记录的中标次数（一般是 1）
            BigDecimal total = toBigDecimal(row.get("total_amount"));
            BigDecimal perHitTotal = (hitCount > 0 && total != null)
                    ? total.divide(BigDecimal.valueOf(hitCount), 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // 省份：可能为 null，归到"未知"
            String province = row.get("province") == null ? "未知" : row.get("province").toString().trim();
            if (province.isEmpty()) province = "未知";

            for (String part : parts) {
                String kw = part.trim();
                if (kw.isEmpty()) continue;

                // 排除词过滤
                boolean excluded = false;
                for (String ex : excludeKeywords) {
                    if (ex == null || ex.isBlank()) continue;
                    if (kw.contains(ex) || ex.contains(kw)) { excluded = true; break; }
                }
                if (excluded) continue;

                Map<String, Object> agg = kwAgg.computeIfAbsent(kw, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("brand", kw);
                    m.put("win_count", 0);
                    m.put("total_amount", BigDecimal.ZERO);
                    m.put("last_win_time", null);
                    m.put("sample_keywords", new HashSet<String>());
                    // 省份维度计数器：Map<province, {win_count, total_amount, last_win_time}>
                    m.put("by_province", new LinkedHashMap<String, Map<String, Object>>());
                    return m;
                });
                // 顶层 keyword 聚合
                agg.put("win_count", numInt(agg.get("win_count")) + hitCount);
                BigDecimal prevTotal = toBigDecimal(agg.get("total_amount"));
                agg.put("total_amount", (prevTotal == null ? BigDecimal.ZERO : prevTotal).add(perHitTotal.multiply(BigDecimal.valueOf(hitCount))));
                Object latest = row.get("last_win_time");
                Object prevLatest = agg.get("last_win_time");
                if (latest != null && (prevLatest == null || latest.toString().compareTo(prevLatest.toString()) > 0)) {
                    agg.put("last_win_time", latest);
                }
                @SuppressWarnings("unchecked")
                Set<String> samples = (Set<String>) agg.get("sample_keywords");
                samples.add(fullKeywords);

                // 省份维度二次拆分
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> provinceMap = (Map<String, Map<String, Object>>) agg.get("by_province");
                Map<String, Object> provAgg = provinceMap.computeIfAbsent(province, p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("province", p);
                    m.put("win_count", 0);
                    m.put("total_amount", BigDecimal.ZERO);
                    m.put("last_win_time", null);
                    return m;
                });
                provAgg.put("win_count", numInt(provAgg.get("win_count")) + hitCount);
                BigDecimal provPrevTotal = toBigDecimal(provAgg.get("total_amount"));
                provAgg.put("total_amount", (provPrevTotal == null ? BigDecimal.ZERO : provPrevTotal).add(perHitTotal.multiply(BigDecimal.valueOf(hitCount))));
                Object provLatest = row.get("last_win_time");
                Object provPrevLatest = provAgg.get("last_win_time");
                if (provLatest != null && (provPrevLatest == null || provLatest.toString().compareTo(provPrevLatest.toString()) > 0)) {
                    provAgg.put("last_win_time", provLatest);
                }
            }
        }

        // 转 list + 按 win_count 倒序 + 取 limit
        List<Map<String, Object>> items = new ArrayList<>(kwAgg.values());
        items.sort((a, b) -> Integer.compare(numInt(b.get("win_count")), numInt(a.get("win_count"))));
        if (items.size() > limit) items = items.subList(0, limit);

        // 清理 sample_keywords set（变 list） + by_province 排序 + 算 avg_price
        for (Map<String, Object> item : items) {
            @SuppressWarnings("unchecked")
            Set<String> samples = (Set<String>) item.remove("sample_keywords");
            item.put("sample_keywords", samples == null ? Collections.emptyList() : new ArrayList<>(samples));

            // by_province 转 list，按 win_count 倒序 + 算 avg_price
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> provMap = (Map<String, Map<String, Object>>) item.remove("by_province");
            List<Map<String, Object>> provinceList = provMap == null ? Collections.emptyList() : new ArrayList<>(provMap.values());
            for (Map<String, Object> prov : provinceList) {
                BigDecimal provTotal = toBigDecimal(prov.get("total_amount"));
                int provWc = numInt(prov.get("win_count"));
                BigDecimal provAvg = provWc > 0 && provTotal != null
                        ? provTotal.divide(BigDecimal.valueOf(provWc), 2, java.math.RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                prov.put("avg_price", provAvg);
                prov.put("avg_price_wan", yuanToWan(provAvg));
            }
            provinceList.sort((a, b) -> Integer.compare(numInt(b.get("win_count")), numInt(a.get("win_count"))));
            item.put("by_province", provinceList);

            BigDecimal total = toBigDecimal(item.get("total_amount"));
            int wc = numInt(item.get("win_count"));
            BigDecimal avg = wc > 0 && total != null
                    ? total.divide(BigDecimal.valueOf(wc), 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            item.put("avg_price", avg);
            item.put("avg_price_wan", yuanToWan(avg));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keywords", keywords);
        data.put("view_by", "keyword");
        data.put("total", items.size());
        data.put("brands", items);
        data.put("data_freshness_note", "按 win_bid.keywords 字段（逗号分隔）拆出单个关键词后再聚合；同一项目会贡献到多个关键词中；brands[].by_province 显示该关键词的省份分布（win_count 倒序）");
        return success(data);
    }

    /**
     * aggregate_bids_advanced：按 group_by 维度（province/industry/month/date）聚合 stats。
     * bid_type 兼容两种写法：中文（招标/中标/商机/采购/全部，与 search_bids 一致）或数字（1=招标 2=中标，历史约定）。
     * 商机 → prepose 表；采购 → purchase_intention 表；全部/未指定 = 四张表合并。
     * 注意：prepose 金额列数据不严格（部分为空），sum_amount 仅累计有金额的记录。
     */
    public Map<String, Object> aggregateBidsAdvanced(Map<String, Object> req) {
        Map<String, Object> filters = req == null ? null : (Map<String, Object>) req.get("filters");
        if (filters == null) {
            filters = new LinkedHashMap<>();
        } else {
            filters = new LinkedHashMap<>(filters);
        }
        // 省份授权下推：把 Controller 注入的授权列表透传给 filters，供 resolveQueryProvinces 求交集
        if (req != null && req.containsKey(AUTH_PROVINCES_KEY)) {
            filters.put(AUTH_PROVINCES_KEY, req.get(AUTH_PROVINCES_KEY));
        }
        if (provinceUnauthorized(filters)) {
            return unauthorizedProvinceError();
        }
        List<String> keywords = listOr(filters, "keywords");
        List<String> provinces = resolveQueryProvinces(filters);
        BigDecimal minBudget = numOr(filters, "min_money");
        if (minBudget == null) minBudget = numOr(filters, "min_amount");
        BigDecimal maxBudget = numOr(filters, "max_money");
        if (maxBudget == null) maxBudget = numOr(filters, "max_amount");
        String beginDate = strOr(filters, "begin_date", null);
        String endDate = strOr(filters, "end_date", null);
        // bid_type 解析：兼容中文与数字。此前用 intOrNull 只认数字，
        // agent 传 "商机"/"采购" 时会被静默当成 null（=全部）且只聚合招标+中标两张表，产生口径误报
        Object bidTypeRaw = filters.get("bid_type");
        String bidType = bidTypeRaw == null ? "" : bidTypeRaw.toString().trim();
        List<String> groupBy = listOr(req, "group_by");
        if (groupBy.isEmpty()) groupBy = listOr(req, "groupBy");
        if (groupBy.isEmpty()) groupBy = List.of("province");

        String gb = groupBy.get(0);
        boolean amountBucket = "amount_bucket".equals(gb);
        int limit = Math.min(intOr(req, "limit", 50), 200);

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean wantAll = bidType.isEmpty() || "全部".equals(bidType);
        boolean wantBidding = wantAll || "招标".equals(bidType) || "1".equals(bidType);
        boolean wantWinner = wantAll || "中标".equals(bidType) || "2".equals(bidType);
        boolean wantPrepose = wantAll || "商机".equals(bidType);
        boolean wantPurchaseIntention = wantAll || "采购".equals(bidType);
        if (wantBidding) {
            rows.addAll(biddingMapper.aggregateBidsAsCaller(keywords, provinces, minBudget, maxBudget, beginDate, endDate, gb, limit));
        }
        if (wantWinner) {
            rows.addAll(winnerMapper.aggregateBidsAsWinner(keywords, provinces, minBudget, maxBudget, beginDate, endDate, gb, limit));
        }
        if (wantPrepose) {
            // 商机表金额列数据不严格（部分为空），sum_amount 仅累计有金额的记录
            rows.addAll(preposeMapper.aggregateBids(keywords, provinces, minBudget, maxBudget, beginDate, endDate, gb, limit));
        }
        if (wantPurchaseIntention) {
            rows.addAll(purchaseIntentionMapper.aggregateBids(keywords, provinces, minBudget, maxBudget, beginDate, endDate, gb, limit));
        }

        // 合并求和（groupBy 维度 + 同 bucket_key）
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = strOf(row, "bucket_key");
            if (key == null) continue;
            // amount_bucket 档位在 SQL 里带 "N-" 序号前缀（保证跨表合并稳定），返回给调用方前剥掉
            if (amountBucket && key.length() > 2 && key.charAt(1) == '-') {
                key = key.substring(2);
            }
            Map<String, Object> m = merged.computeIfAbsent(key, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("key", k);
                x.put("count", 0);
                x.put("sum_amount", BigDecimal.ZERO);
                x.put("disclosed_count", 0);
                return x;
            });
            Object cnt = row.get("cnt");
            int c = cnt instanceof Number ? ((Number) cnt).intValue() : 0;
            m.put("count", numInt(m.get("count")) + c);
            // 金额披露条数（金额非空且 > 0 的记录数），agent 算披露率可直接引用，无需 min_amount 探针
            Object dc = row.get("disclosed_cnt");
            m.put("disclosed_count", numInt(m.get("disclosed_count")) + (dc instanceof Number ? ((Number) dc).intValue() : 0));
            BigDecimal amt = toBigDecimal(row.get("sum_amount"));
            BigDecimal cur = toBigDecimal(m.get("sum_amount"));
            BigDecimal merged_amt = cur == null ? amt : (amt == null ? cur : cur.add(amt));
            m.put("sum_amount", merged_amt);
        }
        // 汇总必须在 merge 完成后基于每个 bucket 的最终值计算：
        // 之前在行循环里累加 merged_amt，会把同一 bucket 的中间累计值重复计入（多表合并时同一 key 必然多行），导致 total_amount 虚高
        long totalCount = 0;
        long totalDisclosedCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> m : merged.values()) {
            totalCount += numInt(m.get("count"));
            totalDisclosedCount += numInt(m.get("disclosed_count"));
            BigDecimal amt = toBigDecimal(m.get("sum_amount"));
            if (amt != null) totalAmount = totalAmount.add(amt);
        }
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (Map<String, Object> m : merged.values()) {
            BigDecimal sum = toBigDecimal(m.get("sum_amount"));
            m.put("sum_amount_wan", yuanToWan(sum));
            // 均价基于有金额记录（disclosed_count）计算，避免未披露金额条数稀释均值
            int disclosed = numInt(m.get("disclosed_count"));
            if (disclosed > 0 && sum != null && sum.signum() > 0) {
                BigDecimal avg = sum.divide(BigDecimal.valueOf(disclosed), 2, RoundingMode.HALF_UP);
                m.put("avg_amount", avg);
                m.put("avg_amount_wan", yuanToWan(avg));
            }
            buckets.add(m);
        }
        if (amountBucket) {
            // 金额档按从小到大固定顺序输出，而不是按 count 倒序
            List<String> order = List.of("小于10万", "10万-50万", "50万-100万", "100万-300万",
                    "300万-500万", "500万-1000万", "大于等于1000万", "未披露");
            buckets.sort(Comparator.comparingInt((Map<String, Object> b) -> {
                int idx = order.indexOf(strOf(b, "key"));
                return idx < 0 ? order.size() : idx;
            }));
        } else {
            buckets.sort((a, b) -> Integer.compare(numInt(b.get("count")), numInt(a.get("count"))));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total_count", totalCount);
        data.put("total_disclosed_count", totalDisclosedCount);
        data.put("total_amount", totalAmount);
        data.put("total_amount_wan", yuanToWan(totalAmount));
        data.put("buckets", buckets);
        data.put("group_by", groupBy);
        if (amountBucket) {
            data.put("_note", "amount_bucket=金额分档（单位元，从小到大）；金额未披露（null 或 0）的记录单独记入'未披露'档；sum_amount/avg_amount 仅基于有金额记录计算；top-level total_disclosed_count 为有金额记录总数");
        }
        if (wantPrepose) {
            data.put("data_freshness_note", "bid_type 含商机（prepose 表）时，sum_amount 仅累计有金额的记录（商机表金额列数据不严格）");
        }
        return success(data);
    }

    // ============================================================
    // 字段映射工具
    // ============================================================

    /** Bidding → tender-search item 字段映射 */
    private Map<String, Object> toBidItem(BidBiddingEntity e, int bidProcess, String bidTypeLabel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bid_id", e.getId());
        m.put("title", e.getTitle());
        m.put("bid_type", bidTypeLabel);
        m.put("bid_process", bidProcess);
        m.put("pub_time", e.getPublishTime() == null ? null : e.getPublishTime().format(DATE_FMT));
        // DB 里存的就是元；money=元（直接透传），money_wan=万元（元 ÷ 10000）
        BigDecimal amount = e.getBiddingBudget();
        m.put("money", amount);
        m.put("money_wan", yuanToWan(amount));
        m.put("caller_name", e.getTenderer());                       // 招标方
        m.put("winner_names", Collections.emptyList());
        m.put("sm_names", e.getProduct() == null ? Collections.emptyList() : List.of(e.getProduct()));
        m.put("province", e.getProvince());
        m.put("city", e.getCity());
        m.put("county", e.getDistrict());
        m.put("url", e.getDetailLink() != null ? e.getDetailLink() : e.getBidUrl());
        return m;
    }

    /** Winner → tender-search item 字段映射 */
    private Map<String, Object> toBidItem(BidWinnerEntity e, int bidProcess, String bidTypeLabel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bid_id", e.getId());
        m.put("title", e.getTitle());
        m.put("bid_type", bidTypeLabel);
        m.put("bid_process", bidProcess);
        m.put("pub_time", e.getPublishTime() == null ? null : e.getPublishTime().format(DATE_FMT));
        // 中标金额优先 win_bid_price，没有则用 bidding_budget；DB 单位=元
        BigDecimal winAmount = e.getWinBidPrice() != null ? e.getWinBidPrice() : e.getBiddingBudget();
        m.put("money", winAmount);
        m.put("money_wan", yuanToWan(winAmount));
        m.put("caller_name", e.getTenderer());
        m.put("winner_names", e.getWinTenderer() == null ? Collections.emptyList() : List.of(e.getWinTenderer()));
        m.put("sm_names", e.getProduct() == null ? Collections.emptyList() : List.of(e.getProduct()));
        m.put("province", e.getProvince());
        m.put("city", e.getCity());
        m.put("county", e.getDistrict());
        m.put("url", e.getBidUrl());
        return m;
    }

    /** Prepose → tender-search item 字段映射（金额=空字符串，因 prepose 是项目储备） */
    private Map<String, Object> toBidItem(BidPreposeEntity e, int bidProcess, String bidTypeLabel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bid_id", e.getId());
        m.put("title", e.getTitle());
        m.put("bid_type", bidTypeLabel);
        m.put("bid_process", bidProcess);
        m.put("pub_time", e.getPublishTime() == null ? null : e.getPublishTime().format(DATE_FMT));
        m.put("money", null);        // null（SKILL 约定空金额展示为 —，不再用空字符串）
        m.put("money_wan", null);
        m.put("caller_name", e.getTenderer());
        m.put("winner_names", Collections.emptyList());
        m.put("sm_names", e.getProduct() == null ? Collections.emptyList() : List.of(e.getProduct()));
        m.put("province", e.getProvince());
        m.put("city", e.getCity());
        m.put("county", e.getDistrict());
        m.put("url", e.getDetailLink() != null ? e.getDetailLink() : e.getBidUrl());
        return m;
    }

    /** PurchaseIntention → tender-search item 字段映射 */
    private Map<String, Object> toBidItem(BidPurchaseIntentionEntity e, int bidProcess, String bidTypeLabel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bid_id", e.getId());
        m.put("title", e.getTitle());
        m.put("bid_type", bidTypeLabel);
        m.put("bid_process", bidProcess);
        m.put("pub_time", e.getPublishTime() == null ? null : e.getPublishTime().format(DATE_FMT));
        // DB 单位=元
        BigDecimal amount = e.getBiddingBudget();
        m.put("money", amount);
        m.put("money_wan", yuanToWan(amount));
        m.put("caller_name", e.getTenderer());
        m.put("winner_names", Collections.emptyList());
        m.put("sm_names", e.getProduct() == null ? Collections.emptyList() : List.of(e.getProduct()));
        m.put("province", e.getProvince());
        m.put("city", e.getCity());
        m.put("county", e.getDistrict());
        m.put("url", e.getDetailLink() != null ? e.getDetailLink() : e.getBidUrl());
        return m;
    }

    // ============================================================
    // 通用工具
    // ============================================================

    /** 与 TenderSearchController.AUTH_PROVINCES_KEY 保持一致：Controller 注入的授权省份内部键 */
    private static final String AUTH_PROVINCES_KEY = "__authorized_provinces";

    /** 取请求中注入的授权省份列表；未注入（内部调用）返回 null 表示不限制 */
    @SuppressWarnings("unchecked")
    private static List<String> authProvinces(Map<String, Object> req) {
        Object v = req == null ? null : req.get(AUTH_PROVINCES_KEY);
        return v instanceof List ? (List<String>) v : null;
    }

    /**
     * 解析查询省份并与授权省份求交集：
     * <ul>
     *   <li>未注入授权列表 → 不限制，直接归一化返回；</li>
     *   <li>用户未传 provinces → 默认限定为授权省份；</li>
     *   <li>用户传了 provinces → 与授权列表取交集；交集为空时返回不可能命中的哨兵值，
     *       防止空列表导致 XML 跳过省份过滤造成越权（配合 provinceUnauthorized 的显式拒绝双重防护）。</li>
     * </ul>
     */
    private static List<String> resolveQueryProvinces(Map<String, Object> req) {
        List<String> provinces = normalizeProvinces(listOr(req, "provinces"));
        List<String> authorized = authProvinces(req);
        if (authorized == null) {
            return provinces;
        }
        List<String> authorizedNorm = normalizeProvinces(authorized);
        if (authorizedNorm.isEmpty()) {
            return provinces;
        }
        if (provinces.isEmpty()) {
            return new ArrayList<>(authorizedNorm);
        }
        List<String> out = provinces.stream().filter(authorizedNorm::contains).collect(Collectors.toList());
        return out.isEmpty() ? List.of("__NO_AUTH_PROVINCE__") : out;
    }

    /** 用户显式传了省份但全部不在授权范围内 → 直接拒绝（优于空结果，便于 agent 提示开通） */
    private static boolean provinceUnauthorized(Map<String, Object> req) {
        List<String> provinces = normalizeProvinces(listOr(req, "provinces"));
        List<String> authorized = authProvinces(req);
        if (authorized == null || authorized.isEmpty() || provinces.isEmpty()) {
            return false;
        }
        List<String> authorizedNorm = normalizeProvinces(authorized);
        return provinces.stream().noneMatch(authorizedNorm::contains);
    }

    /** 省份未授权的统一错误响应（与 Controller error 结构一致） */
    private static Map<String, Object> unauthorizedProvinceError() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", false);
        resp.put("data", null);
        resp.put("message", "所选省份未授权，请联系客户经理进行开通。");
        resp.put("code", "PROVINCE_NOT_AUTHORIZED");
        return resp;
    }

    /**
     * 拆分关键词字符串（用中文逗号 / 英文逗号 / 分号 / 竖线 / 空格 / 中点 分隔）。
     * 用于 prepose.keywords 字段（实际数据格式五花八门："系统,数据治理,平台" / "AI、云计算" / "数据治理|机器学习"）。
     * 同时把 token 修剪干净，去掉明显通用词。
     *
     * @return 去重后的 token 列表（保序）
     */
    private static List<String> keywordsTokenize(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        // 用常见分隔符先拆
        String[] parts = raw.split("[,，;；/|、\\s]+");
        // 通用词黑名单（停用词）—— 只过滤明显无意义的，不主动过滤业务词
        Set<String> STOPWORDS = Set.of(
                "项目", "服务", "采购", "工程", "系统", "建设", "管理", "其他",
                "null", "none", "n/a", "无", "/"
        );
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.length() < 2) continue; // 1 个字不要
            if (t.length() > 20) continue; // 太长（往往是整句）跳过
            if (STOPWORDS.contains(t)) continue;
            out.add(t);
        }
        return new ArrayList<>(out);
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("data", data);
        resp.put("message", null);
        resp.put("meta", Map.of("cost_units", 1));
        return resp;
    }

    private Map<String, Object> stubWithItems() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", 0);
        data.put("items", Collections.emptyList());
        return success(data);
    }

    /** 万元 → 元（保留 2 位小数） */
    private static BigDecimal yuanFromWan(BigDecimal wan) {
        if (wan == null) return null;
        return wan.multiply(WAN_SCALE).setScale(2, RoundingMode.HALF_UP);
    }

    /** 元 → 万元（保留 2 位小数）；聚合 SUM/AVG 返回的可能就是元单位 */
    private static BigDecimal yuanToWan(BigDecimal yuan) {
        if (yuan == null) return null;
        return yuan.divide(WAN_SCALE, 2, RoundingMode.HALF_UP);
    }

    /**
     * 省份名规范化：去掉结尾的"省 / 自治区 / 市"等行政区划后缀。
     * 比如 "广西壮族自治区" → "广西"、"北京市" → "北京"、"新疆维吾尔自治区" → "新疆"、"内蒙古自治区" → "内蒙古"。
     * 注意：本身就叫"北京/上海/天津/重庆"这种直辖市的，做"市"剥离后刚好剩简称，看起来是对的；
     * 但 "吉林市"（地级市）也会被剥成 "吉林"，跟吉林省同名 —— 这个是业务约定，我按"传啥剥啥"的简单规则做。
     * 已知边界：
     *   - "广西壮族自治区" / "新疆维吾尔自治区" / "内蒙古自治区" / "宁夏回族自治区" / "西藏自治区" → 剥成简称
     *   - "香港特别行政区" / "澳门特别行政区" → 剥成 "香港" / "澳门"
     *   - "xx省" / "xx市" → 直接剥
     *   - 已经是简称的（"广西"、"北京"）→ 原样返回
     */
    private static final java.util.regex.Pattern PROVINCE_SUFFIX =
            java.util.regex.Pattern.compile(
                    "(自治区|特别行政区|省|市)$");

    private static String normalizeProvince(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return trimmed;
        // 4 个民族自治区：先剥"自治区"再剥"族"会出问题，统一白名单处理
        // 广西 / 新疆 / 宁夏 / 西藏 / 内蒙古
        if (trimmed.endsWith("壮族自治区")) return trimmed.substring(0, trimmed.length() - "壮族自治区".length());
        if (trimmed.endsWith("维吾尔自治区")) return trimmed.substring(0, trimmed.length() - "维吾尔自治区".length());
        if (trimmed.endsWith("回族自治区")) return trimmed.substring(0, trimmed.length() - "回族自治区".length());
        if (trimmed.endsWith("自治区")) {
            // 剩下的"内蒙古自治区" / "西藏自治区" → 剥"自治区"
            return trimmed.substring(0, trimmed.length() - "自治区".length());
        }
        if (trimmed.endsWith("特别行政区")) return trimmed.substring(0, trimmed.length() - "特别行政区".length());
        // 通用：省 / 市
        return PROVINCE_SUFFIX.matcher(trimmed).replaceAll("");
    }

    private static List<String> normalizeProvinces(List<String> provinces) {
        if (provinces == null || provinces.isEmpty()) return provinces;
        List<String> out = new ArrayList<>(provinces.size());
        for (String p : provinces) {
            String n = normalizeProvince(p);
            if (n != null && !n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** 安全把 MyBatis Map 里的数值转 BigDecimal（处理 Integer/Long/Double/String） */
    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return new BigDecimal(v.toString());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    private static String strOr(Map<String, Object> m, String key, String def) {
        Object v = m == null ? null : m.get(key);
        return v == null ? def : v.toString();
    }

    /** 安全从 MyBatis 返回的 Map 里取 String（处理 null） */
    private static String strOf(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    /** 安全从 Map 里取 Integer（处理 Number/String/null） */
    private static Integer numInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOr(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return Collections.emptyList();
        if (v instanceof List) return (List<String>) v;
        return Collections.singletonList(v.toString());
    }

    /** 解析 `[["a","b"], ["c"]]` 这种 List-of-List 结构（query_bids_advanced 的 keyword_groups）
     * 兼容 SKILL 文档中的对象数组格式：`[{"keywords": ["a","b"]}, ...]`——
     * 旧版只认二维数组，对象形式会被静默丢弃导致 AND 条件全部失效。
     */
    @SuppressWarnings("unchecked")
    private static List<List<String>> listOfListOr(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null || !(v instanceof List)) return Collections.emptyList();
        List<List<String>> result = new ArrayList<>();
        for (Object item : (List<Object>) v) {
            if (item instanceof List) {
                List<String> inner = new ArrayList<>();
                for (Object s : (List<Object>) item) inner.add(s == null ? "" : s.toString());
                if (!inner.isEmpty()) result.add(inner);
            } else if (item instanceof Map) {
                // 对象格式：{"keywords": ["a","b"]}
                Object kws = ((Map<String, Object>) item).get("keywords");
                if (kws == null) continue;
                List<String> inner = new ArrayList<>();
                if (kws instanceof List) {
                    for (Object s : (List<Object>) kws) inner.add(s == null ? "" : s.toString());
                } else if (!kws.toString().isBlank()) {
                    inner.add(kws.toString());
                }
                if (!inner.isEmpty()) result.add(inner);
            }
        }
        return result;
    }

    private static BigDecimal numOr(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return null;
        if (v instanceof Number) return new BigDecimal(v.toString());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    private static int intOr(Map<String, Object> m, String key, int def) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return def; }
    }

    /** 安全从 Map 里取 boolean（支持 Boolean / "true" / "1"，null → def） */
    private static boolean boolOr(Map<String, Object> m, String key, boolean def) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim();
        if (s.isEmpty()) return def;
        if ("1".equals(s)) return true;
        if ("0".equals(s)) return false;
        return Boolean.parseBoolean(s);
    }

    private static Integer intOrNull(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    /** null → "—"（用于响应展示空值） */
    private static String nullToDash(Object v) {
        if (v == null) return "—";
        String s = v.toString().trim();
        return s.isEmpty() ? "—" : s;
    }

    /** 单参版本：String 安全转 String（null → ""） */
    private static String strOf(String s) {
        return s == null ? "" : s;
    }

    /** 安全从任意对象取 long（null/格式错 → 0） */
    private static long numLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0L; }
    }

    /** 计算分位数（线性插值，q ∈ [0,1]）。输入列表需已排序。 */
    private static BigDecimal percentile(List<BigDecimal> sorted, double q) {
        if (sorted == null || sorted.isEmpty()) return BigDecimal.ZERO;
        if (q <= 0) return sorted.get(0);
        if (q >= 1) return sorted.get(sorted.size() - 1);
        double pos = q * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted.get(lo);
        BigDecimal lower = sorted.get(lo);
        BigDecimal upper = sorted.get(hi);
        BigDecimal weight = BigDecimal.valueOf(pos - lo);
        return lower.add(upper.subtract(lower).multiply(weight)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** 计算均值（BigDecimal 列表，空 → 0） */
    private static BigDecimal avgOf(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) sum = sum.add(v == null ? BigDecimal.ZERO : v);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, java.math.RoundingMode.HALF_UP);
    }
}
