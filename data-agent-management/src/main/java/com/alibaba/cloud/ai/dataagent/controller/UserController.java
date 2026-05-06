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

import com.alibaba.cloud.ai.dataagent.dto.TenantDTO;
import com.alibaba.cloud.ai.dataagent.entity.User;
import com.alibaba.cloud.ai.dataagent.mapper.SystemTenantMapper;
import com.alibaba.cloud.ai.dataagent.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * User Management Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class UserController {

	private final UserService userService;
	private final SystemTenantMapper systemTenantMapper;

	/**
	 * 获取用户列表（分页）
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> listUsers(
			@RequestParam(required = false, defaultValue = "1") int page,
			@RequestParam(required = false, defaultValue = "10") int pageSize,
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) Long tenantId) {
		try {
			Map<String, Object> result = userService.getUsersPage(page, pageSize, keyword, tenantId);
			List<User> users = (List<User>) result.get("list");

			// 不返回密码
			users.forEach(user -> user.setPassword(null));

			return ResponseEntity.ok(Map.of(
					"code", 0,
					"data", result
			));
		} catch (Exception e) {
			log.error("Get user list failed", e);
			return ResponseEntity.status(500).body(Map.of(
					"code", 500,
					"message", "获取用户列表失败: " + e.getMessage()
			));
		}
	}

	/**
	 * 获取单个用户
	 */
	@GetMapping("/{id}")
	public ResponseEntity<Map<String, Object>> getUser(@PathVariable Long id) {
		try {
			User user = userService.getUserById(id);
			if (user == null) {
				return ResponseEntity.status(404).body(Map.of(
						"code", 404,
						"message", "用户不存在"
				));
			}
			user.setPassword(null);
			return ResponseEntity.ok(Map.of(
					"code", 0,
					"data", user
			));
		} catch (Exception e) {
			log.error("Get user failed", e);
			return ResponseEntity.status(500).body(Map.of(
					"code", 500,
					"message", "获取用户失败: " + e.getMessage()
			));
		}
	}

	/**
	 * 创建用户
	 */
	@PostMapping
	public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> request) {
		try {
			User user = new User();
			user.setUsername((String) request.get("username"));
			user.setNickname((String) request.get("nickname"));
			user.setEmail((String) request.get("email"));
			user.setPhone((String) request.get("phone"));
			user.setRemark((String) request.get("remark"));
			user.setRole((String) request.getOrDefault("role", "user"));
			user.setAvatar((String) request.get("avatar"));
			// 处理 tenantId
			if (request.get("tenantId") != null) {
				user.setTenantId(((Number) request.get("tenantId")).longValue());
			}

			String rawPassword = (String) request.get("password");
			if (rawPassword == null || rawPassword.isEmpty()) {
				return ResponseEntity.badRequest().body(Map.of(
						"code", 400,
						"message", "密码不能为空"
				));
			}

			User created = userService.createUser(user, rawPassword);
			created.setPassword(null);

			return ResponseEntity.ok(Map.of(
					"code", 0,
					"message", "创建用户成功",
					"data", created
			));
		} catch (Exception e) {
			log.error("Create user failed", e);
			return ResponseEntity.status(400).body(Map.of(
					"code", 400,
					"message", e.getMessage()
			));
		}
	}

	/**
	 * 更新用户
	 */
	@PutMapping("/{id}")
	public ResponseEntity<Map<String, Object>> updateUser(
			@PathVariable Long id,
			@RequestBody Map<String, Object> request) {
		try {
			User user = new User();
			user.setId(id);
			user.setNickname((String) request.get("nickname"));
			user.setEmail((String) request.get("email"));
			user.setPhone((String) request.get("phone"));
			user.setRemark((String) request.get("remark"));
			user.setRole((String) request.get("role"));
			user.setAvatar((String) request.get("avatar"));
			user.setProvince((String) request.get("province"));
			// 处理 tenantId
			if (request.get("tenantId") != null) {
				user.setTenantId(((Number) request.get("tenantId")).longValue());
			}
			// 处理 agentId
			if (request.get("agentId") != null) {
				user.setAgentId(((Number) request.get("agentId")).longValue());
			}
			user.setStatus(request.get("status") != null
					? ((Number) request.get("status")).intValue()
					: 1);

			userService.updateUser(user);

			return ResponseEntity.ok(Map.of(
					"code", 0,
					"message", "更新用户成功"
			));
		} catch (Exception e) {
			log.error("Update user failed", e);
			return ResponseEntity.status(400).body(Map.of(
					"code", 400,
					"message", e.getMessage()
			));
		}
	}

	/**
	 * 重置密码
	 */
	@PutMapping("/{id}/reset-password")
	public ResponseEntity<Map<String, Object>> resetPassword(
			@PathVariable Long id,
			@RequestBody Map<String, String> request) {
		try {
			String newPassword = request.get("password");
			if (newPassword == null || newPassword.isEmpty()) {
				return ResponseEntity.badRequest().body(Map.of(
						"code", 400,
						"message", "新密码不能为空"
				));
			}

			userService.resetPassword(id, newPassword);

			return ResponseEntity.ok(Map.of(
					"code", 0,
					"message", "密码重置成功"
			));
		} catch (Exception e) {
			log.error("Reset password failed", e);
			return ResponseEntity.status(400).body(Map.of(
					"code", 400,
					"message", e.getMessage()
			));
		}
	}

	/**
	 * 删除用户
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long id) {
		try {
			userService.deleteUser(id);

			return ResponseEntity.ok(Map.of(
					"code", 0,
					"message", "删除用户成功"
			));
		} catch (Exception e) {
			log.error("Delete user failed", e);
			return ResponseEntity.status(400).body(Map.of(
					"code", 400,
					"message", e.getMessage()
			));
		}
	}

	/**
	 * 切换用户状态
	 */
	@PutMapping("/{id}/status")
	public ResponseEntity<Map<String, Object>> toggleStatus(
			@PathVariable Long id,
			@RequestBody Map<String, Integer> request) {
		try {
			Integer status = request.get("status");
			if (status == null) {
				return ResponseEntity.badRequest().body(Map.of(
						"code", 400,
						"message", "状态不能为空"
				));
			}

			userService.updateUserStatus(id, status);

			return ResponseEntity.ok(Map.of(
					"code", 0,
					"message", status == 1 ? "用户已启用" : "用户已禁用"
			));
		} catch (Exception e) {
			log.error("Toggle user status failed", e);
			return ResponseEntity.status(400).body(Map.of(
					"code", 400,
					"message", e.getMessage()
			));
		}
	}

	/**
	 * 获取所有启用的租户列表
	 */
	@GetMapping("/tenants")
	public ResponseEntity<Map<String, Object>> getTenants() {
		try {
			List<TenantDTO> tenants = systemTenantMapper.findAllActiveTenants();
			return ResponseEntity.ok(Map.of(
					"code", 0,
					"data", tenants
			));
		} catch (Exception e) {
			log.error("Get tenants failed", e);
			return ResponseEntity.status(500).body(Map.of(
					"code", 500,
					"message", "获取租户列表失败: " + e.getMessage()
			));
		}
	}

}
