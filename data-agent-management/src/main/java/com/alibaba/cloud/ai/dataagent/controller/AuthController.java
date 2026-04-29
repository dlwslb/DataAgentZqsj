package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.dto.LoginRequest;
import com.alibaba.cloud.ai.dataagent.dto.LoginResponse;
import com.alibaba.cloud.ai.dataagent.entity.User;
import com.alibaba.cloud.ai.dataagent.service.AuthService;
import com.alibaba.cloud.ai.dataagent.service.TokenBlacklistService;
import com.alibaba.cloud.ai.dataagent.util.JwtUtil;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Authentication Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final JwtUtil jwtUtil;
	private final TokenBlacklistService blacklistService;

	@PostMapping("/login")
	public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
		try {
			LoginResponse response = authService.login(request);
			User user = authService.getCurrentUser(response.getUserInfo().getId());

			String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
			String refreshToken = jwtUtil.generateRefreshToken(user.getId());

			Map<String, Object> data = new HashMap<>();
			data.put("accessToken", accessToken);
			data.put("refreshToken", refreshToken);
			data.put("userInfo", Map.of(
					"id", user.getId(),
					"username", user.getUsername(),
					"nickname", user.getNickname() != null ? user.getNickname() : "",
					"email", user.getEmail() != null ? user.getEmail() : "",
					"avatar", user.getAvatar() != null ? user.getAvatar() : "",
					"role", user.getRole(),
					"tenantId", user.getTenantId() != null ? user.getTenantId() : 0
			));

			return ResponseEntity.ok(Map.of(
					"code", 0,
					"message", "登录成功",
					"data", data
			));
		} catch (Exception e) {
			log.error("Login failed: {}", e.getMessage());
			return ResponseEntity.status(401).body(Map.of(
					"code", 401,
					"message", "用户名或密码错误"
			));
		}
	}

	@PostMapping("/refresh-token")
	public ResponseEntity<Map<String, Object>> refreshToken(@RequestBody RefreshTokenRequest request) {
		try {
			String tokenType = jwtUtil.getTokenType(request.getRefreshToken());
			if (!"refresh".equals(tokenType)) {
				return ResponseEntity.status(401).body(Map.of(
						"code", 401,
						"message", "无效的刷新令牌"
				));
			}

			Long userId = jwtUtil.getUserIdFromToken(request.getRefreshToken());
			User user = authService.getCurrentUser(userId);

			if (user == null) {
				return ResponseEntity.status(401).body(Map.of(
						"code", 401,
						"message", "用户不存在"
				));
			}

			String newAccessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
			String newRefreshToken = jwtUtil.generateRefreshToken(user.getId());

			return ResponseEntity.ok(Map.of(
					"code", 0,
					"data", Map.of(
							"accessToken", newAccessToken,
							"refreshToken", newRefreshToken
					)
			));
		} catch (Exception e) {
			return ResponseEntity.status(401).body(Map.of(
					"code", 401,
					"message", "刷新令牌已过期或无效"
			));
		}
	}

	@PostMapping("/logout")
	public ResponseEntity<Map<String, Object>> logout(@RequestHeader("Authorization") String authHeader) {
		String token = authHeader.substring(7);
		Claims claims = jwtUtil.parseToken(token);
		String jti = claims.getId();

		Date expiration = claims.getExpiration();
		long remainingSeconds = (expiration.getTime() - System.currentTimeMillis()) / 1000;

		blacklistService.addToBlacklist(jti, remainingSeconds).subscribe();

		return ResponseEntity.ok(Map.of(
				"code", 0,
				"message", "登出成功"
		));
	}

	@Data
	static class RefreshTokenRequest {
		private String refreshToken;
	}
}
