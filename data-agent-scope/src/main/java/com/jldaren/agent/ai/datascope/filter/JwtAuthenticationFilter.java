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

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements WebFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService blacklistService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = extractToken(exchange);

        if (token == null) {
            return unauthorized(exchange, "Missing token");
        }

        try {
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

    private String extractToken(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        
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
