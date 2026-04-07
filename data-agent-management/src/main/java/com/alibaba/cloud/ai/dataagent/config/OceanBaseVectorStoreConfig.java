/*
 * Copyright 2026 the original author or authors.
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
package com.alibaba.cloud.ai.dataagent.config;

import com.alibaba.cloud.ai.dataagent.service.vectorstore.OceanBaseVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "oceanbase")
public class OceanBaseVectorStoreConfig {
	@Bean
	public OceanBaseVectorStore oceanBaseVectorStore(JdbcTemplate jdbcTemplate,EmbeddingModel embeddingModel) {
		log.info("Configuring OceanBase VectorStore...");
		return new OceanBaseVectorStore(jdbcTemplate, embeddingModel, "ai_vector_store");
	}

}
