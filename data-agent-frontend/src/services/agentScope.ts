/**
 * AgentScope API 服务
 * 对接 data-agent-scope 后端 (端口 58064)
 */
import axios from 'axios';

// AgentScope 专用 API 客户端
const agentScopeClient = axios.create({
  baseURL: import.meta.env.VITE_AGENT_SCOPE_API_TARGET || 'http://localhost:58064',
});

export interface AgentScope {
  id: number;
  name: string;
  description: string;
  avatar: string;
  status: 'draft' | 'published' | 'offline';
  prompt: string;
  category: string;
  tags: string;
  toolNames?: string;
  apiKey?: string;
  apiKeyEnabled?: number;
  a2aEnabled?: number;
  adminId?: number;
  createTime: string;
  updateTime: string;
}

export interface ToolMeta {
  name: string;
  description: string;
  provider: string;
  params: ToolParamMeta[];
}

export interface ToolParamMeta {
  name: string;
  description: string;
  required: boolean;
  type: string;
}

export interface ChatSession {
  id: number;
  agentId: number;
  userId?: number;
  sessionName: string;
  status: 'active' | 'finished';
  createTime: string;
  updateTime: string;
}

export interface ChatMessage {
  id: number;
  sessionId: number;
  agentId: number;
  userId?: number;
  role: 'user' | 'assistant';
  content: string;
  messageType: 'text' | 'markdown';
  createTime: string;
}

export interface AgentScopeKnowledge {
  id: number;
  agentId: number;
  title: string;
  type: 'DOCUMENT' | 'QA' | 'FAQ';
  question?: string;
  content?: string;
  isRecall: number;
  embeddingStatus: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  errorMsg?: string;
  sourceFilename?: string;
  filePath?: string;
  fileSize?: number;
  fileType?: string;
  splitterType?: string;
  createTime: string;
  updateTime: string;
}

