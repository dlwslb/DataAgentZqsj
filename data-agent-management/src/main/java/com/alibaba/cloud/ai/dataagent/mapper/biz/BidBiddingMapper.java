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

import com.alibaba.cloud.ai.dataagent.entity.biz.BidBiddingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BidBiddingMapper {

    /**
     * 通用条件查询招标公告
     *
     * @param province        省份（精确）
     * @param city            城市（精确，可空）
     * @param industry        行业大类（精确，可空）
     * @param infoType        行业中类（精确，可空）
     * @param tenderer        招标单位（LIKE 模糊，可空）
     * @param keyword         关键词（在 title/projectName/product 中 LIKE，可空）
     * @param minBudgetYuan   最小预算（元，可空）
     * @param maxBudgetYuan   最大预算（元，可空）
     * @param beginDate       起始发布日期（yyyy-MM-dd，可空）
     * @param endDate         截止发布日期（yyyy-MM-dd，可空）
     * @param limit           LIMIT 上限
     */
    List<BidBiddingEntity> listByConditions(@Param("province") String province,
                                             @Param("city") String city,
                                             @Param("industry") String industry,
                                             @Param("infoType") String infoType,
                                             @Param("tenderer") String tenderer,
                                             @Param("keyword") String keyword,
                                             @Param("minBudgetYuan") java.math.BigDecimal minBudgetYuan,
                                             @Param("maxBudgetYuan") java.math.BigDecimal maxBudgetYuan,
                                             @Param("beginDate") String beginDate,
                                             @Param("endDate") String endDate,
                                             @Param("limit") int limit);

    /**
     * 高级搜索（query_bids_advanced）。
     *
     * @param provinces         省份集合（精确）
     * @param keywordGroups     关键词分组（AND 关系，每个 group 内部是 OR）
     *                          元素是 {@code List<String>}；每个 group 命中 title/projectName/product/keywords 任一字段即算匹配
     * @param excludeKeywords   排除关键词集合（任一 keyword 命中 title/projectName/product/keywords 即整条排除）
     * @param minBudgetYuan     最小预算（元）
     * @param maxBudgetYuan     最大预算（元）
     * @param beginDate         起始发布日期
     * @param endDate           截止发布日期
     * @param limit             LIMIT 上限
     */
    List<BidBiddingEntity> listByAdvanced(@Param("provinces") java.util.List<String> provinces,
                                          @Param("keywordGroups") java.util.List<java.util.List<String>> keywordGroups,
                                          @Param("excludeKeywords") java.util.List<String> excludeKeywords,
                                          @Param("minBudgetYuan") java.math.BigDecimal minBudgetYuan,
                                          @Param("maxBudgetYuan") java.math.BigDecimal maxBudgetYuan,
                                          @Param("beginDate") String beginDate,
                                          @Param("endDate") String endDate,
                                          @Param("limit") int limit);

    BidBiddingEntity selectById(@Param("id") Long id);

    /**
     * Top 采购单位聚合（招标方 tenderer）。
     *
     * @param keywords        业务关键词（在 title/project_name/product/keywords 中 LIKE）
     * @param provinces       省份（精确）
     * @param minBudget       最小预算（元）
     * @param maxBudget       最大预算（元）
     * @param beginDate       起始发布日期
     * @param endDate         截止发布日期
     * @param limit           返回 Top N
     */
    List<java.util.Map<String, Object>> aggregateTopPurchasers(@Param("keywords") java.util.List<String> keywords,
                                                                @Param("provinces") java.util.List<String> provinces,
                                                                @Param("minBudget") java.math.BigDecimal minBudget,
                                                                @Param("maxBudget") java.math.BigDecimal maxBudget,
                                                                @Param("beginDate") String beginDate,
                                                                @Param("endDate") String endDate,
                                                                @Param("limit") int limit);

    /**
     * search_company：按公司名模糊查 tenderer 候选列表，返回每个候选的招标次数、地区。
     * 用于"按名称搜索公司"+"自动匹配总部+分子公司"。
     *
     * @param companyName 公司名 LIKE 模糊
     * @param province    省份（可空）
     * @param city        城市（可空）
     * @param limit       LIMIT 上限
     */
    List<java.util.Map<String, Object>> searchCompanyCandidates(@Param("companyName") String companyName,
                                                                @Param("province") String province,
                                                                @Param("city") String city,
                                                                @Param("limit") int limit);

    /**
     * get_company_profile：聚合公司作为招标方时的画像（tenderer 维度）。
     * 返回公司名、所在省份/城市、招标次数、最近招标时间、累计预算、累计合作中标方数（近似）。
     */
    java.util.Map<String, Object> getCompanyProfileAsTenderer(@Param("companyName") String companyName);

    /**
     * get_company_partners (partner_type=客户)：找出该 tenderer 历史上合作过的中标方。
     * 按 win_tenderer 分组，返回合作次数、合作总金额、最近合作时间、合作产品列表。
     */
    List<java.util.Map<String, Object>> getCompanyPartnersAsCaller(@Param("companyName") String companyName,
                                                                   @Param("provinces") java.util.List<String> provinces,
                                                                   @Param("keywords") java.util.List<String> keywords,
                                                                   @Param("minAmount") java.math.BigDecimal minAmount,
                                                                   @Param("beginDate") String beginDate,
                                                                   @Param("endDate") String endDate,
                                                                   @Param("limit") int limit);

    /**
     * get_company_business_keywords (tenderer 维度)：从招标方项目标题+产品中聚合的关键词频次。
     * 返回 title 中分词 Top N（这里用 LIKE 全文近似，product 字段直接聚合）。
     */
    List<java.util.Map<String, Object>> getCompanyBusinessKeywordsAsTenderer(@Param("companyName") String companyName,
                                                                             @Param("beginDate") String beginDate,
                                                                             @Param("endDate") String endDate,
                                                                             @Param("provinces") java.util.List<String> provinces,
                                                                             @Param("limit") int limit);

    /**
     * find_potential_bidders：找该招标方历史上合作过的中标方（partner 维度复用 + 加历史采购次数/金额）。
     */
    List<java.util.Map<String, Object>> findPotentialBidders(@Param("companyName") String companyName,
                                                             @Param("product") String product,
                                                             @Param("province") String province,
                                                             @Param("limit") int limit);

    /**
     * aggregate_bids_advanced (招标方)：按维度聚合。
     * groupBy 支持 province / industry / month（YYYY-MM）。
     */
    List<java.util.Map<String, Object>> aggregateBidsAsCaller(@Param("keywords") java.util.List<String> keywords,
                                                              @Param("provinces") java.util.List<String> provinces,
                                                              @Param("minBudget") java.math.BigDecimal minBudget,
                                                              @Param("maxBudget") java.math.BigDecimal maxBudget,
                                                              @Param("beginDate") String beginDate,
                                                              @Param("endDate") String endDate,
                                                              @Param("groupBy") String groupBy,
                                                              @Param("limit") int limit);
}
