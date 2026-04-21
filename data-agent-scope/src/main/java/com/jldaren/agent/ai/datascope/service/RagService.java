package com.jldaren.agent.ai.datascope.service;

import com.alibaba.cloud.ai.vectorstore.oceanbase.OceanBaseVectorStore;
import com.jldaren.agent.ai.datascope.entity.AgentScopeKnowledge;
import com.jldaren.agent.ai.datascope.mapper.AgentScopeKnowledgeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索增强生成服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final AgentScopeKnowledgeMapper knowledgeMapper;
    private final OceanBaseVectorStore vectorStore;
    /**
     * 向量化并存储知识
     */
    public void embedAndStoreKnowledge(AgentScopeKnowledge knowledge) {
        try {
            // 更新状态为处理中
            knowledgeMapper.updateEmbeddingStatus(knowledge.getId(), "PROCESSING");

            // 创建 Document
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("knowledgeId", knowledge.getId());
            metadata.put("agentId", knowledge.getAgentId());
            metadata.put("type", knowledge.getType());
            metadata.put("title", knowledge.getTitle());

            Document document = new Document(
                    knowledge.getContent(),
                    metadata
            );

            // 存储到向量数据库
            vectorStore.add(List.of(document));

            // 更新状态为完成
            knowledgeMapper.updateEmbeddingStatus(knowledge.getId(), "COMPLETED");
            log.info("✅ 知识向量化成功: id={}, title={}", knowledge.getId(), knowledge.getTitle());
        } catch (Exception e) {
            knowledgeMapper.updateEmbeddingStatus(knowledge.getId(), "FAILED");
            log.error("❌ 知识向量化失败: id={}, error={}", knowledge.getId(), e.getMessage(), e);
        }
    }

    /**
     * 检索相关知识
     */
    public List<Document> searchRelatedKnowledge(Long agentId, String query, int topK) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();

        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        
        // 手动过滤 agentId
        return documents.stream()
                .filter(doc -> {
                    Object docAgentId = doc.getMetadata().get("agentId");
                    return docAgentId != null && docAgentId.equals(agentId);
                })
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 删除知识的向量数据
     */
    public void deleteKnowledgeVectors(Long knowledgeId) {
        try {
            String filterExpression = String.format("knowledgeId == '%d'", knowledgeId);
            SearchRequest searchRequest = SearchRequest.builder()
                    .query("")
                    .topK(100)
                    .filterExpression(filterExpression)
                    .build();

            List<Document> documents = vectorStore.similaritySearch(searchRequest);
            if (!documents.isEmpty()) {
                List<String> ids = documents.stream()
                        .map(Document::getId)
                        .collect(Collectors.toList());
                vectorStore.delete(ids);
                log.info("🗑️ 删除知识向量: knowledgeId={}, count={}", knowledgeId, ids.size());
            }
        } catch (Exception e) {
            log.error("❌ 删除知识向量失败: knowledgeId={}, error={}", knowledgeId, e.getMessage(), e);
        }
    }
}
