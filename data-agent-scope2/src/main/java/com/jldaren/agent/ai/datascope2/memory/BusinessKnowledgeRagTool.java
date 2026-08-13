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
package com.jldaren.agent.ai.datascope2.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jldaren.agent.ai.datascope2.service.VectorStoreService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

/**
 * RAG 业务知识召回 Tool（OceanBase 向量召回版）
 *
 * <p>从 1.0 的 business_knowledge 表召回业务术语解释，给 LLM 提供业务上下文
 * <p>用户问"商机" / "中标" / "采购意向" 等业务术语时，agent 应该调这个 Tool
 *
 * <h3>召回策略</h3>
 * <ol>
 *   <li><b>主路径</b>：OceanBase 向量召回（cosine_distance，1.0 同样的 SQL 模板）</li>
 *   <li><b>降级路径</b>：向量召回失败 / 召回 0 条时，自动降级到 LIKE 关键词匹配</li>
 * </ol>
 *
 * <h3>前置条件</h3>
 * <ul>
 *   <li>1. business_knowledge 表的 embedding_status = 'COMPLETED' 的记录才会被向量召回</li>
 *   <li>2. OceanBase DataSource 配齐（agentscope.embedding.datasource.url）</li>
 *   <li>3. Embedding API Key 配齐（agentscope.embedding.api-key）</li>
 *   <li>4. 业务库必须是 OceanBase（VECTOR 类型 + cosine_distance 函数依赖）</li>
 * </ul>
 *
 * <h3>未向量化怎么办？</h3>
 * <p>business_knowledge 表里如果某条业务术语的 embedding_status != 'COMPLETED'，
 * <p>向量召回拿不到它。但 LIKE 降级路径可以兜底——所以即使你没跑向量化脚本，
 * <p>这个 Tool 也不会"静默丢数据"。
 */
@Slf4j
@Component
public class BusinessKnowledgeRagTool {

    private final JdbcTemplate jdbcTemplate;
    private final VectorStoreService vectorStoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${agentscope.embedding.similarity-threshold:0.5}")
    private double defaultThreshold;

    @Value("${agentscope.embedding.default-topk:5}")
    private int defaultTopK;

