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
/**
 * @author dlw
 * @date 2025-01-27
 */
package com.alibaba.cloud.ai.dataagent.mapper;

import com.alibaba.cloud.ai.dataagent.dto.TenantDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 系统租户 Mapper
 */
@Mapper
public interface SystemTenantMapper {

    /**
     * 查询所有启用的租户
     */
    @Select("SELECT id, name FROM system_tenant WHERE status = 0 AND deleted = 0 ORDER BY id")
    List<TenantDTO> findAllActiveTenants();

    /**
     * 根据ID查询租户
     */
    @Select("SELECT * FROM system_tenant WHERE id = #{id}")
    Map<String, Object> findById(@Param("id") Long id);
}
