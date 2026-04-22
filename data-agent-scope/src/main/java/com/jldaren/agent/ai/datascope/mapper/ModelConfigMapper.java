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

import com.jldaren.agent.ai.datascope.entity.ModelConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 模型配置 Mapper
 */
@Mapper
public interface ModelConfigMapper {

    @Select("""
            SELECT id, provider, REPLACE(base_url,'/compatible-mode','') as baseUrl, api_key as apiKey, model_name as modelName, 
                   temperature, is_active as isActive, max_tokens as maxTokens,
                   model_type as modelType, completions_path as completionsPath, 
                   embeddings_path as embeddingsPath
            FROM model_config 
            WHERE model_type = #{modelType} AND is_active = 1 AND is_deleted = 0 
            LIMIT 1
            """)
    ModelConfig selectActiveByType(@Param("modelType") String modelType);
}
