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
package com.jldaren.agent.ai.datascope2.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 业务数据详情 Mapper（MyBatis XML 动态 SQL 版）
 *
 * <p>对应 4 张业务表，每张表一个详情查询方法：
 * <ul>
 *   <li>{@link #getBidWinnerDetail}         —— 中标详情（chatbi.bid_biz_win_bid）</li>
 *   <li>{@link #getBiddingDetail}           —— 招标详情（chatbi.bid_biz_bidding）</li>
 *   <li>{@link #getPurchaseIntentionDetail} —— 采购意向详情（chatbi.bid_biz_purchase_intention）</li>
 *   <li>{@link #getPreposeDetail}           —— 前期项目详情（chatbi.bid_biz_prepose）</li>
 * </ul>
 *
 * <p>跟 {@link BizQueryMapper}（列表业务）完全拆开：列表是"动态条件 + 排序 + LIMIT"，
 * 详情是"按 id 精确 或 keyword 模糊 + LIMIT 1"，业务形态不同，拆开维护更清晰。
 *
 * <p>参数约定（{@code @Param("p") Map<String,Object>}）：
 * <ul>
 *   <li>{@code id} —— Long，主键（最准，精确查）</li>
 *   <li>{@code keyword} —— String，项目名/标题/关键词（OR 模糊，二选一兜底）</li>
 *   <li>{@code id} 和 {@code keyword} 二选一必传；都空 → XML 直接 return null（避免全表扫），由 Tool 报错提示</li>
 *   <li>{@code provinceList} —— List&lt;String&gt;，省份列表（已归一化，Tool 层强制注入用户授权范围）</li>
 * </ul>
 *
 * <p><b>WebFlux 调用规范</b>：Mapper 是同步阻塞的，调用方必须包
 * {@code Mono.fromCallable(() -> mapper.getXxxDetail(params)).subscribeOn(Schedulers.boundedElastic())}，
 * 避免阻塞 reactor 线程。
 */
public interface BizDetailMapper {

    /**
     * 中标详情（chatbi.bid_biz_win_bid）
     *
     * @return 1 条详情 Map（所有列）；无匹配返回空 List
     */
    List<Map<String, Object>> getBidWinnerDetail(@Param("p") Map<String, Object> params);

    /**
     * 招标详情（chatbi.bid_biz_bidding）
     */
    List<Map<String, Object>> getBiddingDetail(@Param("p") Map<String, Object> params);

    /**
     * 采购意向详情（chatbi.bid_biz_purchase_intention）
     */
    List<Map<String, Object>> getPurchaseIntentionDetail(@Param("p") Map<String, Object> params);

    /**
     * 前期项目详情（chatbi.bid_biz_prepose）—— 无金额列
     */
    List<Map<String, Object>> getPreposeDetail(@Param("p") Map<String, Object> params);
}
