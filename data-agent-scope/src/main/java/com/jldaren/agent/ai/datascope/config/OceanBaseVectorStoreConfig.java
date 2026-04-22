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
package com.jldaren.agent.ai.datascope.config;

import com.alibaba.druid.pool.DruidDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OceanBase VectorStore 配置
 * 手动创建 VectorStore Bean,避免自动配置问题
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "oceanbase")
public class OceanBaseVectorStoreConfig {

    @Value("${spring.ai.vectorstore.oceanbase.table-name:agent_scope_vector_store}")
    private String tableName;

    @Value("${spring.ai.vectorstore.oceanbase.initialize-schema:false}")
    private boolean initializeSchema;

    @Value("${spring.ai.vectorstore.oceanbase.distance-type:COSINE}")
    private String distanceType;

    @Value("${spring.ai.vectorstore.oceanbase.embedding-dimension:1536}")
    private int embeddingDimension;

    @Value("${spring.ai.vectorstore.oceanbase.datasource.url:}")
    private String datasourceUrl;

    @Value("${spring.ai.vectorstore.oceanbase.datasource.username:}")
    private String datasourceUsername;

    @Value("${spring.ai.vectorstore.oceanbase.datasource.password:}")
    private String datasourcePassword;

    @Value("${spring.ai.vectorstore.oceanbase.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}")
    private String driverClassName;

    /**
     * 创建 VectorStore Bean
     * 使用 Spring AI Alibaba 的 OceanBaseVectorStore
     */
    @Bean
    public com.alibaba.cloud.ai.vectorstore.oceanbase.OceanBaseVectorStore oceanBaseVectorStore(EmbeddingModel embeddingModel) {
        log.info("Configuring OceanBase VectorStore with table: {}", tableName);

        // 创建数据源
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl(datasourceUrl);
        dataSource.setUsername(datasourceUsername);
        dataSource.setPassword(datasourcePassword);
        dataSource.setDriverClassName(driverClassName);
        dataSource.setInitialSize(5);
        dataSource.setMinIdle(5);
        dataSource.setMaxActive(20);
        dataSource.setMaxWait(60000);

        try {
            dataSource.init();
            log.info("✅OceanBase VectorStore DataSource initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize OceanBase VectorStore DataSource", e);
            throw new RuntimeException("Failed to initialize DataSource", e);
        }

        // 使用 Builder 创建 OceanBaseVectorStore
        try {
            com.alibaba.cloud.ai.vectorstore.oceanbase.OceanBaseVectorStore vectorStore = com.alibaba.cloud.ai.vectorstore.oceanbase.OceanBaseVectorStore.builder(
                    tableName,
                    dataSource,
                    embeddingModel
            )
                    .build();
            
            log.info("✅OceanBase VectorStore created successfully with table: {}", tableName);
            return vectorStore;
        } catch (Exception e) {
            log.error("Failed to create OceanBaseVectorStore", e);
            throw new RuntimeException("Failed to create OceanBaseVectorStore", e);
        }
    }
}
