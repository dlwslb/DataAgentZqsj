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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 业务数据查询 Tool - Druid 直连 4 张业务表（绕开 1.0 的 NL2SQL 慢链路）
 *
 * <p>对比 1.0：
 * <ul>
 *   <li>1.0: NL2SQL 链路 → LLM 动态生成 SQL → 校验 → Druid 执行（5-15 秒）</li>
 *   <li>2.0: Skill 模板化查询 → LLM 选 skill + 拼 conditions → 直查 Druid（1-3 秒）</li>
 * </ul>
 *
 * <p>业务表（都继承 TenantBaseDO，多租户字段 tenant_id）：
 * <ul>
 *   <li>bid_biz_win_bid        —— 中标信息（含 winTenderer / winBidPrice）</li>
 *   <li>bid_biz_bidding        —— 招标信息</li>
 *   <li>bid_biz_purchase_intention —— 采购意向</li>
 *   <li>bid_biz_prepose        —— 前期项目</li>
 * </ul>
 *
 * <p>通用字段（4 表都有）：title / projectName / publishTime(LocalDate) / province / city / district /
 * industry / infoType / biddingBudget(BigDecimal) / tenderer / channel / webSourceName / bidUrl / product / keywords
 *
 * <p>win_bid 独有：winTenderer / winBidPrice / secondTenderer / thirdTenderer
 */
@Slf4j
@Component
public class QueryBizDataTool {

    private final JdbcTemplate businessJdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * bizType → 表名 映射
     *
     * <p>业务表在 chatbi 库，用跨库查询（businessDataSource 连 tender_data_agent，
     * 但 MySQL 跨库查询 chatbi.{table} 需要当前用户对两个库都有权限）。
     */
    private static final Map<String, String> BIZ_TYPE_TO_TABLE = Map.of(
            "bid_winner", "chatbi.bid_biz_win_bid",
            "bidding", "chatbi.bid_biz_bidding",
            "purchase_intention", "chatbi.bid_biz_purchase_intention",
            "prepose", "chatbi.bid_biz_prepose",
            "origin_announcement", "chatbi.bid_origin_announcement"
    );

    /**
     * 哪些表有"中标单位"字段
     */
    private static final Set<String> HAS_WINNER = Set.of("chatbi.bid_biz_win_bid", "chatbi.bid_origin_announcement");

    /**
     * 哪些表没有多租户字段 tenant_id（不需加 WHERE tenant_id 条件）
     */
    private static final Set<String> NO_TENANT_TABLE = Set.of("chatbi.bid_origin_announcement");

