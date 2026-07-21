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
package com.jldaren.agent.ai.datascope2.controller;

import com.jldaren.agent.ai.datascope2.auth.AuthContext;
import com.jldaren.agent.ai.datascope2.auth.JwtAuthWebFilter;
import com.jldaren.agent.ai.datascope2.dto.BizQueryRequest;
import com.jldaren.agent.ai.datascope2.tool.QueryBizDataTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 业务数据查询 REST API（绕开 LLM，直通 {@link QueryBizDataTool}）
 *
 * <p>适用场景：前端 / 测试 / 外部系统需要直接拉业务数据，省掉 LLM 的 1-3 秒延迟和 token 成本。
 *
 * <h3>与 /api/chat/stream 的差异</h3>
 * <table>
 *   <caption>REST 直通 vs LLM 路径对比</caption>
 *   <tr><th>维度</th><th>/api/biz/query（本接口）</th><th>/api/chat/stream</th></tr>
 *   <tr><td>延迟</td><td>50-300ms</td><td>1-3s（LLM 选 Tool + 拼参数）</td></tr>
 *   <tr><td>Token 成本</td><td>0</td><td>每次几百到几千 token</td></tr>
 *   <tr><td>查询精度</td><td>100%（调用方写死参数）</td><td>看 LLM 幻觉程度</td></tr>
 *   <tr><td>省份权限</td><td>走 Tool 内部 validateProvince()</td><td>走 Tool 内部 validateProvince()</td></tr>
 *   <tr><td>租户隔离</td><td>走 AuthContext（userId / tenantId 强校验）</td><td>走 AuthContext（同）</td></tr>
 * </table>
 *
 * <h3>接口规范</h3>
 * <ul>
 *   <li>POST /api/biz/query</li>
 *   <li>Header：Authorization: Bearer &lt;token&gt;（JwtAuthWebFilter 自动解出 AuthInfo）</li>
 *   <li>Body：{@link BizQueryRequest}</li>
 *   <li>响应：直接透传 Tool 的 JSON String
 *     <ul>
 *       <li>成功：{ "success": true, "bizType": "...", "province": "...", "total": N, "resultSet": [...], "message": "..." }</li>
 *       <li>失败：{ "error": "..." }</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>为什么不重写 Tool 的 SQL 构建？</h3>
 * <p>{@link QueryBizDataTool} 里的 buildSelectSql() / validateProvince() / 字段归一化逻辑是项目核心，
 * 已经被 LLM 链路（ChatController → HarnessAgent → Tool）反复验证。Controller 透传 Tool 即可，
 * 避免双份实现漂移。
 *
 * # 1. 启服务
 * cd /Users/dlw/software/progromfiles/java-develop/DarenProdects/DataAgentZqsj/data-agent-scope2
 * ./mvnw spring-boot:run
 *
 * # 2. 拿个有效 JWT
 *
 * # 3. 调 5 个 bizType 验证(每张表至少 1 个,先简单测)
 * curl -X POST http://localhost:8082/api/biz/query \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{"bizType":"bid_winner","province":"北京","limit":5}'
 *
 * curl -X POST http://localhost:8082/api/biz/query \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{"bizType":"bidding","conditions":{"industry":"信息技术","minBudget":100}}'
 *
 * curl -X POST http://localhost:8082/api/biz/query \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{"bizType":"origin_announcement","datePreset":"today"}'
 */
@Slf4j
@RestController
@RequestMapping("/api/biz")
@RequiredArgsConstructor
public class BizDataController {

    /** 并发上限（防止数据库连接池被拖死） */
    private static final int MAX_CONCURRENT = 200;

    private final QueryBizDataTool queryBizDataTool;
    private final Semaphore concurrencySemaphore = new Semaphore(MAX_CONCURRENT, true);

