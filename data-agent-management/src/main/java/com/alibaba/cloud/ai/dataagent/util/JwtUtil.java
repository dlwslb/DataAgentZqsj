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
package com.alibaba.cloud.ai.dataagent.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT Utility Class
 */
@Slf4j
@Component
public class JwtUtil {

	@Value("${jwt.secret:DataAgentZqsjSecretKey2026ForJWTTokenGeneration}")
	private String secret;

	@Value("${jwt.expiration:86400000}")
	private Long expiration;

	private SecretKey getSigningKey() {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		return Keys.hmacShaKeyFor(keyBytes);
	}

	/**
	 * Generate JWT token
	 */
	public String generateToken(Long userId, String username, String role) {
		Map<String, Object> claims = new HashMap<>();
		claims.put("userId", userId);
		claims.put("username", username);
		claims.put("role", role);

		Date now = new Date();
		Date expiryDate = new Date(now.getTime() + expiration);

		return Jwts.builder()
				.setClaims(claims)
				.setSubject(username)
				.setIssuedAt(now)
				.setExpiration(expiryDate)
				.signWith(getSigningKey(), SignatureAlgorithm.HS256)
				.compact();
	}

	/**
	 * Validate JWT token
	 */
	public Claims validateToken(String token) {
		try {
			return Jwts.parserBuilder()
					.setSigningKey(getSigningKey())
					.build()
					.parseClaimsJws(token)
					.getBody();
		} catch (Exception e) {
			log.error("Invalid JWT token: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Get user ID from token
	 */
	public Long getUserIdFromToken(String token) {
		Claims claims = validateToken(token);
		if (claims != null) {
			return claims.get("userId", Long.class);
		}
		return null;
	}

	/**
	 * Get username from token
	 */
	public String getUsernameFromToken(String token) {
		Claims claims = validateToken(token);
		if (claims != null) {
			return claims.getSubject();
		}
		return null;
	}

}
