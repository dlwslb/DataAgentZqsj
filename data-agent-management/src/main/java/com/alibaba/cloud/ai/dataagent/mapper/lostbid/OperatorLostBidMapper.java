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
package com.alibaba.cloud.ai.dataagent.mapper.lostbid;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/**
 * 运营商丢标分析（chatbi.bid_biz_win_bid）。
 *
 * <p>丢标口径：项目参与情况 join_status 已填报且不等于「已中标」。
 */
@Mapper
public interface OperatorLostBidMapper {

    /**
     * 丢标明细（按中标金额倒序，优先暴露损失最大的项目）。
     */
    List<Map<String, Object>> selectLostBidRecords(Map<String, Object> param);

    /**
     * 丢标总体指标：条数、金额、原因/情况说明缺失量、时间跨度。
     */
    Map<String, Object> selectLostBidOverview(Map<String, Object> param);

    /**
     * 按弃标/丢标/漏单原因分类（lost_reason）聚合。
     */
    List<Map<String, Object>> aggregateByLostReason(Map<String, Object> param);

    /**
     * 按行业（industry）聚合。
     */
    List<Map<String, Object>> aggregateByIndustry(Map<String, Object> param);

    /**
     * 按丢标是否复盘（column9）聚合。
     */
    List<Map<String, Object>> aggregateByReviewStatus(Map<String, Object> param);

    /**
     * 按运营商市场（operator）聚合，回答“在哪个运营商的盘子里丢得多”。
     */
    List<Map<String, Object>> aggregateByOperator(Map<String, Object> param);

    /**
     * 按项目参与情况（join_status）聚合，区分"弃标审批/投标未中/客户直采"等定性差异。
     */
    List<Map<String, Object>> aggregateByJoinStatus(Map<String, Object> param);

    /**
     * 按地市聚合。
     */
    List<Map<String, Object>> aggregateByCity(Map<String, Object> param);

    /**
     * 按 ISO 周聚合，观察丢标走势。
     */
    List<Map<String, Object>> aggregateByWeek(Map<String, Object> param);

    /**
     * 中标方类型粗分（按名称关键词）：移动系/电信系/联通系/广电铁塔/本地厂商等。
     */
    List<Map<String, Object>> aggregateByWinnerFamily(Map<String, Object> param);

    /**
     * 丢标机制归因：按具体情况说明（column8）关键词粗分，各行存在交叉命中，仅作归因参考。
     */
    List<Map<String, Object>> aggregateByMechanism(Map<String, Object> param);
}
