package com.jldaren.agent.ai.datascope.util;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 认证工具：从 Authorization Bearer 提取用户信息
 *
 * <p>失败统一抛 {@link ResponseStatusException}（HTTP 401），
 * <p>触发前端 axios 拦截器的 token 刷新逻辑。
 *
 * <p>用法（Controller 层）：
 * <pre>{@code
 * AuthInfo auth = authUtils.extractFromHeader(authHeader);
 * message.setUserId(auth.userId());
 * message.setTenantId(auth.tenantId());
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthUtils {

    private final JwtUtil jwtUtil;

    /**
     * 从 Authorization 头提取认证信息
     *
     * @param authHeader Authorization 头值（"Bearer xxx"）
     * @return {@link AuthInfo}
     * @throws ResponseStatusException 401 - 缺失/无效/过期 token / token 缺少 userId claim
     */
    public AuthInfo extractFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("⚠️ 缺少 Authorization Bearer 头");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header");
        }
        try {
            String token = authHeader.substring(7);
            Claims claims = jwtUtil.parseToken(token);
            Long userId = claims.get("userId", Long.class);
            Long tenantId = claims.get("tenantId", Long.class);

            // 🔑 防御性校验：合法 token 必须有 userId，缺了视为非法 token（防 DB 存 null）
            //    tenantId 允许 null（多租户场景可能没设置，后续用 setIfPresent 处理）
            if (userId == null) {
                log.warn("⚠️ token 缺少 userId claim");
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token missing userId claim");
            }

            String username = claims.getSubject();
            String role = claims.get("role", String.class);
            log.debug("✅ 认证解析成功: userId={}, tenantId={}, username={}, role={}", userId, tenantId, username, role);
            return new AuthInfo(userId, tenantId, username, role);
        } catch (ResponseStatusException e) {
            // 🔑 透传 ResponseStatusException，不被外层 catch 吞掉
            throw e;
        } catch (Exception e) {
            log.warn("⚠️ token 解析失败: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
    }
}
