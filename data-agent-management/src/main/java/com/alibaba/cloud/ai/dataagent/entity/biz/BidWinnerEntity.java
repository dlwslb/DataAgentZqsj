/*
 * Copyright 2024-2026 the original author or authors.
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
package com.alibaba.cloud.ai.dataagent.entity.biz;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 招中标数据 — 中标结果（bid_biz_win_bid 表）
 *
 * 字段说明来自 query-bid-winner SKILL.md L70-88。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BidWinnerEntity {

    /** 主键 → tender-search.bid_id */

    private Long id;


    private String title;


    private String projectName;


    private String province;


    private String city;


    private String district;


    private String industry;


    private String infoType;


    private String tenderer;

    /** 中标单位（win_bid 独有） */

    private String winTenderer;

    /** DB 存元，输出万元 */

    private BigDecimal winBidPrice;


    private BigDecimal biddingBudget;


    private String channel;


    private LocalDate publishTime;


    private String product;


    private String agency;


    private String detailLink;


    private String bidUrl;


    private String operator;


    private Boolean operatorWinStatus;


    private String topGrade;


    /** 服务开始日期（来自 bid_biz_win_bid.service_start） */

    private LocalDate serviceStart;


    /** 服务天数（来自 bid_biz_win_bid.service_day） */

    private Integer serviceDay;


    /** 服务期原始 JSON 字符串（来自 bid_biz_win_bid.service_period，如 {"service_start":"","service_end":"","service_days":N}） */

    private String servicePeriod;


    /** 中标单位邮箱（来自 bid_biz_win_bid.win_tenderer_email） */

    private String winTendererEmail;


    /** 中标单位联系人姓名（来自 bid_biz_win_bid.win_tenderer_manager） */

    private String winTendererManager;


    /** 中标单位联系人电话（来自 bid_biz_win_bid.win_tenderer_phone） */

    private String winTendererPhone;


    /** 关键词（来自 bid_biz_win_bid.keywords，逗号分隔文本如 "数字化,监测,系统"） */

    private String keywords;
}
