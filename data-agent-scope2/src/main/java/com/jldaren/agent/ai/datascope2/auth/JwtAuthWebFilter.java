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
package com.jldaren.agent.ai.datascope2.auth;

import com.jldaren.agent.ai.datascope2.tool.ProvinceUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * JWT 认证 WebFilter
 *
 * <p>从 `Authorization: Bearer <token>` 解 token，把 userId / tenantId 注入到
 * <p>Reactor Context + ThreadLocal，供下游 Controller 和 Tool 使用。
 *
 * <p>没有 token 放行（健康检查、Swagger 等公开接口）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthWebFilter implements WebFilter {

    public static final String CTX_USER_ID = "userId";
    public static final String CTX_TENANT_ID = "tenantId";
    public static final String CTX_PROVINCE = "province";
    public static final String CTX_USERNAME = "username";
    public static final String CTX_ROLE = "role";
    /** 完整 AuthInfo 对象的 Reactor Context key（给 Tool 跨线程用） */
    public static final String CTX_AUTH_INFO = "authInfo";

    private final JwtUtil jwtUtil;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return chain.filter(exchange);  // 没 token 放行
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = jwtUtil.parseToken(token);
            Long userId = claims.get("userId", Long.class);
            Long tenantId = claims.get("tenantId", Long.class);
            // ⭐ province：用户授权的省份范围（逗号分隔，如 "北京,上海,广东"），"全国" 表示不限制
            String province = claims.get("province", String.class);
            province = ProvinceUtil.normalizeProvince(province);
            String username = claims.getSubject();
            String role = claims.get("role", String.class);

            log.debug("✅ JWT 解析成功: userId={}, tenantId={}, province={}, username={}, role={}",
                    userId, tenantId, province, username, role);

            // ⭐ 关键：用 Reactor Context 跨线程传递（替代 ThreadLocal）
            //   - 简单字段：CTX_USER_ID / CTX_TENANT_ID / CTX_PROVINCE（Controller 层用）
            //   - 完整对象：CTX_AUTH_INFO（Tool 层跨线程用，从 context 拿后注入 ThreadLocal）
            AuthContext.AuthInfo authInfo = new AuthContext.AuthInfo(userId, tenantId, province, username, role);
            return chain.filter(exchange)
                    .contextWrite(reactor.util.context.Context.of(
                            java.util.Map.ofEntries(
                                    java.util.Map.entry(CTX_USER_ID, userId),
                                    java.util.Map.entry(CTX_TENANT_ID, tenantId),
                                    java.util.Map.entry(CTX_PROVINCE, province != null ? province : ""),
                                    java.util.Map.entry(CTX_USERNAME, username != null ? username : ""),
                                    java.util.Map.entry(CTX_ROLE, role != null ? role : "user"),
                                    java.util.Map.entry("authInfo", authInfo)
                            )
                    ));

        } catch (Exception e) {
            log.warn("⚠️ JWT 解析失败: {}", e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = "{\"error\":\"Invalid or expired token\"}".getBytes();
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        }
    }
}
