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
import com.jldaren.agent.ai.datascope2.auth.AuthContext;
import com.jldaren.agent.ai.datascope2.mapper.BizQueryMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 业务数据查询 Tool - MyBatis XML 动态 SQL 版
 *
 * <p>对比历史版本（StringBuilder 拼 SQL）：
 * <ul>
 *   <li>之前：Tool 内部 StringBuilder 拼 SQL，可读性差，加字段要改 Java 拼装逻辑</li>
 *   <li>现在：SQL 全部在 {@code resources/mapper/BizQueryMapper.xml}，用 {@code <where> + <if test="...">} 动态条件</li>
 *   <li>Tool 只负责：参数准备（provinceList 拆 List、conditions 透传）、bizType 路由、字段归一化</li>
 * </ul>
 *
 * <p>5 张表（都继承 TenantBaseDO，多租户字段 tenant_id）：
 * <ul>
 *   <li>bid_biz_win_bid        —— 中标信息</li>
 *   <li>bid_biz_bidding        —— 招标信息</li>
 *   <li>bid_biz_purchase_intention —— 采购意向</li>
 *   <li>bid_biz_prepose        —— 前期项目</li>
 *   <li>bid_origin_announcement —— 原始每日标讯</li>
 * </ul>
 */
@Slf4j
@Component
public class QueryBizDataTool {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * MyBatis Mapper（5 个表的动态 SQL 都在 XML 里）
     * <p>Mapper 是同步阻塞的，调用方在 {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} 里用
     */
    private final BizQueryMapper bizQueryMapper;

    /**
     * 业务库 JdbcTemplate（保留兼容：业务表查询走 MyBatis Mapper，但 JdbcTemplate 仍用于 schema 健康检查 / 应急 SQL）
     */
    @SuppressWarnings("unused")
    private final JdbcTemplate businessJdbcTemplate;

