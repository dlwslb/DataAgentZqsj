package com.jldaren.agent.ai.datascope.filter;

import com.jldaren.agent.ai.datascope.service.TokenBlacklistService;
import com.jldaren.agent.ai.datascope.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * JWT 认证过滤器 - 基于 WebFlux WebFilter
 * 拦截所有请求，验证 JWT Token
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements WebFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService blacklistService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String token = extractToken(exchange);

        if (token == null) {
            return unauthorized(exchange, "Missing token");
        }

        try {
            Claims claims = jwtUtil.parseToken(token);

            String tokenType = claims.get("type", String.class);
            if (!"access".equals(tokenType)) {
                return unauthorized(exchange, "Invalid token type");
            }

            String jti = claims.getId();

            return blacklistService.isBlacklisted(jti)
                    .flatMap(isBlacklisted -> {
                        if (isBlacklisted) {
                            return unauthorized(exchange, "Token已失效");
                        }

                        Long userId = claims.get("userId", Long.class);
                        String username = claims.getSubject();

                        exchange.getRequest().mutate()
                                .header("X-User-Id", String.valueOf(userId))
                                .header("X-Username", username)
                                .build();

                        log.debug("Authenticated: {} (id={})", username, userId);
                        return chain.filter(exchange);
                    });

        } catch (ExpiredJwtException e) {
            return unauthorized(exchange, "Token expired");
        } catch (Exception e) {
            return unauthorized(exchange, "Invalid token");
        }
    }

    private boolean isPublicPath(String path) {
        return path.startsWith("/api/scope/app/version")
                || path.startsWith("/swagger")
                || path.startsWith("/v3/api-docs")
                || path.equals("/health")
                || path.equals("/");
    }

    private String extractToken(ServerWebExchange exchange) {
        // 从 Authorization Header 获取
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
        // 从 Query Parameter 获取
        return exchange.getRequest().getQueryParams().getFirst("token");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        
        String body = String.format("{\"code\":401,\"message\":\"%s\"}", message);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory().wrap(bytes)));
    }
}
