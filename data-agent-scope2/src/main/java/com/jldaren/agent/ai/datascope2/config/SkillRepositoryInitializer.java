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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

/**
 * Skill 仓库 MySQL 表结构初始化器
 *
 * <p>启动时自动建表（如果用 MySQL Skill 仓库），避免生产环境第一次跑时报"表不存在"
 *
 * <p>只在 type=mysql 时执行
 */
@Slf4j
@Component
public class SkillRepositoryInitializer {

    @Value("${agentscope.skill.type:classpath}")
    private String skillType;

    private final DataSource dataSource;

    public SkillRepositoryInitializer(@Qualifier("skillDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initSchema() {
        if (!"mysql".equalsIgnoreCase(skillType)) {
            log.info("⏭️  skill.type={} 不是 mysql，跳过 Skill 仓库表初始化", skillType);
            return;
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            // 检查主表是否已存在
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema = DATABASE() AND table_name = 'skills'",
                    Integer.class);
            if (count != null && count > 0) {
                log.info("✅ Skill 仓库表已存在，跳过初始化");
                return;
            }

            log.info("🔧 开始初始化 Skill 仓库 MySQL 表结构...");
            ClassPathResource resource = new ClassPathResource("db/migration/skill-schema.sql");
            String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // ScriptUtils 不会自动处理 MySQL COMMENT 语法，但这里用简单 jdbc.execute 即可
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (StringUtils.hasText(trimmed) && !trimmed.startsWith("--")) {
                    jdbc.execute(trimmed);
                }
            }
            log.info("✅ Skill 仓库 MySQL 表结构初始化完成");
        } catch (Exception e) {
            log.error("❌ Skill 仓库 MySQL 表结构初始化失败: {}", e.getMessage(), e);
            // 不抛异常——生产可能手动建表，不阻塞应用启动
        }
    }
}
