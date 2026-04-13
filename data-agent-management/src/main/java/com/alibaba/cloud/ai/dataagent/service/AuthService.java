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
package com.alibaba.cloud.ai.dataagent.service;

import com.alibaba.cloud.ai.dataagent.dto.LoginRequest;
import com.alibaba.cloud.ai.dataagent.dto.LoginResponse;
import com.alibaba.cloud.ai.dataagent.entity.User;
import com.alibaba.cloud.ai.dataagent.mapper.UserMapper;
import com.alibaba.cloud.ai.dataagent.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Authentication Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserMapper userMapper;

	private final JwtUtil jwtUtil;

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

	/**
	 * User login
	 */
	public LoginResponse login(LoginRequest request) {
		User user = userMapper.selectByUsername(request.getUsername());
		
		if (user == null) {
			throw new RuntimeException("用户名或密码错误");
		}

		if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
			throw new RuntimeException("用户名或密码错误");
		}

		if (user.getStatus() != 1) {
			throw new RuntimeException("账户已被禁用");
		}

		String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

		LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
				.id(user.getId())
				.username(user.getUsername())
				.nickname(user.getNickname())
				.email(user.getEmail())
				.avatar(user.getAvatar())
				.role(user.getRole())
				.build();

		return LoginResponse.builder()
				.token(token)
				.userInfo(userInfo)
				.build();
	}

	/**
	 * Get current user by ID
	 */
	public User getCurrentUser(Long userId) {
		return userMapper.selectById(userId);
	}

}
