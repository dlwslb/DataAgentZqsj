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

import java.util.Map;

/**
 * 运营商丢标分析。
 *
 * <p>数据源为 chatbi.bid_biz_win_bid（中标表），丢标口径：项目参与情况 join_status 已填报且不等于「已中标」。
 */
public interface OperatorLostBidService {

    /**
     * 分析指定运营商在指定省份的丢标情况。
     *
     * @param tenantId     当前登录用户租户编号，用于多租户隔离
     * @param province     省份短名（已归一化，如 北京）
     * @param operator     运营商市场维度（项目归属的运营商），可选；不传/传「全部」= 本省全部运营商市场的丢标，
     *                     传具体值 = 只看该市场下的丢标分布
     * @param city         地市，可选，模糊匹配
     * @param industry     行业大类，可选，精确匹配
     * @param lostReason   弃标/丢标/漏单原因分类，可选，模糊匹配
     * @param reviewStatus 丢标是否复盘，可选，模糊匹配（取值以库中实际填报内容为准）
     * @param keyword      项目名称/公告标题/招标单位/中标单位关键词，可选
     * @param beginDate    中标发布时间起，可选，格式 yyyy-MM-dd
     * @param endDate      中标发布时间止，可选，格式 yyyy-MM-dd
     * @param limit        明细返回条数上限，可选，默认 20，最大 100
     * @return 丢标总体指标 + 原因/行业/复盘三维度分布 + 丢标明细；operator 非法时返回 error 结构
     */
    Map<String, Object> analyzeLostBid(Long tenantId, String province, String operator, String city, String industry,
                                       String lostReason, String reviewStatus, String keyword,
                                       String beginDate, String endDate, Integer limit);
}
