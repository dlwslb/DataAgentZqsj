/*
 * Copyright 2024-2026 the original author or authors.
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
package com.jldaren.agent.ai.datascope.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AgentScope 知识库实体类
 * 对应数据库表: agent_scope_knowledge
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class AgentScopeKnowledge {

    private Long id;

    private Long agentId;

    private String title;

    // DOCUMENT-文档, QA-问答对, FAQ-常见问题
    private String type;

    // QA/FAQ 问题
    private String question;

    // QA/FAQ 答案内容
    private String content;

    // 是否召回: 1-召回, 0-不召回
    private Integer isRecall;

    // 向量化状态: PENDING, PROCESSING, COMPLETED, FAILED
    private String embeddingStatus;

    private String errorMsg;

    private String sourceFilename;

    private String filePath;

    private Long fileSize;

    private String fileType;

    // 分块策略: token, recursive, sentence, paragraph, semantic
    private String splitterType;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    private Integer isDeleted;

    private Integer isResourceCleaned;

}
