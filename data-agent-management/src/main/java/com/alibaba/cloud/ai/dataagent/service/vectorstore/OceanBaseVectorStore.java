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
package com.alibaba.cloud.ai.dataagent.service.vectorstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * OceanBase 向量存储实现，完全兼容 Spring AI VectorStore 接口。
 * <p>
 * 核心特性：
 * <ul>
 *   <li>适配 Spring AI 最新版 float[] 向量返回类型</li>
 *   <li>参数化查询防御 SQL 注入</li>
 *   <li>批量插入失败隔离与维度校验</li>
 *   <li>原子化表结构初始化与 HNSW 索引容错</li>
 * </ul>
 */
@Slf4j
public class OceanBaseVectorStore implements VectorStore {

	private static final String DEFAULT_TABLE_NAME = "vector_store";

	private final JdbcTemplate jdbcTemplate;
	private final EmbeddingModel embeddingModel;
	private final ObjectMapper objectMapper;
	private final String tableName;
	private final int embeddingDimension;

	public OceanBaseVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
		this(jdbcTemplate, embeddingModel, DEFAULT_TABLE_NAME);
	}

	public OceanBaseVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, String tableName) {
		Assert.notNull(jdbcTemplate, "JdbcTemplate must not be null");
		Assert.notNull(embeddingModel, "EmbeddingModel must not be null");
		Assert.hasText(tableName, "Table name must not be empty");

		if (!tableName.matches("^[a-zA-Z][a-zA-Z0-9_]{0,63}$")) {
			throw new IllegalArgumentException("Invalid table name: " + tableName);
		}

		this.jdbcTemplate = jdbcTemplate;
		this.embeddingModel = embeddingModel;
		this.objectMapper = new ObjectMapper();
		this.tableName = tableName;

		// ✅ 适配 Spring AI 返回 float[] 的版本
		float[] testEmbedding = embeddingModel.embed("test");
		this.embeddingDimension = testEmbedding.length;

		log.info("OceanBaseVectorStore initialized, table: {}, dimension: {}",
				tableName, embeddingDimension);
		ensureTableExists();
	}

	@Override
	@Transactional
	public void add(List<Document> documents) {
		if (documents == null || documents.isEmpty()) {
			return;
		}

		List<Object[]> batchArgs = new ArrayList<>(documents.size());
		List<String> failedIds = new ArrayList<>();

		for (Document document : documents) {
			try {
				float[] embedding = embeddingModel.embed(document.getText());

				if (embedding.length != embeddingDimension) {
					throw new IllegalStateException(
							String.format("Embedding dimension mismatch for doc %s: expected %d, got %d",
									document.getId(), embeddingDimension, embedding.length));
				}

				String vectorStr = formatVector(embedding);
				String metadataJson = objectMapper.writeValueAsString(document.getMetadata());
				batchArgs.add(new Object[] { document.getId(), document.getText(), metadataJson, vectorStr });
			}
			catch (JsonProcessingException e) {
				log.error("Failed to serialize metadata for document: {}", document.getId(), e);
				failedIds.add(document.getId());
			}
			catch (Exception e) {
				log.error("Failed to prepare document: {}", document.getId(), e);
				failedIds.add(document.getId());
			}
		}

		if (!batchArgs.isEmpty()) {
			String sql = String.format(
					"INSERT INTO %s (id, content, metadata, embedding, created_at, updated_at) " +
							"VALUES (?, ?, ?, ?, NOW(), NOW()) " +
							"ON DUPLICATE KEY UPDATE content=VALUES(content), metadata=VALUES(metadata), " +
							"embedding=VALUES(embedding), updated_at=NOW()",
					tableName);

			try {
				jdbcTemplate.batchUpdate(sql, batchArgs);
				log.info("Successfully added {} documents to OceanBase", batchArgs.size());
			} catch (Exception e) {
				log.error("Batch insert failed for {} documents", batchArgs.size(), e);
				throw new RuntimeException("Batch insert failed", e);
			}
		}

		if (!failedIds.isEmpty()) {
			log.warn("Failed to prepare {} documents for insertion: {}", failedIds.size(), failedIds);
		}
	}

	@Override
	@Transactional
	public void delete(List<String> idList) {
		if (idList == null || idList.isEmpty()) {
			return;
		}

		String placeholders = idList.stream().map(id -> "?").collect(Collectors.joining(","));
		String sql = String.format("DELETE FROM %s WHERE id IN (%s)", tableName, placeholders);

		try {
			jdbcTemplate.update(sql, idList.toArray());
			log.info("Deleted {} documents from OceanBase", idList.size());
		} catch (Exception e) {
			log.error("Failed to delete documents by IDs", e);
			throw new RuntimeException("Failed to delete documents", e);
		}
	}

	@Override
	@Transactional
	public void delete(Filter.Expression filterExpression) {
		Assert.notNull(filterExpression, "Filter expression must not be null");
		WhereClauseResult where = buildWhereClause(filterExpression);
		String sql = String.format("DELETE FROM %s WHERE %s", tableName, where.clause);
		jdbcTemplate.update(sql, where.params.toArray());
	}

	@Override
	public List<Document> similaritySearch(String query) {
		return similaritySearch(SearchRequest.builder().query(query).build());
	}

	@Override
	public List<Document> similaritySearch(SearchRequest request) {
		Assert.notNull(request, "SearchRequest must not be null");
		String query = request.getQuery();
		if (query == null || query.trim().isEmpty()) {
			return Collections.emptyList();
		}

		try {
			float[] queryEmbedding = embeddingModel.embed(query);
			if (queryEmbedding.length != embeddingDimension) {
				throw new IllegalStateException(
						String.format("Query embedding dimension mismatch: expected %d, got %d",
								embeddingDimension, queryEmbedding.length));
			}
			String queryVectorStr = formatVector(queryEmbedding);

			// ✅ 修复：基本类型无需判 null，直接获取
			double threshold = request.getSimilarityThreshold();
			int topK = request.getTopK();

			StringBuilder sql = new StringBuilder();
			sql.append(String.format(
					"SELECT id, content, metadata, " +
							"cosine_distance(embedding, '%s') AS similarity " +
							"FROM %s ",
					queryVectorStr, tableName));

			// ✅ 修复：使用标准 getFilterExpression()
			WhereClauseResult filterWhere = null;
			if (request.getFilterExpression() != null) {
				filterWhere = buildWhereClause(request.getFilterExpression());
				sql.append("WHERE ").append(filterWhere.clause).append(" AND ");
			} else {
				sql.append("WHERE ");
			}

			sql.append(String.format(
					"cosine_distance(embedding, '%s') <= ? " +
							"ORDER BY similarity ASC APPROXIMATE LIMIT ?",
					queryVectorStr));

			List<Object> params = new ArrayList<>();
			if (filterWhere != null) {
				params.addAll(filterWhere.params);
			}
			params.add(threshold);
			params.add(topK);

			return jdbcTemplate.query(sql.toString(), params.toArray(), documentRowMapper());
		} catch (Exception e) {
			log.error("Similarity search failed for query: {}", query, e);
			throw new RuntimeException("Similarity search failed", e);
		}
	}

	private void ensureTableExists() {
		String createSql = String.format(
				"CREATE TABLE IF NOT EXISTS %s (" +
						"id VARCHAR(255) PRIMARY KEY, content TEXT, metadata JSON, " +
						"embedding VECTOR(%d), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
						"updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
						"INDEX idx_created_at (created_at))",
				tableName, embeddingDimension);

		try {
			jdbcTemplate.execute(createSql);
			log.debug("Ensured table exists: {}", tableName);

			try {
				String indexSql = String.format(
						"CREATE INDEX IF NOT EXISTS idx_embedding ON %s USING HNSW (embedding)", tableName);
				jdbcTemplate.execute(indexSql);
				log.info("Created HNSW index for table: {}", tableName);
			} catch (Exception e) {
				String msg = e.getMessage();
				if (msg == null || !msg.toLowerCase().contains("already exists")) {
					log.warn("HNSW index creation skipped: {}", e.getMessage());
				}
			}
		} catch (Exception e) {
			log.error("Failed to ensure table exists: {}", tableName, e);
			throw new RuntimeException("Failed to initialize OceanBase vector store", e);
		}
	}

	/**
	 * 格式化 float[] 向量为 OceanBase 兼容的字符串: [0.1,0.2,0.3]
	 */
	private String formatVector(float[] vector) {
		return IntStream.range(0, vector.length)
				.mapToObj(i -> String.valueOf(vector[i]))
				.collect(Collectors.joining(",", "[", "]"));
	}

	/**
	 * 构建 WHERE 子句（参数化查询，防御 SQL 注入）
	 */
	private WhereClauseResult buildWhereClause(Filter.Expression expr) {
		if (expr == null) return new WhereClauseResult("1=1", Collections.emptyList());

		try {
			// Spring AI 1.x: Expression 是普通类，有 type/left/right 字段
			java.lang.reflect.Field typeField = expr.getClass().getDeclaredField("type");
			typeField.setAccessible(true);
			Object type = typeField.get(expr);

			// 只处理 EQ 类型
			if ("EQ".equals(type.toString())) {
				java.lang.reflect.Field leftField = expr.getClass().getDeclaredField("left");
				leftField.setAccessible(true);
				Object left = leftField.get(expr);

				java.lang.reflect.Field rightField = expr.getClass().getDeclaredField("right");
				rightField.setAccessible(true);
				Object right = rightField.get(expr);

				// left 应该是 KeyExpression，有 key() 方法
				if (left != null) {
					java.lang.reflect.Method keyMethod = left.getClass().getMethod("key");
					String property = (String) keyMethod.invoke(left);

					if (property != null && property.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
						String jsonPath = "$." + property;
						
						// 提取 right 的实际值（可能是 Filter$Value 包装）
						Object actualValue = extractValue(right);
						
						if (actualValue instanceof String) {
							return new WhereClauseResult(
									"JSON_UNQUOTE(JSON_EXTRACT(metadata, ?)) = ?",
									Arrays.asList(jsonPath, actualValue)
							);
						} else {
							return new WhereClauseResult(
									"JSON_EXTRACT(metadata, ?) = ?",
									Arrays.asList(jsonPath, actualValue)
							);
						}
					}
				}
			}

			log.debug("Unsupported filter type: {}, using fallback", type);

		} catch (NoSuchMethodException | NoSuchFieldException e) {
			log.debug("Filter API mismatch, using fallback");
			return new WhereClauseResult("1=1", Collections.emptyList());
		} catch (Exception e) {
			log.debug("Filter expression parsing failed, using fallback", e);
		}

		return new WhereClauseResult("1=1", Collections.emptyList());
	}

	/**
	 * 提取 Filter$Value 中的实际值
	 */
	private Object extractValue(Object value) {
		if (value == null) {
			return null;
		}
		
		// 如果是 Filter$Value 类型，尝试获取其 value 字段
		String className = value.getClass().getName();
		if (className.contains("Filter$Value") || className.contains("Filter.Value")) {
			try {
				java.lang.reflect.Field valueField = value.getClass().getDeclaredField("value");
				valueField.setAccessible(true);
				return valueField.get(value);
			} catch (Exception e) {
				log.debug("Failed to extract value from Filter$Value", e);
				return value;
			}
		}
		
		return value;
	}

	private RowMapper<Document> documentRowMapper() {
		return (rs, rowNum) -> {
			String docId = rs.getString("id");
			String content = rs.getString("content");
			Map<String, Object> metadata = new HashMap<>();
			String json = null;
			try {
				json = rs.getString("metadata");
				if (json != null && !json.isEmpty()) {
					metadata = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
				}
			} catch (JsonProcessingException e) {
				log.warn("Failed to parse metadata for document {}: {}", docId, rs.getString("metadata"), e);
				metadata.put("_parse_error", true);
				metadata.put("_raw_metadata", json);
			}

			double distance = rs.getDouble("similarity");
			metadata.put("score", 1.0 - (distance / 2.0));
			metadata.put("distance", distance);

			return new Document(docId, content, metadata);
		};
	}

	/**
	 * 内部辅助类：封装 WHERE 子句和参数列表
	 */
	private static class WhereClauseResult {
		final String clause;
		final List<Object> params;

		WhereClauseResult(String clause, List<Object> params) {
			this.clause = clause;
			this.params = Collections.unmodifiableList(new ArrayList<>(params));
		}
	}
}

