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
package com.jldaren.agent.ai.datascope2.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 在 Spring Boot 启动最早阶段把 Spring 配置里的 DashScope API Key
 * 注入到系统环境变量，让 agentscope-harness 的 ModelRegistry 能读到。
 *
 * <p>问题根因：
 * agentscope-harness 2.0 的 DashScopeModelProvider.create() 里直接调
 * System.getenv("DASHSCOPE_API_KEY")，不读 Spring 配置。
 * 这个 PostProcessor 在任何 bean 创建之前把 spring.ai.dashscope.api-key
 * → DASHSCOPE_API_KEY 系统环境变量，实现 yml 配置 → agentscope 打通。
 *
 * <p>注册方式：META-INF/spring.factories（Spring Boot 2.x 兼容写法）
 */
public class DashScopeEnvVarPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "dashScopeEnvVar";

    /** Spring 配置里的 key */
    private static final String SPRING_API_KEY = "spring.ai.dashscope.api-key";

    /** agentscope DashScopeModelProvider 读的 env var */
    private static final String ENV_VAR_API_KEY = "DASHSCOPE_API_KEY";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 跳过 test 环境
        if (environment.getActiveProfiles().length > 0
                && environment.getActiveProfiles()[0].equals("test")) {
            return;
        }

        // 读取 spring.ai.dashscope.api-key（支持占位符展开后的值）
        String apiKey = environment.getProperty(SPRING_API_KEY, "").trim();

        // 只覆盖空的 env var，避免 IntelliJ Run Configuration 里设的值被冲掉
        String existingEnv = System.getenv(ENV_VAR_API_KEY);
        if (existingEnv != null && !existingEnv.isBlank()) {
            return; // 已有值，不覆盖
        }

        if (apiKey == null || apiKey.isBlank()) {
            return; // 没配置，跳过
        }

        // 注入到系统环境变量（ProcessBuilder 启动的子进程也会继承）
        Map<String, Object> envVars = new HashMap<>();
        envVars.put(ENV_VAR_API_KEY, apiKey);

        // Priority: HIGHEST - 在所有 propertySource 之前，保证 ModelRegistry 能读到
        environment.getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, envVars));

        // 同时写 System.setProperty，防止直接读 System.getProperty 的情况
        System.setProperty(ENV_VAR_API_KEY, apiKey);
    }
}
