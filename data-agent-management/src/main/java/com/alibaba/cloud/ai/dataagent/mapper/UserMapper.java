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

	@Insert("INSERT INTO system_users (username, password, nickname, email, phone, remark, avatar, role, status, province, tenant_id, agent_id, create_time, update_time) VALUES (#{username}, #{password}, #{nickname}, #{email}, #{phone}, #{remark}, #{avatar}, #{role}, #{status}, #{province}, #{tenantId}, #{agentId}, NOW(), NOW())")
	@Options(useGeneratedKeys = true, keyProperty = "id")
	int insert(User user);

	@Update("UPDATE system_users SET nickname = #{nickname}, email = #{email}, phone = #{phone}, remark = #{remark}, avatar = #{avatar}, role = #{role}, status = #{status}, province = #{province}, tenant_id = #{tenantId}, agent_id = #{agentId}, update_time = NOW() WHERE id = #{id}")
	int update(User user);

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