    @Autowired
    public QueryBizDataTool(BizQueryMapper bizQueryMapper,
                            @org.springframework.beans.factory.annotation.Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate) {
        this.bizQueryMapper = bizQueryMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    @Tool(name = "query_biz_data",
          description = "直接查询业务数据表（不走 NL2SQL，速度快）。" +
                  "bizType 支持: bid_winner(中标信息) / bidding(招标信息) / purchase_intention(采购意向) / prepose(前期项目) / origin_announcement(原始每日标讯，今日/昨日查这个）。" +
                  "对应业务类型: 中标信息 / 招标信息 / 采购意向 / 前期项目 / 原始每日标讯（今日/昨日查这个）。" +
                  "⛔ 【硬性省份权限】province 必传，且必须是用户 authorizedProvince 范围内的省份（系统 prompt 会告诉你授权范围）。" +
                  "   - authorizedProvince='全国' → province 可不传，自动查全量" +
                  "   - authorizedProvince='北京'（单值）→ province='北京'" +
                  "   - authorizedProvince='北京,上海'（多值）→ province='北京' 或 province='上海'（单个传）" +
                  "   - **禁止传 '全国'**（除非 authorizedProvince 本身就是 '全国'）—— 用户输入'全国'也是误传" +
                  "   - 用户问的省份不在授权范围 → 工具会报错，把工具返回的报错信息直接告诉用户即可" +
                  "   - 用户没问省份 → province 传 authorizedProvince 整体（多值用逗号分隔整体传）" +
                  "datePreset 快捷日期（与 startDate/endDate 互斥，datePreset 优先）: " +
                  "today(今天) / yesterday(昨天) / thisWeek(本周一-今天) / lastWeek(上周一-上周日) / " +
                  "thisMonth(本月1号-今天) / lastMonth(上月) / last7Days(最近7天) / last30Days(最近30天)。" +
                  "conditions 是 Map<String,Object>，支持 key: " +
                  "province(省份精确匹配) / city(地市精确匹配) / district(区县精确匹配) / " +
                  "industry(行业大类精确匹配) / infoType(行业中类精确匹配) / " +
                  "tenderer(招标单位 LIKE 模糊匹配) / " +
                  "winTenderer(中标单位 LIKE 模糊匹配, 仅 win_bid / origin_announcement 表生效) / " +
                  "keyword(项目名/标题/关键词 模糊匹配) / " +
                  "channel(公告类型精确匹配) / webSourceName(网站来源精确匹配) / " +
                  "startDate(开始日期 yyyy-MM-dd, publishTime >= startDate) / " +
                  "endDate(截止日期 yyyy-MM-dd, publishTime <= endDate) / " +
                  "minBudget(最小预算, biddingBudget/winBidPrice) / " +
                  "maxBudget(最大预算, biddingBudget/winBidPrice)。" +
                  "limit 默认 20，最大 100，按金额列降序（具体列由表决定：win_bid_price / bidding_budget / win_bid_amount），" +
                  "金额相同时按 publishTime 降序。prepose（前期项目）无金额列，按 publishTime 降序。" +
                  "注意：金额单位用万元。" +
                  "deleted=0 逻辑删除过滤自动加，不用传。",
          readOnly = true,
          concurrencySafe = true)
    public Mono<String> queryBizData(
            @ToolParam(name = "bizType", description = "业务类型: bid_winner / bidding / purchase_intention / prepose / origin_announcement（原始每日标讯）", required = true) String bizType,
            @ToolParam(name = "province", description = "省份。单值如'北京'，多值用逗号分隔如'北京,上海'。必须在用户 authorizedProvince 范围内（'全国' 时可不传）", required = false) String province,
            @ToolParam(name = "datePreset", description = "快捷日期预设: today/yesterday/thisWeek/lastWeek/thisMonth/lastMonth/last7Days/last30Days（与 startDate/endDate 互斥，datePreset 优先）") String datePreset,
            @ToolParam(name = "conditions", description = "查询条件 Map<String,Object>") Map<String, Object> conditions,
            @ToolParam(name = "limit", description = "返回条数限制，默认 20，最大 100") Integer limit) {

        log.info("🔍 [query_biz_data] bizType={}, province={}, datePreset={}, conditions={}, limit={}",
                bizType, province, datePreset, conditions, limit);

        // ⭐ 关键：从 Reactor Context 拿 AuthInfo（跨线程传递）→ 注入 ThreadLocal（同一线程 set/clear）
        return Mono.deferContextual(view -> {
            Object authObj = view.getOrEmpty("authInfo").orElse(null);
            return Mono.fromCallable(() -> {
                if (authObj instanceof AuthContext.AuthInfo a) {
                    AuthContext.set(a);
                }
                try {
                    return doQueryBizData(bizType, province, datePreset, conditions, limit);
                } finally {
                    AuthContext.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    /**
     * 实际业务逻辑（拆出方法方便 try-finally 包裹 ThreadLocal）
     * <p>核心变化：原来用 buildSelectSql() 手拼 SQL，现在用 MyBatis Mapper 调 XML 动态 SQL
     */
    private String doQueryBizData(String bizType, String province,
                                  String datePreset, Map<String, Object> conditions, Integer limit) {
        if (bizType == null || bizType.isBlank()) {
            return errorJson("bizType 不能为空，支持: bid_winner / bidding / purchase_intention / prepose / origin_announcement");
        }

        // ⭐ 硬性省份权限校验（保留 Tool 内部校验逻辑，省份权限必须在 Tool 层兜底）
        AuthContext.AuthInfo auth = AuthContext.get();
        String authorizedProvince = (auth != null && auth.province() != null) ? auth.province() : "";
        authorizedProvince = ProvinceUtil.normalizeList(authorizedProvince);
        if (authorizedProvince.isBlank()) {
            log.warn("⛔ [query_biz_data] 用户无任何省份授权: userId={}, AuthContext={}",auth != null ? auth.userId() : "null", auth);
            return errorJson("您还没开通此省份的数据查询权限,需要联系达仁科技开通一下哦");
        }

        String validatedProvince = validateProvince(province, authorizedProvince);
        if (validatedProvince == null) {
            return errorJson("⛔ 您查询的\"" + (province == null ? "(空)" : province) + "\"不在您开通省份范围(" + authorizedProvince + ")内,请联系客户经理进行开通");
        }

        String bizTypeLower = bizType.toLowerCase();
        TableDef table = BIZ_TABLES.get(bizTypeLower);
        if (table == null) {
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
            log.info("📅 [datePreset={}] 解析为 startDate={}, endDate={}", datePreset, range[0], range[1]);

            // 路由守卫：datePreset=today/yesterday 时强制走 origin_announcement
            if (("today".equalsIgnoreCase(datePreset) || "yesterday".equalsIgnoreCase(datePreset))
                    && !bizTypeLower.contains("origin")) {
                return errorJson("🚫 datePreset=" + datePreset + " 必须用 bizType=origin_announcement（原始每日标讯），" +
                        "不要用 " + bizType + "。请重新调用：bizType=\"origin_announcement\"，datePreset=\"" + datePreset + "\"，" +
                        "province=您授权范围内的省份");
            }
        }

        // ⭐ 组装 MyBatis 参数 Map：provinceList（List<String>）+ 其他 conditions 透传
        Map<String, Object> mapperParams = buildMapperParams(validatedProvince, cond);
        log.info("📊 [query_biz_data] bizType={}, mapperParams={}, limit={}", bizTypeLower, mapperParams, realLimit);

        try {
            // ⭐ 调 MyBatis Mapper：5 个表的方法路由在这里
            //   Mapper 是同步阻塞的，但 Tool 已经在 Mono.fromCallable(...).subscribeOn(boundedElastic) 里被调
            List<Map<String, Object>> rawResult = dispatchToMapper(bizTypeLower, mapperParams, realLimit);

            // 字段归一化：snake_case → 统一 amount / amountType / publishTime(驼峰)
            List<Map<String, Object>> resultSet = rawResult.stream()
                    .map(this::normalizeFields)
                    .collect(Collectors.toList());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("bizType", bizType);
            response.put("province", validatedProvince);
            response.put("total", resultSet.size());
            response.put("conditions", cond);
            response.put("resultSet", resultSet);
            response.put("message", resultSet.isEmpty()
                    ? "查询成功，暂无匹配数据（不是错误，不要再调用其他表）"
                    : "查询成功，返回 " + resultSet.size() + " 条数据");

            log.info("✅ [query_biz_data] 返回 {} 条数据, bizType={}", resultSet.size(), bizTypeLower);
            return toJson(response);

        } catch (Exception e) {
            log.error("❌ [query_biz_data] 查询失败, bizType={}, error={}", bizType, e.getMessage(), e);
            return errorJson("查询失败: " + e.getMessage());
        }
    }

    /**
     * 字段归一化：mapper 返回的 Map key 是 snake_case（MyBatis 对 Map 类型不应用 mapUnderscoreToCamelCase）
     * <p>统一成 amount / amountType / publishTime 驼峰名，方便下游 LLM / Skill 处理
     */
    private Map<String, Object> normalizeFields(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>(row);
        // 金额归一：按表不同的金额列
        if (row.containsKey("win_bid_price") && row.get("win_bid_price") != null) {
            normalized.put("amount", row.get("win_bid_price"));
            normalized.put("amountType", "winBidPrice");
        } else if (row.containsKey("win_bid_amount") && row.get("win_bid_amount") != null) {
            normalized.put("amount", row.get("win_bid_amount"));
            normalized.put("amountType", "winBidAmount");
        } else if (row.containsKey("bidding_budget") && row.get("bidding_budget") != null) {
            normalized.put("amount", row.get("bidding_budget"));
            normalized.put("amountType", "biddingBudget");
        }
        // 日期归一：snake_case publish_time → camelCase publishTime
        if (row.containsKey("publish_time")) {
            normalized.put("publishTime", row.get("publish_time"));
            normalized.remove("publish_time");
        }
        return normalized;
    }

    /**
     * 准备 MyBatis Mapper 参数
     * <p>key 用 snake_case(p.startDate 等)，跟 BizQueryMapper.xml 里的 test 表达式一致
     *
     * @param validatedProvince 已经省份权限校验过的省份字符串（"北京" / "北京,上海" / ""=全量 / null=不加过滤）
     * @param conditions        原始 conditions Map（已经 datePreset 展开）
     */
    private Map<String, Object> buildMapperParams(String validatedProvince, Map<String, Object> conditions) {
        Map<String, Object> params = new HashMap<>(conditions);
        // ⭐ province 拆 List<String>：mapper XML 用 <foreach> 拼 OR 链
        if (validatedProvince != null && !validatedProvince.isBlank() && !"全国".equals(validatedProvince.trim())) {
            String[] provinces = validatedProvince.split(",");
            List<String> validProvinces = new ArrayList<>();
            for (String p : provinces) {
                String t = ProvinceUtil.normalize(p);
                if (!t.isEmpty()) validProvinces.add(t);
            }
            if (!validProvinces.isEmpty()) {
                params.put("provinceList", validProvinces);
            }
        }
        return params;
    }

    /**
     * bizType → MyBatis Mapper 方法路由
     * <p>这里用 switch 显式分支，避免反射 / Map.get 性能损耗
     */
    private List<Map<String, Object>> dispatchToMapper(String bizType, Map<String, Object> params, int limit) {
        return switch (bizType) {
            case "bid_winner" -> bizQueryMapper.queryBidWinner(params, limit);
            case "bidding" -> bizQueryMapper.queryBidding(params, limit);
            case "purchase_intention" -> bizQueryMapper.queryPurchaseIntention(params, limit);
            case "prepose" -> bizQueryMapper.queryPrepose(params, limit);
            case "origin_announcement" -> bizQueryMapper.queryOriginAnnouncement(params, limit);
            default -> throw new IllegalArgumentException("不支持的 bizType: " + bizType);
        };
    }

    /**
     * 表定义：表名 + LLM 需要的字段 + 金额排序列
     * <p>现在排序/列都在 XML 里硬编码了，这里只保留"bizType 是否支持"的校验作用
     */
    private record TableDef(String tableName, String columns, String orderBy) {}

    private static final Map<String, TableDef> BIZ_TABLES = Map.of(
            "bid_winner", new TableDef(
                    "chatbi.bid_biz_win_bid",
                    "project_name, win_tenderer, win_bid_price",
                    "win_bid_price"
            ),
            "bidding", new TableDef(
                    "chatbi.bid_biz_bidding",
                    "project_name, tenderer, bidding_budget",
                    "bidding_budget"
            ),
            "purchase_intention", new TableDef(
                    "chatbi.bid_biz_purchase_intention",
                    "project_name, tenderer, bidding_budget,time_bid_open,time_bid_close",
                    "bidding_budget"
            ),
            "prepose", new TableDef(
                    "chatbi.bid_biz_prepose",
                    "project_name, tenderer, time_bid_open, time_bid_close",
                    "publish_time"
            ),
            "origin_announcement", new TableDef(
                    "chatbi.bid_origin_announcement",
                    "title, province, win_tenderer, win_bid_amount",
                    "win_bid_amount"
            )
    );

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

    /**
     * 校验 province 是否在用户授权范围内
     *
     * <p>逻辑：授权 set + 用户输入 set，做子集校验
     * <ul>
     *   <li>授权"全国" → 不加 province 过滤，全量查（合法授权）</li>
     *   <li>授权为空 → 拒绝查询（用户没被授权，非法状态）</li>
     *   <li>用户没传 → 默认用授权范围整体（体验优化）</li>
     *   <li>用户传"全国" → 跟传"黑龙江"一样严格拒绝（除非授权本身就是"全国"）</li>
     *   <li>用户传的每个省份（逗号分隔）必须都在授权 set 里，否则拒绝</li>
     * </ul>
     *
     * @return null = 校验失败（拒绝查询）
     *         ""   = 不加 province 过滤（全量，仅授权是"全国"时）
     *         "北京" / "北京,上海" = 校验通过
     */
    private String validateProvince(String userInputProvince, String authorizedProvince) {
        String auth = authorizedProvince == null ? "" : authorizedProvince.trim();

        Set<String> authSet = new HashSet<>();
        for (String p : auth.split(",")) {
            String normalized = ProvinceUtil.normalize(p);
            if (!normalized.isEmpty()) authSet.add(normalized);
        }

        if (authSet.isEmpty()) {
            return null;
        }

        if (authSet.contains("全国")) {
            return "";
        }

        if (userInputProvince == null || userInputProvince.isBlank()) {
            return String.join(",", authSet);
        }

        String[] inputs = userInputProvince.split(",");
        for (String p : inputs) {
            String t = ProvinceUtil.normalize(p);
            if (t.isEmpty()) continue;
            if (!authSet.contains(t)) {
                return null;
            }
        }

        List<String> normalizedInputs = new ArrayList<>();
        for (String p : inputs) {
            String t = ProvinceUtil.normalize(p);
            if (!t.isEmpty()) normalizedInputs.add(t);
        }
        return String.join(",", normalizedInputs);
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
}
