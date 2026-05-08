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

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.druid.pool.DruidDataSource;
import com.jldaren.agent.ai.datascope.entity.ModelConfig;
import com.jldaren.agent.ai.datascope.mapper.ModelConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;

/**
 * OceanBase VectorStore 配置
 * 手动创建 VectorStore Bean,避免自动配置问题
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "oceanbase")
public class OceanBaseVectorStoreConfig {

    private final ModelConfigMapper modelConfigMapper;

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

    // 默认配置（当数据库没有配置时使用）
    @Value("${agentscope.api-key:}")
    private String defaultApiKey;

    @Value("${agentscope.model-name:text-embedding-v4}")
    private String defaultModelName;

    public OceanBaseVectorStoreConfig(@Lazy ModelConfigMapper modelConfigMapper) {
        this.modelConfigMapper = modelConfigMapper;
    }

    /**
     * 创建 DashScope EmbeddingModel Bean
     * ⭐ 从数据库读取配置，优先使用数据库配置，回退到默认配置
     */
    @Bean
    public EmbeddingModel dashscopeEmbeddingModel() {
        ModelConfig dbConfig = null;
        try {
            log.info("🔍 开始从数据库查询 Embedding 模型配置: model_type='EMBEDDING'");
            dbConfig = modelConfigMapper.selectActiveByType("EMBEDDING");
            if (dbConfig != null) {
                log.info("✅ 数据库查询成功: provider={}, model_name={}", 
                        dbConfig.getProvider(), dbConfig.getModelName());
            } else {
                log.warn("⚠️ 数据库查询返回 null，未找到 model_type='EMBEDDING' 且 is_active=1 的记录，使用默认配置");
            }
        } catch (Exception e) {
            log.error("❌ 从数据库读取 Embedding 模型配置失败，使用默认配置: {}", e.getMessage(), e);
        }

        // 如果数据库有配置，使用数据库的值；否则使用默认值
        String apiKey = (dbConfig != null && dbConfig.getApiKey() != null) ? dbConfig.getApiKey() : defaultApiKey;
        String modelName = (dbConfig != null && dbConfig.getModelName() != null) ? dbConfig.getModelName() : defaultModelName;

        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("⚠️ DashScope API Key 未配置，Embedding Model 可能无法正常工作");
        }
        
        // 创建 DashScopeApi
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .build();
        
        // 创建 EmbeddingOptions（设置模型名称和维度）
        com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions options = 
                com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions.builder()
                        .withModel(modelName)
                        .withDimensions(embeddingDimension)  // 使用配置的维度
                        .build();
        
        // 使用 Builder 创建 EmbeddingModel
        DashScopeEmbeddingModel embeddingModel = DashScopeEmbeddingModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(options)
                .metadataMode(MetadataMode.NONE)
                .build();
        
        if (dbConfig != null) {
            log.info("✅Using database embedding model config: provider={}, model={}, dimensions={}", 
                    dbConfig.getProvider(), modelName, embeddingDimension);
        } else {
            log.info("✅Using default embedding model config: model={}, dimensions={}", 
                    modelName, embeddingDimension);
        }
        
        return embeddingModel;
    }

    /**
     * 创建 DataSource Bean
     * ⭐ 暴露 DataSource 供其他 Service 使用（如 LongTermMemoryService）
     */
    @Bean(name = "oceanbaseDataSource")
    public DataSource oceanbaseDataSource() {
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
            log.info("✅OceanBase DataSource initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize OceanBase DataSource", e);
            throw new RuntimeException("Failed to initialize DataSource", e);
        }
        return dataSource;
    }

    /**
     * 创建 VectorStore Bean
     * 使用 Spring AI Alibaba 的 OceanBaseVectorStore
     */
    @Bean
    public com.alibaba.cloud.ai.vectorstore.oceanbase.OceanBaseVectorStore oceanBaseVectorStore(EmbeddingModel embeddingModel, DataSource dataSource) {
        log.info("Configuring OceanBase VectorStore with table: {}", tableName);

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
