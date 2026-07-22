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
package com.jldaren.agent.ai.datascope2.dto;

import lombok.Data;

import java.util.Map;

/**
 * 业务数据查询请求 DTO（REST API 直通 QueryBizDataTool，绕开 LLM）
 *
 * <p>调用示例：
 * <pre>
 * POST /api/biz/query
 * Authorization: Bearer &lt;token&gt;
 * Content-Type: application/json
 *
 * {
 *   "bizType": "bid_winner",
 *   "province": "北京",
 *   "datePreset": "last30Days",
 *   "conditions": {
 *     "industry": "信息技术",
 *     "winTenderer": "中国移动",
 *     "minBudget": 100.0,
 *     "maxBudget": 5000.0
 *   },
 *   "limit": 20
 * }
 * </pre>
 *
 * <p>响应直接透传 Tool 的 JSON（success + resultSet / error）。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>bizType —— 必填，业务类型：bid_winner / bidding / purchase_intention / prepose / origin_announcement</li>
 *   <li>province —— 可选，省份（单值如"北京"，多值如"北京,上海"）。为空则用用户授权范围整体。必须经过 AuthInfo.authorizedProvince 校验（Tool 内部强制）</li>
 *   <li>datePreset —— 可选，日期快捷预设：today / yesterday / thisWeek / lastWeek / thisMonth / lastMonth / last7Days / last30Days。与 conditions.startDate/endDate 互斥，datePreset 优先</li>
 *   <li>conditions —— 可选，查询条件 Map，支持：
 *     <ul>
 *       <li>city / district / industry / infoType / channel / webSourceName —— 精确匹配</li>
 *       <li>tenderer / winTenderer / keyword —— 模糊匹配（winTenderer 仅 win_bid 表生效）</li>
 *       <li>startDate / endDate —— yyyy-MM-dd 字符串，publishTime 范围</li>
 *       <li>minBudget / maxBudget —— 金额范围（win_bid 用 win_bid_price，其他用 bidding_budget）</li>
 *     </ul>
 *   </li>
 *   <li>limit —— 可选，返回条数，默认 20，最大 100</li>
 * </ul>
 */
@Data
public class BizQueryRequest {

    /**
     * 业务类型：bid_winner / bidding / purchase_intention / prepose / origin_announcement
     */
    private String bizType;

    /**
     * 省份（单值如"北京"，多值如"北京,上海"）
     * <p>为空 → 用用户授权范围整体（多值用逗号拼接）
     * <p>必须经过 AuthInfo.authorizedProvince 校验（不在授权范围 → Tool 报错）
     */
    private String province;

    /**
     * 日期快捷预设（与 conditions.startDate/endDate 互斥，datePreset 优先）
     * <p>支持：today / yesterday / thisWeek / lastWeek / thisMonth / lastMonth / last7Days / last30Days
     * <p>today / yesterday 时强制走 origin_announcement（Tool 内部守卫）
     */
    private String datePreset;

    /**
     * 查询条件 Map（详见类注释）
     */
    private Map<String, Object> conditions;

    /**
     * 返回条数限制，默认 20，最大 100
     */
    private Integer limit;
}
