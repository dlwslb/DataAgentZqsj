<!--
 * AgentScope 智能体运行页
 * 支持会话历史记录的对话界面（与 AgentRun.vue 保持一致）
-->

<template>
  <BaseLayout>
    <el-container style="height: calc(100vh - 60px); gap: 0">
      <!-- 左侧会话列表 - 使用与 AgentRun.vue 相同的组件 -->
      <ChatSessionSidebar
        :agent="agentForSidebar"
        :externalSessions="sessions"
        :handleSetCurrentSession="handleSetCurrentSession"
        :handleGetCurrentSession="handleGetCurrentSession"
        :handleSelectSession="selectSession"
        :handleDeleteSessionState="deleteSessionState"
        :handleUpdateSessionTitle="updateSessionTitle"
        :handlePinSession="pinSession"
        :handleCreateSession="createSession"
        :handleClearAllSessions="clearAllSessions"
      />

      <!-- 右侧对话栏 -->
      <el-main style="background-color: white; display: flex; flex-direction: column">
        <!-- 消息显示区域 -->
        <div class="chat-container" ref="chatContainer">
          <div v-if="!currentSession" class="empty-state">
            <el-empty description="请选择一个会话或创建新会话开始对话" />
          </div>
          <div v-else class="messages-area">
            <div
              v-for="(msg, index) in currentMessages"
              :key="index"
              :class="['message', msg.role]"
            >
              <div class="message-avatar">
                <el-avatar :size="32">
                  {{ msg.role === 'user' ? '我' : 'A' }}
                </el-avatar>
              </div>
              <div class="message-content">
                <div class="message-text">
                  <span v-html="formatMessage(msg.content)"></span>
                  <div class="message-time">{{ msg.createTime }}</div>
                </div>
              </div>
            </div>

            <div v-if="sending" class="message assistant">
              <div class="message-avatar">
                <el-avatar :size="32">A</el-avatar>
              </div>
              <div class="message-content">
                <div class="message-text">
                  <span>
                    <el-icon class="is-loading"><Loading /></el-icon>
                    思考中...
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 输入区域 -->
        <div class="input-area">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="3"
            placeholder="输入问题，按 Enter 发送..."
            :disabled="!currentSession || sending"
            @keyup.enter="handleEnterKey"
          />
          <div class="input-actions">
            <span class="hint">Enter 发送，Shift+Enter 换行</span>
            <el-button
              type="primary"
              :disabled="!inputMessage.trim() || !currentSession || sending"
              :loading="sending"
              @click="sendMessage"
            >
              发送
            </el-button>
          </div>
        </div>
      </el-main>
    </el-container>
  </BaseLayout>
</template>

