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
import com.jldaren.agent.ai.datascope2.dto.BizDetailRequest;
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
 *   <li>POST /api/biz/query —— 列表查询（按条件返回最多 100 条）
 *     <ul>
 *       <li>Header：Authorization: Bearer &lt;token&gt;（JwtAuthWebFilter 自动解出 AuthInfo）</li>
 *       <li>Body：{@link BizQueryRequest}</li>
 *       <li>响应：{ "success": true, "bizType": "...", "province": "...", "total": N, "resultSet": [...], "message": "..." }</li>
 *     </ul>
 *   </li>
 *   <li>POST /api/biz/detail —— 详情查询（按 id 或 keyword 返回 1 条全字段）
 *     <ul>
 *       <li>Header：同上</li>
 *       <li>Body：{@link BizDetailRequest}（bizType 必填，id/keyword 二选一必填，province 必填）</li>
 *       <li>响应：{ "success": true, "bizType": "...", "found": true/false, "detail": {...} | null, "message": "..." }</li>
 *     </ul>
 *   </li>
 *   <li>失败响应（两个接口通用）：{ "error": "..." }</li>
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
 * curl -X POST http://localhost:8082/api2/biz/query \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{"bizType":"bid_winner","province":"北京","limit":5}'
 *
 * curl -X POST http://localhost:8082/api2/biz/query \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{"bizType":"bidding","conditions":{"industry":"信息技术","minBudget":100}}'
 *
 * curl -X POST http://localhost:8082/api2/biz/query \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{"bizType":"origin_announcement","datePreset":"today"}'
 *
 * # 4. 调详情接口（按 id）
 * curl -X POST http://localhost:8082/api2/biz/detail \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{"bizType":"bidding","id":12345,"province":"北京"}'
 *
 * # 5. 调详情接口（按 keyword 模糊）
 * curl -X POST http://localhost:8082/api2/biz/detail \
 *   -H "Authorization: Bearer $TOKEN" \
 *   -H "Content-Type: application/json" \
 *   -d '{"bizType":"bid_winner","keyword":"智慧政务","province":"北京"}'
 */
@Slf4j
@RestController
@RequestMapping("/api2/biz")
@RequiredArgsConstructor
public class BizDataController {

    /** 并发上限（防止数据库连接池被拖死） */
    private static final int MAX_CONCURRENT = 200;

    private final QueryBizDataTool queryBizDataTool;
    private final Semaphore concurrencySemaphore = new Semaphore(MAX_CONCURRENT, true);

    /**
     * 从 Reactor Context 拿 AuthInfo（JwtAuthWebFilter 已经从 JWT 解出并注入）
     * <p>拿不到 → 返回 null（调用方决定怎么处理：一般是直接 401 未授权）
     * <p>为什么必须再校验一次？JwtAuthWebFilter 拿到合法 token 会放行到 Controller，
     * 但 Controller 必须自己再确认 AuthInfo 真的在 ctx 里——这是"防 Filter 误放行"的最后一道闸。
     *
     * @param ctx      Reactor Context
     * @param endpoint 接口标识（仅用于日志）
     * @return AuthInfo 对象，拿不到返回 null
     */
    private AuthContext.AuthInfo extractAuthOrNull(reactor.util.context.ContextView ctx, String endpoint) {
        return ctx.getOrEmpty(JwtAuthWebFilter.CTX_AUTH_INFO)
                .filter(o -> o instanceof AuthContext.AuthInfo)
                .map(o -> (AuthContext.AuthInfo) o)
                .orElseGet(() -> {
                    log.warn("⛔ [{}] 请求未携带登录信息（AuthInfo 为空，未带 token 或 token 解析失败）", endpoint);
                    return null;
                });
    }

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
            // 1) ⭐ 先校验登录信息（放在限流前面：未授权请求不应该消耗并发配额）
            AuthContext.AuthInfo auth = extractAuthOrNull(ctx, "biz/query");
            if (auth == null) {
                return Mono.just("{\"error\":\"未授权，请先登录\"}");
            }

