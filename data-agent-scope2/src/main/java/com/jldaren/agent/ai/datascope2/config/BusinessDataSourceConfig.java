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

import com.alibaba.druid.pool.DruidDataSource;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 业务数据源配置（第二个 Druid）
 *
 * <p>对比 1.0：
 * <ul>
 *   <li>1.0: 业务查询走 NL2SQL 链路 → data-agent-management → 它的 Druid 查业务库</li>
 *   <li>2.0: 业务查询直连业务库 Druid，Skill 模板固定 SQL，速度更快</li>
 * </ul>
 *
 * <p>业务库包含 4 张表（跟 1.0 同源）：
 * <ul>
 *   <li>bid_biz_win_bid        —— 中标信息</li>
 *   <li>bid_biz_bidding        —— 招标信息</li>
 *   <li>bid_biz_purchase_intention —— 采购意向</li>
 *   <li>bid_biz_prepose        —— 前期项目</li>
 * </ul>
 */
@Slf4j
@Configuration
// ⭐ MyBatis Mapper 扫描:只扫 com.jldaren.agent.ai.datascope2.mapper 包下的接口
//   sqlSessionFactoryRef 指向下面定义的 businessSqlSessionFactory
@MapperScan(basePackages = "com.jldaren.agent.ai.datascope2.mapper",
        sqlSessionFactoryRef = "businessSqlSessionFactory")
public class BusinessDataSourceConfig {

    @Value("${agentscope.datasource.business.url:}")
    private String businessUrl;

    @Value("${agentscope.datasource.business.username:}")
    private String businessUsername;

    @Value("${agentscope.datasource.business.password:}")
    private String businessPassword;

    @Value("${agentscope.datasource.business.driver-class:com.mysql.cj.jdbc.Driver}")
    private String driverClass;

    @Value("${agentscope.datasource.business.initial-size:5}")
    private int initialSize;

    @Value("${agentscope.datasource.business.min-idle:5}")
    private int minIdle;

    @Value("${agentscope.datasource.business.max-active:20}")
    private int maxActive;

    @Bean(name = "businessDataSource")
    public DataSource businessDataSource() {
        if (businessUrl == null || businessUrl.isBlank()) {
            log.warn("⚠️ 业务数据源未配置（agentscope.datasource.business.url 为空），QueryBizDataTool 将不可用");
            return null;
        }
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(businessUrl);
        ds.setUsername(businessUsername);
        ds.setPassword(businessPassword);
        ds.setDriverClassName(driverClass);
        ds.setInitialSize(initialSize);
        ds.setMinIdle(minIdle);
        ds.setMaxActive(maxActive);
        // 业务库查询慢没关系，agent 端 timeout 由 Tool 控制
        ds.setMaxWait(30000);
        ds.setValidationQuery("SELECT 1");
        ds.setTestWhileIdle(true);
        // 业务表查询需要元数据，把 prepareStatement cache 关掉避免表结构变化导致缓存出错
        ds.setPoolPreparedStatements(false);
        log.info("✅ 业务数据源初始化完成: url={}, user={}", businessUrl, businessUsername);
        return ds;
    }

    /**
     * Skill 仓库 DataSource（连 data_agent_v2）
     */
    @Value("${agentscope.skill.mysql.database:data_agent_v2}")
    private String skillDatabase;

    @Value("${agentscope.datasource.business.url:}")
    private String skillUrl;

    @Value("${agentscope.datasource.business.username:}")
    private String skillUsername;

    @Value("${agentscope.datasource.business.password:}")
    private String skillPassword;

    /**
     * Skill 仓库专用 DataSource（连 data_agent_v2，不是 tender_data_agent）
     */
    @Bean(name = "skillDataSource")
    public DataSource skillDataSource() {
        // 复用业务库的 driver/连接参数，只换 database 名字
        String url = skillUrl;
        if (url != null && !url.isBlank()) {
            // 把 database 替换成 skill 库
            url = url.replaceFirst("/tender_data_agent", "/" + skillDatabase);
        }
        if (url == null || url.isBlank()) {
            log.warn("⚠️ Skill DataSource 未配置，跳过");
            return null;
        }
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(url);
        ds.setUsername(skillUsername);
        ds.setPassword(skillPassword);
        ds.setDriverClassName(driverClass);
        ds.setInitialSize(2);
        ds.setMinIdle(1);
        ds.setMaxActive(5);
        ds.setMaxWait(10000);
        ds.setValidationQuery("SELECT 1");
        log.info("✅ Skill DataSource 初始化完成: {}", url);
        return ds;
    }

    @Bean(name = "businessJdbcTemplate")
    public JdbcTemplate businessJdbcTemplate(@Qualifier("businessDataSource") DataSource dataSource) {
        if (dataSource == null) {
            log.warn("⚠️ 业务数据源未配置,businessJdbcTemplate 创建为 null");
            return null;
        }
        return new JdbcTemplate(dataSource);
    }

    /**
     * 业务库 SqlSessionFactory(给 MyBatis Mapper XML 用)
     * <p>跟业务 JdbcTemplate 复用同一个 Druid 数据源,但走 MyBatis 走 XML 动态 SQL
     * <p>注意:WebFlux 响应式环境下,Mapper 调用必须包 Mono.fromCallable(...).subscribeOn(boundedElastic) 避免阻塞 reactor 线程
     */
    @Bean(name = "businessSqlSessionFactory")
    public SqlSessionFactoryBean businessSqlSessionFactory(@Qualifier("businessDataSource") DataSource dataSource) throws Exception {
        if (dataSource == null) {
            log.warn("⚠️ 业务数据源未配置,SqlSessionFactory 无法创建");
            return null;
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        // ⭐ Mapper XML 位置:classpath:mapper/**/*.xml
        //   ⚠️ 不要配 mybatis-plus 的 VFS,这里就是纯 MyBatis(没有 plus),保持最简
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath:mapper/**/*.xml"));
        // 下划线转驼峰:win_tenderer → winTenderer(数据库字段是 snake_case,Java 字段是 camelCase)
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(config);
        log.info("✅ 业务库 SqlSessionFactory 初始化完成,mapper 位置: classpath:mapper/**/*.xml");
        return factory;
    }
}