<script lang="ts">
  import { ref, onMounted, nextTick, computed } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import { Loading } from '@element-plus/icons-vue';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import ChatSessionSidebar from '@/components/run/ChatSessionSidebar.vue';
  import { agentScopeApi, AgentScope, ChatSession, ChatMessage } from '@/services/agentScope';

  export default {
    name: 'AgentScopeRun',
    components: {
      BaseLayout,
      ChatSessionSidebar,
      Loading,
    },
    setup() {
      const router = useRouter();
      const agent = ref<AgentScope>({
        id: 0,
        name: '',
        avatar: '',
        status: 'draft',
        description: '',
        prompt: '',
        category: '',
        tags: '',
        createTime: '',
        updateTime: '',
      });
      const sessions = ref<any[]>([]);
      const currentSession = ref<any>(null);
      const currentMessages = ref<any[]>([]);
      const inputMessage = ref('');
      const sending = ref(false);
      const chatContainer = ref<HTMLElement | null>(null);

      // 转换为 ChatSessionSidebar 需要的格式
      const agentForSidebar = computed(() => ({
        id: agent.value.id,
        name: agent.value.name,
        avatar: agent.value.avatar,
      }));

      const handleSetCurrentSession = async (session: any) => {
        currentSession.value = session;
        if (session) {
          await loadMessages(session.id);
        } else {
          currentMessages.value = [];
        }
      };

      const handleGetCurrentSession = () => {
        return currentSession.value;
      };

      const loadAgent = async () => {
        const id = parseInt(router.currentRoute.value.params.id as string);
        if (!id) {
          ElMessage.error('无效的 Agent ID');
          return;
        }

        try {
          const response = await agentScopeApi.get(id);
          const data = response.data?.data || response.data || response;
          agent.value = data;
          console.log('Agent 加载成功:', agent.value);
        } catch (error) {
          ElMessage.error('加载智能体失败');
        }
      };

      const loadSessions = async () => {
        if (!agent.value.id || agent.value.id === 0) {
          console.warn('Agent ID 无效，跳过加载会话列表');
          return;
        }
        try {
          console.log('开始加载会话列表，agentId:', agent.value.id);
          const response = await agentScopeApi.getSessions(agent.value.id);
          console.log('API 响应:', response);
          const sessionsData = response.data?.data || response.data || [];
          console.log('会话数据:', sessionsData);
          // 确保过滤掉空值
          sessions.value = (Array.isArray(sessionsData) ? sessionsData : [])
            .filter(session => session != null)
            .map((session: any) => ({
              id: session.id,
              title: session.title || '新会话',
              updateTime: session.updateTime,
              createTime: session.createTime,
              isPinned: false,
            }));
          console.log('处理后的会话列表:', sessions.value);
        } catch (error) {
          console.error('加载会话列表失败:', error);
          sessions.value = [];
        }
      };

      const selectSession = async (session: any) => {
        currentSession.value = session;
        await loadMessages(session.id);
      };

      const createSession = async (title?: string) => {
        if (!agent.value.id) return null;
        try {
          const response = await agentScopeApi.createSession(agent.value.id, title || '新会话');
          const session = response.data?.data || response.data;
          const sidebarSession = {
            id: session.id,
            title: session.title,
            updateTime: session.updateTime,
            createTime: session.createTime,
            isPinned: false,
          };
          sessions.value.unshift(sidebarSession);
          // 重新加载会话列表以确保数据同步
          await loadSessions();
          return sidebarSession;
        } catch (error) {
          console.error('创建会话失败:', error);
          return null;
        }
      };

      const loadMessages = async (sessionId: string) => {
        try {
          const response = await agentScopeApi.getMessages(sessionId);
          currentMessages.value = response.data?.data || response.data || [];
          await nextTick();
          scrollToBottom();
        } catch (error) {
          console.error('加载消息失败:', error);
          currentMessages.value = [];
        }
      };

      const deleteSessionState = async (sessionId: string) => {
        try {
          await agentScopeApi.deleteSession(sessionId);
          sessions.value = sessions.value.filter(s => s.id !== sessionId);
          if (currentSession.value && currentSession.value.id === sessionId) {
            currentSession.value = null;
            currentMessages.value = [];
          }
        } catch (error) {
          console.error('删除会话失败:', error);
        }
      };

      const updateSessionTitle = async (sessionId: string, title: string) => {
        try {
          await agentScopeApi.updateSessionTitle(sessionId, title);
          const session = sessions.value.find(s => s.id === sessionId);
          if (session) {
            session.title = title;
          }
        } catch (error) {
          console.error('更新会话标题失败:', error);
          throw error;
        }
      };

      const pinSession = async (sessionId: string, pinned: boolean) => {
        try {
          await agentScopeApi.pinSession(sessionId, pinned);
          const session = sessions.value.find(s => s.id === sessionId);
          if (session) {
            session.isPinned = pinned;
          }
        } catch (error) {
          console.error('置顶会话失败:', error);
          throw error;
        }
      };

      const clearAllSessions = async () => {
        try {
          // 批量删除所有会话
          const deletePromises = sessions.value.map(session => 
            agentScopeApi.deleteSession(session.id).catch(err => {
              console.error(`删除会话 ${session.id} 失败:`, err);
            })
          );
          await Promise.all(deletePromises);
          sessions.value = [];
          currentSession.value = null;
          currentMessages.value = [];
          ElMessage.success('所有会话已清空');
        } catch (error) {
          console.error('清空会话失败:', error);
          ElMessage.error('清空会话失败');
        }
      };

      const handleEnterKey = (e: KeyboardEvent) => {
        if (!e.shiftKey) {
          e.preventDefault();
          sendMessage();
        }
      };

      const sendMessage = async () => {
        if (!inputMessage.value.trim() || sending.value || !currentSession.value) return;

        const userMessage = inputMessage.value.trim();
        currentMessages.value.push({
          id: Date.now(),
          sessionId: currentSession.value.id,
          agentId: agent.value.id,
          role: 'user',
          content: userMessage,
          messageType: 'text',
          createTime: new Date().toLocaleString(),
        });

        inputMessage.value = '';
        sending.value = true;
        await nextTick();
        scrollToBottom();

        try {
          const response = await agentScopeApi.chat(agent.value.id, userMessage, currentSession.value.id);
          const data = response.data?.data || response.data;
          currentMessages.value.push({
            id: data.messageId,
            sessionId: currentSession.value.id,
            agentId: agent.value.id,
            role: 'assistant',
            content: data.message,
            messageType: 'text',
            createTime: new Date().toLocaleString(),
          });

          // 更新会话列表
        } catch (error: any) {
          ElMessage.error(error.response?.data?.message || '发送失败');
          currentMessages.value.pop();
        } finally {
          sending.value = false;
          await nextTick();
          scrollToBottom();
        }
      };

      const scrollToBottom = () => {
        if (chatContainer.value) {
          chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
        }
      };

      const formatMessage = (content: string) => {
        return content.replace(/\n/g, '<br>');
      };

      onMounted(async () => {
        await loadAgent();
        await loadSessions();
        // 如果有会话，默认选中第一个
        if (sessions.value.length > 0) {
          currentSession.value = sessions.value[0];
          await loadMessages(sessions.value[0].id);
        }
      });

      return {
        agent,
        agentForSidebar,
        sessions,
        currentSession,
        currentMessages,
        inputMessage,
        sending,
        chatContainer,
        handleSetCurrentSession,
        handleGetCurrentSession,
        selectSession,
        deleteSessionState,
        updateSessionTitle,
        pinSession,
        clearAllSessions,
        createSession,
        loadSessions,
        sendMessage,
        handleEnterKey,
        formatMessage,
      };
    },
  };
