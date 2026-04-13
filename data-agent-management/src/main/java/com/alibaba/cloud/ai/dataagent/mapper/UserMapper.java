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

/**
 * User Mapper Interface
 */
@Mapper
public interface UserMapper {

	@Select("""
			SELECT * FROM system_users
			WHERE username = #{username} AND status = 1
			""")
	User selectByUsername(@Param("username") String username);

	@Select("""
			SELECT * FROM system_users
			WHERE id = #{id} AND status = 1
			""")
	User selectById(@Param("id") Long id);

	@Insert("""
			INSERT INTO system_users (username, password, nickname, email, avatar, role, status, create_time, update_time)
			VALUES (#{username}, #{password}, #{nickname}, #{email}, #{avatar}, #{role}, #{status}, NOW(), NOW())
			""")
	@Options(useGeneratedKeys = true, keyProperty = "id")
	int insert(User user);

	@Update("""
			UPDATE system_users
			SET nickname = #{nickname}, email = #{email}, avatar = #{avatar}, update_time = NOW()
			WHERE id = #{id}
			""")
	int update(User user);

}
