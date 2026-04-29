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

import com.alibaba.cloud.ai.dataagent.entity.User;
import com.alibaba.cloud.ai.dataagent.mapper.UserMapper;
import com.alibaba.cloud.ai.dataagent.util.Sm3PasswordEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * User Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

	private final UserMapper userMapper;

	/**
	 * 获取所有用户列表
	 */
	public List<User> getAllUsers() {
		return userMapper.selectAll();
	}

	/**
	 * 分页获取用户列表
	 */
	public Map<String, Object> getUsersPage(int page, int pageSize, String keyword) {
		int offset = (page - 1) * pageSize;
		List<User> users;
		int total;

		if (keyword != null && !keyword.trim().isEmpty()) {
			users = userMapper.searchByKeywordPage(keyword.trim(), offset, pageSize);
			total = userMapper.countByKeyword(keyword.trim());
		} else {
			users = userMapper.selectPage(offset, pageSize);
			total = userMapper.countAll();
		}

		return Map.of(
			"list", users,
			"total", total,
			"page", page,
			"pageSize", pageSize
		);
	}

	/**
	 * 搜索用户
	 */
	public List<User> searchUsers(String keyword) {
		if (keyword == null || keyword.trim().isEmpty()) {
			return userMapper.selectAll();
		}
		return userMapper.searchByKeyword(keyword.trim());
	}

	/**
	 * 根据ID获取用户
	 */
	public User getUserById(Long id) {
		return userMapper.selectById(id);
	}

	/**
	 * 创建用户
	 */
	@Transactional
	public User createUser(User user, String rawPassword) {
		// 检查用户名是否存在
		if (userMapper.countByUsername(user.getUsername()) > 0) {
			throw new RuntimeException("用户名已存在");
		}

		// 加密密码
		String encryptedPassword = encryptPassword(rawPassword);
		user.setPassword(encryptedPassword);
		user.setStatus(1);

		userMapper.insert(user);
		return user;
	}

	/**
	 * 更新用户
	 */
	@Transactional
	public boolean updateUser(User user) {
		User existing = userMapper.selectById(user.getId());
		if (existing == null) {
			throw new RuntimeException("用户不存在");
		}
		return userMapper.update(user) > 0;
	}

	/**
	 * 重置密码
	 */
	@Transactional
	public boolean resetPassword(Long userId, String newRawPassword) {
		User user = userMapper.selectById(userId);
		if (user == null) {
			throw new RuntimeException("用户不存在");
		}

		String encryptedPassword = encryptPassword(newRawPassword);
		return userMapper.updatePassword(userId, encryptedPassword) > 0;
	}

	/**
	 * 更新用户状态
	 */
	@Transactional
	public boolean updateUserStatus(Long userId, Integer status) {
		User user = userMapper.selectById(userId);
		if (user == null) {
			throw new RuntimeException("用户不存在");
		}
		return userMapper.updateStatus(userId, status) > 0;
	}

	/**
	 * 删除用户
	 */
	@Transactional
	public boolean deleteUser(Long userId) {
		User user = userMapper.selectById(userId);
		if (user == null) {
			throw new RuntimeException("用户不存在");
		}

		// 不允许删除自己
		if ("admin".equals(user.getUsername())) {
			throw new RuntimeException("不能删除管理员账户");
		}

		return userMapper.deleteById(userId) > 0;
	}

	/**
	 * 更新用户登录信息
	 */
	public boolean updateLoginInfo(Long userId, String loginIp) {
		return userMapper.updateLoginInfo(userId, loginIp, LocalDateTime.now()) > 0;
	}

	/**
	 * 使用SM3加密密码（双重加密）
	 * 前端SM3 + 后端加盐SM3
	 */
	private String encryptPassword(String rawPassword) {
		// 前端SM3哈希
		String frontendHash = Sm3PasswordEncoder.sm3Hash(rawPassword);
		// 后端加盐哈希
		return Sm3PasswordEncoder.encode(frontendHash);
	}

}
