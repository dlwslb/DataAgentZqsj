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
                <!-- Markdown/HTML 报告：与 AgentRun.vue 保持一致 -->
                <div
                  v-if="msg.messageType === 'markdown-report' || msg.messageType === 'html-report'"
                  class="markdown-report-message"
                >
                  <div
                    class="markdown-report-header"
                    style="display: flex; justify-content: space-between; align-items: center"
                  >
                    <div class="report-info">
                      <el-icon><Document /></el-icon>
                      <span>报告已生成</span>
                      <el-radio-group
                        v-model="reportFormat"
                        size="small"
                        class="report-format-inline"
                      >
                        <el-radio-button value="markdown">Markdown</el-radio-button>
                        <el-radio-button value="html">HTML</el-radio-button>
                      </el-radio-group>
                    </div>
                    <el-button-group size="large">
                      <el-button
                        type="primary"
                        @click="downloadMarkdownReport(stripReportPrefix(msg.content))"
                      >
                        <el-icon><Download /></el-icon>
                        下载Markdown报告
                      </el-button>
                      <el-button
                        type="success"
                        @click="downloadHtmlReport(stripReportPrefix(msg.content))"
                      >
                        <el-icon><Download /></el-icon>
                        下载HTML报告
                      </el-button>
                      <el-tooltip content="全屏查看报告" placement="top">
                        <el-button type="info" @click="openReportFullscreen(stripReportPrefix(msg.content))">
                          <el-icon><FullScreen /></el-icon>
                          全屏
                        </el-button>
                      </el-tooltip>
                    </el-button-group>
                  </div>
                  <div class="markdown-report-content">
                    <MarkdownAgentContainer
                      v-if="reportFormat === 'markdown'"
                      class="md-body"
                      :content="stripReportPrefix(msg.content)"
                      :options="options"
                    />
                    <ReportHtmlView v-else :content="stripReportPrefix(msg.content)" />
                  </div>
                </div>
                <!-- 普通文本消息 -->
                <div v-else class="message-text">
                  <span v-html="formatMessage(msg.content, msg.messageType)"></span>
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
        <div class="input-area" v-if="currentSession">
          <div class="input-controls">
            <div
              class="input-controls-header"
              @click="inputControlsCollapsed = !inputControlsCollapsed"
            >
              <span class="input-controls-title">更多选项</span>
              <el-button
                type="primary"
                size="small"
                class="input-controls-toggle-btn"
                :class="{ collapsed: inputControlsCollapsed }"
              >
                <el-icon class="input-controls-toggle-icon">
                  <ArrowDown />
                </el-icon>
                {{ inputControlsCollapsed ? '展开' : '收起' }}
              </el-button>
            </div>
            <div v-show="!inputControlsCollapsed" class="input-controls-body">
              <div class="switch-group">
                <div class="switch-item">
                  <span class="switch-label">管理员模式</span>
                  <el-switch
                    v-model="isAdminMode"
                    :disabled="sending || showHumanFeedback"
                  />
                </div>
                <div class="switch-item">
                  <span class="switch-label">人工反馈</span>
                  <el-tooltip
                    :disabled="!requestOptions.nl2sqlOnly"
                    content="该功能在NL2SQL模式下不能使用"
                    placement="top"
                  >
                    <el-switch
                      v-model="requestOptions.humanFeedback"
                      :disabled="requestOptions.nl2sqlOnly || sending || showHumanFeedback"
                    />
                  </el-tooltip>
                </div>
                <div class="switch-item">
                  <span class="switch-label">仅NL2SQL</span>
                  <el-switch
                    v-model="requestOptions.nl2sqlOnly"
                    :disabled="sending || showHumanFeedback"
                    @change="handleNl2sqlOnlyChange"
                  />
                </div>
                <div class="switch-item">
                  <span class="switch-label">自动Scroll</span>
                  <el-switch v-model="autoScroll" />
                </div>
                <div class="switch-item">
                  <span class="switch-label">显示SQL结果</span>
                  <el-tooltip
                    content="启用本功能会将SQL查询结果存储到DataAgent项目的数据库中，如果数据量较大不建议开启本功能"
                    placement="top"
                  >
                    <el-switch
                      v-model="resultSetDisplayConfig.showSqlResults"
                      :disabled="sending || showHumanFeedback"
                    />
                  </el-tooltip>
                </div>
                <div class="switch-item">
                  <span class="switch-label">每页数量</span>
                  <el-select
                    v-model="resultSetDisplayConfig.pageSize"
                    :disabled="sending || showHumanFeedback"
                    style="width: 80px"
                  >
                    <el-option label="5" :value="5" />
                    <el-option label="10" :value="10" />
                    <el-option label="20" :value="20" />
                    <el-option label="50" :value="50" />
                    <el-option label="100" :value="100" />
                  </el-select>
                </div>
              </div>
            </div>
          </div>
          <div class="input-container">
            <el-input
              v-model="inputMessage"
              type="textarea"
              :rows="3"
              placeholder="输入问题，按 Enter 发送..."
              :disabled="!currentSession || sending"
              @keydown.enter.exact.prevent="handleEnterKey"
            />
            <el-button
              v-if="!sending"
              type="primary"
              @click="sendMessage"
              :disabled="showHumanFeedback || !inputMessage.trim()"
              circle
              class="send-button"
            >
              <el-icon><Promotion /></el-icon>
            </el-button>
            <el-button
              v-else
              type="danger"
              @click="stopStreaming"
              circle
              class="send-button stop-button-inline"
            >
              <el-icon><CircleClose /></el-icon>
            </el-button>
          </div>
        </div>

        <!-- 人类反馈区域 -->
        <HumanFeedback
          v-if="showHumanFeedback"
          :request="lastRequest"
          :handleFeedback="handleHumanFeedback"
        />
      </el-main>
    </el-container>

    <!-- 报告全屏遮罩 -->
    <Teleport to="body">
      <div
        v-if="showReportFullscreen"
        class="report-fullscreen-overlay"
        @click.self="closeReportFullscreen"
      >
        <div class="report-fullscreen-container">
          <div class="report-fullscreen-header">
            <span class="report-fullscreen-title">
              {{ reportFormat === 'markdown' ? 'Markdown 报告' : 'HTML 报告' }}
            </span>
            <el-button
              type="danger"
              circle
              class="report-fullscreen-close"
              @click="closeReportFullscreen"
            >
              <el-icon><Close /></el-icon>
            </el-button>
          </div>
          <div class="report-fullscreen-content">
            <MarkdownAgentContainer
              v-if="reportFormat === 'markdown'"
              class="md-body report-fullscreen-body"
              :content="fullscreenReportContent"
              :options="options"
            />
            <ReportHtmlView
              v-else
              :content="fullscreenReportContent"
              class="report-fullscreen-body"
            />
          </div>
        </div>
      </div>
    </Teleport>
  </BaseLayout>
