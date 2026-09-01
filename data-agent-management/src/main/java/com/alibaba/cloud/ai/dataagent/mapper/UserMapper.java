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
package com.alibaba.cloud.ai.dataagent.mapper;

import com.alibaba.cloud.ai.dataagent.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * User Mapper Interface
 */
@Mapper
public interface UserMapper {

	@Select("SELECT * FROM system_users WHERE username = #{username} AND status = 1")
	User selectByUsername(@Param("username") String username);

	@Select("SELECT * FROM system_users WHERE id = #{id}")
	User selectById(@Param("id") Long id);

	@Select("SELECT * FROM system_users ORDER BY create_time DESC")
	List<User> selectAll();

	@Select("SELECT * FROM system_users WHERE username LIKE CONCAT('%', #{keyword}, '%') OR nickname LIKE CONCAT('%', #{keyword}, '%') ORDER BY create_time DESC")
	List<User> searchByKeyword(@Param("keyword") String keyword);

	@Insert("INSERT INTO system_users (username, password, nickname, email, phone, remark, avatar, role, status, province, ai_daily_limit, tenant_id, agent_id, create_time, update_time) VALUES (#{username}, #{password}, #{nickname}, #{email}, #{phone}, #{remark}, #{avatar}, #{role}, #{status}, #{province}, #{aiDailyLimit}, #{tenantId}, #{agentId}, NOW(), NOW())")
	@Options(useGeneratedKeys = true, keyProperty = "id")
	int insert(User user);

	@Update("UPDATE system_users SET nickname = #{nickname}, email = #{email}, phone = #{phone}, remark = #{remark}, avatar = #{avatar}, role = #{role}, status = #{status}, province = #{province}, ai_daily_limit = #{aiDailyLimit}, tenant_id = #{tenantId}, agent_id = #{agentId}, update_time = NOW() WHERE id = #{id}")
	int update(User user);

	/**
	 * 原子扣减用户当日 AI 调用次数（带跨天自动重置）。
	 *
	 * <p>规则：
	 * <ul>
	 *   <li>ai_daily_limit 为 NULL 或 ≤ 0 → 不限，直接计数 +1</li>
	 *   <li>ai_usage_date 不是今天 → 归 1 并更新日期（相当于跨天清零重计）</li>
	 *   <li>当日已用次数 ≥ 上限 → WHERE 不命中，受影响行数 0，表示配额耗尽</li>
	 * </ul>
	 * @return 受影响行数：1=扣减成功（允许调用），0=配额耗尽或用户不存在
	 */
	@Update("UPDATE system_users SET "
			+ "ai_used_count = CASE WHEN ai_usage_date = CURDATE() THEN ai_used_count + 1 ELSE 1 END, "
			+ "ai_usage_date = CURDATE() "
			+ "WHERE id = #{id} AND ("
			+ "ai_daily_limit IS NULL OR ai_daily_limit <= 0 "
			+ "OR CASE WHEN ai_usage_date = CURDATE() THEN ai_used_count ELSE 0 END < ai_daily_limit)")
	int consumeAiQuota(@Param("id") Long id);

	@Update("UPDATE system_users SET password = #{password}, update_time = NOW() WHERE id = #{id}")
	int updatePassword(@Param("id") Long id, @Param("password") String password);

	@Update("UPDATE system_users SET status = #{status}, update_time = NOW() WHERE id = #{id}")
	int updateStatus(@Param("id") Long id, @Param("status") Integer status);

	@Update("UPDATE system_users SET login_ip = #{loginIp}, login_date = #{loginDate}, update_time = NOW() WHERE id = #{id}")
	int updateLoginInfo(@Param("id") Long id, @Param("loginIp") String loginIp, @Param("loginDate") java.time.LocalDateTime loginDate);

	@Delete("DELETE FROM system_users WHERE id = #{id}")
	int deleteById(@Param("id") Long id);

	@Select("SELECT COUNT(*) FROM system_users WHERE username = #{username}")
	int countByUsername(@Param("username") String username);

	@Select("SELECT * FROM system_users ORDER BY create_time DESC LIMIT #{offset}, #{limit}")
	List<User> selectPage(@Param("offset") int offset, @Param("limit") int limit);

	@Select("SELECT * FROM system_users WHERE username LIKE CONCAT('%', #{keyword}, '%') OR nickname LIKE CONCAT('%', #{keyword}, '%') ORDER BY create_time DESC LIMIT #{offset}, #{limit}")
	List<User> searchByKeywordPage(@Param("keyword") String keyword, @Param("offset") int offset, @Param("limit") int limit);

	@Select("SELECT COUNT(*) FROM system_users")
	int countAll();

	@Select("SELECT COUNT(*) FROM system_users WHERE username LIKE CONCAT('%', #{keyword}, '%') OR nickname LIKE CONCAT('%', #{keyword}, '%')")
	int countByKeyword(@Param("keyword") String keyword);

	@Select("SELECT * FROM system_users WHERE tenant_id = #{tenantId} ORDER BY create_time DESC LIMIT #{offset}, #{limit}")
	List<User> selectByTenantIdPage(@Param("tenantId") Long tenantId, @Param("offset") int offset, @Param("limit") int limit);

	@Select("SELECT COUNT(*) FROM system_users WHERE tenant_id = #{tenantId}")
	int countByTenantId(@Param("tenantId") Long tenantId);

	@Select("SELECT * FROM system_users WHERE tenant_id = #{tenantId} AND (username LIKE CONCAT('%', #{keyword}, '%') OR nickname LIKE CONCAT('%', #{keyword}, '%')) ORDER BY create_time DESC LIMIT #{offset}, #{limit}")
	List<User> searchByTenantAndKeywordPage(@Param("tenantId") Long tenantId, @Param("keyword") String keyword, @Param("offset") int offset, @Param("limit") int limit);

	@Select("SELECT COUNT(*) FROM system_users WHERE tenant_id = #{tenantId} AND (username LIKE CONCAT('%', #{keyword}, '%') OR nickname LIKE CONCAT('%', #{keyword}, '%'))")
	int countByTenantAndKeyword(@Param("tenantId") Long tenantId, @Param("keyword") String keyword);

}
