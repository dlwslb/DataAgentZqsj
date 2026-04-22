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
package com.jldaren.agent.ai.datascope.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.storage.PlanStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于数据库的计划存储实现
 */
@Slf4j
public class DatabasePlanStorage implements PlanStorage {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DatabasePlanStorage(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initTable();
    }

    private void initTable() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS agent_scope_plan_storage (
                        plan_id VARCHAR(255) PRIMARY KEY,
                        plan_data TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    )
                    """);
            log.info("✅ 计划存储表初始化成功");
        } catch (Exception e) {
            log.error("❌ 计划存储表初始化失败: {}", e.getMessage());
        }
    }

    @Override
    public Mono<Void> addPlan(Plan plan) {
        return Mono.fromRunnable(() -> {
            try {
                String planJson = objectMapper.writeValueAsString(plan);
                jdbcTemplate.update(
                        "INSERT INTO agent_scope_plan_storage (plan_id, plan_data) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE plan_data = ?, updated_at = CURRENT_TIMESTAMP",
                        plan.getId(), planJson, planJson
                );
                log.debug("保存计划: {}", plan.getId());
            } catch (JsonProcessingException e) {
                log.error("序列化计划失败: {}", e.getMessage());
            }
        });
    }

    @Override
    public Mono<Plan> getPlan(String planId) {
        return Mono.fromCallable(() -> {
            try {
                String planJson = jdbcTemplate.queryForObject(
                        "SELECT plan_data FROM agent_scope_plan_storage WHERE plan_id = ?",
                        String.class,
                        planId
                );
                if (planJson != null) {
                    return objectMapper.readValue(planJson, Plan.class);
                }
            } catch (JsonProcessingException e) {
                log.error("反序列化计划失败: {}", e.getMessage());
            }
            return null;
        });
    }

    @Override
    public Mono<List<Plan>> getPlans() {
        return Mono.fromCallable(() -> {
            List<String> planJsons = jdbcTemplate.queryForList(
                    "SELECT plan_data FROM agent_scope_plan_storage ORDER BY created_at DESC",
                    String.class
            );
            
            List<Plan> plans = new ArrayList<>();
            for (String planJson : planJsons) {
                try {
                    plans.add(objectMapper.readValue(planJson, Plan.class));
                } catch (JsonProcessingException e) {
                    log.error("反序列化计划失败: {}", e.getMessage());
                }
            }
            return plans;
        });
    }
}
