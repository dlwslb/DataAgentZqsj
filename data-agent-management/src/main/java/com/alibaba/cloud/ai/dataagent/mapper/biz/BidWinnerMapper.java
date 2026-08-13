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

import com.alibaba.cloud.ai.dataagent.entity.biz.BidWinnerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface BidWinnerMapper {

    /**
     * 通用条件查询中标结果
     *
     * 多了 {@code winTenderer} 参数：中标单位 LIKE 模糊。
     */
    List<BidWinnerEntity> listByConditions(@Param("province") String province,
                                            @Param("city") String city,
                                            @Param("industry") String industry,
                                            @Param("infoType") String infoType,
                                            @Param("tenderer") String tenderer,
                                            @Param("winTenderer") String winTenderer,
                                            @Param("keyword") String keyword,
                                            @Param("minBudgetYuan") BigDecimal minBudgetYuan,
                                            @Param("maxBudgetYuan") BigDecimal maxBudgetYuan,
                                            @Param("beginDate") String beginDate,
                                            @Param("endDate") String endDate,
                                            @Param("limit") int limit);

    /**
     * 高级搜索（query_bids_advanced）。语义同 BidBiddingMapper.listByAdvanced。
     *
     * @param keywordGroups 关键词分组（AND 关系），group 内是 OR；命中 title/projectName/product/win_tenderer 任一字段
     */
    List<BidWinnerEntity> listByAdvanced(@Param("provinces") java.util.List<String> provinces,
                                         @Param("keywordGroups") java.util.List<java.util.List<String>> keywordGroups,
                                         @Param("excludeKeywords") java.util.List<String> excludeKeywords,
                                         @Param("minBudgetYuan") java.math.BigDecimal minBudgetYuan,
                                         @Param("maxBudgetYuan") java.math.BigDecimal maxBudgetYuan,
                                         @Param("beginDate") String beginDate,
                                         @Param("endDate") String endDate,
                                         @Param("limit") int limit);

    BidWinnerEntity selectById(@Param("id") Long id);

    /**
     * Top 中标单位聚合（按 win_tenderer 分组）。
     */
    List<java.util.Map<String, Object>> aggregateTopSuppliers(@Param("keywords") java.util.List<String> keywords,
                                                              @Param("provinces") java.util.List<String> provinces,
                                                              @Param("minPrice") java.math.BigDecimal minPrice,
                                                              @Param("maxPrice") java.math.BigDecimal maxPrice,
                                                              @Param("beginDate") String beginDate,
                                                              @Param("endDate") String endDate,
                                                              @Param("limit") int limit);

    /**
     * Top 品牌聚合（按 product 前缀分组；产品名可能是 "迈瑞 SV300, 飞利浦 VM6" 形式，按首段品牌聚合）。
     *
     * 用 win_bid_price 计算 avg_price。
     */
    List<java.util.Map<String, Object>> aggregateTopBrands(@Param("product") String product,
                                                           @Param("excludeKeywords") java.util.List<String> excludeKeywords,
                                                           @Param("provinces") java.util.List<String> provinces,
                                                           @Param("minPrice") java.math.BigDecimal minPrice,
                                                           @Param("maxPrice") java.math.BigDecimal maxPrice,
                                                           @Param("beginDate") String beginDate,
                                                           @Param("endDate") String endDate,
                                                           @Param("limit") int limit);

    /**
     * search_company：按公司名模糊查 win_tenderer 候选列表，返回每个候选的中标次数、地区。
     */
    List<java.util.Map<String, Object>> searchCompanyCandidatesWinner(@Param("companyName") String companyName,
                                                                       @Param("province") String province,
                                                                       @Param("city") String city,
                                                                       @Param("limit") int limit);

    /**
     * get_company_profile：聚合公司作为中标方时的画像（win_tenderer 维度）。
     */
    java.util.Map<String, Object> getCompanyProfileAsWinner(@Param("companyName") String companyName);

    /**
     * get_company_partners (partner_type=供应商)：找出中标方历史上合作过的招标方（tenderer）。
     */
    List<java.util.Map<String, Object>> getCompanyPartnersAsWinner(@Param("companyName") String companyName,
                                                                   @Param("provinces") java.util.List<String> provinces,
                                                                   @Param("keywords") java.util.List<String> keywords,
                                                                   @Param("minAmount") java.math.BigDecimal minAmount,
                                                                   @Param("beginDate") String beginDate,
                                                                   @Param("endDate") String endDate,
                                                                   @Param("limit") int limit);

    /**
     * get_company_business_keywords (win_tenderer 维度)：从中标项目标题+产品中聚合。
     */
    List<java.util.Map<String, Object>> getCompanyBusinessKeywordsAsWinner(@Param("companyName") String companyName,
                                                                           @Param("beginDate") String beginDate,
                                                                           @Param("endDate") String endDate,
                                                                           @Param("provinces") java.util.List<String> provinces,
                                                                           @Param("limit") int limit);

    /**
     * find_competitors：找与该公司 win_tenderer 共同中标（即相同 project 上其他 win_tenderer）的对手公司。
     *
     * 通过 project_name + win_tenderer 反向关联（同一项目多个中标方或同一项目不同时段中标方）。
     */
    List<java.util.Map<String, Object>> findCompetitorsByCoBid(@Param("companyName") String companyName,
                                                               @Param("limit") int limit);

    /**
     * find_competitors 辅助：基于"同一招标方曾合作过的中标方"找对手
     * —— 即跟本方中标过同一个招标方的公司，视为同赛道竞争对手。
     */
    List<java.util.Map<String, Object>> findCompetitorsBySharedCaller(@Param("companyName") String companyName,
                                                                       @Param("limit") int limit);

    /**
     * find_competitors 辅助：基于"同省份 + 同品类 + 时间接近"找对手
     * —— 通过 product 字段 LIKE 模糊命中 + 同一省份 + 时间段相近（±90 天）。
     */
    List<java.util.Map<String, Object>> findCompetitorsByRegionProduct(@Param("companyName") String companyName,
                                                                      @Param("limit") int limit);

    /**
     * aggregate_bids_advanced (中标方)：按维度聚合。groupBy 支持 province / industry / month。
     */
    List<java.util.Map<String, Object>> aggregateBidsAsWinner(@Param("keywords") java.util.List<String> keywords,
                                                              @Param("provinces") java.util.List<String> provinces,
                                                              @Param("minPrice") java.math.BigDecimal minPrice,
                                                              @Param("maxPrice") java.math.BigDecimal maxPrice,
                                                              @Param("beginDate") String beginDate,
                                                              @Param("endDate") String endDate,
                                                              @Param("groupBy") String groupBy,
                                                              @Param("limit") int limit);

    /**
     * search_expiring_projects：拉近 3 年内有合同期信息（service_period JSON / service_start DATE / service_day INT）
     * 的中标合同，用于 ServicePeriodParser 解析 → 计算临期 expire_date。
     *
     * 数据源：bid_biz_win_bid（中标合同期），比 bid_biz_prepose（开标时间）更准。
     */
    List<BidWinnerEntity> listExpiringCandidates(@Param("provinces") java.util.List<String> provinces,
                                                 @Param("keyword") String keyword,
                                                 @Param("beginDate") String beginDate,
                                                 @Param("endDate") String endDate,
                                                 @Param("limit") int limit);

    /**
     * get_company_contacts (中标方维度)：聚合 win_tenderer_manager / phone / email。
     */
    List<java.util.Map<String, Object>> aggregateContactsAsWinner(@Param("companyName") String companyName,
                                                                  @Param("limit") int limit);

    /**
     * get_company_contacts (招标方维度)：从 bidding 表聚合 tenderer_contact / phone / address。
     */
    List<java.util.Map<String, Object>> aggregateContactsAsTenderer(@Param("companyName") String companyName,
                                                                    @Param("limit") int limit);

    /**
     * get_company_contacts (代理机构维度)：从 bidding 表按 agency LIKE 模糊聚合 agency_contact / phone。
     */
    List<java.util.Map<String, Object>> aggregateContactsAsAgency(@Param("companyName") String companyName,
                                                                  @Param("limit") int limit);

    /**
     * get_price_trends：按 brand + 可选 product + 可选 model 模糊命中 product 字段，
     * 拉所有 win_bid_price &gt; 0 的中标记录，service 层算分位数和月度趋势。
     */
    List<java.util.Map<String, Object>> listPricesByBrandProduct(@Param("brand") String brand,
                                                                 @Param("product") String product,
                                                                 @Param("model") String model,
                                                                 @Param("provinces") java.util.List<String> provinces,
                                                                 @Param("beginDate") String beginDate,
                                                                 @Param("endDate") String endDate,
                                                                 @Param("limit") int limit);
}
