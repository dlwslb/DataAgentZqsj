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
package com.alibaba.cloud.ai.dataagent.util;

import com.alibaba.cloud.ai.dataagent.entity.User;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT Utility Class
 */
@Slf4j
@Component
public class JwtUtil {

	private static final long ACCESS_TOKEN_EXPIRATION = 2 * 60 * 60 * 1000;
	private static final long REFRESH_TOKEN_EXPIRATION = 7 * 24 * 60 * 60 * 1000;
	private static final long TEMP_TOKEN_EXPIRATION = 5 * 60 * 1000; // 临时Token有效期5分钟

	@Value("${jwt.secret:DataAgentZqsjSecretKey2026ForJWTTokenGeneration}")
	private String secret;

	private SecretKey getSigningKey() {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		return Keys.hmacShaKeyFor(keyBytes);
	}

	public String generateAccessToken(User user) {
		return generateToken(user, "access", ACCESS_TOKEN_EXPIRATION);
	}

	public String generateRefreshToken(User user) {
		return generateToken(user, "refresh", REFRESH_TOKEN_EXPIRATION);
	}

	/**
	 * 生成临时Token（用于模拟用户）
	 * 有效期5分钟
	 */
	public String generateTempToken(User user) {
		return generateToken(user, "temp", TEMP_TOKEN_EXPIRATION);
	}

	private String generateToken(User user, String type, long expiration) {
		String jti = UUID.randomUUID().toString();
		Date now = new Date();
		Date expiryDate = new Date(now.getTime() + expiration);

		var builder = Jwts.builder()
				.id(jti)
				.subject(user.getUsername())
				.claim("userId", user.getId())
				.claim("type", type)
				.issuedAt(now)
				.expiration(expiryDate);

		// 添加租户ID
		if (user.getTenantId() != null) {
			builder.claim("tenantId", user.getTenantId());
		}

		// 添加角色
		if (user.getRole() != null && !user.getRole().isEmpty()) {
			builder.claim("role", user.getRole());
		}
		// 添加province
		if (user.getProvince() != null && !user.getProvince().isEmpty()) {
			builder.claim("province", user.getProvince());
		}

		return builder
				.signWith(getSigningKey())
				.compact();
	}

	public Claims parseToken(String token) {
		try {
			return Jwts.parser()
					.verifyWith(getSigningKey())
					.build()
					.parseSignedClaims(token)
					.getPayload();
		} catch (ExpiredJwtException e) {
			log.warn("Token expired: {}", e.getMessage());
			throw e;
		} catch (JwtException e) {
			log.error("JWT parsing failed: {}", e.getMessage());
			throw new SecurityException("Invalid token");
		}
	}

	public Long getUserIdFromToken(String token) {
		Claims claims = parseToken(token);
		return claims.get("userId", Long.class);
	}

	public String getUsernameFromToken(String token) {
		Claims claims = parseToken(token);
		return claims.getSubject();
	}

	public Long getTenantIdFromToken(String token) {
		Claims claims = parseToken(token);
		return claims.get("tenantId", Long.class);
	}

	public String getRoleFromToken(String token) {
		Claims claims = parseToken(token);
		return claims.get("role", String.class);
	}
	public String getProvinceFromToken(String token) {
		Claims claims = parseToken(token);
		return claims.get("province", String.class);
	}

	public String getTokenType(String token) {
		Claims claims = parseToken(token);
		return claims.get("type", String.class);
	}

	public String getJti(String token) {
		Claims claims = parseToken(token);
		return claims.getId();
	}
}