export const agentScopeApi = {
  // ==================== Agent 管理 ====================

  /**
   * 获取 Agent 列表
   */
  list: (params?: { status?: string; keyword?: string }) => {
    return agentScopeClient.get('/api/scope/agent/list', { params });
  },

  /**
   * 获取 Agent 详情
   */
  get: (id: number) => {
    return agentScopeClient.get(`/api/scope/agent/${id}`);
  },

  /**
   * 创建 Agent
   */
  create: (data: Partial<AgentScope>) => {
    return agentScopeClient.post('/api/scope/agent', data);
  },

  /**
   * 更新 Agent
   */
  update: (id: number, data: Partial<AgentScope>) => {
    return agentScopeClient.put(`/api/scope/agent/${id}`, data);
  },

  /**
   * 删除 Agent
   */
  delete: (id: number) => {
    return agentScopeClient.delete(`/api/scope/agent/${id}`);
  },

  /**
   * 发布 Agent
   */
  publish: (id: number) => {
    return agentScopeClient.post(`/api/scope/agent/${id}/publish`);
  },

  /**
   * 下线 Agent
   */
  offline: (id: number) => {
    return agentScopeClient.post(`/api/scope/agent/${id}/offline`);
  },

  /**
   * 重新发布 Agent
   */
  republish: (id: number) => {
    return agentScopeClient.post(`/api/scope/agent/${id}/republish`);
  },

  /**
   * 与 Agent 聊天（支持会话）
   */
  chat: (
    agentId: number,
    message: string,
    sessionId?: number,
    userRole?: 'user' | 'admin',
    nl2sqlOnly?: boolean,
    humanFeedback?: boolean,
    rejectedPlan?: boolean,
    humanFeedbackContent?: string,
    showSqlResults?: boolean
  ) => {
    return agentScopeClient.post(`/api/scope/agent/${agentId}/chat`, {
      message,
      sessionId,
      userRole,
      nl2sqlOnly,
      humanFeedback,
      rejectedPlan,
      humanFeedbackContent,
      showSqlResults,
    });
  },


  // ==================== 会话管理 ====================

  /**
   * 获取会话列表
   */
  getSessions: (agentId: number) => {
    return agentScopeClient.get(`/api/scope/agent/${agentId}/sessions`);
  },

  /**
   * 创建新会话
   */
  createSession: (agentId: number, sessionName?: string) => {
    return agentScopeClient.post(`/api/scope/agent/${agentId}/sessions`, {}, { params: { sessionName } });
  },

  /**
   * 删除会话
   */
  deleteSession: (sessionId: string) => {
    return agentScopeClient.delete(`/api/scope/agent/sessions/${sessionId}`);
  },

  /**
   * 更新会话标题
   */
  updateSessionTitle: (sessionId: string, title: string) => {
    return agentScopeClient.put(`/api/scope/agent/sessions/${sessionId}/title`, { title });
  },

  /**
   * 置顶/取消置顶会话
   */
  pinSession: (sessionId: string, pinned: boolean) => {
    return agentScopeClient.put(`/api/scope/agent/sessions/${sessionId}/pin`, { pinned });
  },

  /**
   * 获取会话消息列表
   */
  getMessages: (sessionId: string) => {
    return agentScopeClient.get(`/api/scope/agent/sessions/${sessionId}/messages`);
  },

  /**
   * 刷新所有 Agent
   */
  refreshAll: () => {
    return agentScopeClient.post('/api/scope/agent/refresh');
  },

  /**
   * 获取注册状态
   */
  getRegistryStatus: () => {
    return agentScopeClient.get('/api/scope/agent/registry/status');
  },

  // ==================== API Key 管理 ====================

  /**
   * 获取 API Key
   */
  getApiKey: (agentId: number) => {
    return agentScopeClient.get(`/api/scope/agent/${agentId}/api-key`);
  },

  /**
   * 生成 API Key
   */
  generateApiKey: (agentId: number) => {
    return agentScopeClient.post(`/api/scope/agent/${agentId}/api-key/generate`);
  },

  /**
   * 重置 API Key
   */
  resetApiKey: (agentId: number) => {
    return agentScopeClient.post(`/api/scope/agent/${agentId}/api-key/reset`);
  },

  /**
   * 删除 API Key
   */
  deleteApiKey: (agentId: number) => {
    return agentScopeClient.delete(`/api/scope/agent/${agentId}/api-key`);
  },

  /**
   * 切换 API Key 启用状态
   */
  toggleApiKey: (agentId: number, enabled: boolean) => {
    return agentScopeClient.put(`/api/scope/agent/${agentId}/api-key/toggle?enabled=${enabled}`);
  },

  // ==================== 知识库管理 ====================

  // ==================== 工具管理 ====================

  /**
   * 获取所有已注册工具列表
   */
  listTools: () => {
    return agentScopeClient.get<ToolMeta[]>('/api/scope/tool/list');
  },

  // ==================== 知识库管理 ====================

  knowledge: {
    /**
     * 获取知识列表
     */
    list: (agentId: number, params?: { type?: string; embeddingStatus?: string }) => {
      return agentScopeClient.get(`/api/scope/knowledge/${agentId}/list`, { params });
    },

    /**
     * 获取知识详情
     */
    get: (id: number) => {
      return agentScopeClient.get(`/api/scope/knowledge/${id}`);
    },

    /**
     * 创建知识
     */
    create: (agentId: number, data: Partial<AgentScopeKnowledge>) => {
      return agentScopeClient.post(`/api/scope/knowledge/${agentId}`, data);
    },

    /**
     * 更新知识
     */
    update: (id: number, data: Partial<AgentScopeKnowledge>) => {
      return agentScopeClient.put(`/api/scope/knowledge/${id}`, data);
    },

    /**
     * 删除知识
     */
    delete: (id: number) => {
      return agentScopeClient.delete(`/api/scope/knowledge/${id}`);
    },

    /**
     * 更新召回状态
     */
    updateRecall: (id: number, isRecall: boolean) => {
      return agentScopeClient.put(`/api/scope/knowledge/${id}/recall`, { isRecall });
    },

    /**
     * 重试向量化
     */
    retryEmbedding: (id: number) => {
      return agentScopeClient.post(`/api/scope/knowledge/${id}/retry`);
    },

    /**
     * 获取可召回的知识
     */
    getRecallable: (agentId: number) => {
      return agentScopeClient.get(`/api/scope/knowledge/${agentId}/recallable`);
    },
  },
};

export default agentScopeApi;