</script>

<style scoped>
  /* 聊天容器样式 - 与 AgentRun.vue 保持一致 */
  .chat-container {
    flex: 1;
    overflow-y: auto;
    padding: 20px;
    background: #f8f9fa;
    border-radius: 8px;
    margin-bottom: 20px;
  }

  .empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 24px;
    padding: 40px 20px;
  }

  .messages-area {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  /* 消息容器样式 */
  .message-container {
    display: flex;
    max-width: 100%;
  }

  .message-container.user {
    justify-content: flex-end;
  }

  .message-container.assistant {
    justify-content: flex-start;
  }

  /* 消息样式 */
  .message {
    display: flex;
    gap: 12px;
    max-width: 80%;
  }

  .message.user {
    align-self: flex-end;
    flex-direction: row-reverse;
  }

  .message.assistant {
    align-self: flex-start;
  }

  .message-avatar {
    flex-shrink: 0;
  }

  .message-content {
    flex: 1;
  }

  .message-text {
    padding: 12px 16px;
    border-radius: 12px;
    line-height: 1.5;
    word-wrap: break-word;
    position: relative;
  }

  .message.user .message-text {
    background: #409eff;
    color: white;
  }

  .message.assistant .message-text {
    background: white;
    color: #303133;
    border: 1px solid #e8e8e8;
  }

  .message-time {
    font-size: 12px;
    color: #909399;
    margin-top: 4px;
    padding: 0 4px;
    text-align: right;
  }

  .message.user .message-time {
    color: rgba(255, 255, 255, 0.8);
  }

  .input-area {
    padding: 16px 20px;
    border-top: 1px solid #e4e7ed;
  }

  .input-actions {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 12px;
  }

  .hint {
    font-size: 12px;
    color: #909399;
  }
</style>
