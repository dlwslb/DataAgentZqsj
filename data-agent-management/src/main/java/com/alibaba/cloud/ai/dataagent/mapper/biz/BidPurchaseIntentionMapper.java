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
package com.alibaba.cloud.ai.dataagent.mapper.biz;

import com.alibaba.cloud.ai.dataagent.entity.biz.BidPurchaseIntentionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface BidPurchaseIntentionMapper {

    /**
     * 通用条件查询采购意向
     */
    List<BidPurchaseIntentionEntity> listByConditions(@Param("province") String province,
                                                       @Param("city") String city,
                                                       @Param("industry") String industry,
                                                       @Param("infoType") String infoType,
                                                       @Param("tenderer") String tenderer,
                                                       @Param("keyword") String keyword,
                                                       @Param("minBudgetYuan") BigDecimal minBudgetYuan,
                                                       @Param("maxBudgetYuan") BigDecimal maxBudgetYuan,
                                                       @Param("beginDate") String beginDate,
                                                       @Param("endDate") String endDate,
                                                       @Param("limit") int limit);

    /**
     * 高级搜索（query_bids_advanced）。与 bidding 表 listByAdvanced 字段含义一致：
     * keywordGroups 是 AND 关系（每个 group 内部是 OR），excludeKeywords 任一命中即整条排除。
     */
    List<BidPurchaseIntentionEntity> listByAdvanced(@Param("provinces") java.util.List<String> provinces,
                                                     @Param("keywordGroups") java.util.List<java.util.List<String>> keywordGroups,
                                                     @Param("excludeKeywords") java.util.List<String> excludeKeywords,
                                                     @Param("minBudgetYuan") BigDecimal minBudgetYuan,
                                                     @Param("maxBudgetYuan") BigDecimal maxBudgetYuan,
                                                     @Param("beginDate") String beginDate,
                                                     @Param("endDate") String endDate,
                                                     @Param("limit") int limit);

    BidPurchaseIntentionEntity selectById(@Param("id") Long id);
}
