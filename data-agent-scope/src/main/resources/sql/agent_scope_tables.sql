-- =========================================================
-- AgentScope 智能体相关表
-- 用于 data-agent-scope 模块
-- =========================================================

-- ---------------------------------------------------------
-- 表1: agent_scope_agent - AgentScope 智能体表
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_scope_agent (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    name VARCHAR(100) NOT NULL COMMENT '智能体名称',
    description TEXT COMMENT '智能体描述',
    avatar VARCHAR(500) COMMENT '头像URL',
    status VARCHAR(20) DEFAULT 'draft' COMMENT '状态: draft-草稿, published-已发布, offline-已下线',
    api_key VARCHAR(100) COMMENT 'API Key',
    api_key_enabled INT DEFAULT 0 COMMENT 'API Key是否启用: 0-禁用, 1-启用',
    prompt TEXT COMMENT 'Prompt配置',
    category VARCHAR(50) COMMENT '分类',
    admin_id BIGINT COMMENT '管理员ID',
    tags VARCHAR(255) COMMENT '标签，逗号分隔',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status (status),
    INDEX idx_category (category),
    INDEX idx_admin_id (admin_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AgentScope智能体表';

-- ---------------------------------------------------------
-- 表2: agent_scope_knowledge - AgentScope 智能体知识库表
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_scope_knowledge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    agent_id BIGINT NOT NULL COMMENT '智能体ID',
    title VARCHAR(255) NOT NULL COMMENT '知识标题',
    type VARCHAR(20) NOT NULL COMMENT '类型: DOCUMENT-文档, QA-问答对, FAQ-常见问题',
    question TEXT COMMENT '问题（QA/FAQ类型时使用）',
    content TEXT COMMENT '答案内容',
    is_recall INT DEFAULT 1 COMMENT '是否召回: 1-召回, 0-不召回',
    embedding_status VARCHAR(20) DEFAULT 'PENDING' COMMENT '向量化状态: PENDING-待处理, PROCESSING-处理中, COMPLETED-已完成, FAILED-失败',
    error_msg TEXT COMMENT '错误信息',
    source_filename VARCHAR(255) COMMENT '源文件名',
    file_path VARCHAR(500) COMMENT '文件存储路径',
    file_size BIGINT COMMENT '文件大小（字节）',
    file_type VARCHAR(50) COMMENT '文件类型',
    splitter_type VARCHAR(20) DEFAULT 'token' COMMENT '分块策略: token, recursive, sentence, paragraph, semantic',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted INT DEFAULT 0 COMMENT '是否删除: 0-未删除, 1-已删除',
    is_resource_cleaned INT DEFAULT 0 COMMENT '资源是否清理: 0-未清理, 1-已清理',
    INDEX idx_agent_id (agent_id),
    INDEX idx_type (type),
    INDEX idx_embedding_status (embedding_status),
    INDEX idx_is_recall (is_recall)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AgentScope智能体知识库表';

-- ---------------------------------------------------------
-- 表3: agent_scope_session - AgentScope 会话表（可选，用于会话持久化）
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_scope_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    agent_id BIGINT NOT NULL COMMENT '智能体ID',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    user_id VARCHAR(64) COMMENT '用户ID',
    messages TEXT COMMENT '会话消息JSON',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_agent_id (agent_id),
    INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AgentScope会话表';

-- ---------------------------------------------------------
-- 表4: agentscope_vector_store - AgentScope 向量存储表
-- 用于 Spring AI Alibaba OceanBase VectorStore (AgentScope模块专用)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS agentscope_vector_store (
    id VARCHAR(64) PRIMARY KEY COMMENT '向量ID',
    content TEXT NOT NULL COMMENT '文本内容',
    metadata JSON COMMENT '元数据JSON',
    embedding VECTOR(1536) COMMENT '向量数据,维度1536',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AgentScope向量存储表';

