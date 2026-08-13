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
import java.time.LocalDateTime;

/**
 * 招中标数据 — 招标公告（bid_biz_bidding 表）
 *
 * 字段说明来自 query-bidding SKILL.md（data-agent-scope2/src/main/resources/skills/query-bidding/SKILL.md L66-110）。
 * DB 列名按 Java 字段驼峰转下划线写死；如实际表列名不一致，请后端同事调整。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BidBiddingEntity {

    /** 主键 → tender-search.bid_id */

    private Long id;


    private String title;


    private String projectName;


    private String projectId;


    private String province;


    private String city;


    private String district;


    private String industry;


    private String infoType;


    private String tenderer;


    private String tendererCode;


    private String tendererAddress;


    private String tendererContact;


    private String tendererPhone;


    private String agency;


    private String agencyContact;


    private String agencyPhone;

    /** DB 存元，输出万元 */

    private BigDecimal biddingBudget;


    private String bidWay;


    private String channel;


    private LocalDate publishTime;


    private String timeBidOpen;


    private String timeBidClose;


    private String timeGetFileStart;


    private String timeGetFileEnd;


    private String biddingScale;


    private LocalDateTime bidCreateTime;


    private String product;


    private String detailLink;


    private String webSourceName;


    private String bidUrl;


    private String keywords;


    private String marketingUnit;


    private String projectSite;


    private String isOperatorTenderer;


    private Boolean written;


    private String writtenTime;


    private Boolean biddingJoined;


    private String abandoned;


    private String abandonedNo;


    private String joinStatus;


    private String lostReason;


    private String listLevel;


    private String listGridCus;


    private String yewuleixing;


    private String editstatus;


    private String field;


    private String colorType;
}
