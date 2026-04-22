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
package com.alibaba.cloud.ai.dataagent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OpenAPI/Swagger 配置。
 * <p>
 * 仅在 dev/test 环境启用，生产环境自动禁用，避免接口暴露。
 */
@Configuration
@Profile({"dev", "test", "h2"})
public class OpenApiConfig {

	@Bean
	public OpenAPI dataAgentOpenApi() {
		return new OpenAPI()
			.info(new Info().title("DataAgent Backend API").description("DataAgent 后端接口文档").version("v1"));
	}

	@Bean
	public GroupedOpenApi dataAgentApiGroup() {
		return GroupedOpenApi.builder()
			.group("data-agent")
			.packagesToScan("com.alibaba.cloud.ai.dataagent.controller")
			.pathsToMatch("/api/**")
			.build();
	}

}
