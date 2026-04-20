-- AgentScope 聊天会话表
CREATE TABLE IF NOT EXISTS agent_scope_chat_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '会话ID',
    agent_id BIGINT NOT NULL COMMENT '智能体ID',
    user_id BIGINT DEFAULT NULL COMMENT '用户ID',
    title VARCHAR(255) DEFAULT '新会话' COMMENT '会话名称',
    status VARCHAR(20) DEFAULT 'active' COMMENT '状态: active-活跃, finished-已完成',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_agent_id (agent_id),
    INDEX idx_user_id (user_id),
    INDEX idx_update_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AgentScope 聊天会话表';

-- AgentScope 聊天消息表
CREATE TABLE IF NOT EXISTS agent_scope_chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    session_id VARCHAR NOT NULL COMMENT '会话ID',
    agent_id BIGINT NOT NULL COMMENT '智能体ID',
    user_id BIGINT DEFAULT NULL COMMENT '用户ID',
    role VARCHAR(20) NOT NULL COMMENT '角色: user-用户, assistant-助手',
    content TEXT COMMENT '消息内容',
    message_type VARCHAR(50) DEFAULT 'text' COMMENT '消息类型: text-文本, markdown-富文本',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_session_id (session_id),
    INDEX idx_agent_id (agent_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AgentScope 聊天消息表';