            // 2) 限流：超过并发上限直接拒绝
            if (!concurrencySemaphore.tryAcquire()) {
                log.warn("⚠️ [biz/query] 并发超限 ({}), 拒绝新请求", MAX_CONCURRENT);
                return Mono.just("{\"error\":\"服务繁忙，请稍后重试\"}");
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
     * 业务数据详情查询入口
     * <p>内部直接调 {@link QueryBizDataTool#queryDetail(String, Long, String, String)}，
     * 走 4 个 @Tool 详情方法（{@code get_bidding_detail} / {@code get_bid_winner_detail} /
     * {@code get_purchase_intention_detail} / {@code get_prepose_detail}）同款的业务逻辑：
     * 省份权限校验 + id/keyword 二选一校验 + Mapper 查 1 条 + 字段归一化。
     *
     * <p>适用场景：前端列表页点开"查看详情"，省掉 LLM 链路（1-3s 延迟 + token 成本）。
     *
     * <p>与 /api/biz/query 的差异：
     * <ul>
     *   <li>支持 bizType：bidding / bid_winner / purchase_intention / prepose（4 张详情表）</li>
     *   <li>支持 origin_announcement：❌ 不支持（原始每日标讯详情就是元数据本身，列表行展开即可）</li>
     *   <li>入参：id 和 keyword 二选一必填（query 只需 conditions 即可）</li>
     *   <li>返回：固定 1 条详情（query 返回列表）</li>
     * </ul>
     *
     * <p>省份权限校验（来自 Tool 内部）：
     * <ul>
     *   <li>没拿到 AuthInfo → 401 等价（"未授权，请先登录"）</li>
     *   <li>AuthInfo.province 为空 → 403 "您还没开通此省份的数据查询权限"</li>
     *   <li>用户传的 province 不在 authorizedProvince 范围 → 403 "您查询的xx不在您开通省份范围xx内"</li>
     * </ul>
     */
    @PostMapping(value = "/detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> detail(@RequestBody BizDetailRequest request) {
        return Mono.deferContextual(ctx -> {
            // 1) ⭐ 先校验登录信息（放在限流前面：未授权请求不应该消耗并发配额）
            AuthContext.AuthInfo auth = extractAuthOrNull(ctx, "biz/detail");
            if (auth == null) {
                return Mono.just("{\"error\":\"未授权，请先登录\"}");
            }

            // 2) 限流：与 /query 共用同一个信号量
            if (!concurrencySemaphore.tryAcquire()) {
                log.warn("⚠️ [biz/detail] 并发超限 ({}), 拒绝新请求", MAX_CONCURRENT);
                return Mono.just("{\"error\":\"服务繁忙，请稍后重试\"}");
            }

            log.info("🔍 [biz/detail] userId={}, tenantId={}, authorizedProvince={}, bizType={}, id={}, keyword={}, province={}",
                    auth.userId(), auth.tenantId(), auth.province(),
                    request.getBizType(), request.getId(), request.getKeyword(), request.getProvince());

            // 3) 调 Tool：AuthInfo 已经在 Reactor Context 里，queryDetail 内部会从 ctx 拿并注入 ThreadLocal
            return queryBizDataTool.queryDetail(
                            request.getBizType(),
                            request.getId(),
                            request.getKeyword(),
                            request.getProvince())
                    // 4) 异常兜底：Tool 内部已经 return errorJson(...)；这里只处理"Tool 本身抛异常"的极端情况
                    .onErrorResume(e -> {
                        log.error("❌ [biz/detail] Tool 调用异常: bizType={}, error={}",
                                request.getBizType(), e.getMessage(), e);
                        return Mono.just("{\"error\":\"查询详情失败: " + e.getMessage().replace("\"", "\\\"") + "\"}");
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
