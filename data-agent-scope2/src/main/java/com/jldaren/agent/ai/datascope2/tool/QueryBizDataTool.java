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
     * 表定义：表名 + LLM 需要的字段 + 金额排序列（null/空时 fallback 到 publish_time DESC）
     * <p>orderBy 字段名用 snake_case（MySQL 实际列名）
     */
    private record TableDef(String tableName, String columns, String orderBy) {}

    /**
     * bizType → 表定义（表名 + 查询字段 + 金额排序列）
     * <p>orderBy 说明：
     * <ul>
     *   <li>有金额字段的表：按该表"金额列"降序（NULL 自动排最后）</li>
     *   <li>prepose（前期项目）无金额列：orderBy=null → fallback 到 publish_time DESC</li>
     *   <li>origin_announcement 同时有 budget_amount（招标预算）和 win_bid_amount（中标金额），
     *       选 win_bid_amount —— 中标金额是真实成交金额，更"实"；预算可能为 0 或未公布</li>
     * </ul>
     */
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
     * 哪些表有"中标单位"字段
     */
    private static final Set<String> HAS_WINNER = Set.of("chatbi.bid_biz_win_bid", "chatbi.bid_origin_announcement");

    @Autowired(required = false)
    public QueryBizDataTool(@Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate) {
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    @Tool(name = "query_biz_data",
          description = "直接查询业务数据表（不走 NL2SQL，速度快）。" +
                  "bizType 支持: bid_winner(中标信息) / bidding(招标信息) / purchase_intention(采购意向) / prepose(前期项目) / origin_announcement(原始每日标讯，今日/昨日查这个)。" +
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
                  "winTenderer(中标单位 LIKE 模糊匹配, 仅 win_bid 表生效) / " +
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
        //   Reactor Context 跨线程传递，ThreadLocal 仅在本 call 链的 boundedElastic 线程有效
        //   authorizedProvince 从 AuthInfo.province() 拿，**不需要 LLM 传**
        return Mono.deferContextual(view -> {
            Object authObj = view.getOrEmpty("authInfo").orElse(null);
            return Mono.fromCallable(() -> {
                // set 和 clear 都在 boundedElastic 线程上，确保 ThreadLocal 匹配
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
     * <p>authorizedProvince 从 AuthContext.get().province() 拿（不是参数）
     */
    private String doQueryBizData(String bizType, String province,
                                  String datePreset, Map<String, Object> conditions, Integer limit) {
        if (businessJdbcTemplate == null) {
            return errorJson("业务数据源未配置（agentscope.datasource.business.url 为空），" +
                    "请在 application.yml 配置 BUSINESS_DB_URL / USERNAME / PASSWORD");
        }
        if (bizType == null || bizType.isBlank()) {
            return errorJson("bizType 不能为空，支持: bid_winner / bidding / purchase_intention / prepose / origin_announcement");
        }
        // ⭐ 硬性省份权限校验：authorizedProvince 从 AuthContext.get().province() 拿（Reactor Context 跨线程传过来）
        //    ⛔ 不允许默认 "全国"（用户没传或为空 → 直接拒）
        AuthContext.AuthInfo auth = AuthContext.get();
        String authorizedProvince = (auth != null && auth.province() != null) ? auth.province() : "";
        // ⭐ 多值归一化：拆开逐个去后缀（"黑龙江省,吉林省" → "黑龙江,吉林"）
        //   单值场景也安全（"全国" → "全国"，"北京市" → "北京"）
        authorizedProvince = ProvinceUtil.normalizeList(authorizedProvince);
        // ⭐ 授权归一化后为空 → 直接拒
        if (authorizedProvince.isBlank()) {
            log.warn("⛔ [query_biz_data] 用户无任何省份授权: userId={}, AuthContext={}",
                    auth != null ? auth.userId() : "null", auth);
            return errorJson("您还没开通此省份的数据查询权限,需要联系达仁科技开通一下哦");
        }

        // validateProvince 返回：
        //   - null：校验失败（用户问的省份不在授权范围）
        //   - ""：授权是"全国"，不传 province 即可（全量查询）
        //   - "北京" / "北京,上海"：校验通过，原样返回
        String validatedProvince = validateProvince(province, authorizedProvince);
        if (validatedProvince == null) {
            // 授权非空，但用户问的省份不在范围内 → 拒
            return errorJson("⛔ 您查询的\"" + (province == null ? "(空)" : province) + "\"不在您开通省份范围(" + authorizedProvince + ")内,请联系客户经理进行开通");
        }
        if (validatedProvince.isEmpty()) {
            log.info("🔧 [query_biz_data] authorizedProvince='全国' → 不加 province 过滤（全量）");
        } else {
            log.info("🔧 [query_biz_data] province 校验通过: 输入='{}' → SQL 用='{}'", province, validatedProvince);
        }

        TableDef table = BIZ_TABLES.get(bizType.toLowerCase());
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
            log.info("📅 [datePreset={}] 解析为 startDate={}, endDate={}",
                    datePreset, range[0], range[1]);

            // 🔑 路由守卫：datePreset=today/yesterday 时，旧 bizType 必须改用 origin_announcement
            // 强制纠正 LLM 的幻觉路由（避免它"脑补"参数验证失败退回老表）
            String bizTypeLower = bizType == null ? "" : bizType.toLowerCase();
            if (("today".equalsIgnoreCase(datePreset) || "yesterday".equalsIgnoreCase(datePreset))
                    && !bizTypeLower.contains("origin")) {
                return errorJson("🚫 datePreset=" + datePreset + " 必须用 bizType=origin_announcement（原始每日标讯），" +
                        "不要用 " + bizType + "。请重新调用：bizType=\"origin_announcement\"，datePreset=\"" + datePreset + "\"，" +
                        "province=您授权范围内的省份");
            }
        }

        // 构建 SQL：SELECT * FROM {table} WHERE [province 过滤] AND deleted = 0 [AND ...] ORDER BY publish_time DESC LIMIT ?
        SqlWithParams sqlWithParams = buildSelectSql(table, validatedProvince, cond, realLimit);

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
            response.put("province", validatedProvince);
            response.put("total", resultSet.size());
            response.put("conditions", cond);
            response.put("resultSet", resultSet);
            // 明确告诉 LLM 调用是成功的，0 条不代表出错
            response.put("message", resultSet.isEmpty()
                    ? "查询成功，暂无匹配数据（不是错误，不要再调用其他表）"
                    : "查询成功，返回 " + resultSet.size() + " 条数据");

            log.info("✅ [query_biz_data] 返回 {} 条数据, table={}", resultSet.size(), table.tableName);
            return toJson(response);

        } catch (Exception e) {
            log.error("❌ [query_biz_data] 查询失败, bizType={}, error={}", bizType, e.getMessage(), e);
            return errorJson("查询失败: " + e.getMessage());
        }
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
            normalized.remove("publish_time");  // ← 删掉原始的
        }
        return normalized;
    }

    /**
     * 动态拼 SQL（参数化，防注入）
     * <p>省份权限过滤（硬性）：province 必传
     * <ul>
     *   <li>单值（如 "北京"）→ province = ?</li>
     *   <li>多值（如 "北京,上海"）→ province = ? OR province = ? OR ...</li>
     *   <li>"全国" 或 空 → 不加省份过滤</li>
     * </ul>
     * <p>逻辑删除过滤 deleted = 0 是硬编码 WHERE，必须有
     */
    private SqlWithParams buildSelectSql(TableDef table, String validatedProvince, Map<String, Object> conditions, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(table.columns())
                .append(" FROM ")
                .append(table.tableName());
        List<Object> params = new ArrayList<>();
        List<String> where = new ArrayList<>();

        // ⭐ 省份权限过滤（硬性）
        if (validatedProvince != null && !validatedProvince.isBlank() && !"全国".equals(validatedProvince.trim())) {
            // 按逗号分隔成多个值
            String[] provinces = validatedProvince.split(",");
            // trim + 去空 + 归一化（"黑龙江省"→"黑龙江"，防止 buildSelectSql 拼出"黑龙江省"查不到）
            List<String> validProvinces = new java.util.ArrayList<>();
            for (String p : provinces) {
                String t = ProvinceUtil.normalize(p);
                if (!t.isEmpty()) validProvinces.add(t);
            }
            if (validProvinces.size() == 1) {
                // 单值：精确匹配
                where.add("province = ?");
                params.add(validProvinces.get(0));
            } else if (validProvinces.size() > 1) {
                // 多值：OR 链（比 LIKE 更精准）
                StringBuilder orChain = new StringBuilder("(");
                for (int i = 0; i < validProvinces.size(); i++) {
                    if (i > 0) orChain.append(" OR ");
                    orChain.append("province = ?");
                    params.add(validProvinces.get(i));
                }
                orChain.append(")");
                where.add(orChain.toString());
            }
            // 逻辑删除过滤（MyBatis Plus 通用约定）
            where.add("deleted = 0");
        }
        // validatedProvince 是 "全国" 或空 → 不加省份过滤，只加 deleted = 0
        if (!where.stream().anyMatch(w -> w.contains("deleted = 0"))) {
            where.add("deleted = 0");
        }

        // ========== 通用字段（4 表都有） ==========
        // province 已经作为参数处理过了，不再从 conditions 读
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
        if (HAS_WINNER.contains(table.tableName) && conditions.containsKey("winTenderer")
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
            String amountCol = HAS_WINNER.contains(table.tableName) ? "win_bid_price" : "bidding_budget";
            where.add(amountCol + " >= ?");
            params.add(toBigDecimal(conditions.get("minBudget")));
        }
        if (conditions.containsKey("maxBudget") && conditions.get("maxBudget") != null) {
            String amountCol = HAS_WINNER.contains(table.tableName) ? "win_bid_price" : "bidding_budget";
            where.add(amountCol + " <= ?");
            params.add(toBigDecimal(conditions.get("maxBudget")));
        }

        sql.append(" WHERE ").append(String.join(" AND ", where));
        // 排序字段：snake_case（MySQL 字段）
        //   - 优先用 table.orderBy() 配置的金额列（每张表自己的金额字段）
        //   - orderBy 为 null/空（如 prepose 无金额列）→ fallback 到 publish_time DESC
        //   - 同一金额下用 publish_time DESC 作 tie-breaker，让结果更稳定
        String orderCol = (table.orderBy() != null && !table.orderBy().isBlank())
                ? table.orderBy()
                : "publish_time";
        sql.append(" ORDER BY ").append(orderCol).append(" DESC, publish_time DESC LIMIT ?");
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

        // ⭐ 拆授权范围前先归一化：去 "省/市/自治区/特别行政区" 后缀
        //    "黑龙江省" → "黑龙江"  |  "北京市" → "北京"  |  "内蒙古自治区" → "内蒙古"
        Set<String> authSet = new HashSet<>();
        for (String p : auth.split(",")) {
            String normalized = ProvinceUtil.normalize(p);
            if (!normalized.isEmpty()) authSet.add(normalized);
        }

        // ⭐ 授权为空（JWT 没传 province 字段 / 解析失败）→ 直接拒绝（无授权不允许查）
        if (authSet.isEmpty()) {
            return null;
        }

        // 授权是"全国" → 不加 province 过滤（全量）
        if (authSet.contains("全国")) {
            return "";
        }

        // 用户没传 province → 默认用授权范围整体（归一化后用逗号拼接）
        if (userInputProvince == null || userInputProvince.isBlank()) {
            return String.join(",", authSet);
        }

        // ⭐ 统一逻辑：用户传的每个省份（归一化后）都必须在 authSet 里
        //    "全国" / "黑龙江" / "黑龙江省" → 走同一个分支——除非授权是"全国"，否则都拒
        String[] inputs = userInputProvince.split(",");
        for (String p : inputs) {
            String t = ProvinceUtil.normalize(p);
            if (t.isEmpty()) continue;
            if (!authSet.contains(t)) {
                return null;  // 失败：用户问的省份（"全国"/"黑龙江"/"黑龙江省"都一样）不在授权范围
            }
        }

        // 校验通过：用户输入归一化后拼接返回（让 buildSelectSql 拿到 "黑龙江,上海" 而不是 "黑龙江省,上海市"）
        List<String> normalizedInputs = new java.util.ArrayList<>();
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

    /** SQL + 参数 封装 */
    private record SqlWithParams(String sql, List<Object> params) {}
}
