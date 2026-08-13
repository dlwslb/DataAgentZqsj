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

import com.alibaba.cloud.ai.dataagent.entity.biz.BidPreposeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface BidPreposeMapper {

    /**
     * 通用条件查询拟在建项目
     */
    List<BidPreposeEntity> listByConditions(@Param("province") String province,
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

    BidPreposeEntity selectById(@Param("id") Long id);

    /**
     * 拟在建侧聚合某 tenderer 的 product 关键词（含 count + amount_yuan）。
     * 用于 get_company_business_keywords（v2 升级补充）。
     */
    List<java.util.Map<String, Object>> aggregateProductAsTenderer(@Param("tenderer") String tenderer,
                                                                       @Param("provinces") List<String> provinces,
                                                                       @Param("beginDate") String beginDate,
                                                                       @Param("endDate") String endDate,
                                                                       @Param("limit") int limit);
}
