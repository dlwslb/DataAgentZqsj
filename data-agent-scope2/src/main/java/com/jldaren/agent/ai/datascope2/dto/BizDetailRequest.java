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

/**
 * 业务数据详情查询请求 DTO（REST API 直通 QueryBizDataTool，绕开 LLM）
 *
 * <p>调用示例：
 * <pre>
 * POST /api/biz/detail
 * Authorization: Bearer &lt;token&gt;
 * Content-Type: application/json
 *
 * // 方式 1：按主键查（最准）
 * {
 *   "bizType": "bidding",
 *   "id": 12345,
 *   "province": "北京"
 * }
 *
 * // 方式 2：按项目名模糊查（兜底）
 * {
 *   "bizType": "bid_winner",
 *   "keyword": "智慧政务平台",
 *   "province": "北京"
 * }
 * </pre>
 *
 * <p>响应直接透传 Tool 的 JSON：
 * <ul>
 *   <li>成功找到：{ "success": true, "bizType": "...", "found": true, "detail": {...}, "message": "查询成功，返回 1 条详情" }</li>
 *   <li>未找到：  { "success": true, "bizType": "...", "found": false, "detail": null, "message": "未找到匹配的详情记录..." }</li>
 *   <li>失败：    { "error": "..." }</li>
 * </ul>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>bizType —— 必填，业务类型（详情接口支持的）：bidding（招标） / bid_winner（中标） / purchase_intention（采购意向） / prepose（前期项目）</li>
 *   <li>id —— 与 keyword 二选一必填（主键，最准，列表接口拿到的 id 直接传）</li>
 *   <li>keyword —— 与 id 二选一必填（项目名/标题/关键词，OR 模糊）</li>
 *   <li>province —— 必填，省份（单值如"北京"，多值如"北京,上海"）。必须经过 AuthInfo.authorizedProvince 校验（Tool 内部强制）</li>
 * </ul>
 *
 * <h3>与 /api/biz/query 的差异</h3>
 * <table>
 *   <caption>list vs detail 接口对比</caption>
 *   <tr><th>维度</th><th>/api/biz/query（列表）</th><th>/api/biz/detail（详情）</th></tr>
 *   <tr><td>返回条数</td><td>默认 20，最大 100</td><td>固定 1 条</td></tr>
 *   <tr><td>主键定位</td><td>不支持（按 conditions 过滤）</td><td>支持（id=主键）</td></tr>
 *   <tr><td>模糊匹配</td><td>keyword（可空）</td><td>keyword（与 id 二选一必填）</td></tr>
 *   <tr><td>底层 Tool</td><td>query_biz_data</td><td>get_bidding_detail / get_bid_winner_detail / get_purchase_intention_detail / get_prepose_detail</td></tr>
 * </table>
 */
@Data
public class BizDetailRequest {

    /**
     * 业务类型（详情接口支持的）
     * <p>支持：bidding（招标） / bid_winner（中标） / purchase_intention（采购意向） / prepose（前期项目）
     * <p>注意：origin_announcement（原始每日标讯）详情接口不支持（其语义就是"原始记录"而非可读详情，列表里点开看即可）
     */
    private String bizType;

    /**
     * 主键 id（与 keyword 二选一必填）
     * <p>最准的查询方式，从 /api/biz/query 列表结果里拿到的 id 直接传过来
     */
    private Long id;

    /**
     * 项目名/标题/关键词（与 id 二选一必填，OR 模糊）
     * <p>只有项目名时用这个（兜底）
     */
    private String keyword;

    /**
     * 省份（必填）
     * <p>单值如"北京"，多值如"北京,上海"
     * <p>必须经过 AuthInfo.authorizedProvince 校验（不在授权范围 → Tool 报错）
     */
    private String province;
}
