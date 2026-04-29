package com.alibaba.cloud.ai.dataagent.util;

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

	@Value("${jwt.secret:DataAgentZqsjSecretKey2026ForJWTTokenGeneration}")
	private String secret;

	private SecretKey getSigningKey() {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		return Keys.hmacShaKeyFor(keyBytes);
	}

	public String generateAccessToken(Long userId, String username) {
		return generateToken(userId, username, "access", ACCESS_TOKEN_EXPIRATION);
	}

	public String generateRefreshToken(Long userId) {
		return generateToken(userId, null, "refresh", REFRESH_TOKEN_EXPIRATION);
	}

	private String generateToken(Long userId, String username, String type, long expiration) {
		String jti = UUID.randomUUID().toString();
		Date now = new Date();
		Date expiryDate = new Date(now.getTime() + expiration);

		return Jwts.builder()
				.id(jti)
				.subject(username)
				.claim("userId", userId)
				.claim("type", type)
				.issuedAt(now)
				.expiration(expiryDate)
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

	public String getTokenType(String token) {
		Claims claims = parseToken(token);
		return claims.get("type", String.class);
	}

	public String getJti(String token) {
		Claims claims = parseToken(token);
		return claims.getId();
	}
}
