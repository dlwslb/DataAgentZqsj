package com.jldaren.agent.ai.datascope.filter;

import com.jldaren.agent.ai.datascope.service.TokenBlacklistService;
import com.jldaren.agent.ai.datascope.service.UserInfoService;
import com.jldaren.agent.ai.datascope.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT 认证过滤器 - 基于 Servlet Filter
 * 拦截所有请求，验证 JWT Token
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService blacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (token == null) {
            sendUnauthorized(response, "Missing token");
            return;
        }

        try {
            // 检查 Token 类型
            String tokenType = jwtUtil.getTokenType(token);
            if (!"access".equals(tokenType)) {
                sendUnauthorized(response, "Invalid token type");
                return;
            }

            // 检查黑名单
            String jti = jwtUtil.getJti(token);
            if (blacklistService.isBlacklistedSync(jti)) {
                sendUnauthorized(response, "Token已失效");
                return;
            }

            // 解析 Token
            Claims claims = jwtUtil.parseToken(token);
            Long userId = claims.get("userId", Long.class);
            String username = claims.getSubject();

            // 将用户信息设置到请求属性中，供后续使用
            request.setAttribute("userId", userId);
            request.setAttribute("username", username);

            log.debug("Authenticated: {} (id={})", username, userId);
            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            sendUnauthorized(response, "Token expired");
        } catch (Exception e) {
            sendUnauthorized(response, "Invalid token");
        }
    }

    private String extractToken(HttpServletRequest request) {
        // 从 Authorization Header 获取
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        // 从 Query Parameter 获取
        return request.getParameter("token");
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        String body = String.format("{\"code\":401,\"message\":\"%s\"}", message);
        response.getWriter().write(body);
    }
}
