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

import com.alibaba.cloud.ai.dataagent.entity.User;
import com.alibaba.cloud.ai.dataagent.service.UserService;
import com.alibaba.cloud.ai.dataagent.service.biz.TenderSearchService;
import com.alibaba.cloud.ai.dataagent.util.ProvinceUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Function;

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

    @Autowired
    private UserService userService;

    // ===== 标讯搜索（4 个）=====

    @PostMapping(value = "/search_bids", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> searchBids(@RequestBody(required = false) Map<String, Object> req,@RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/search_bids] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::searchBids);
    }

    @PostMapping(value = "/query_bids_advanced", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> queryBidsAdvanced(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/query_bids_advanced] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::queryBidsAdvanced);
    }

    @PostMapping(value = "/get_bid_detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getBidDetail(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/get_bid_detail] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::getBidDetail);
    }

    @PostMapping(value = "/search_expiring_projects", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> searchExpiringProjects(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/search_expiring_projects] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::searchExpiringProjects);
    }

    // ===== 企业分析（7 个）=====

    @PostMapping(value = "/search_company", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> searchCompany(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/search_company] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::searchCompany);
    }

    @PostMapping(value = "/get_company_profile", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getCompanyProfile(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/get_company_profile] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::getCompanyProfile);
    }

    @PostMapping(value = "/get_company_business_keywords", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getCompanyBusinessKeywords(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/get_company_business_keywords] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::getCompanyBusinessKeywords);
    }

    @PostMapping(value = "/get_company_partners", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getCompanyPartners(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/get_company_partners] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::getCompanyPartners);
    }

    @PostMapping(value = "/get_company_contacts", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getCompanyContacts(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/get_company_contacts] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::getCompanyContacts);
    }

    @PostMapping(value = "/find_competitors", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> findCompetitors(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/find_competitors] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::findCompetitors);
    }

    @PostMapping(value = "/find_potential_bidders", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> findPotentialBidders(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/find_potential_bidders] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::findPotentialBidders);
    }

    // ===== 市场分析（5 个）=====

    @PostMapping(value = "/get_top_purchasers", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getTopPurchasers(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/get_top_purchasers] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::getTopPurchasers);
    }

    @PostMapping(value = "/get_top_suppliers", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getTopSuppliers(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/get_top_suppliers] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::getTopSuppliers);
    }

    @PostMapping(value = "/get_top_brands", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getTopBrands(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/get_top_brands] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::getTopBrands);
    }

    @PostMapping(value = "/aggregate_bids_advanced", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> aggregateBidsAdvanced(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/aggregate_bids_advanced] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::aggregateBidsAdvanced);
    }

    @PostMapping(value = "/get_price_trends", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getPriceTrends(@RequestBody(required = false) Map<String, Object> req, @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("[/api_v2/get_price_trends] req={}", req);
        return executeWithAuth(authHeader, req, tenderSearchService::getPriceTrends);
    }

    /**
     * 公共 token 校验 + 业务执行模板。
     *
     * <p>携带了 {@code Bearer} token 时进行校验，失败则返回统一的 error 结构；
     * 未携带 token 或校验通过时，继续执行具体业务逻辑。
     * @param authHeader Authorization 请求头（可选）
     * @param req 请求体
     * @param action 具体的业务查询逻辑
     * @return 业务结果，或校验失败的 error 结构
     */
    private Map<String, Object> executeWithAuth(String authHeader, Map<String, Object> req,
            Function<Map<String, Object>, Map<String, Object>> action) {
        if(authHeader == null){
            return Map.of("error", "无效 token");
        }else if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            // 一次性获取所有信息
            User user = userService.getUserInfoFromToken(token);
            if (user == null) {
                log.warn("无法解析 token，token 可能无效或已过期");
                return Map.of("error", "无效 token");
            }
            String authorizedProvince = (user != null && user.getProvince() != null) ? user.getProvince() : "";
            authorizedProvince = ProvinceUtil.normalizeList(authorizedProvince);
            if (authorizedProvince.isBlank()) {
                log.warn("⛔ [query_biz_data] 用户无任何省份授权: userId={}, AuthContext={}",user != null ? user.getId() : "null", user);
                return Map.of("error", "此省份未授权,需要请联系客户经理进行开通。");
            }
            // AI 每日调用限制：业务调用前检查并扣减当日次数，用完拒绝（跨天自动重置）
            if (!userService.tryConsumeAiQuota(user.getId())) {
                log.warn("⛔ [api_v2] 用户 AI 每日调用配额已用完: userId={}", user.getId());
                return Map.of("error", "今日 AI 调用次数已用完，次日将自动恢复。");
            }
        }
        return action.apply(req);
    }
}
