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
import com.alibaba.cloud.ai.dataagent.util.JwtUtil;
import com.alibaba.cloud.ai.dataagent.util.Sm3PasswordEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

	private final JwtUtil jwtUtil;


	private final UserMapper userMapper;

	private String provinceList="北京,天津,上海,重庆,河北,山西,辽宁,吉林,黑龙江,江苏,浙江,安徽,福建,江西,山东,河南,湖北,湖南,广东,海南,四川,贵州,云南,陕西,甘肃,青海,台湾,内蒙古,广西,西藏,宁夏,新疆,香港,澳门";

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
		return getUsersPage(page, pageSize, keyword, null);
	}

	/**
	 * 分页获取用户列表（支持按租户筛选）
	 */
	public Map<String, Object> getUsersPage(int page, int pageSize, String keyword, Long tenantId) {
		int offset = (page - 1) * pageSize;
		List<User> users;
		int total;

		if (tenantId != null) {
			// 按租户筛选
			if (keyword != null && !keyword.trim().isEmpty()) {
				users = userMapper.searchByTenantAndKeywordPage(tenantId, keyword.trim(), offset, pageSize);
				total = userMapper.countByTenantAndKeyword(tenantId, keyword.trim());
			} else {
				users = userMapper.selectByTenantIdPage(tenantId, offset, pageSize);
				total = userMapper.countByTenantId(tenantId);
			}
		} else {
			// 不按租户筛选
			if (keyword != null && !keyword.trim().isEmpty()) {
				users = userMapper.searchByKeywordPage(keyword.trim(), offset, pageSize);
				total = userMapper.countByKeyword(keyword.trim());
			} else {
				users = userMapper.selectPage(offset, pageSize);
				total = userMapper.countAll();
			}
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
	public Map getUserProvince(Long id) {
		User user =	userMapper.selectById(id);
		if("全国".equals(user.getProvince())){
			user.setProvince(provinceList);
		}
		Map<String, String> paramMap = new HashMap<>();
		paramMap.put("province", user!=null?user.getProvince():null);
		return paramMap;
	}

	/**
	 * 检查并扣减用户当日 AI 调用次数（原子操作，含跨天自动重置）。
	 *
	 * <p>不限（ai_daily_limit 为 NULL/≤0）的用户直接放行，不做任何扣减。
	 * <p>容错：DB 异常时放行（不因配额功能故障阻断主流程）。
	 * @param userId 用户ID
	 * @return true=允许调用（已扣减 1 次或不限），false=当日配额耗尽
	 */
	public boolean tryConsumeAiQuota(Long userId) {
		if (userId == null) {
			return true;
		}
		try {
			// 先判断是否不限：不限的用户直接放行，不扣减、不写库
			User user = userMapper.selectById(userId);
			if (user == null || user.getAiDailyLimit() == null || user.getAiDailyLimit() <= 0) {
				return true;
			}
			// 有限额：原子扣减，影响行数 0 表示当日配额耗尽
			return userMapper.consumeAiQuota(userId) > 0;
		} catch (Exception e) {
			log.warn("AI 配额扣减异常，默认放行: userId={}, error={}", userId, e.getMessage());
			return true;
		}
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


	/**
	 * 从Token解析获取完整用户信息（无需远程调用）
	 * 包含: id, username, tenantId, role
	 */
	public User getUserInfoFromToken(String token) {
		try {
			Long userId = jwtUtil.getUserIdFromToken(token);
			String username = jwtUtil.getUsernameFromToken(token);
			Long tenantId = jwtUtil.getTenantIdFromToken(token);
			String role = jwtUtil.getRoleFromToken(token);
			String province = jwtUtil.getProvinceFromToken(token);
			if("全国".equals(province)){
				province = provinceList;
			}

			User info = new User();
			info.setId(userId);
			info.setUsername(username);
			info.setTenantId(tenantId);
			info.setRole(role);
			info.setProvince(province);
			return info;
		} catch (Exception e) {
			log.error("解析Token获取用户信息失败: {}", e.getMessage());
			return null;
		}
	}

	public Long getTenantId(String tenantName) {
		return userMapper.getTenantId(tenantName);
	}
}
