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
package com.alibaba.cloud.ai.dataagent.service.lostbid;

import com.alibaba.cloud.ai.dataagent.mapper.lostbid.OperatorLostBidMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OperatorLostBidServiceImpl implements OperatorLostBidService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 500;

    /** bid_biz_win_bid.operator 存的是短名，与 BizStatisticsMapper 的统计口径保持一致 */
    private static final List<String> SUPPORTED_OPERATORS = List.of("联通", "电信", "移动", "广电", "铁塔", "其他");

    private static final String LOST_BID_DEFINITION =
            "丢标 = 中标表 bid_biz_win_bid 中，当前租户（tenant_id）+ 指定省份下，"
                    + "项目参与情况（join_status）已填报且不等于「已中标」的记录；"
                    + "参与情况为空的记录属于未跟进的公开标讯，不计入丢标。"
                    + "operator 是项目归属的运营商市场维度（招标方是哪家运营商），不是“谁丢的标”，本省丢标大多发生在移动/电信/其他等市场。";

    @Resource
    private OperatorLostBidMapper operatorLostBidMapper;

    @Override
    public Map<String, Object> analyzeLostBid(Long tenantId, String province, String operator, String city,
                                              String industry, String lostReason, String reviewStatus,
                                              String keyword, String beginDate, String endDate, Integer limit) {
        // operator 是「项目归属的运营商市场」维度，可选：不传/传「全部」= 本省全部运营商市场的丢标，
        // 丢标原因报告应该用全量口径，禁止逐个运营商分别查询再拼接
        String normalizedOperator = operator == null ? "" : operator.trim();
        boolean filterByOperator = StringUtils.hasText(normalizedOperator) && !"全部".equals(normalizedOperator);
        if (filterByOperator && !SUPPORTED_OPERATORS.contains(normalizedOperator)) {
            return Map.of("error", "operator 仅支持 " + String.join("/", SUPPORTED_OPERATORS)
                    + "，或传「全部」/不传查全部运营商市场；当前传入：" + normalizedOperator);
        }
        int safeLimit = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        Map<String, Object> param = new HashMap<>();
        param.put("tenantId", tenantId == null ? 0L : tenantId);
        param.put("operator", filterByOperator ? normalizedOperator : null);
        param.put("province", StringUtils.hasText(province) ? province.trim() : null);
        param.put("city", StringUtils.hasText(city) ? city.trim() : null);
        param.put("industry", StringUtils.hasText(industry) ? industry.trim() : null);
        param.put("lostReason", StringUtils.hasText(lostReason) ? lostReason.trim() : null);
        param.put("reviewStatus", StringUtils.hasText(reviewStatus) ? reviewStatus.trim() : null);
        param.put("keyword", StringUtils.hasText(keyword) ? keyword.trim() : null);
        param.put("beginDate", StringUtils.hasText(beginDate) ? beginDate.trim() : null);
        param.put("endDate", StringUtils.hasText(endDate) ? endDate.trim() : null);
        param.put("limit", safeLimit);

        Map<String, Object> overview = operatorLostBidMapper.selectLostBidOverview(param);
        List<Map<String, Object>> records = operatorLostBidMapper.selectLostBidRecords(param);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queryScope", buildQueryScope(tenantId, province, normalizedOperator, param));
        result.put("lostBidDefinition", LOST_BID_DEFINITION);
        result.put("overview", overview == null ? Map.of("lost_count", 0) : overview);
        result.put("reasonStats", operatorLostBidMapper.aggregateByLostReason(param));
        result.put("joinStatusStats", operatorLostBidMapper.aggregateByJoinStatus(param));
        result.put("industryStats", operatorLostBidMapper.aggregateByIndustry(param));
        result.put("cityStats", operatorLostBidMapper.aggregateByCity(param));
        result.put("weeklyStats", operatorLostBidMapper.aggregateByWeek(param));
        result.put("reviewStats", operatorLostBidMapper.aggregateByReviewStatus(param));
        result.put("operatorStats", operatorLostBidMapper.aggregateByOperator(param));
        result.put("winnerFamilyStats", operatorLostBidMapper.aggregateByWinnerFamily(param));
        result.put("mechanismStats", operatorLostBidMapper.aggregateByMechanism(param));
        result.put("total", overview == null ? 0L : overview.get("lost_count"));
        result.put("returned", records.size());
        result.put("records", records);
        result.put("amountUnit", "win_bid_price_wan、lost_amount_wan、avg_amount_wan 单位均为万元，直接展示，禁止再做单位换算");
        // mapper 返回的是 Map，MyBatis 不会对 Map 应用 mapUnderscoreToCamelCase，返回的 key 就是下面的 snake_case 字段名。
        // analysisGuide 的目标：报告所需的全部统计维度都已在响应中预计算好，让模型一次调用直接写报告，
        // 避免它像之前那样用 bash/python 对 records 反复重算/核验导致几十步不收敛
        result.put("analysisGuide", "写丢标报告所需的全部统计维度都已在本响应中预计算好："
                + "overview（总量/总金额/均价/缺失计数）、reasonStats（原因分类）、joinStatusStats（参与情况，"
                + "用于判断是『投标前主动放弃』还是『投标后落败』还是『结构性不可得』）、industryStats（行业）、"
                + "cityStats（地市）、weeklyStats（按周走势）、reviewStats（是否复盘）、operatorStats（运营商市场）、"
                + "winnerFamilyStats（中标方类型）、mechanismStats（丢标机制归因），records 为按金额倒序的明细。"
                + "全部数字直接引用这些字段，禁止再用 bash/python 做任何聚合、分类、核验或时间换算；"
                + "mechanismStats 按 situation_desc 关键词粗分且存在交叉命中，各行不能相加，仅作归因参考；"
                + "典型案例直接从 records 复制字段值引用，无需回读核验；报告建议一次性写完。"
                + "reviewStats 中未复盘占比高时，应单独提示补齐丢标复盘。禁止编造数据中没有的原因。");
        if (records.isEmpty()) {
            result.put("emptyHint", "该筛选条件下没有已填报参与情况的丢标记录。可放宽日期范围后重试；"
                    + "若已按 operator 过滤，注意 operator 是项目归属的运营商市场而非丢标方，"
                    + "丢标原因报告应不传 operator（或传「全部”）查全市场口径。");
        }
        return result;
    }

    private Map<String, Object> buildQueryScope(Long tenantId, String province, String operator,
                                               Map<String, Object> param) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("tenantId", tenantId);
        putIfPresent(scope, "province", province);
        scope.put("operator", StringUtils.hasText(operator) ? operator : "全部（不按运营商市场过滤）");
        putIfPresent(scope, "city", param.get("city"));
        putIfPresent(scope, "industry", param.get("industry"));
        putIfPresent(scope, "lostReason", param.get("lostReason"));
        putIfPresent(scope, "reviewStatus", param.get("reviewStatus"));
        putIfPresent(scope, "keyword", param.get("keyword"));
        putIfPresent(scope, "beginDate", param.get("beginDate"));
        putIfPresent(scope, "endDate", param.get("endDate"));
        return scope;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
