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
package com.jldaren.agent.ai.datascope.mapper;

import com.jldaren.agent.ai.datascope.dto.UserDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 系统用户 Mapper
 */
@Mapper
public interface SystemUserMapper {

    /**
     * 根据租户ID查询用户列表
     */
    @Select("SELECT id, username, nickname FROM system_users WHERE tenant_id = #{tenantId} AND status = 1 ORDER BY id")
    List<UserDTO> findByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 根据ID查询用户
     */
    @Select("SELECT * FROM system_users WHERE id = #{id}")
    Map<String, Object> findById(@Param("id") Long id);
}
