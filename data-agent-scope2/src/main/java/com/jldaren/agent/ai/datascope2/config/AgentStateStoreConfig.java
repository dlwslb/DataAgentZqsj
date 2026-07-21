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

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Agent 状态存储
 *
 * <p>dev: JsonFileAgentStateStore（单机）
 * <p>prod: RedisAgentStateStore（多副本，跨进程恢复）
 */
@Slf4j
@Configuration
public class AgentStateStoreConfig {

    @Value("${agentscope.state-store.type:file}")
    private String stateStoreType;

    @Value("${agentscope.state-store.base-path}")
    private String stateStoreBasePath;

    @Value("${agentscope.state-store.key-prefix:dataagent-scope2:session:}")
    private String stateStoreKeyPrefix;

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    /**
     * 状态存储（dev: file, prod: redis）
     */
    @Bean
    @Primary
    public AgentStateStore agentStateStore() {
        if ("redis".equalsIgnoreCase(stateStoreType)) {
            log.info("🔧 初始化 RedisAgentStateStore: {}:{}/db{}", redisHost, redisPort, redisDatabase);
            RedisURI.Builder uriBuilder = RedisURI.builder()
                    .withHost(redisHost)
                    .withPort(redisPort)
                    .withDatabase(redisDatabase);
            if (redisPassword != null && !redisPassword.isBlank()) {
                uriBuilder.withPassword(redisPassword.toCharArray());
            }
            RedisClient redisClient = RedisClient.create(uriBuilder.build());
            AgentStateStore store = RedisAgentStateStore.builder()
                    .lettuceClient(redisClient)
                    .keyPrefix(stateStoreKeyPrefix)
                    .build();
            log.info("✅ RedisAgentStateStore 初始化完成, keyPrefix={}", stateStoreKeyPrefix);
            return store;
        }
        // dev: file
        Path basePath = Paths.get(stateStoreBasePath);
        log.info("📁 初始化 JsonFileAgentStateStore: {}", basePath.toAbsolutePath());
        return new JsonFileAgentStateStore(basePath);
    }
}