</template>

<script lang="ts">
  import { ref, onMounted, nextTick, computed } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import { Loading, Document, Download, FullScreen, Close, ArrowDown, Promotion, CircleClose } from '@element-plus/icons-vue';
  import { marked } from 'marked';
  import DOMPurify from 'dompurify';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import ChatSessionSidebar from '@/components/run/ChatSessionSidebar.vue';
  import MarkdownAgentContainer from '@/components/run/markdown';
  import ReportHtmlView from '@/components/run/ReportHtmlView.vue';
  import HumanFeedback from '@/components/run/HumanFeedback.vue';
  import { agentScopeApi, AgentScope, ChatSession, ChatMessage } from '@/services/agentScope';

  export default {
    name: 'AgentScopeRun',
    components: {
      BaseLayout,
      ChatSessionSidebar,
      Loading,
      Document,
      Download,
      FullScreen,
      Close,
      ArrowDown,
      Promotion,
      CircleClose,
      MarkdownAgentContainer,
      ReportHtmlView,
      HumanFeedback,
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
      const reportFormat = ref<'markdown' | 'html'>('markdown');
      const showReportFullscreen = ref(false);
      const fullscreenReportContent = ref('');
      const inputControlsCollapsed = ref(false);
      const autoScroll = ref(true);

      // 人工反馈相关数据
      const showHumanFeedback = ref(false);
      const lastRequest = ref<any>(null);

      // 结果集显示配置
      const resultSetDisplayConfig = ref({
        showSqlResults: false,
        pageSize: 20,
      });

      const requestOptions = ref({
        userRole: 'user' as 'user' | 'admin',
        humanFeedback: false,
        nl2sqlOnly: false,
      });

      // 管理员模式开关（双向绑定到 requestOptions.userRole）
      const isAdminMode = computed({
        get: () => requestOptions.value.userRole === 'admin',
        set: (val: boolean) => {
          requestOptions.value.userRole = val ? 'admin' : 'user';
        },
      });

      // 监听NL2SQL开关变化
      const handleNl2sqlOnlyChange = (value: boolean) => {
        if (value) {
          requestOptions.value.humanFeedback = false;
        }
      };

      const options = ref({
        markdownIt: {
          linkify: true,
        },
        linkAttributes: {
          attrs: {
            target: '_blank',
            rel: 'noopener',
          },
        },
      });

      // Markdown转HTML
      const markdownToHtml = (markdown: string): string => {
        if (!markdown) return '';
        marked.setOptions({ gfm: true, breaks: true });
        const rawHtml = marked.parse(markdown) as string;
        return DOMPurify.sanitize(rawHtml);
      };

      // 清理报告内容：去掉 $$$markdown-report 前缀和 报告生成完成 后缀
      const stripReportPrefix = (content: string): string => {
        if (!content) return content;
        let cleaned = content;
        // 去掉 $$$markdown-report 前缀
        if (cleaned.startsWith('$$$markdown-report')) {
          cleaned = cleaned.substring('$$$markdown-report'.length);
        }
        // 去掉 $$$html-report 前缀
        if (cleaned.startsWith('$$$html-report')) {
          cleaned = cleaned.substring('$$$html-report'.length);
        }
        // 去掉 报告生成完成 后缀
        cleaned = cleaned.replace(/报告生成完成！?\s*$/, '');
        return cleaned.trim();
      };

      // 格式化消息内容，根据类型选择渲染方式
      const formatMessage = (content: string, messageType?: string) => {
        if (messageType === 'markdown-report' || messageType === 'markdown') {
          return markdownToHtml(stripReportPrefix(content));
        }
        if (messageType === 'html-report' || messageType === 'html') {
          return stripReportPrefix(content);
        }
        return content.replace(/\n/g, '<br>');
      };

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
        lastRequest.value = {
          agentId: agent.value.id,
          query: userMessage,
          humanFeedback: requestOptions.value.humanFeedback,
          nl2sqlOnly: requestOptions.value.nl2sqlOnly,
          rejectedPlan: false,
          humanFeedbackContent: null,
          userRole: requestOptions.value.userRole,
          showSqlResults: resultSetDisplayConfig.value.showSqlResults,
        };
        await nextTick();
        scrollToBottom();

        try {
          const response = await agentScopeApi.chat(
            agent.value.id,
            userMessage,
            currentSession.value.id,
            requestOptions.value.userRole,
            requestOptions.value.nl2sqlOnly,
            requestOptions.value.humanFeedback,
            false,
            undefined,
            resultSetDisplayConfig.value.showSqlResults
          );
          const data = response.data?.data || response.data;
          currentMessages.value.push({
            id: data.messageId,
            sessionId: currentSession.value.id,
            agentId: agent.value.id,
            role: 'assistant',
            content: data.message,
            messageType: data.messageType || 'text',
            createTime: new Date().toLocaleString(),
          });
        } catch (error: any) {
          ElMessage.error(error.response?.data?.message || '发送失败');
          currentMessages.value.pop();
        } finally {
          sending.value = false;
          await nextTick();
          scrollToBottom();
        }
      };

      // 停止流式响应
      const stopStreaming = () => {
        sending.value = false;
        ElMessage.success('已停止对话');
      };

      // 处理人工反馈
      const handleHumanFeedback = async (request: any, rejectedPlan: boolean, content: string) => {
        content = content.trim() || 'Accept';
        showHumanFeedback.value = false;
        const newRequest = { ...request };
        newRequest.rejectedPlan = rejectedPlan;
        newRequest.humanFeedbackContent = content;
        lastRequest.value = newRequest;
        sending.value = true;
        await nextTick();
        scrollToBottom();

        try {
          const response = await agentScopeApi.chat(
            agent.value.id,
            newRequest.query,
            currentSession.value.id,
            newRequest.userRole,
            newRequest.nl2sqlOnly,
            newRequest.humanFeedback,
            newRequest.rejectedPlan,
            newRequest.humanFeedbackContent,
            newRequest.showSqlResults
          );
          const data = response.data?.data || response.data;
          currentMessages.value.push({
            id: data.messageId,
            sessionId: currentSession.value.id,
            agentId: agent.value.id,
            role: 'assistant',
            content: data.message,
            messageType: data.messageType || 'text',
            createTime: new Date().toLocaleString(),
          });
        } catch (error: any) {
          ElMessage.error(error.response?.data?.message || '处理失败');
        } finally {
          sending.value = false;
          await nextTick();
          scrollToBottom();
        }
      };

      // 下载 Markdown 报告
      const downloadMarkdownReport = (content: string) => {
        if (!content) {
          ElMessage.warning('没有可下载的Markdown报告');
          return;
        }
        const blob = new Blob([content], { type: 'text/markdown' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `report_${new Date().getTime()}.md`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        ElMessage.success('Markdown报告下载成功');
      };

      // 下载 HTML 报告
      const downloadHtmlReport = (content: string) => {
        if (!content) {
          ElMessage.warning('没有可下载的HTML报告');
          return;
        }
        const html = markdownToHtml(content);
        const blob = new Blob([html], { type: 'text/html' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `report_${new Date().getTime()}.html`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        ElMessage.success('HTML报告下载成功');
      };

      // 全屏查看报告
      const openReportFullscreen = (content: string) => {
        fullscreenReportContent.value = content;
        showReportFullscreen.value = true;
      };

      const closeReportFullscreen = () => {
        showReportFullscreen.value = false;
        fullscreenReportContent.value = '';
      };

      const scrollToBottom = () => {
        if (autoScroll.value && chatContainer.value) {
          chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
        }
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
        reportFormat,
        showReportFullscreen,
        fullscreenReportContent,
        options,
        markdownToHtml,
        formatMessage,
        stripReportPrefix,
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
        downloadMarkdownReport,
        downloadHtmlReport,
        openReportFullscreen,
        closeReportFullscreen,
        inputControlsCollapsed,
        autoScroll,
        showHumanFeedback,
        lastRequest,
        resultSetDisplayConfig,
        requestOptions,
        isAdminMode,
        handleNl2sqlOnlyChange,
        stopStreaming,
        handleHumanFeedback,
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
    background: white;
    border-radius: 8px;
    padding: 16px;
    border: 1px solid #e8e8e8;
  }

  .input-controls {
    margin-bottom: 12px;
    border-bottom: 1px solid #f0f0f0;
  }

  .input-controls-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 8px 0;
    cursor: pointer;
    user-select: none;
    color: #606266;
    font-size: 14px;
  }

  .input-controls-header:hover {
    color: #409eff;
  }

  .input-controls-title {
    font-weight: 500;
  }

  .input-controls-toggle-btn {
    flex-shrink: 0;
  }

  .input-controls-toggle-btn .input-controls-toggle-icon {
    margin-right: 4px;
    transition: transform 0.2s ease;
  }

  .input-controls-toggle-btn.collapsed .input-controls-toggle-icon {
    transform: rotate(-90deg);
  }

  .input-controls-body {
    padding-bottom: 12px;
  }

  .switch-group {
    display: flex;
    flex-wrap: wrap;
    gap: 20px;
    align-items: center;
  }

  .switch-item {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .switch-label {
    font-size: 14px;
    color: #606266;
  }

  .send-button {
    width: 48px;
    height: 48px;
  }

  .stop-button-inline {
    width: 48px;
    height: 48px;
  }

  .input-container {
    display: flex;
    gap: 12px;
    align-items: flex-end;
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

  /* Markdown报告消息样式 - 与 AgentRun.vue 保持一致 */
  .markdown-report-message {
    background: white;
    border: 1px solid #e8e8e8;
    border-radius: 12px;
    padding: 16px;
    margin-bottom: 16px;
  }

  .markdown-report-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    padding-bottom: 12px;
    border-bottom: 1px solid #f0f0f0;
  }

  .markdown-report-content {
    margin-top: 16px;
  }

  .report-info {
    display: flex;
    align-items: center;
    gap: 12px;
    color: #409eff;
    font-size: 16px;
    font-weight: 500;
  }

  .report-format-inline {
    margin-left: 8px;
  }

  /* 报告全屏样式 */
  .report-fullscreen-overlay {
    position: fixed;
    inset: 0;
    z-index: 9999;
    background: rgba(0, 0, 0, 0.7);
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
  }

  .report-fullscreen-container {
    width: 100%;
    max-width: 1200px;
    height: 90vh;
    background: white;
    border-radius: 12px;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
  }

  .report-fullscreen-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 16px 24px;
    border-bottom: 1px solid #e8e8e8;
    background: #f8f9fa;
    flex-shrink: 0;
  }

  .report-fullscreen-title {
    font-size: 18px;
    font-weight: 600;
    color: #303133;
  }

  .report-fullscreen-close {
    flex-shrink: 0;
  }

  .report-fullscreen-content {
    flex: 1;
    overflow: auto;
    padding: 24px;
  }

  .report-fullscreen-body {
    min-height: 100%;
  }

  /* Markdown报告容器 - 与 AgentRun.vue 保持一致 */
  .markdown-report-content {
    line-height: 1.6;
    color: #1f2933;
  }

  /* Markdown样式重置 - 与 AgentRun.vue 保持一致 */
  .markdown-report-content .markdown-container {
    line-height: 1.4;
    white-space: normal;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue',
      Arial, sans-serif;
  }

  .markdown-report-content pre {
    background: #f6f8fa;
    padding: 10px 12px;
    border-radius: 6px;
    overflow: auto;
    margin: 0;
    border: none;
  }

  .markdown-report-content code {
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    background: transparent;
    padding: 0;
  }

  /* 全屏报告 Markdown 样式 */
  .report-fullscreen-body .markdown-container {
    line-height: 1.4;
    white-space: normal;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue',
      Arial, sans-serif;
  }

  .report-fullscreen-body pre {
    background: #f6f8fa;
    padding: 10px 12px;
    border-radius: 6px;
    overflow: auto;
    margin: 0;
    border: none;
  }

  .report-fullscreen-body code {
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    background: transparent;
    padding: 0;
  }
</style>
