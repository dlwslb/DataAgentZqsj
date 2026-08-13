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
package com.alibaba.cloud.ai.dataagent.controller.biz;

import com.alibaba.cloud.ai.dataagent.service.biz.TenderSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * tender-search skill 后端 Controller（v0.1 极简实现）
 *
 * <p>对应 deer-flow/skills/public/tender-search/SKILL.md 中描述的 16 个工具。
 * 全部 POST，路径前缀 {@code /api_v2}，已在 JwtAuthenticationFilter 白名单中跳过 token 校验。
 *
 * <p>数据来自 bid_biz_bidding / bid_biz_win_bid / bid_biz_prepose / bid_biz_purchase_intention 4 张表。
 */
@Slf4j
@RestController
@RequestMapping("/api_v2")
@RequiredArgsConstructor
public class TenderSearchController {

    private final TenderSearchService tenderSearchService;

    // ===== 标讯搜索（4 个）=====

    @PostMapping(value = "/search_bids", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> searchBids(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/search_bids] req={}", req);
        return tenderSearchService.searchBids(req);
    }

    @PostMapping(value = "/query_bids_advanced", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> queryBidsAdvanced(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/query_bids_advanced] req={}", req);
        return tenderSearchService.queryBidsAdvanced(req);
    }

    @PostMapping(value = "/get_bid_detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getBidDetail(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/get_bid_detail] req={}", req);
        return tenderSearchService.getBidDetail(req);
    }

    @PostMapping(value = "/search_expiring_projects", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> searchExpiringProjects(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/search_expiring_projects] req={}", req);
        return tenderSearchService.searchExpiringProjects(req);
    }

    // ===== 企业分析（7 个）=====

    @PostMapping(value = "/search_company", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> searchCompany(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/search_company] req={}", req);
        return tenderSearchService.searchCompany(req);
    }

    @PostMapping(value = "/get_company_profile", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getCompanyProfile(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/get_company_profile] req={}", req);
        return tenderSearchService.getCompanyProfile(req);
    }

    @PostMapping(value = "/get_company_business_keywords", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getCompanyBusinessKeywords(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/get_company_business_keywords] req={}", req);
        return tenderSearchService.getCompanyBusinessKeywords(req);
    }

    @PostMapping(value = "/get_company_partners", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getCompanyPartners(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/get_company_partners] req={}", req);
        return tenderSearchService.getCompanyPartners(req);
    }

    @PostMapping(value = "/get_company_contacts", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getCompanyContacts(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/get_company_contacts] req={}", req);
        return tenderSearchService.getCompanyContacts(req);
    }

    @PostMapping(value = "/find_competitors", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> findCompetitors(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/find_competitors] req={}", req);
        return tenderSearchService.findCompetitors(req);
    }

    @PostMapping(value = "/find_potential_bidders", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> findPotentialBidders(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/find_potential_bidders] req={}", req);
        return tenderSearchService.findPotentialBidders(req);
    }

    // ===== 市场分析（5 个）=====

    @PostMapping(value = "/get_top_purchasers", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getTopPurchasers(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/get_top_purchasers] req={}", req);
        return tenderSearchService.getTopPurchasers(req);
    }

    @PostMapping(value = "/get_top_suppliers", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getTopSuppliers(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/get_top_suppliers] req={}", req);
        return tenderSearchService.getTopSuppliers(req);
    }

    @PostMapping(value = "/get_top_brands", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getTopBrands(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/get_top_brands] req={}", req);
        return tenderSearchService.getTopBrands(req);
    }

    @PostMapping(value = "/aggregate_bids_advanced", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> aggregateBidsAdvanced(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/aggregate_bids_advanced] req={}", req);
        return tenderSearchService.aggregateBidsAdvanced(req);
    }

    @PostMapping(value = "/get_price_trends", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getPriceTrends(@RequestBody(required = false) Map<String, Object> req) {
        log.info("[/api_v2/get_price_trends] req={}", req);
        return tenderSearchService.getPriceTrends(req);
    }
}