    /**
     * 业务数据查询主入口
     * <p>内部直接调 {@link QueryBizDataTool#queryBizData(String, String, String, Map, Integer)}，
     * 把 LLM 那一层"选择 + 拼参数"的链路省掉。
     *
     * <p>省份权限校验逻辑（来自 Tool 内部）：
     * <ul>
     *   <li>从 Reactor Context 拿 AuthInfo.authInfo（JwtAuthWebFilter 注入的）</li>
     *   <li>若没拿到 → 401 UNAUTHORIZED（业务上等价于"未登录"）</li>
     *   <li>AuthInfo.province 为空（用户没任何省份授权） → 403 "您还没开通此省份的数据查询权限"</li>
     *   <li>用户传的 province 不在 authorizedProvince 范围内 → 403 "您查询的xx不在您开通省份范围xx内"</li>
     * </ul>
     */
    @PostMapping(value = "/query", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> query(@RequestBody BizQueryRequest request) {
        return Mono.deferContextual(ctx -> {
            // 1) 限流：超过并发上限直接拒绝
            if (!concurrencySemaphore.tryAcquire()) {
                log.warn("⚠️ [biz/query] 并发超限 ({}), 拒绝新请求", MAX_CONCURRENT);
                return Mono.just("{\"error\":\"服务繁忙，请稍后重试\"}");
            }

            // 2) 从 Reactor Context 拿 AuthInfo（JwtAuthWebFilter 已经从 JWT 解出并注入）
            //    拿不到 → 等价于没带 token（虽然 Filter 放行了，但 Controller 必须再校验一次）
            AuthContext.AuthInfo auth = ctx.getOrEmpty(JwtAuthWebFilter.CTX_AUTH_INFO)
                    .filter(o -> o instanceof AuthContext.AuthInfo)
                    .map(o -> (AuthContext.AuthInfo) o)
                    .orElse(null);
            if (auth == null) {
                log.warn("⛔ [biz/query] 无 AuthInfo（未登录或 token 解析失败）");
                return Mono.just("{\"error\":\"未登录或 token 无效\"}");
            }

            log.info("🔍 [biz/query] userId={}, tenantId={}, authorizedProvince={}, bizType={}, province={}, datePreset={}, limit={}",
                    auth.userId(), auth.tenantId(), auth.province(),
                    request.getBizType(), request.getProvince(),
                    request.getDatePreset(), request.getLimit());

            // 3) 调 Tool：AuthInfo 已经在 Reactor Context 里了（JwtAuthWebFilter 注入 + 上面 ctx.get 验证存在）
            //    Tool 内部 queryBizData 用 Mono.deferContextual(view -> view.getOrEmpty("authInfo")) 拿
            //    注意：CTX_AUTH_INFO 常量值就是 "authInfo"（见 JwtAuthWebFilter 第 50 行）
            return queryBizDataTool.queryBizData(
                            request.getBizType(),
                            request.getProvince(),
                            request.getDatePreset(),
                            request.getConditions(),
                            request.getLimit())
                    // 4) 异常兜底：Tool 内部已经 return errorJson(...)
                    //    这里只处理"Tool 本身抛异常"的极端情况（DB 连不上 / ClassCast 等）
                    .onErrorResume(e -> {
                        log.error("❌ [biz/query] Tool 调用异常: bizType={}, error={}",
                                request.getBizType(), e.getMessage(), e);
                        return Mono.just("{\"error\":\"查询失败: " + e.getMessage().replace("\"", "\\\"") + "\"}");
                    })
                    .doFinally(sig -> concurrencySemaphore.release());
        });
    }

    /**
     * 列出支持的 bizType + datePresets（方便前端调试 / 文档生成）
     * <p>不需要鉴权，直接 GET
     */
    @GetMapping(value = "/supported-types", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> supportedTypes() {
        return Mono.just("{" +
                "\"success\":true," +
                "\"bizTypes\":[" +
                "  {\"name\":\"bid_winner\",\"description\":\"中标信息（含 winTenderer / winBidPrice）\",\"amountField\":\"win_bid_price\"}," +
                "  {\"name\":\"bidding\",\"description\":\"招标信息\",\"amountField\":\"bidding_budget\"}," +
                "  {\"name\":\"purchase_intention\",\"description\":\"采购意向\",\"amountField\":\"bidding_budget\"}," +
                "  {\"name\":\"prepose\",\"description\":\"前期项目（无金额列，按 publishTime 排序）\",\"amountField\":null}," +
                "  {\"name\":\"origin_announcement\",\"description\":\"原始每日标讯（今日/昨日查这个）\",\"amountField\":\"win_bid_amount\"}" +
                "]," +
                "\"datePresets\":[\"today\",\"yesterday\",\"thisWeek\",\"lastWeek\",\"thisMonth\",\"lastMonth\",\"last7Days\",\"last30Days\"]" +
                "}");
    }
}
