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
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.dto.LoginRequest;
import com.alibaba.cloud.ai.dataagent.dto.LoginResponse;
import com.alibaba.cloud.ai.dataagent.entity.User;
import com.alibaba.cloud.ai.dataagent.service.AuthService;
import com.alibaba.cloud.ai.dataagent.util.JwtUtil;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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

	/**
	 * User login
	 */
	@PostMapping("/login")
	public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		try {
			LoginResponse response = authService.login(request);
			return ApiResponse.success("登录成功", response);
		} catch (Exception e) {
			log.error("Login failed: {}", e.getMessage());
			return ApiResponse.error(e.getMessage());
		}
	}

	/**
	 * Get current user info
	 */
	@GetMapping("/me")
	public ApiResponse<User> getCurrentUser(@RequestHeader(value = "Authorization", required = false) String authorization) {
		try {
			if (authorization == null || !authorization.startsWith("Bearer ")) {
				return ApiResponse.error("未提供有效的认证令牌");
			}

			String token = authorization.substring(7);
			Long userId = jwtUtil.getUserIdFromToken(token);

			if (userId == null) {
				return ApiResponse.error("无效的认证令牌");
			}

			User user = authService.getCurrentUser(userId);
			if (user == null) {
				return ApiResponse.error("用户不存在");
			}

			// Don't return password
			user.setPassword(null);
			return ApiResponse.success("获取用户信息成功", user);
		} catch (Exception e) {
			log.error("Get current user failed: {}", e.getMessage());
			return ApiResponse.error(e.getMessage());
		}
	}

	/**
	 * 临时接口：生成BCrypt密码哈希（仅用于测试，生产环境请删除）
	 * 访问：GET /api/auth/generate-password?password=admin123
	 */
/*	@GetMapping("/generate-password")
	public ApiResponse<String> generatePassword(@RequestParam String password) {
		try {
			org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder = 
				new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
			String hash = encoder.encode(password);
			boolean matches = encoder.matches(password, hash);
			
			String result = String.format(
				"原始密码: %s\n加密哈希: %s\n验证结果: %s\n\n请执行以下SQL更新数据库:\nUPDATE `user` SET `password` = '%s' WHERE `username` = 'admin';",
				password, hash, matches, hash
			);
			
			return ApiResponse.success("密码生成成功", result);
		} catch (Exception e) {
			log.error("Generate password failed: {}", e.getMessage());
			return ApiResponse.error(e.getMessage());
		}
	}*/

}
