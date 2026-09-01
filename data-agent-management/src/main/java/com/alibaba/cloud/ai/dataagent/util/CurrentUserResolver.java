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

import com.alibaba.cloud.ai.dataagent.entity.User;
import com.alibaba.cloud.ai.dataagent.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * 当前登录用户解析器（公共组件）
 *
 * <p>从 WebFlux 请求中自动提取 Token 并解析出当前登录用户，供各 Controller 复用。
 * <p>Token 来源与 {@link com.alibaba.cloud.ai.dataagent.filter.JwtAuthenticationFilter} 保持一致：
 * 优先取 {@code Authorization: Bearer} 请求头，其次取 URL 查询参数 {@code token}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CurrentUserResolver {

	private final UserService userService;

	/**
	 * 从请求中自动解析当前登录用户：
	 * 先从 Token 解出用户 ID，再查库获取最新完整信息（Token 内信息 2 小时内可能滞后），并清除密码。
	 * @param request 当前请求（由 Spring WebFlux 自动注入）
	 * @return 当前登录用户；未携带有效 Token 或用户已被禁用时返回 null
	 */
	public User resolve(ServerHttpRequest request) {
		String token = extractToken(request);
		if (token == null) {
			return null;
		}
		User tokenUser = userService.getUserInfoFromToken(token);
		if (tokenUser == null || tokenUser.getId() == null) {
			return null;
		}
		User user = userService.getUserById(tokenUser.getId());
		if (user == null || (user.getStatus() != null && user.getStatus() != 1)) {
			return null;
		}
		user.setPassword(null);
		return user;
	}

	/**
	 * 从请求中提取 Token：优先 Authorization 请求头，其次 URL 查询参数 token
	 */
	public String extractToken(ServerHttpRequest request) {
		String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		if (authHeader != null && authHeader.startsWith("Bearer ")) {
			return authHeader.substring(7);
		}
		return request.getQueryParams().getFirst("token");
	}
}
