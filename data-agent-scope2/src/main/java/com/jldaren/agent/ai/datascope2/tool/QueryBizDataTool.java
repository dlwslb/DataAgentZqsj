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
import com.jldaren.agent.ai.datascope2.mapper.BizDetailMapper;
import com.jldaren.agent.ai.datascope2.mapper.BizQueryMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *
 * <p>工具集合：1 个列表 Tool（{@code query_biz_data}，按 bizType 路由 5 张表）+ 4 个详情 Tool
 * （{@code get_bidding_detail} / {@code get_bid_winner_detail} / {@code get_purchase_intention_detail} / {@code get_prepose_detail}）。
 *
 * <p>详情 Tool 设计：用户问"XX 项目的招标/中标/采购意向/前期项目详情"时直接调用，按 {@code id}（主键，最准）
 * 或 {@code keyword}（项目名/标题模糊，兜底）查 1 条全字段。省份权限校验复用列表 Tool 的逻辑。
 */
@Slf4j
@Component
public class QueryBizDataTool {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * MyBatis 列表 Mapper（5 个表的动态 SQL 都在 BizQueryMapper.xml）
     * <p>Mapper 是同步阻塞的，调用方在 {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())} 里用
     */
    private final BizQueryMapper bizQueryMapper;

    /**
     * MyBatis 详情 Mapper（4 个详情 SQL 在 BizDetailMapper.xml，跟列表完全拆开）
     */
    private final BizDetailMapper bizDetailMapper;

    /**
     * 业务库 JdbcTemplate（保留兼容：业务表查询走 MyBatis Mapper，但 JdbcTemplate 仍用于 schema 健康检查 / 应急 SQL）
     */
    @SuppressWarnings("unused")
    private final JdbcTemplate businessJdbcTemplate;

    @Autowired
    public QueryBizDataTool(BizQueryMapper bizQueryMapper,
                            BizDetailMapper bizDetailMapper,
                            @org.springframework.beans.factory.annotation.Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate) {
        this.bizQueryMapper = bizQueryMapper;
        this.bizDetailMapper = bizDetailMapper;
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    @Tool(name = "query_biz_data",
          description = "直接查询业务数据表（不走 NL2SQL，速度快）。" +
                  "bizType 支持: bid_winner(中标信息) / bidding(招标信息) / purchase_intention(采购意向) / prepose(前期项目) / origin_announcement(原始每日标讯，今日/昨日查这个）。" +
                  "对应业务类型: 中标信息 / 招标信息 / 采购意向 / 前期项目 / 原始每日标讯（今日/昨日查这个）。" +
                  "⛔ 【今日/昨日查询硬性规则】datePreset=today/yesterday 时，conditions.channel 必传！" +
                  "   - 传 '中标' / '招标' / '采购' / '合同' → 按类型过滤（Tool 自动展开 LIKE 链）" +
                  "   - 传 '' (空字符串) → 全量返回" +
                  "   - 不传 → Tool 直接报错，强制重试" +
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
                  "⭐ 金额单位：所有金额字段（win_bid_price / bidding_budget 等）Tool 已自动从元转万元，保留 2 位小数。" +
                  "LLM 输出时按响应 JSON 中的 `unit: \"万元\"` 标识展示，对应表格列写'金额（万元）'。" +
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

            // ⭐ 强制 channel 校验：today/yesterday 查询必须传 channel（修 LLM 漏传的 bug）
            //    原因：SKILL 改了好几版 LLM 还是会漏传 channel（"黑龙江今日中标"被识别成泛问今日）
            //    现在 Tool 层强制：channel 没传 → 返回错误，LLM 必须重试带 channel
            //    channel="" (空字符串) 表示"全量"（不报错）
            Object channelVal = cond.get("channel");
            if (channelVal == null) {
                return errorJson("🚫 今日/昨日查询必须传 channel 参数！\n" +
                        "- 传 '中标' / '招标' / '采购' / '合同' → 过滤指定类型（Tool 自动展开 LIKE 链）\n" +
                        "- 传 '' (空字符串) → 不过滤，全量返回\n" +
                        "示例：conditions={\"channel\":\"中标\",\"province\":\"黑龙江\",\"datePreset\":\"today\"}\n" +
                        "（如果不传 channel 之前能跑，那是因为没拦截——但会返回混合类型数据，是 bug）");
            }
        }

        // ⭐ 组装 MyBatis 参数 Map：provinceList（List<String>）+ 其他 conditions 透传
        Map<String, Object> mapperParams = buildMapperParams(validatedProvince, cond);
        log.info("📊 [query_biz_data] bizType={}, mapperParams={}, limit={}", bizTypeLower, mapperParams, realLimit);

        try {
            // ⭐ 调 MyBatis Mapper：5 个表的方法路由在这里
            //   Mapper 是同步阻塞的，但 Tool 已经在 Mono.fromCallable(...).subscribeOn(boundedElastic) 里被调
            List<Map<String, Object>> rawResult = dispatchToMapper(bizTypeLower, mapperParams, realLimit);

            // 字段归一化：日期 publish_time → publishTime + 金额元 → 万元（按 bizType）
            List<Map<String, Object>> resultSet = rawResult.stream()
                    .map(r -> normalizeFields(r, bizTypeLower))
                    .collect(Collectors.toList());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("bizType", bizType);
            response.put("province", validatedProvince);
            response.put("total", resultSet.size());
            response.put("unit", "万元");  // ⭐ 金额单位标识：4 张业务表已统一转万元，origin_announcement 字段本身就是万元
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
     * <p>做两件事：
     * <ul>
     *   <li>日期归一：snake_case publish_time → camelCase publishTime</li>
     *   <li>金额归一：4 张业务表（DB 存元）的金额字段 → Tool 输出统一为万元（除 10000，保留 2 位小数）</li>
     * </ul>
     * <p>⚠️ 不再做"金额类型"重复字段：
     * <ul>
     *   <li>之前会给同一个金额加 2 份（原始 win_bid_amount + 归一化 amount + amountType=winBidAmount），
     *       详情给用户展示时变成"中标金额/金额/金额类型"三列重复</li>
     *   <li>现在各表金额列名已经在 XML / 详情 SQL 显式 select 出来，LLM 看到 win_bid_price / bidding_budget 自己就懂</li>
     * </ul>
     *
     * @param row     Mapper 返回的单行 Map（snake_case key）
     * @param bizType 业务类型（用于判断是否做金额转换）：bidding / bid_winner / purchase_intention / prepose 需要转换，origin_announcement 跳过
     */
    private Map<String, Object> normalizeFields(Map<String, Object> row, String bizType) {
        Map<String, Object> normalized = new LinkedHashMap<>(row);
        // 日期归一：snake_case publish_time → camelCase publishTime
        if (row.containsKey("publish_time")) {
            normalized.put("publishTime", row.get("publish_time"));
            normalized.remove("publish_time");
        }
        // 金额归一：DB 存元的 4 张业务表 → 输出统一为万元
        if (bizType != null && BIZ_TYPES_IN_YUAN.contains(bizType)) {
            convertYuanToWanYuan(normalized, "win_bid_price");
            convertYuanToWanYuan(normalized, "bidding_budget");
        }
        return normalized;
    }

    /**
     * 业务表金额单位是"元"的 4 张表（Tool 输出统一转万元）
     * <p>origin_announcement 不在内——它的金额字段是 win_bid_amount / budget_amount，
     *    按 SKILL.md 描述本身已经是万元，不重复转换。
     */
    private static final Set<String> BIZ_TYPES_IN_YUAN = Set.of(
            "bidding", "bid_winner", "purchase_intention", "prepose"
    );

    /**
     * 把元转万元（除 10000，保留 2 位小数，HALF_UP）
     * <p>支持 BigDecimal / Number / String（数字）三种输入；null / 非数字 / 0 直接跳过
     * <p>原地修改 row（值类型变 BigDecimal，序列化后是数字，LLM 拿到不会拼字符串）
     */
    private void convertYuanToWanYuan(Map<String, Object> row, String fieldName) {
        Object val = row.get(fieldName);
        if (val == null) return;
        BigDecimal wan;
        try {
            if (val instanceof BigDecimal bd) {
                wan = bd.divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);
            } else if (val instanceof Number n) {
                wan = BigDecimal.valueOf(n.doubleValue())
                        .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);
            } else if (val instanceof String s && !s.isBlank()) {
                wan = new BigDecimal(s.trim())
                        .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);
            } else {
                return; // 未知类型 / null / 空字符串 → 跳过
            }
        } catch (NumberFormatException e) {
            log.debug("⚠️ 金额字段 {} 不是数字，跳过转换: {}", fieldName, val);
            return;
        }
        row.put(fieldName, wan);
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
        // ⭐ channel 拆 List<String>：LLM 传类型（"中标"/"招标"/"采购"/"合同"），Tool 展开成实际 channel 值
        //    原因：LLM 经常漏传 channel（"黑龙江今日中标"被识别成泛问今日），现在 channel 由 Tool 端按"省→List 展开"模式处理
        //    SQL 用 <foreach> 拼 LIKE OR 链，兼容 LIKE 模糊匹配
        Object channelRaw = conditions.get("channel");
        if (channelRaw instanceof String channelStr && !channelStr.isBlank()) {
            List<String> channelList = expandChannelTypes(channelStr);
            if (!channelList.isEmpty()) {
                params.put("channelList", channelList);
            }
        }
        return params;
    }

    /**
     * channel 类型 → 实际 channel 值列表
     * <p>设计目的：LLM 传"中标"这种大类名 → Tool 展开成 DB 里实际存的子类型（"中标信息" / "中标候选人" / "中标结果" / "合同公告"），
     *    SQL 用 LIKE 模糊匹配。LLM 不需要知道 DB 里具体存什么值，Tool 全包了。
     * <p>为什么"中标"要包含"合同公告"？因为 SKILL 定义中标类型包含"中标信息"和"合同公告"，
     *    但 SQL `LIKE '%中标%'` 匹配不到"合同公告"（"合同公告"里没"中标"），
     *    所以 Tool 端显式加进去，否则中标结果会漏合同公告。
     *
     * <p>⭐ <b>数据来源</b>：从 SKILL.md 的 `<!-- channel-mapping -->` 块加载（机器可读格式），
     *    加载失败 fallback 到下面的硬编码默认值。SKILL 是 single source of truth，
     *    改 SKILL 即可生效，不用动 Java 代码。
     */
    private volatile Map<String, List<String>> channelTypeExpansion;

    /**
     * 硬编码兜底（SKILL 加载失败时用）
     */
    private static final Map<String, List<String>> FALLBACK_CHANNEL_EXPANSION = Map.of(
            "中标", List.of("中标", "合同公告"),
            "招标", List.of("招标"),
            "采购", List.of("采购"),
            "前置商机", List.of("审批项目", "拟在建", "前期项目")
    );

    /**
     * 启动时从 SKILL.md 加载 channel mapping
     * <p>SKILL.md 里有一个 `<!-- channel-mapping ... end channel-mapping -->` 块，
     *    格式是 `key=value1,value2,value3` 一行一对。LLM/Linter 改这个块就行。
     * <p>加载失败/解析失败 → fallback 到硬编码默认值，绝不让 LLM 卡死
     */
    @PostConstruct
    public void loadChannelMappingFromSkill() {
        Map<String, List<String>> loaded = null;
        try {
            ClassPathResource resource = new ClassPathResource(
                    "skills/query-daily-announcement/SKILL.md");
            if (!resource.exists()) {
                log.warn("⚠️ SKILL.md 不存在（{}），用兜底 Map", resource.getPath());
            } else {
                String content = StreamUtils.copyToString(resource.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8);
                loaded = parseChannelMappingFromSkill(content);
                if (loaded == null || loaded.isEmpty()) {
                    log.warn("⚠️ SKILL.md 解析出来是空，用兜底 Map");
                    loaded = null;
                } else {
                    log.info("✅ 从 SKILL.md 加载 channel mapping: {}", loaded);
                }
            }
        } catch (Exception e) {
            log.error("❌ 从 SKILL.md 加载 channel mapping 失败，用兜底 Map", e);
        }
        this.channelTypeExpansion = loaded != null ? loaded : new HashMap<>(FALLBACK_CHANNEL_EXPANSION);
    }

    /**
     * 从 SKILL.md 内容里解析 `<!-- channel-mapping ... end channel-mapping -->` 块
     * <p>块里每行格式: `类型名=值1,值2,值3`，# 开头的注释行忽略
     */
    private static Map<String, List<String>> parseChannelMappingFromSkill(String content) {
        // 匹配 <!-- channel-mapping (任意内容) --> 到 end channel-mapping --> 之间的内容
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<!--\\s*channel-mapping[^>]*>([\\s\\S]*?)end channel-mapping\\s*-->",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(content);
        if (!m.find()) {
            return null;
        }
        String block = m.group(1);
        Map<String, List<String>> result = new HashMap<>();
        for (String line : block.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String[] values = line.substring(eq + 1).split(",");
            List<String> list = new ArrayList<>();
            for (String v : values) {
                String t = v.trim();
                if (!t.isEmpty()) list.add(t);
            }
            if (!list.isEmpty()) result.put(key, list);
        }
        return result;
    }

    private List<String> expandChannelTypes(String channelStr) {
        List<String> result = new ArrayList<>();
        for (String c : channelStr.split(",")) {
            String trimmed = c.trim();
            if (trimmed.isEmpty()) continue;
            List<String> expanded = channelTypeExpansion.get(trimmed);
            if (expanded != null) {
                result.addAll(expanded);
            } else {
                // 未识别的 channel 值，原样透传（LLM 可能知道更具体的子类型）
                result.add(trimmed);
            }
        }
        return result;
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

    /**
     * 详情表定义：表名（只用于日志/错误信息，不参与 SQL 拼装）
     */
    private record DetailTableDef(String tableName) {}

    private static final Map<String, DetailTableDef> DETAIL_TABLES = Map.of(
            "bidding", new DetailTableDef("chatbi.bid_biz_bidding"),
            "bid_winner", new DetailTableDef("chatbi.bid_biz_win_bid"),
            "purchase_intention", new DetailTableDef("chatbi.bid_biz_purchase_intention"),
            "prepose", new DetailTableDef("chatbi.bid_biz_prepose")
    );

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

    // ============================================================
    //  详情 Tool（4 个） —— 按 id 主键或 keyword 模糊查 1 条
    //
    //  设计要点：
    //    1) 4 个独立 Tool 而不是 1 个通用 get_biz_detail：
    //       - 跟现有 4 个 query-*-skill 命名对称，LLM 路由清晰
    //       - 每个 Tool 的 description 单独描述业务语义，LLM 看 description 就能路由
    //    2) 入参：province 必填（做省份权限校验），id 和 keyword 二选一必填
    //    3) 省份过滤：Tool 层强制注入用户授权 provinceList（不依赖 LLM 传）
    //    4) 详情返回全字段（Mapper XML SELECT *），含 tenant_id / create_time 等基础字段
    //    5) 字段归一化复用 normalizeFields（金额列→amount，publish_time→publishTime）
    // ============================================================

    @Tool(name = "get_bidding_detail",
          description = "【招标信息详情】按 id 主键或项目名关键词查招标项目详情（chatbi.bid_biz_bidding 表），返回 1 条全字段。" +
                  "用户问'XX 项目的招标详情/招标公告内容'时使用。id 和 keyword 二选一必传：" +
                  "id=主键（最准，先调 query_biz_data 拿到 id 再调本工具）；" +
                  "keyword=项目名/标题/关键词（OR 模糊，LLM 从用户问题里抽）。" +
                  "province 必传（用户授权范围），Tool 会自动注入省份权限过滤。" +
                  "返回字段：title/projectName/projectId/tenderer/agency/biddingBudget/bidWay/" +
                  "timeGetFileEnd(投标截止)/timeBidOpen/detailLink/contact/phone 等所有列。" +
                  "不返回列表，如需列表用 query_biz_data。",
          readOnly = true,
          concurrencySafe = true)
    public Mono<String> getBiddingDetail(
            @ToolParam(name = "id", description = "招标记录主键 id（最准，二选一必填）", required = false) Long id,
            @ToolParam(name = "keyword", description = "项目名/标题/关键词（OR 模糊，二选一必填，LLM 从用户问题里抽）", required = false) String keyword,
            @ToolParam(name = "province", description = "省份，单值如'北京'，多值如'北京,上海'。必须在用户 authorizedProvince 范围内", required = true) String province) {
        return queryDetail("bidding", id, keyword, province);
    }

    @Tool(name = "get_bid_winner_detail",
          description = "【中标信息详情】按 id 主键或项目名关键词查中标项目详情（chatbi.bid_biz_win_bid 表），返回 1 条全字段。" +
                  "用户问'XX 项目的中标详情/中标单位/中标金额/中标公示内容'时使用。id 和 keyword 二选一必传：" +
                  "id=主键（最准）；keyword=项目名/标题/关键词（OR 模糊）。" +
                  "province 必传，Tool 自动注入省份权限过滤。" +
                  "返回字段：title/projectName/winTenderer(中标单位)/winTendererContact/winBidPrice(中标金额)/" +
                  "tenderer/biddingBudget/agency/publishTime/detailLink 等所有列。" +
                  "不返回列表，如需列表用 query_biz_data。",
          readOnly = true,
          concurrencySafe = true)
    public Mono<String> getBidWinnerDetail(
            @ToolParam(name = "id", description = "中标记录主键 id（最准，二选一必填）", required = false) Long id,
            @ToolParam(name = "keyword", description = "项目名/标题/关键词（OR 模糊，二选一必填）", required = false) String keyword,
            @ToolParam(name = "province", description = "省份，必须在用户 authorizedProvince 范围内", required = true) String province) {
        return queryDetail("bid_winner", id, keyword, province);
    }

    @Tool(name = "get_purchase_intention_detail",
          description = "【采购意向详情】按 id 主键或项目名关键词查采购意向详情（chatbi.bid_biz_purchase_intention 表），返回 1 条全字段。" +
                  "用户问'XX 采购意向详情/采购计划内容'时使用。id 和 keyword 二选一必传：" +
                  "id=主键；keyword=项目名/标题/关键词（OR 模糊）。" +
                  "province 必传，Tool 自动注入省份权限过滤。" +
                  "返回字段：title/projectName/tenderer/biddingBudget(万元)/timeBidOpen/timeBidClose/" +
                  "product(采购需求)/channel/publishTime/detailLink 等所有列。" +
                  "不返回列表，如需列表用 query_biz_data。",
          readOnly = true,
          concurrencySafe = true)
    public Mono<String> getPurchaseIntentionDetail(
            @ToolParam(name = "id", description = "采购意向记录主键 id（最准，二选一必填）", required = false) Long id,
            @ToolParam(name = "keyword", description = "项目名/标题/关键词（OR 模糊，二选一必填）", required = false) String keyword,
            @ToolParam(name = "province", description = "省份，必须在用户 authorizedProvince 范围内", required = true) String province) {
        return queryDetail("purchase_intention", id, keyword, province);
    }

    @Tool(name = "get_prepose_detail",
          description = "【前期项目详情】按 id 主键或项目名关键词查前期项目详情（chatbi.bid_biz_prepose 表，拟在建/项目储备），返回 1 条全字段。" +
                  "用户问'XX 前期项目详情/拟在建项目内容/项目储备详情'时使用。id 和 keyword 二选一必传：" +
                  "id=主键；keyword=项目名/标题/关键词（OR 模糊）。" +
                  "province 必传，Tool 自动注入省份权限过滤。" +
                  "返回字段：title/projectName/tenderer/biddingBudget(万元)/bidWay/timeBidOpen/timeBidClose/" +
                  "serviceTime/publishTime/detailLink 等所有列。" +
                  "不返回列表，如需列表用 query_biz_data。",
          readOnly = true,
          concurrencySafe = true)
    public Mono<String> getPreposeDetail(
            @ToolParam(name = "id", description = "前期项目记录主键 id（最准，二选一必填）", required = false) Long id,
            @ToolParam(name = "keyword", description = "项目名/标题/关键词（OR 模糊，二选一必填）", required = false) String keyword,
            @ToolParam(name = "province", description = "省份，必须在用户 authorizedProvince 范围内", required = true) String province) {
        return queryDetail("prepose", id, keyword, province);
    }

    /**
     * 详情查询公共入口（Controller / 4 个 @Tool 详情方法都委托到这里）
     * <p>职责：
     * <ul>
     *   <li>从 Reactor Context 拿 AuthInfo 注入 ThreadLocal（跨线程传递）</li>
     *   <li>try-finally 清理 ThreadLocal（防止内存泄漏）</li>
     *   <li>跑在 boundedElastic 线程池（Mapper 是阻塞调用）</li>
     * </ul>
     * 业务逻辑（bizType 校验 / id+keyword 校验 / 省份权限 / Mapper 路由 / 字段归一化）由 {@link #doDetail} 完成。
     *
     * @param bizType  bidding / bid_winner / purchase_intention / prepose
     * @param id       主键（id 和 keyword 至少传一个；doDetail 内部校验）
     * @param keyword  项目名/标题关键词（id 和 keyword 至少传一个；doDetail 内部校验）
     * @param province 省份（必传，做省份权限校验 + 过滤）
     */
    public Mono<String> queryDetail(String bizType, Long id, String keyword, String province) {
        return Mono.deferContextual(view -> {
            Object authObj = view.getOrEmpty("authInfo").orElse(null);
            return Mono.fromCallable(() -> {
                if (authObj instanceof AuthContext.AuthInfo a) {
                    AuthContext.set(a);
                }
                try {
                    return doDetail(bizType, id, keyword, province);
                } finally {
                    AuthContext.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    /**
     * 详情查询的共享执行逻辑（4 个 Tool 复用）
     * <p>流程：省份权限校验 → 校验 id/keyword 二选一 → 调 Mapper → 字段归一化 → 包装响应
     *
     * @param bizType   bidding / bid_winner / purchase_intention / prepose
     * @param id        主键（可空，keyword 兜底）
     * @param keyword   项目名/标题/关键词模糊（可空，id 兜底）
     * @param province  用户授权省份（必传，做权限校验+过滤）
     */
    private String doDetail(String bizType, Long id, String keyword, String province) {
        log.info("🔍 [get_{}_detail] id={}, keyword={}, province={}", bizType, id, keyword, province);

        // 1) bizType 校验
        DetailTableDef table = DETAIL_TABLES.get(bizType);
        if (table == null) {
            return errorJson("不支持的 bizType: " + bizType);
        }

        // 2) id 和 keyword 二选一必传
        if ((id == null) && (keyword == null || keyword.isBlank())) {
            return errorJson("必须传 id 或 keyword 其中一个：" +
                    "id 是主键（最准），keyword 是项目名/标题/关键词（OR 模糊）。" +
                    "如果你只有项目名，传 keyword；如果你从 query_biz_data 列表里拿到了 id，传 id");
        }

        // 3) 省份权限校验（复用列表 Tool 的逻辑）
        AuthContext.AuthInfo auth = AuthContext.get();
        String authorizedProvince = (auth != null && auth.province() != null) ? auth.province() : "";
        authorizedProvince = ProvinceUtil.normalizeList(authorizedProvince);
        if (authorizedProvince.isBlank()) {
            log.warn("⛔ [get_{}_detail] 用户无任何省份授权: userId={}",
                    bizType, auth != null ? auth.userId() : "null");
            return errorJson("您还没开通此省份的数据查询权限,需要联系达仁科技开通一下哦");
        }

        String validatedProvince = validateProvince(province, authorizedProvince);
        if (validatedProvince == null) {
            return errorJson("⛔ 您查询的\"" + (province == null ? "(空)" : province) + "\"不在您开通省份范围(" + authorizedProvince + ")内,请联系客户经理进行开通");
        }

        // 4) 组装参数：id / keyword / provinceList
        Map<String, Object> mapperParams = new HashMap<>();
        if (id != null) {
            mapperParams.put("id", id);
        }
        if (keyword != null && !keyword.isBlank()) {
            mapperParams.put("keyword", keyword.trim());
        }
        // 复用 buildMapperParams 注入 provinceList
        mapperParams = buildMapperParams(validatedProvince, mapperParams);

        log.info("📊 [get_{}_detail] bizType={}, mapperParams={}", bizType, bizType, mapperParams);

        try {
            // 5) 调 Mapper（4 个详情方法的 switch 路由）
            List<Map<String, Object>> rawResult = dispatchToDetailMapper(bizType, mapperParams);

            if (rawResult.isEmpty()) {
                // ⭐ 兜底查询：主表（bid_biz_win_bid 等）没数据时，自动 fall back 到 origin_announcement
                //    场景：中标详情/招标详情等走主表，但新数据可能只在原始每日标讯（origin_announcement）里
                //    主表是人工审核后的，中标库有同步延迟；原始标讯是实时抓取的
                log.info("🔄 [get_{}_detail] 主表无数据,fall back to origin_announcement, keyword={}", bizType, keyword);
                Map<String, Object> fallbackRow = tryOriginAnnouncementFallback(bizType, id, keyword, validatedProvince);
                if (fallbackRow != null) {
                    Map<String, Object> normalized = normalizeFields(fallbackRow, bizType);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", true);
                    response.put("bizType", bizType);
                    response.put("found", true);
                    response.put("source", "origin_announcement_fallback");
                    response.put("primaryTable", "chatbi.bid_biz_" + bizType);
                    response.put("detail", normalized);
                    response.put("message", "主表未收录，已从原始标讯库找到（数据未经人工审核，详情以主表为准）");
                    log.info("✅ [get_{}_detail] 兜底找到 1 条详情（来源: origin_announcement）", bizType);
                    return toJson(response);
                }

                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("success", true);
                empty.put("bizType", bizType);
                empty.put("found", false);
                empty.put("detail", null);
                empty.put("message", "未找到匹配的详情记录（id 或 keyword 无效，或不在您授权省份范围）");
                return toJson(empty);
            }

            // 6) 字段归一化（日期 publish_time→publishTime + 金额元→万元，按 bizType）
            Map<String, Object> detail = rawResult.get(0);
            Map<String, Object> normalized = normalizeFields(detail, bizType);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("bizType", bizType);
            response.put("found", true);
            response.put("unit", "万元");  // ⭐ 金额单位标识：4 张业务表已统一转万元
            response.put("detail", normalized);
            response.put("message", "查询成功，返回 1 条详情");

            log.info("✅ [get_{}_detail] 找到 1 条详情, id={}", bizType, normalized.get("id"));
            return toJson(response);

        } catch (Exception e) {
            log.error("❌ [get_{}_detail] 查询失败, error={}", bizType, e.getMessage(), e);
            return errorJson("查询详情失败: " + e.getMessage());
        }
    }

    /**
     * 详情 Mapper 路由（4 个 getXxxDetail 方法，委托给 BizDetailMapper）
     */
    private List<Map<String, Object>> dispatchToDetailMapper(String bizType, Map<String, Object> params) {
        return switch (bizType) {
            case "bidding" -> bizDetailMapper.getBiddingDetail(params);
            case "bid_winner" -> bizDetailMapper.getBidWinnerDetail(params);
            case "purchase_intention" -> bizDetailMapper.getPurchaseIntentionDetail(params);
            case "prepose" -> bizDetailMapper.getPreposeDetail(params);
            default -> throw new IllegalArgumentException("不支持的详情 bizType: " + bizType);
        };
    }

    /**
     * ⭐ 详情查询兜底：主表（bid_biz_*）没数据时，fall back 到 origin_announcement
     * <p>业务背景：主表是人工审核后的"标讯详情库"，有同步延迟；origin_announcement 是原始每日标讯（实时抓取）。
     *    用户问"今日中标详情"时，主表可能还没收录，但 origin_announcement 已经有这条。
     * <p>兜底策略：用相同的 id / keyword 查 origin_announcement，按 bizType 映射到对应 channel：
     * <ul>
     *   <li>bid_winner → channel=中标（中标信息/中标候选人/合同公告）</li>
     *   <li>bidding → channel=招标</li>
     *   <li>purchase_intention → channel=采购</li>
     *   <li>prepose → channel=前置商机（审批项目/拟在建/项目储备）</li>
     * </ul>
     * <p>返回 null = 兜底也查不到；非 null = 找到一条
     *
     * @param bizType            业务类型
     * @param id                 主键（可空，keyword 兜底）
     * @param keyword            项目名/标题关键词（可空，id 兜底）
     * @param validatedProvince  已省份权限校验过的省份字符串
     * @return 找到的第一条记录（Map），未找到返回 null
     */
    private Map<String, Object> tryOriginAnnouncementFallback(String bizType, Long id, String keyword, String validatedProvince) {
        // bizType → channel 映射
        String fallbackChannel = switch (bizType) {
            case "bid_winner" -> "中标";
            case "bidding" -> "招标";
            case "purchase_intention" -> "采购";
            case "prepose" -> "前置商机";
            default -> null;
        };
        if (fallbackChannel == null) {
            return null;
        }

        // 组装 origin_announcement 查询参数（同 keyword 模糊匹配）
        Map<String, Object> params = new HashMap<>();
        if (id != null) {
            params.put("id", id);
        }
        if (keyword != null && !keyword.isBlank()) {
            params.put("keyword", keyword.trim());
        }
        params.put("channel", fallbackChannel);
        // 复用 buildMapperParams 注入 provinceList（走省份权限）
        params = buildMapperParams(validatedProvince, params);

        log.info("🔍 [origin_fallback] bizType={}, channel={}, mapperParams={}", bizType, fallbackChannel, params);

        try {
            // limit=1：兜底只取第一条
            List<Map<String, Object>> result = bizQueryMapper.queryOriginAnnouncement(params, 1);
            if (result == null || result.isEmpty()) {
                return null;
            }
            return result.get(0);
        } catch (Exception e) {
            log.warn("⚠️ [origin_fallback] 兜底查询异常（不致命，返回 null）", e);
            return null;
        }
    }
}