    @Autowired
    public BusinessKnowledgeRagTool(
            @Qualifier("businessJdbcTemplate") JdbcTemplate jdbcTemplate,
            VectorStoreService vectorStoreService) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStoreService = vectorStoreService;
        log.info("✅ BusinessKnowledgeRagTool 初始化: vectorStore={}",
                vectorStoreService != null && vectorStoreService.isAvailable() ? "ready" : "降级模式(LIKE only)");
    }

    /**
     * 检索业务知识（向量召回 + LIKE 降级）
     */
    @Tool(name = "search_business_knowledge",
          description = "从业务知识库（business_knowledge 表）检索业务术语解释。" +
                  "当用户使用业务术语（如'中标'、'商机'、'采购意向'）时，调这个 Tool 召回相关解释，给 agent 业务上下文。" +
                  "内部走 OceanBase 向量召回（embedding 相似度），召回失败时自动降级到关键词匹配。" +
                  "query 是关键词（用户原话里的术语），limit 默认 5。" +
                  "返回匹配的 business_term + description + synonyms + similarity（向量召回时）。",
          readOnly = true,
          concurrencySafe = true)
    public Mono<String> searchBusinessKnowledge(
            @ToolParam(name = "query", description = "业务关键词（必填）", required = true) String query,
            @ToolParam(name = "limit", description = "返回条数，默认 5") Integer limit,
            @ToolParam(name = "agentId", description = "智能体 ID（限定知识范围，0 表示查全部）") Long agentId) {
        return Mono.fromCallable(() -> doSearch(query, limit, agentId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String doSearch(String query, Integer limit, Long agentId) {
        if (query == null || query.isBlank()) {
            return "{\"error\":\"query 不能为空\"}";
        }
        int realLimit = (limit == null || limit <= 0) ? defaultTopK : Math.min(limit, 20);
        long realAgentId = (agentId == null) ? 0 : agentId;

        // ========== 路径 1：向量召回（主路径） ==========
        if (vectorStoreService != null && vectorStoreService.isAvailable()) {
            try {
                List<Map<String, Object>> vectorResults =
                        vectorStoreService.searchBusinessKnowledge(query, realAgentId, realLimit, defaultThreshold);

                if (!vectorResults.isEmpty()) {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("query", query);
                    response.put("mode", "vector");  // 标识召回方式
                    response.put("total", vectorResults.size());
                    response.put("knowledge", enrichWithBusinessTerm(vectorResults));
                    log.info("✅ [search_business_knowledge] 向量召回 query='{}', 返回 {} 条", query, vectorResults.size());
                    return toJson(response);
                }
                log.info("⚠️ [search_business_knowledge] 向量召回 0 条，降级到 LIKE: query='{}'", query);
            } catch (Exception e) {
                log.error("❌ [search_business_knowledge] 向量召回异常，降级到 LIKE: {}", e.getMessage());
            }
        } else {
            log.debug("🔧 [search_business_knowledge] 向量服务未就绪，直接走 LIKE");
        }

        // ========== 路径 2：LIKE 关键词召回（降级路径） ==========
        return doLikeSearch(query, realLimit, realAgentId);
    }

    /**
     * 向量召回的结果不带 business_term（向量表只有 description + metadata）
     * 关联回 business_knowledge 表补上 business_term + synonyms + agent_id
     */
    private List<Map<String, Object>> enrichWithBusinessTerm(List<Map<String, Object>> vectorResults) {
        if (vectorResults.isEmpty()) return vectorResults;
        try {
            // 收集 id
            List<String> ids = new ArrayList<>();
            for (Map<String, Object> r : vectorResults) {
                ids.add(String.valueOf(r.get("id")));
            }

            // 批量查 business_knowledge 补全字段
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            List<Map<String, Object>> knowledgeRows = jdbcTemplate.queryForList(
                    "SELECT id, business_term, description, synonyms, agent_id " +
                            "FROM business_knowledge WHERE id IN (" + placeholders + ") " +
                            "AND is_deleted = 0",
                    ids.toArray());

            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Map<String, Object> row : knowledgeRows) {
                byId.put(String.valueOf(row.get("id")), row);
            }

            // 合并（保留 similarity）
            List<Map<String, Object>> enriched = new ArrayList<>();
            for (Map<String, Object> vr : vectorResults) {
                Map<String, Object> row = byId.get(String.valueOf(vr.get("id")));
                Map<String, Object> merged = new LinkedHashMap<>();
                if (row != null) {
                    merged.putAll(row);
                } else {
                    merged.put("id", vr.get("id"));
                    merged.put("description", vr.get("description"));
                }
                merged.put("similarity", vr.get("similarity"));
                enriched.add(merged);
            }
            return enriched;
        } catch (Exception e) {
            log.warn("⚠️ [enrichWithBusinessTerm] 补全失败，返回原始向量结果: {}", e.getMessage());
            return vectorResults;
        }
    }

    /**
     * 关键词 LIKE 召回（降级路径）
     */
    private String doLikeSearch(String query, int limit, long agentId) {
        try {
            String like = "%" + query + "%";
            List<Map<String, Object>> rows;
            if (agentId == 0) {
                rows = jdbcTemplate.queryForList(
                        "SELECT id, business_term, description, synonyms, agent_id " +
                                "FROM business_knowledge " +
                                "WHERE is_deleted = 0 AND is_recall = 1 " +
                                "  AND (business_term LIKE ? OR synonyms LIKE ? OR description LIKE ?) " +
                                "ORDER BY id DESC LIMIT ?",
                        like, like, like, limit);
            } else {
                rows = jdbcTemplate.queryForList(
                        "SELECT id, business_term, description, synonyms, agent_id " +
                                "FROM business_knowledge " +
                                "WHERE is_deleted = 0 AND is_recall = 1 " +
                                "  AND (agent_id = ? OR agent_id = 0) " +
                                "  AND (business_term LIKE ? OR synonyms LIKE ? OR description LIKE ?) " +
                                "ORDER BY id DESC LIMIT ?",
                        agentId, like, like, like, limit);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query", query);
            response.put("mode", "keyword");  // 标识召回方式
            response.put("total", rows.size());
            response.put("knowledge", rows);
            log.info("✅ [search_business_knowledge] LIKE 降级召回 query='{}', 返回 {} 条", query, rows.size());
            return toJson(response);
        } catch (Exception e) {
            log.error("❌ [search_business_knowledge] LIKE 也挂了: {}", e.getMessage(), e);
            return "{\"error\":\"检索失败: " + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"JSON 序列化失败\"}";
        }
    }
}
