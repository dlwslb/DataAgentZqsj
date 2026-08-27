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
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.dto.LoginRequest;
import com.alibaba.cloud.ai.dataagent.dto.LoginResponse;
import com.alibaba.cloud.ai.dataagent.entity.User;
import com.alibaba.cloud.ai.dataagent.service.AuthService;
import com.alibaba.cloud.ai.dataagent.service.TokenBlacklistService;
import com.alibaba.cloud.ai.dataagent.util.JwtUtil;
import com.alibaba.cloud.ai.dataagent.util.Sm3PasswordEncoder;
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

	/**
	 * 获取当前登录用户信息
	 */
	@GetMapping("/current")
	public ResponseEntity<Map<String, Object>> getCurrentUser(
			@RequestHeader(value = "Authorization", required = false) String authHeader) {
		try {
			if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			return ResponseEntity.status(401).body(Map.of(
					"code", 401,
					"message", "未登录"
			));
		}

		String token = authHeader.substring(7);
		Long userId = jwtUtil.getUserIdFromToken(token);
		User user = authService.getCurrentUser(userId);

		if (user == null) {
			return ResponseEntity.status(404).body(Map.of(
					"code", 404,
					"message", "用户不存在"
			));
		}

		return ResponseEntity.ok(Map.of(
				"code", 0,
				"message", "获取成功",
				"data", Map.of(
						"id", user.getId(),
						"username", user.getUsername(),
						"nickname", user.getNickname() != null ? user.getNickname() : "",
						"email", user.getEmail() != null ? user.getEmail() : "",
						"avatar", user.getAvatar() != null ? user.getAvatar() : "",
						"role", user.getRole(),
						"agentId", user.getAgentId() != null ? user.getAgentId() : 0,
						"tenantId", user.getTenantId() != null ? user.getTenantId() : 0
				)
		));
	} catch (Exception e) {
		log.error("获取当前用户信息失败: {}", e.getMessage());
		return ResponseEntity.status(500).body(Map.of(
				"code", 500,
				"message", "获取用户信息失败"
		));
	}
	}

	@PostMapping("/login")
	public ResponseEntity<Map<String, Object>> login(
			@Valid @RequestBody LoginRequest request,
			org.springframework.http.server.reactive.ServerHttpRequest serverRequest) {
		try {
			// 获取客户端真实IP
			String clientIp = getClientIp(serverRequest);
			request.setLoginIp(clientIp);

			LoginResponse response = authService.login(request);
			User user = authService.getCurrentUser(response.getUserInfo().getId());

			String accessToken = jwtUtil.generateAccessToken(user);
			String refreshToken = jwtUtil.generateRefreshToken(user);

			Map<String, Object> data = new HashMap<>();
			data.put("accessToken", accessToken);
			data.put("refreshToken", refreshToken);
			data.put("userInfo", Map.of(
					"id", user.getId(),
					"username", user.getUsername(),
					"nickname", user.getNickname() != null ? user.getNickname() : "",
					"province", user.getProvince() != null ? user.getProvince() : "",
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

			String newAccessToken = jwtUtil.generateAccessToken(user);
			String newRefreshToken = jwtUtil.generateRefreshToken(user);

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

	/**
	 * 获取客户端真实IP地址（WebFlux）
	 */
	private String getClientIp(org.springframework.http.server.reactive.ServerHttpRequest request) {
		// 优先从 X-Forwarded-For 获取
		String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isEmpty()) {
			String[] ips = forwardedFor.split(",");
			return ips[0].trim();
		}
		
		// 其次从 X-Real-IP 获取
		String realIp = request.getHeaders().getFirst("X-Real-IP");
		if (realIp != null && !realIp.isEmpty()) {
			return realIp;
		}
		
		// 最后从远程地址获取
		if (request.getRemoteAddress() != null) {
			String remoteAddr = request.getRemoteAddress().getAddress().getHostAddress();
			if (remoteAddr != null && !remoteAddr.isEmpty()) {
				return remoteAddr;
			}
		}
		
		return "unknown";
	}

	/**
	 * 一键生成完整密码哈希（用于数据迁移）
	 * 访问：GET /api/auth/generate-complete-password?password=你的密码
	 */
	@GetMapping("/generate-complete-password")
	public ResponseEntity<Map<String, Object>> generateCompletePassword(@RequestParam String password) {
		try {
			// 第一步：前端SM3哈希
			String frontendHash = Sm3PasswordEncoder.sm3Hash(password);
			// 第二步：后端加盐哈希
			String backendHash = Sm3PasswordEncoder.encode(frontendHash);
			// 验证
			boolean matches = Sm3PasswordEncoder.matches(frontendHash, backendHash);
			
			Map<String, Object> data = new HashMap<>();
			data.put("originalPassword", password);
			data.put("frontendSm3Hash", frontendHash);
			data.put("backendStoredHash", backendHash);
			data.put("verificationResult", matches);
			data.put("sqlStatement", "UPDATE `system_users` SET `password` = '" + backendHash + "' WHERE `username` = '你的用户名';");
			
			return ResponseEntity.ok(Map.of(
					"code", 0,
					"message", "密码生成成功",
					"data", data
			));
		} catch (Exception e) {
			log.error("Generate password failed: {}", e.getMessage());
			return ResponseEntity.status(500).body(Map.of(
					"code", 500,
					"message", "密码生成失败: " + e.getMessage()
			));
		}
	}

	/**
	 * 超级管理员模拟用户登录（生成临时Token）
	 * 只有超级管理员可以调用此接口
	 */
	@PostMapping("/impersonate")
	public ResponseEntity<Map<String, Object>> impersonateUser(
			@RequestBody ImpersonateRequest request,
			@RequestHeader(value = "Authorization", required = false) String authHeader) {
		try {
			if (authHeader == null || !authHeader.startsWith("Bearer ")) {
				return ResponseEntity.status(401).body(Map.of(
						"code", 401,
						"message", "未登录"
				));
			}

			String currentToken = authHeader.substring(7);
			String currentRole = jwtUtil.getRoleFromToken(currentToken);
			String currentUsername = jwtUtil.getUsernameFromToken(currentToken);

			// 严格验证：仅允许超级管理员
			if (!"super_admin".equals(currentRole)) {
				log.warn("【安全警告】非超级管理员尝试模拟用户 - 用户名: {}, 角色: {}", 
						currentUsername != null ? currentUsername : "unknown", currentRole);
				return ResponseEntity.status(403).body(Map.of(
						"code", 403,
						"message", "禁止访问：此功能仅限超级管理员使用"
				));
			}

			// 验证请求参数
			if (request == null || request.getUserId() == null) {
				log.warn("模拟用户请求参数为空: request={}", request);
				return ResponseEntity.status(400).body(Map.of(
						"code", 400,
						"message", "请求参数错误：userId不能为空"
				));
			}

			log.info("开始模拟用户，目标用户ID: {}", request.getUserId());

			// 获取目标用户信息
			User targetUser = authService.getUserById(request.getUserId());
			if (targetUser == null) {
				log.warn("目标用户不存在，ID: {}", request.getUserId());
				return ResponseEntity.status(404).body(Map.of(
						"code", 404,
						"message", "目标用户不存在"
				));
			}

			// 检查目标用户状态
			if (targetUser.getStatus() != null && targetUser.getStatus() != 1) {
				return ResponseEntity.status(400).body(Map.of(
						"code", 400,
						"message", "目标用户已被禁用"
				));
			}

			// 生成临时Token（有效期5分钟）
			String tempToken = jwtUtil.generateTempToken(targetUser);

			String adminUsername = jwtUtil.getUsernameFromToken(currentToken);
			log.info("超级管理员 [{}] 模拟用户 [{}] (ID: {})",
					adminUsername != null ? adminUsername : "unknown",
					targetUser.getUsername(),
					targetUser.getId());

			Map<String, Object> data = new HashMap<>();
			data.put("accessToken", tempToken);
			
			// 构建用户信息（处理null值）
			Map<String, Object> userInfo = new HashMap<>();
			userInfo.put("id", targetUser.getId());
			userInfo.put("username", targetUser.getUsername());
			userInfo.put("nickname", targetUser.getNickname() != null ? targetUser.getNickname() : "");
			userInfo.put("tenantId", targetUser.getTenantId());
			userInfo.put("role", targetUser.getRole());
			userInfo.put("agentId", targetUser.getAgentId()); // 允许为null
			data.put("userInfo", userInfo);

			return ResponseEntity.ok(Map.of(
					"code", 0,
					"message", "模拟登录成功",
					"data", data
			));
		} catch (Exception e) {
			log.error("Impersonate user failed: {}", e.getMessage(), e);
			return ResponseEntity.status(500).body(Map.of(
					"code", 500,
					"message", "模拟登录失败: " + e.getMessage()
			));
		}
	}

	@Data
	public static class ImpersonateRequest {
		private Long userId;
	}
}
