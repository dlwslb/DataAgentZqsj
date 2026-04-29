package com.alibaba.cloud.ai.dataagent.filter;

import com.alibaba.cloud.ai.dataagent.service.TokenBlacklistService;
import com.alibaba.cloud.ai.dataagent.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements WebFilter {

    // 完全公开的路径
    // 完全公开的路径（支持正则匹配）
    private static final List<String> WHITE_LIST = List.of(
            "/api/auth/login",
            "/api/auth/refresh-token",
            "/api/system/config",
            "/api/model-config/check-ready",
            "/api/auth/generate-complete-password",
            "/actuator/health"
    );

    // 完全公开的正则路径模式
    private static final List<String> WHITE_LIST_REGEX = List.of(
            "/api/agent/\\d+/sessions/stream"
    );

    // 内部服务调用时需验证的路径
    private static final List<String> INTERNAL_AUTH_PATHS = List.of(
            "/api/stream/search",
            "/api/agent/",
            "/api/agent-scope/"
    );

    @Value("${jwt.internal-api-key:DataAgentInternalKey2026}")
    private String internalApiKey;

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService blacklistService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. 完全公开路径直接放行（前缀匹配）
        if (isWhiteListPath(path)) {
            return chain.filter(exchange);
        }

        // 2. 内部服务调用路径：支持 Token 或 API Key 认证
        if (isInternalAuthPath(path)) {
            // 优先检查 Authorization Token
            String token = extractToken(exchange);
            if (token != null) {
                return validateAndProceed(exchange, chain, token);
            }

            // 检查 API Key
            String apiKey = exchange.getRequest().getHeaders().getFirst("X-Internal-Api-Key");
            if (apiKey != null && apiKey.equals(internalApiKey)) {
                log.debug("Internal API key auth passed for: {}", path);
                return chain.filter(exchange);
            }

            // 都没有，返回 401
            return unauthorized(exchange, "Missing authentication");
        }

        // 3. 其他路径：必须携带有效 Token
        String token = extractToken(exchange);
        if (token == null) {
            return unauthorized(exchange, "Missing token");
        }
        return validateAndProceed(exchange, chain, token);
    }

    private Mono<Void> validateAndProceed(ServerWebExchange exchange, WebFilterChain chain, String token) {
        try {
            String tokenType = jwtUtil.getTokenType(token);
            if (!"access".equals(tokenType)) {
                return unauthorized(exchange, "Invalid token type");
            }

            String jti = jwtUtil.getJti(token);

            return blacklistService.isBlacklisted(jti)
                    .flatMap(isBlacklisted -> {
                        if (isBlacklisted) {
                            return unauthorized(exchange, "Token已失效");
                        }

                        Claims claims = jwtUtil.parseToken(token);
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

    private boolean isWhiteListPath(String path) {
        // 前缀匹配
        for (String prefix : WHITE_LIST) {
            if (path.startsWith(prefix) || path.equals(prefix)) {
                return true;
            }
        }
        // 正则匹配
        for (String regex : WHITE_LIST_REGEX) {
            if (Pattern.matches(regex, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInternalAuthPath(String path) {
        for (String prefix : INTERNAL_AUTH_PATHS) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
    private String extractToken(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
        return exchange.getRequest().getQueryParams().getFirst("token");
    }

    /**
     * 从 Cookie 中提取 token
     */
    private String extractTokenFromCookie(ServerWebExchange exchange) {
        String cookieHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.COOKIE);
        if (cookieHeader == null) {
            return null;
        }
        // 简单解析 access_token=xxx; 格式
        for (String cookie : cookieHeader.split(";")) {
            String[] parts = cookie.trim().split("=");
            if (parts.length == 2 && "access_token".equals(parts[0])) {
                return parts[1];
            }
        }
        return null;
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
