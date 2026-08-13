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
 * 招中标数据 — 拟在建项目（bid_biz_prepose 表）
 *
 * 字段说明来自 query-prepose SKILL.md L67-101。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BidPreposeEntity {

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


    private BigDecimal biddingBudget;


    private String bidWay;


    private String timeBidOpen;


    private String timeBidClose;


    private String timeGetFileStart;


    private String timeGetFileEnd;


    private String channel;


    private LocalDate publishTime;


    private String serviceTime;


    private String procurementSystem;


    private String product;


    private String detailLink;


    private String webSourceName;


    private String bidUrl;


    private LocalDateTime bidCreateTime;


    private String notes;


    private String batchNo;


    private Boolean collectStatus;


    private Boolean written;


    private LocalDateTime writtenTime;


    private Boolean biddingJoined;


    private String column1;


    private Boolean kept;


    private String keywords;


    private String tendererAddress;


    private String tendererContact;


    private String tendererPhone;


    private String agency;


    private String agencyContact;


    private String agencyPhone;
}
