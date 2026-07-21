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
 * 业务数据查询 Mapper（MyBatis XML 动态 SQL）
 *
 * <p>对应 5 张业务表，每张表一个查询方法：
 * <ul>
 *   <li>{@link #queryBidWinner}          —— 中标信息（chatbi.bid_biz_win_bid）</li>
 *   <li>{@link #queryBidding}            —— 招标信息（chatbi.bid_biz_bidding）</li>
 *   <li>{@link #queryPurchaseIntention}  —— 采购意向（chatbi.bid_biz_purchase_intention）</li>
 *   <li>{@link #queryPrepose}            —— 前期项目（chatbi.bid_biz_prepose）</li>
 *   <li>{@link #queryOriginAnnouncement} —— 原始每日标讯（chatbi.bid_origin_announcement）</li>
 * </ul>
 *
 * <p>参数统一用 {@code Map<String,Object>} 透传，XML 里用 {@code <if test="...">} 动态判断，
 * 跟 {@link com.jldaren.agent.ai.datascope2.tool.QueryBizDataTool} 里的 conditions 字段 100% 对齐。
 *
 * <p><b>WebFlux 调用规范</b>：Mapper 是同步阻塞的，调用方必须包
 * {@code Mono.fromCallable(() -> mapper.queryXxx(params, limit)).subscribeOn(Schedulers.boundedElastic())}，
 * 避免阻塞 reactor 线程。
 */
public interface BizQueryMapper {

    /**
     * 中标信息（chatbi.bid_biz_win_bid）
     *
     * @param params 查询条件 Map（key 见 QueryBizDataTool.Tool 描述）
     *               <ul>
     *                 <li>provinceList —— List<String>，省份列表（单值 / 多值，已归一化），null 或空 = 全量</li>
     *                 <li>city / district / industry / infoType / channel / webSourceName —— 精确匹配</li>
     *                 <li>tenderer / winTenderer —— 模糊匹配（winTenderer 仅 win_bid 表生效）</li>
     *                 <li>keyword —— 多字段 OR 模糊（title / project_name / keywords）</li>
     *                 <li>startDate / endDate —— yyyy-MM-dd 字符串</li>
     *                 <li>minBudget / maxBudget —— 金额范围（win_bid 用 win_bid_price）</li>
     *               </ul>
     * @param limit  返回条数（已校验，1-100）
     * @return List of Map，列名按 XML 配置的 columns（默认下划线风格，Map key 跟 column 同名）
     */
    List<Map<String, Object>> queryBidWinner(@Param("p") Map<String, Object> params,
                                             @Param("limit") int limit);

    /**
     * 招标信息（chatbi.bid_biz_bidding）
     */
    List<Map<String, Object>> queryBidding(@Param("p") Map<String, Object> params,
                                           @Param("limit") int limit);

    /**
     * 采购意向（chatbi.bid_biz_purchase_intention）
     */
    List<Map<String, Object>> queryPurchaseIntention(@Param("p") Map<String, Object> params,
                                                     @Param("limit") int limit);

    /**
     * 前期项目（chatbi.bid_biz_prepose）—— 无金额列，按 publishTime 排序
     */
    List<Map<String, Object>> queryPrepose(@Param("p") Map<String, Object> params,
                                           @Param("limit") int limit);

    /**
     * 原始每日标讯（chatbi.bid_origin_announcement）—— 今日/昨日查这个
     */
    List<Map<String, Object>> queryOriginAnnouncement(@Param("p") Map<String, Object> params,
                                                      @Param("limit") int limit);
}