    @Autowired(required = false)
    public QueryBizDataTool(@Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate) {
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    @Tool(name = "query_biz_data",
          description = "直接查询业务数据表（不走 NL2SQL，速度快）。" +
                  "bizType 支持: bid_winner(中标信息) / bidding(招标信息) / purchase_intention(采购意向) / prepose(前期项目) / origin_announcement(原始每日标讯，今日/昨日查这个)。" +
                  "对应物理表: chatbi.bid_biz_win_bid / chatbi.bid_biz_bidding / chatbi.bid_biz_purchase_intention / chatbi.bid_biz_prepose / chatbi.bid_origin_announcement。" +
                  "tenantId 由框架自动注入（从 session 取），调用时**不要传**。" +
                  "datePreset 快捷日期（与 startDate/endDate 互斥，datePreset 优先）: " +
                  "today(今天) / yesterday(昨天) / thisWeek(本周一-今天) / lastWeek(上周一-上周日) / " +
                  "thisMonth(本月1号-今天) / lastMonth(上月) / last7Days(最近7天) / last30Days(最近30天)。" +
                  "conditions 是 Map<String,Object>，支持 key: " +
                  "province(省份精确匹配) / city(地市精确匹配) / district(区县精确匹配) / " +
                  "industry(行业大类精确匹配) / infoType(行业中类精确匹配) / " +
                  "tenderer(招标单位 LIKE 模糊匹配) / " +
                  "winTenderer(中标单位 LIKE 模糊匹配, 仅 win_bid 表生效) / " +
                  "keyword(项目名/标题/关键词 模糊匹配) / " +
                  "channel(公告类型精确匹配) / webSourceName(网站来源精确匹配) / " +
                  "startDate(开始日期 yyyy-MM-dd, publishTime >= startDate) / " +
                  "endDate(截止日期 yyyy-MM-dd, publishTime <= endDate) / " +
                  "minBudget(最小预算, biddingBudget/winBidPrice) / " +
                  "maxBudget(最大预算, biddingBudget/winBidPrice)。" +
                  "limit 默认 20，最大 100，按 publishTime 降序。" +
                  "注意：金额单位 bidding/bid_winner 用元，purchase_intention/prepose 用万元。" +
                  "deleted=0 逻辑删除过滤自动加，不用传。",
          readOnly = true,
          concurrencySafe = true)
    public Mono<String> queryBizData(
            @ToolParam(name = "bizType", description = "业务类型: bid_winner / bidding / purchase_intention / prepose / origin_announcement（原始每日标讯）", required = true) String bizType,
            @ToolParam(name = "tenantId", description = "租户 ID（多租户隔离）。bizType=origin_announcement 时不要传此字段", required = false) Long tenantId,
            @ToolParam(name = "datePreset", description = "快捷日期预设: today/yesterday/thisWeek/lastWeek/thisMonth/lastMonth/last7Days/last30Days（与 startDate/endDate 互斥，datePreset 优先）") String datePreset,
            @ToolParam(name = "conditions", description = "查询条件 Map<String,Object>") Map<String, Object> conditions,
            @ToolParam(name = "limit", description = "返回条数限制，默认 20，最大 100") Integer limit) {

        log.info("🔍 [query_biz_data] bizType={}, tenantId={}, datePreset={}, conditions={}, limit={}",
                bizType, tenantId, datePreset, conditions, limit);

        // 同步 JdbcTemplate 必须在 boundedElastic 线程池跑（不能在 Netty event loop 阻塞）
        return Mono.fromCallable(() -> {
        if (businessJdbcTemplate == null) {
            return errorJson("业务数据源未配置（agentscope.datasource.business.url 为空），" +
                    "请在 application.yml 配置 BUSINESS_DB_URL / USERNAME / PASSWORD");
        }
        if (bizType == null || bizType.isBlank()) {
            return errorJson("bizType 不能为空，支持: bid_winner / bidding / purchase_intention / prepose / origin_announcement");
        }
        if ((tenantId == null || tenantId == 0) && !NO_TENANT_TABLE.contains(BIZ_TYPE_TO_TABLE.get(bizType.toLowerCase()))) {
            return errorJson("tenantId 必填（多租户隔离），origin_announcement 除外");
        }
        // origin_announcement 传 0 时也用 null（让 SQL 跳过 tenant_id 条件）
        if (tenantId != null && tenantId == 0L
                && NO_TENANT_TABLE.contains(BIZ_TYPE_TO_TABLE.get(bizType.toLowerCase()))) {
            log.info("🔧 [origin_announcement] tenantId=0 → 视为 null（无租户过滤）");
        }

        String tableName = BIZ_TYPE_TO_TABLE.get(bizType.toLowerCase());
        if (tableName == null) {
            return errorJson("不支持的 bizType: " + bizType);
        }

        int realLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        Map<String, Object> cond = conditions == null ? new HashMap<>() : new HashMap<>(conditions);

        // datePreset 优先：把快捷日期算成 startDate/endDate，覆盖到 conditions
        if (datePreset != null && !datePreset.isBlank()) {
            LocalDate[] range = parseDatePreset(datePreset);
            if (range == null) {
                return errorJson("不支持的 datePreset: " + datePreset +
                        "，支持: today/yesterday/thisWeek/lastWeek/thisMonth/lastMonth/last7Days/last30Days");
            }
            cond.put("startDate", range[0].toString());
            cond.put("endDate", range[1].toString());
            log.info("📅 [datePreset={}] 解析为 startDate={}, endDate={}",
                    datePreset, range[0], range[1]);

            // 🔑 路由守卫：datePreset=today/yesterday 时，旧 bizType 必须改用 origin_announcement
            // 强制纠正 LLM 的幻觉路由（避免它"脑补"参数验证失败退回老表）
            String bizTypeLower = bizType == null ? "" : bizType.toLowerCase();
            if (("today".equalsIgnoreCase(datePreset) || "yesterday".equalsIgnoreCase(datePreset))
                    && !bizTypeLower.contains("origin")) {
                return errorJson("🚫 datePreset=" + datePreset + " 必须用 bizType=origin_announcement（原始每日标讯表 chatbi.bid_origin_announcement），" +
                        "不要用 " + bizType + "。请重新调用：bizType=\"origin_announcement\"，datePreset=\"" + datePreset + "\"，" +
                        "channel 可选（招标/中标/采购/前置商机），tenantId 不传");
            }
        }

        // 构建 SQL：SELECT * FROM {table} WHERE tenant_id = ? AND deleted = 0 [AND ...] ORDER BY publish_time DESC LIMIT ?
        SqlWithParams sqlWithParams = buildSelectSql(tableName, tenantId, cond, realLimit);

        try {
            log.debug("📊 [query_biz_data] SQL: {} | params: {}", sqlWithParams.sql, sqlWithParams.params);

            List<Map<String, Object>> rawResult = businessJdbcTemplate.queryForList(
                    sqlWithParams.sql, sqlWithParams.params.toArray());

            // 字段归一化：把表特有字段统一成 "amount"（win_bid 用 winBidPrice，其他用 biddingBudget）
            List<Map<String, Object>> resultSet = rawResult.stream()
                    .map(this::normalizeFields)
                    .collect(Collectors.toList());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("bizType", bizType);
            response.put("table", tableName);
            response.put("tenantId", tenantId);
            response.put("total", resultSet.size());
            response.put("conditions", cond);
            response.put("resultSet", resultSet);
            // 明确告诉 LLM 调用是成功的，0 条不代表出错
            response.put("message", resultSet.isEmpty()
                    ? "查询成功，暂无匹配数据（不是错误，不要再调用其他表）"
                    : "查询成功，返回 " + resultSet.size() + " 条数据");

            log.info("✅ [query_biz_data] 返回 {} 条数据, table={}", resultSet.size(), tableName);
            return toJson(response);

        } catch (Exception e) {
            log.error("❌ [query_biz_data] 查询失败, bizType={}, error={}", bizType, e.getMessage(), e);
            return errorJson("查询失败: " + e.getMessage());
        }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 字段归一化：把表特有字段统一，方便 LLM/Skill 处理
     */
    private Map<String, Object> normalizeFields(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>(row);
        // 金额归一
        if (row.containsKey("winBidPrice") && row.get("winBidPrice") != null) {
            normalized.put("amount", row.get("winBidPrice"));
            normalized.put("amountType", "winBidPrice");
        } else if (row.containsKey("biddingBudget") && row.get("biddingBudget") != null) {
            normalized.put("amount", row.get("biddingBudget"));
            normalized.put("amountType", "biddingBudget");
        }
        // 日期归一（snake_case → camelCase）
        if (row.containsKey("publish_time")) {
            normalized.put("publishTime", row.get("publish_time"));
        }
        return normalized;
    }

    /**
     * 动态拼 SQL（参数化，防注入）
     * <p>注意：多租户隔离 + 逻辑删除 是硬编码 WHERE，必须有
     */
    private SqlWithParams buildSelectSql(String tableName, Long tenantId, Map<String, Object> conditions, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(tableName);
        List<Object> params = new ArrayList<>();
        List<String> where = new ArrayList<>();

        // 多租户隔离（硬性），origin_announcement 表无此字段
        if (!NO_TENANT_TABLE.contains(tableName)) {
            where.add("tenant_id = ?");
            params.add(tenantId);
            // 逻辑删除过滤（MyBatis Plus 通用约定）
            where.add("deleted = 0");
        }

        // ========== 通用字段（4 表都有） ==========

        if (conditions.containsKey("province") && conditions.get("province") != null) {
            where.add("province = ?");
            params.add(conditions.get("province").toString());
        }
        if (conditions.containsKey("city") && conditions.get("city") != null) {
            where.add("city = ?");
            params.add(conditions.get("city").toString());
        }
        if (conditions.containsKey("district") && conditions.get("district") != null) {
            where.add("district = ?");
            params.add(conditions.get("district").toString());
        }
        if (conditions.containsKey("industry") && conditions.get("industry") != null) {
            where.add("industry = ?");
            params.add(conditions.get("industry").toString());
        }
        if (conditions.containsKey("infoType") && conditions.get("infoType") != null) {
            where.add("info_type = ?");
            params.add(conditions.get("infoType").toString());
        }
        if (conditions.containsKey("channel") && conditions.get("channel") != null) {
            // channel 字段在表里存的是具体子类型（中标信息/合同公告/招标文件等），
            // LLM 传的是大类名（中标/招标/采购/前置商机），用 LIKE 模糊匹配
            where.add("channel LIKE ?");
            params.add("%" + conditions.get("channel").toString() + "%");
        }
        if (conditions.containsKey("webSourceName") && conditions.get("webSourceName") != null) {
            where.add("web_source_name = ?");
            params.add(conditions.get("webSourceName").toString());
        }
        if (conditions.containsKey("tenderer") && conditions.get("tenderer") != null) {
            // 招标单位模糊匹配
            where.add("tenderer LIKE ?");
            params.add("%" + conditions.get("tenderer").toString() + "%");
        }

        // ========== win_bid 表独有 ==========
        if (HAS_WINNER.contains(tableName) && conditions.containsKey("winTenderer")
                && conditions.get("winTenderer") != null) {
            // 中标单位模糊匹配
            where.add("win_tenderer LIKE ?");
            params.add("%" + conditions.get("winTenderer").toString() + "%");
        }

        // ========== 关键词（模糊匹配多个字段） ==========
        if (conditions.containsKey("keyword") && conditions.get("keyword") != null) {
            String like = "%" + conditions.get("keyword").toString() + "%";
            where.add("(title LIKE ? OR project_name LIKE ? OR keywords LIKE ?)");
            params.add(like);
            params.add(like);
            params.add(like);
        }

        // ========== 日期范围 ==========
        if (conditions.containsKey("startDate") && conditions.get("startDate") != null) {
            where.add("publish_time >= ?");
            params.add(conditions.get("startDate").toString());
        }
        if (conditions.containsKey("endDate") && conditions.get("endDate") != null) {
            where.add("publish_time <= ?");
            params.add(conditions.get("endDate").toString());
        }

        // ========== 金额范围 ==========
        if (conditions.containsKey("minBudget") && conditions.get("minBudget") != null) {
            // win_bid 用 win_bid_price，其他用 bidding_budget
            String amountCol = HAS_WINNER.contains(tableName) ? "win_bid_price" : "bidding_budget";
            where.add(amountCol + " >= ?");
            params.add(toBigDecimal(conditions.get("minBudget")));
        }
        if (conditions.containsKey("maxBudget") && conditions.get("maxBudget") != null) {
            String amountCol = HAS_WINNER.contains(tableName) ? "win_bid_price" : "bidding_budget";
            where.add(amountCol + " <= ?");
            params.add(toBigDecimal(conditions.get("maxBudget")));
        }

        sql.append(" WHERE ").append(String.join(" AND ", where));
        // 排序字段：snake_case（MySQL 字段）
        sql.append(" ORDER BY publish_time DESC LIMIT ?");
        params.add(limit);

        return new SqlWithParams(sql.toString(), params);
    }

    private java.math.BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return new java.math.BigDecimal(n.toString());
        return new java.math.BigDecimal(o.toString());
    }

    /**
     * 把 datePreset 解析成 [startDate, endDate]
     * <p>datePreset 支持：
     * <ul>
     *   <li>today       —— 今天</li>
     *   <li>yesterday   —— 昨天</li>
     *   <li>thisWeek    —— 本周一 到 今天</li>
     *   <li>lastWeek    —— 上周一 到 上周日</li>
     *   <li>thisMonth   —— 本月 1 号 到 今天</li>
     *   <li>lastMonth   —— 上月 1 号 到 上月最后一天</li>
     *   <li>last7Days   —— 今天 - 7 到 今天</li>
     *   <li>last30Days  —— 今天 - 30 到 今天</li>
     * </ul>
     */
    private LocalDate[] parseDatePreset(String preset) {
        if (preset == null) return null;
        LocalDate today = LocalDate.now();
        return switch (preset.toLowerCase()) {
            case "today" -> new LocalDate[]{today, today};
            case "yesterday" -> {
                LocalDate y = today.minusDays(1);
                yield new LocalDate[]{y, y};
            }
            case "thisweek" -> {
                LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
                yield new LocalDate[]{monday, today};
            }
            case "lastweek" -> {
                LocalDate thisMonday = today.with(java.time.DayOfWeek.MONDAY);
                LocalDate lastMonday = thisMonday.minusDays(7);
                LocalDate lastSunday = thisMonday.minusDays(1);
                yield new LocalDate[]{lastMonday, lastSunday};
            }
            case "thismonth" -> {
                LocalDate firstDay = today.withDayOfMonth(1);
                yield new LocalDate[]{firstDay, today};
            }
            case "lastmonth" -> {
                LocalDate firstDayThisMonth = today.withDayOfMonth(1);
                LocalDate lastDayLastMonth = firstDayThisMonth.minusDays(1);
                LocalDate firstDayLastMonth = lastDayLastMonth.withDayOfMonth(1);
                yield new LocalDate[]{firstDayLastMonth, lastDayLastMonth};
            }
            case "last7days" -> new LocalDate[]{today.minusDays(7), today};
            case "last30days" -> new LocalDate[]{today.minusDays(30), today};
            default -> null;
        };
    }

    private String errorJson(String msg) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", msg));
        } catch (Exception e) {
            return "{\"error\":\"" + msg.replace("\"", "\\\"") + "\"}";
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            return "{\"error\":\"JSON 序列化失败\"}";
        }
    }

    /** SQL + 参数 封装 */
    private record SqlWithParams(String sql, List<Object> params) {}
}
