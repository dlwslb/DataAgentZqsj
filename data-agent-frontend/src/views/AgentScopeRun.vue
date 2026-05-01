<!--
 * AgentScope 智能体运行页 - 最终完整版
 * 修复清单：
 * 1. Markdown 格式自动识别渲染（支持 ### 后无空格）
 * 2. 思考预览动态类型渲染（与后端 messageType 同步）
 * 3. 1./。开头内容标准间距（不再贴左侧）
 * 4. 嵌套列表 2./- 缩进累加（每层 +20px）
 * 5. 普通 Markdown 消息样式与 markdown-report 对齐
 * 6. 移除占位符，避免双排显示
 * 7. 时间戳只在有内容时生成
 * 8. 思考状态与最终消息互斥显示
-->

<template>
  <BaseLayout>
    <el-container style="height: calc(100vh - 60px); gap: 0">
      <!-- 左侧会话列表 -->
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
                <!-- Markdown/HTML 报告 -->
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
                        下载 Markdown 报告
                      </el-button>
                      <el-button
                          type="success"
                          @click="downloadHtmlReport(stripReportPrefix(msg.content))"
                      >
                        <el-icon><Download /></el-icon>
                        下载 HTML 报告
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
                  <div class="message-time">{{ msg.createTime }}</div>
                </div>
                <!-- 普通文本/Markdown 消息 -->
                <div v-else class="message-text">
                  <!-- 🔑 ECharts 图表：使用 MarkdownAgentContainer 组件 -->
                  <MarkdownAgentContainer
                      v-if="msg.messageType === 'markdown' && msg.content.includes('```echarts')"
                      class="md-body"
                      :content="stripReportPrefix(msg.content)"
                      :options="options"
                  />
                  <!-- 🔑 普通 Markdown：使用 v-html -->
                  <div
                      v-else
                      :class="{ 'markdown-container': msg.messageType === 'markdown' || msg.messageType === 'markdown-report' }"
                      v-html="formatMessage(msg.content, msg.messageType)"
                  ></div>
                  <div class="message-time">{{ msg.createTime }}</div>
                </div>
              </div>
            </div>

            <!-- ✅ 思考中状态：只在 发送中 && 未收到最终结果 时显示 -->
            <div v-if="sending && !hasFinalMessage" class="message assistant thinking-state">
              <div class="message-avatar thinking-avatar">
                <el-avatar :size="32" class="avatar-pulse">A</el-avatar>
              </div>
              <div class="message-content">
                <div class="thinking-container">
                  <!-- 真实推理预览：来自后端 reasoning/summary 事件 -->
                  <div v-if="thinkingPreview" class="thinking-preview">
                    <div
                        class="preview-text markdown-container"
                        v-html="formatMessage(thinkingPreview, currentPreviewType)"
                    ></div>
                    <span class="typing-cursor">▋</span>
                  </div>
                  <!-- 空状态提示 -->
                  <div v-else class="thinking-placeholder">
                    <el-icon class="is-loading"><Loading /></el-icon>
                    <span>正在思考...</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 输入区域 -->
        <div class="input-area" v-if="currentSession">
          <div class="input-controls" v-if="isSuperAdmin">
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
                  <span class="switch-label">SSE 回答</span>
                  <el-switch v-model="sseEnabled" />
                </div>
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
                      content="该功能在 NL2SQL 模式下不能使用"
                      placement="top"
                  >
                    <el-switch
                        v-model="requestOptions.humanFeedback"
                        :disabled="requestOptions.nl2sqlOnly || sending || showHumanFeedback"
                    />
                  </el-tooltip>
                </div>
                <div class="switch-item">
                  <span class="switch-label">仅 NL2SQL</span>
                  <el-switch
                      v-model="requestOptions.nl2sqlOnly"
                      :disabled="sending || showHumanFeedback"
                      @change="handleNl2sqlOnlyChange"
                  />
                </div>
                <div class="switch-item">
                  <span class="switch-label">自动 Scroll</span>
                  <el-switch v-model="autoScroll" />
                </div>
                <div class="switch-item">
                  <span class="switch-label">显示 SQL 结果</span>
                  <el-tooltip
                      content="启用本功能会将 SQL 查询结果存储到 DataAgent 项目的数据库中，如果数据量较大不建议开启本功能"
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
import { useRouter, useRoute } from 'vue-router';
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
import authService from '@/services/auth';

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
    const route = useRoute();
    // 获取模拟用户ID（从URL参数）
    const impersonateUserId = computed(() => route.query.impersonateUserId as string);
    // 检查是否在模拟模式
    const isImpersonating = ref(authService.isImpersonating());
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

    // SSE 回答开关
    const sseEnabled = ref(true);
    
    // 是否为超级管理员
    const isSuperAdmin = ref(false);
    const checkSuperAdmin = () => {
      const userInfoStr = localStorage.getItem('userInfo');
      if (userInfoStr) {
        try {
          const userInfo = JSON.parse(userInfoStr);
          isSuperAdmin.value = userInfo?.role === 'super_admin';
        } catch (e) {
          isSuperAdmin.value = false;
        }
      }
    };

    // 状态控制
    const thinkingPreview = ref('');
    const hasFinalMessage = ref(false);

    // 🔑 跟踪思考预览的当前渲染类型（与后端 messageType 同步）
    const currentPreviewType = ref<'text' | 'markdown' | 'markdown-report' | 'html-report'>('text');

    const requestOptions = ref({
      userRole: 'user' as 'user' | 'admin',
      humanFeedback: false,
      nl2sqlOnly: false,
    });

    // 🔑 修复版：自动检测 Markdown 特征（支持 ### 后无空格，与后端逻辑一致）
    const detectMessageType = (content: string): string => {
      if (!content) return 'text';

      // 1. 优先识别报告类型（检查原始内容，前缀是报告标识）
      if (content.includes('$$$html-report') || content.includes('<html') || content.includes('</')) {
        return 'html-report';
      }
      if (content.includes('$$$markdown-report') || content.includes('report content:')) {
        return 'markdown-report';
      }

      // 2. 检测 Markdown 特征（支持 # 后无空格的情况，如 "###标题"）
      // 2.1 代码块
      if (content.includes('```')) return 'markdown';

      // 2.2 标题：支持 # 到 ######，允许后面紧跟文字
      if (content.includes('######') || content.includes('#####') || content.includes('####') ||
          content.includes('###') || content.includes('##') || content.includes('# ')) {
        return 'markdown';
      }

      // 2.3 粗体/斜体
      if (content.includes('**') || content.includes('__')) return 'markdown';

      // 2.4 列表（行首检测）
      if (/^\s*[-*+]\s+/m.test(content) || /^\s*\d+\.\s+/m.test(content)) return 'markdown';

      // 2.5 链接/图片
      if (/\[.+?\]\(.+?\)/.test(content) || /!\[.+?\]\(.+?\)/.test(content)) return 'markdown';

      // 3. 检测 HTML 标签
      if (content.includes('<html') || content.includes('</div>') || content.includes('</p>') ||
          content.includes('<table') || content.includes('<br')) {
        return 'html-report';
      }

      return 'text';
    };

    // 🔑 流式内容预处理：确保列表/段落语法完整，避免贴左侧
    const preprocessStreamingContent = (rawContent: string, accumulatedContent: string): string => {
      let content = rawContent;

      // 🔑 核心修复：标准化列表格式，确保 marked 能正确识别
      // 1. 处理有序列表：\n1. xxx → \n\n1. xxx（添加空行使 marked 识别为新列表）
      if (/^\s*\d+\.\s+/.test(content)) {
        // 如果前面有内容且不以空行结尾，添加双换行
        if (accumulatedContent && accumulatedContent.length > 0 && !accumulatedContent.trimEnd().endsWith('\n\n')) {
          content = '\n\n' + content.replace(/^\s+/, ''); // 🔑 只去除前导空格/Tab，保留一个\n
        } else if (!accumulatedContent || accumulatedContent.length === 0) {
          // 🔑 如果是完整消息（无累积内容），直接去除前导空白
          content = content.replace(/^\s+/, '');
        }
      }

      // 2. 处理无序列表：\n   - xxx → \n\n   - xxx（保留缩进，添加空行）
      if (/^\s*[\-\*\+]\s+/.test(content)) {
        // 如果前面有内容且不以空行结尾，添加双换行
        if (accumulatedContent && accumulatedContent.length > 0 && !accumulatedContent.trimEnd().endsWith('\n\n')) {
          // 🔑 关键：保留缩进空格，只确保前面有\n\n
          const leadingWhitespace = content.match(/^(\s*)[\-\*\+]/)?.[1] || '';
          const trimmedContent = content.replace(/^\s*/, '');
          content = '\n\n' + leadingWhitespace + trimmedContent;
        } else if (!accumulatedContent || accumulatedContent.length === 0) {
          // 🔑 如果是完整消息，保留原始格式（包括缩进）
          // 不做任何处理，让 marked 根据缩进识别嵌套层级
        }
      }

      // 3. 中文标点开头（。！？；：）- 加空格避免粘连
      if (/^[。！？；：，、]/.test(content) && accumulatedContent && !/[。！？；：，、\n]$/.test(accumulatedContent)) {
        content = ' ' + content;
      }

      // 4. 确保代码块 ``` 单独成行
      if (content.includes('```') && !/\n```/.test(content)) {
        content = content.replace(/```/g, '\n```');
      }

      return content;
    };

    const isAdminMode = computed({
      get: () => requestOptions.value.userRole === 'admin',
      set: (val: boolean) => {
        requestOptions.value.userRole = val ? 'admin' : 'user';
      },
    });

    const handleNl2sqlOnlyChange = (value: boolean) => {
      if (value) {
        requestOptions.value.humanFeedback = false;
      }
    };

    // 🔑 marked 配置 + 自定义渲染器
    const options = ref({
      marked: {
        gfm: true,
        breaks: true,
        mangle: false,
        headerIds: false,
      },
      linkAttributes: {
        attrs: {
          target: '_blank',
          rel: 'noopener',
        },
      },
    });

    // 🔑 初始化 marked 自定义渲染器（增强中文/流式兼容）
    const initMarkedRenderer = () => {
      const renderer = new marked.Renderer();

      // 🔑 列表项：直接在内联样式中添加左边距，确保缩进生效
      renderer.listitem = (text: string) => {
        return `<li style="margin: 4px 0 4px 12px;">${text}</li>`;
      };

      // 段落：确保标准间距 + 中文两端对齐
      renderer.paragraph = (text: string) => {
        return `<p style="margin: 8px 0; line-height: 1.6; text-align: justify; text-justify: inter-ideograph;">${text}</p>`;
      };

      marked.use({ renderer });
    };

    const markdownToHtml = (markdown: string): string => {
      if (!markdown) return '';
      const rawHtml = marked.parse(markdown) as string;
      return DOMPurify.sanitize(rawHtml);
    };

    const stripReportPrefix = (content: string): string => {
      if (!content) return content;
      let cleaned = content;
      if (cleaned.startsWith('$$$markdown-report')) {
        cleaned = cleaned.substring('$$$markdown-report'.length);
      }
      if (cleaned.startsWith('$$$html-report')) {
        cleaned = cleaned.substring('$$$html-report'.length);
      }
      cleaned = cleaned.replace(/ 报告生成完成！?\s*$/, '');
      return cleaned.trim();
    };

    const formatMessage = (content: string, messageType?: string) => {
      if (messageType === 'markdown-report' || messageType === 'markdown') {
        return markdownToHtml(stripReportPrefix(content));
      }
      if (messageType === 'html-report' || messageType === 'html') {
        return stripReportPrefix(content);
      }
      return content.replace(/\n/g, '<br>');
    };

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

    const handleGetCurrentSession = () => currentSession.value;

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
      } catch (error) {
        ElMessage.error('加载智能体失败');
      }
    };

    const loadSessions = async () => {
      if (!agent.value.id || agent.value.id === 0) return;
      try {
        const response = await agentScopeApi.getSessions(agent.value.id);
        const sessionsData = response.data?.data || response.data || [];
        sessions.value = (Array.isArray(sessionsData) ? sessionsData : [])
            .filter(session => session != null)
            .map((session: any) => ({
              id: session.id,
              title: session.title || '新会话',
              updateTime: session.updateTime,
              createTime: session.createTime,
              isPinned: false,
            }));
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
        if (session) session.title = title;
      } catch (error) {
        console.error('更新会话标题失败:', error);
        throw error;
      }
    };

    const pinSession = async (sessionId: string, pinned: boolean) => {
      try {
        await agentScopeApi.pinSession(sessionId, pinned);
        const session = sessions.value.find(s => s.id === sessionId);
        if (session) session.isPinned = pinned;
      } catch (error) {
        console.error('置顶会话失败:', error);
        throw error;
      }
    };

    const clearAllSessions = async () => {
      try {
        await Promise.all(sessions.value.map(session =>
            agentScopeApi.deleteSession(session.id).catch(err => console.error(`删除会话 ${session.id} 失败:`, err))
        ));
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
      hasFinalMessage.value = false;
      thinkingPreview.value = '';
      currentPreviewType.value = 'text'; // 🔑 重置预览类型
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
        if (sseEnabled.value) {
          await sendStreamingMessage(userMessage);
        } else {
          await sendNormalMessage(userMessage);
        }
      } catch (error: any) {
        ElMessage.error(error.response?.data?.message || error.message || '发送失败');
        currentMessages.value.pop();
      } finally {
        if (!sseEnabled.value) {
          sending.value = false;
          await nextTick();
          scrollToBottom();
        }
      }
    };

    const sendNormalMessage = async (userMessage: string) => {
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
      // 🔑 关键修复：对完整消息也进行预处理，确保列表格式正确
      const processedContent = preprocessStreamingContent(data.message, '');
      currentMessages.value.push({
        id: data.messageId,
        sessionId: currentSession.value.id,
        agentId: agent.value.id,
        role: 'assistant',
        content: processedContent,
        messageType: data.messageType || detectMessageType(processedContent),
        createTime: new Date().toLocaleString(),
      });
    };

    const sendStreamingMessage = async (userMessage: string) => {
      return new Promise<void>(async (resolve, reject) => {
        // 🔑 关键修复：SSE 请求前检查并刷新 Token
        try {
          await ensureValidToken();
        } catch (error) {
          reject(error);
          return;
        }
        
        const token = localStorage.getItem('accessToken');
        const userInfoStr = localStorage.getItem('userInfo');
        const extraHeaders: Record<string, string> = {};
        if (token) extraHeaders['Authorization'] = `Bearer ${token}`;
        if (userInfoStr) {
          try {
            const userInfo = JSON.parse(userInfoStr);
            if (userInfo.id) extraHeaders['User-ID'] = String(userInfo.id);
            if (userInfo.tenantId) extraHeaders['Tenant-ID'] = String(userInfo.tenantId);
          } catch (e) { console.error('Failed to parse user info:', e); }
        }
        // 如果有模拟用户ID，添加到header
        if (impersonateUserId.value) {
          extraHeaders['X-Impersonate-User-ID'] = impersonateUserId.value;
        }

        const body = JSON.stringify({
          message: userMessage,
          sessionId: currentSession.value.id,
          userRole: requestOptions.value.userRole,
          nl2sqlOnly: requestOptions.value.nl2sqlOnly,
          humanFeedback: requestOptions.value.humanFeedback,
          rejectedPlan: false,
          humanFeedbackContent: undefined,
          showSqlResults: resultSetDisplayConfig.value.showSqlResults,
        });

        const baseUrl = import.meta.env.VITE_AGENT_SCOPE_API_TARGET || 'http://localhost:58064';
        fetch(`${baseUrl}/api/scope/agent/${agent.value.id}/chat/stream`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...extraHeaders },
          body,
        }).then(async response => {
          // 🔑 处理 401 错误：Token 过期时刷新后重试
          if (response.status === 401) {
            try {
              await refreshToken();
              // 刷新成功后重新获取 Token
              const newToken = localStorage.getItem('accessToken');
              if (newToken) {
                extraHeaders['Authorization'] = `Bearer ${newToken}`;
              }
              // 重新发起请求
              return fetch(`${baseUrl}/api/scope/agent/${agent.value.id}/chat/stream`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', ...extraHeaders },
                body,
              });
            } catch (refreshError) {
              throw new Error('Token 刷新失败，请重新登录');
            }
          }
          
          if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
          const reader = response.body?.getReader();
          if (!reader) throw new Error('ReadableStream not supported');

          const decoder = new TextDecoder();
          let buffer = '';
          let eventType = 'message';

          const read = () => {
            reader.read().then(({ done, value }) => {
              if (done) {
                // 🔹 流结束兜底：用思考预览生成最终消息
                if (!hasFinalMessage.value && thinkingPreview.value) {
                  // 🔑 关键修复：对兜底消息也进行预处理
                  const processedContent = preprocessStreamingContent(thinkingPreview.value, '');
                  currentMessages.value.push({
                    id: Date.now(),
                    sessionId: currentSession.value.id,
                    agentId: agent.value.id,
                    role: 'assistant',
                    content: stripReportPrefix(processedContent),
                    messageType: detectMessageType(processedContent),
                    createTime: new Date().toLocaleString(),
                  });
                }
                sending.value = false;
                hasFinalMessage.value = true;
                thinkingPreview.value = '';
                currentPreviewType.value = 'text'; // 🔑 重置
                nextTick().then(() => scrollToBottom());
                resolve();
                return;
              }

              buffer += decoder.decode(value, { stream: true });
              const lines = buffer.split('\n');
              buffer = lines.pop() || '';

              for (const line of lines) {
                if (!line.trim()) { eventType = 'message'; continue; }
                if (line.startsWith('event:')) {
                  eventType = line.substring(6).trim();
                  continue;
                }
                if (line.startsWith('data:')) {
                  const dataStr = line.substring(5).trim();

                  // 🔑 核心修改：尝试解析为 JSON 对象
                  let parsedData: {
                    content?: string;
                    messageType?: string;
                    messageId?: string;
                    eventType?: string;
                  } | null = null;

                  try {
                    parsedData = JSON.parse(dataStr);
                  } catch (e) {
                    // 🔄 兼容旧格式：非 JSON 时当作纯文本处理
                    parsedData = {
                      content: dataStr,
                      messageType: 'text',
                      messageId: null,
                      eventType: eventType
                    };
                  }

                  // 🔑 提取标准化字段
                  const content = parsedData?.content || '';
                  const messageType = parsedData?.messageType || 'text';
                  const messageId = parsedData?.messageId;

                  // ─────────────────────────────────────
                  // 📡 流式片段：更新思考预览（不创建消息）
                  // ─────────────────────────────────────
                  if (eventType === 'reasoning' || eventType === 'summary') {
                    if (content) {
                      // 🔑 关键：预处理内容后再拼接，确保列表/段落语法完整
                      const processedContent = preprocessStreamingContent(content, thinkingPreview.value);
                      thinkingPreview.value += processedContent;

                      // 类型判断用原始内容（避免预处理干扰检测）
                      if (messageType !== 'text') {
                        currentPreviewType.value = messageType as any;
                      } else {
                        // 兜底：用累积内容重新检测
                        currentPreviewType.value = detectMessageType(thinkingPreview.value) as any;
                      }
                      nextTick().then(() => scrollToBottom());
                    }
                  }
                  else if (eventType === 'acting') {
                    if (content) {
                      thinkingPreview.value += '\n' + content + '\n';
                      nextTick().then(() => scrollToBottom());
                    }
                  }

                      // ─────────────────────────────────────
                      // ✅ 完整消息：创建并推入消息列表
                  // ─────────────────────────────────────
                  else if ((eventType === 'message' || eventType === 'final') && content) {
                    // 🔑 自动修正 messageType：后端返回 'text' 时自动检测是否含 Markdown
                    let finalMessageType = messageType;
                    if (finalMessageType === 'text') {
                      finalMessageType = detectMessageType(content);
                    }

                    // 🔑 关键修复：对最终消息进行预处理，确保列表格式正确
                    const processedContent = preprocessStreamingContent(content, '');
                    
                    currentMessages.value.push({
                      id: messageId || Date.now(),
                      sessionId: currentSession.value.id,
                      agentId: agent.value.id,
                      role: 'assistant',
                      content: stripReportPrefix(processedContent), // 🔑 使用预处理后的内容
                      messageType: finalMessageType,
                      createTime: new Date().toLocaleString(),
                    });
                    hasFinalMessage.value = true;
                    thinkingPreview.value = '';
                    currentPreviewType.value = 'text'; // 🔑 重置
                    nextTick().then(() => scrollToBottom());
                  }

                      // ─────────────────────────────────────
                      // 🎯 done 事件：流正常结束
                  // ─────────────────────────────────────
                  else if (eventType === 'done') {
                    if (!hasFinalMessage.value && thinkingPreview.value) {
                      // 🔑 关键修复：对兜底消息也进行预处理
                      const processedContent = preprocessStreamingContent(thinkingPreview.value, '');
                      currentMessages.value.push({
                        id: Date.now(),
                        sessionId: currentSession.value.id,
                        agentId: agent.value.id,
                        role: 'assistant',
                        content: stripReportPrefix(processedContent),
                        messageType: detectMessageType(processedContent),
                        createTime: new Date().toLocaleString(),
                      });
                    }
                    sending.value = false;
                    hasFinalMessage.value = true;
                    thinkingPreview.value = '';
                    currentPreviewType.value = 'text'; // 🔑 重置
                    nextTick().then(() => scrollToBottom());
                    resolve();
                    return;
                  }

                      // ─────────────────────────────────────
                      // ❌ error 事件：流异常结束
                  // ─────────────────────────────────────
                  else if (eventType === 'error') {
                    sending.value = false;
                    hasFinalMessage.value = true;
                    thinkingPreview.value = '';
                    currentPreviewType.value = 'text';
                    reject(new Error(content || dataStr));
                    return;
                  }

                  // 重置 event 类型，避免污染下一条
                  eventType = 'message';
                }
              }
              read();
            }).catch(error => {
              sending.value = false;
              hasFinalMessage.value = true;
              thinkingPreview.value = '';
              currentPreviewType.value = 'text';
              reject(error);
            });
          };
          read();
        }).catch(error => {
          sending.value = false;
          hasFinalMessage.value = true;
          thinkingPreview.value = '';
          currentPreviewType.value = 'text';
          reject(error);
        });
      });
    };

    const stopStreaming = () => {
      sending.value = false;
      hasFinalMessage.value = true;
      thinkingPreview.value = '';
      currentPreviewType.value = 'text';
      ElMessage.success('已停止对话');
    };

    const handleHumanFeedback = async (request: any, rejectedPlan: boolean, content: string) => {
      content = content.trim() || 'Accept';
      showHumanFeedback.value = false;
      const newRequest = { ...request };
      newRequest.rejectedPlan = rejectedPlan;
      newRequest.humanFeedbackContent = content;
      lastRequest.value = newRequest;
      sending.value = true;
      hasFinalMessage.value = false;
      thinkingPreview.value = '';
      currentPreviewType.value = 'text';
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
        // 🔑 关键修复：对人工反馈响应也进行预处理
        const processedContent = preprocessStreamingContent(data.message, '');
        currentMessages.value.push({
          id: data.messageId,
          sessionId: currentSession.value.id,
          agentId: agent.value.id,
          role: 'assistant',
          content: stripReportPrefix(processedContent),
          messageType: data.messageType || detectMessageType(processedContent),
          createTime: new Date().toLocaleString(),
        });
      } catch (error: any) {
        ElMessage.error(error.response?.data?.message || '处理失败');
      } finally {
        sending.value = false;
        hasFinalMessage.value = true;
        thinkingPreview.value = '';
        currentPreviewType.value = 'text';
        await nextTick();
        scrollToBottom();
      }
    };

    const downloadMarkdownReport = (content: string) => {
      if (!content) { ElMessage.warning('没有可下载的 Markdown 报告'); return; }
      const blob = new Blob([content], { type: 'text/markdown' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = `report_${new Date().getTime()}.md`;
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      URL.revokeObjectURL(url);
      ElMessage.success('Markdown 报告下载成功');
    };

    const downloadHtmlReport = (content: string) => {
      if (!content) { ElMessage.warning('没有可下载的 HTML 报告'); return; }
      const html = markdownToHtml(content);
      const blob = new Blob([html], { type: 'text/html' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = `report_${new Date().getTime()}.html`;
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      URL.revokeObjectURL(url);
      ElMessage.success('HTML 报告下载成功');
    };

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

    // 🔑 SSE 请求前确保 Token 有效
    const ensureValidToken = async (): Promise<void> => {
      const token = localStorage.getItem('accessToken');
      if (!token) {
        throw new Error('未登录，请先登录');
      }

      // 🔑 主动检查：如果 Token 即将过期（剩余时间 < 5 分钟），主动刷新
      try {
        const decoded = decodeJWT(token);
        if (decoded && decoded.exp) {
          const now = Math.floor(Date.now() / 1000); // 当前时间戳（秒）
          const expiresIn = decoded.exp - now; // 剩余有效期（秒）
          
          console.log(`🔑 Token 剩余有效期: ${expiresIn} 秒 (${Math.floor(expiresIn / 60)} 分钟)`);
          
          // 如果剩余时间小于 5 分钟（300 秒），主动刷新
          if (expiresIn < 300) {
            console.log('⚠️ Token 即将过期，主动刷新...');
            await refreshToken();
            console.log('✅ Token 刷新成功');
          }
        }
      } catch (error) {
        console.warn('⚠️ Token 解码失败，将在请求时被动刷新:', error);
      }
    };

    // 🔑 解码 JWT Token（不验证签名，仅读取 payload）
    const decodeJWT = (token: string): { exp?: number; [key: string]: any } | null => {
      try {
        const parts = token.split('.');
        if (parts.length !== 3) {
          return null;
        }
        
        // 解码 payload（第二部分）
        const payload = parts[1];
        const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
        const jsonPayload = decodeURIComponent(
          atob(base64)
            .split('')
            .map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
            .join('')
        );
        
        return JSON.parse(jsonPayload);
      } catch (error) {
        console.error('JWT 解码失败:', error);
        return null;
      }
    };

    // 🔑 刷新 Token
    let isRefreshingToken = false; // 防止并发刷新
    const refreshToken = async (): Promise<void> => {
      // 🔑 如果正在刷新，等待完成
      if (isRefreshingToken) {
        console.log('⏳ Token 正在刷新中，等待...');
        return new Promise((resolve, reject) => {
          const checkInterval = setInterval(() => {
            if (!isRefreshingToken) {
              clearInterval(checkInterval);
              resolve();
            }
          }, 100);
          
          // 超时保护（5秒）
          setTimeout(() => {
            clearInterval(checkInterval);
            reject(new Error('Token 刷新超时'));
          }, 5000);
        });
      }

      const refreshTokenValue = localStorage.getItem('refreshToken');
      if (!refreshTokenValue) {
        throw new Error('没有 refresh token，请重新登录');
      }

      isRefreshingToken = true;
      try {
        const response = await fetch('/api/auth/refresh-token', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken: refreshTokenValue }),
        });

        if (!response.ok) {
          throw new Error('Token 刷新失败');
        }

        const result = await response.json();
        if (result.code === 0 && result.data) {
          localStorage.setItem('accessToken', result.data.accessToken);
          localStorage.setItem('refreshToken', result.data.refreshToken);
          console.log('✅ Token 已更新');
        } else {
          throw new Error(result.message || 'Token 刷新失败');
        }
      } catch (error) {
        console.error('❌ Token 刷新失败:', error);
        // 清除所有认证信息并跳转到登录页
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        localStorage.removeItem('userInfo');
        window.location.href = '/login';
        throw error;
      } finally {
        isRefreshingToken = false;
      }
    };

    onMounted(async () => {
      // 🔑 初始化 marked 自定义渲染器
      initMarkedRenderer();
      checkSuperAdmin();
      await loadAgent();
      await loadSessions();
      if (sessions.value.length > 0) {
        currentSession.value = sessions.value[0];
        await loadMessages(sessions.value[0].id);
      }
    });

    return {
      agent, agentForSidebar, sessions, currentSession, currentMessages,
      inputMessage, sending, chatContainer, reportFormat, showReportFullscreen,
      fullscreenReportContent, options, markdownToHtml, formatMessage, stripReportPrefix,
      handleSetCurrentSession, handleGetCurrentSession, selectSession, deleteSessionState,
      updateSessionTitle, pinSession, clearAllSessions, createSession, loadSessions,
      sendMessage, handleEnterKey, downloadMarkdownReport, downloadHtmlReport,
      openReportFullscreen, closeReportFullscreen, inputControlsCollapsed, autoScroll,
      showHumanFeedback, lastRequest, resultSetDisplayConfig, requestOptions,
      isAdminMode, sseEnabled, isSuperAdmin, isImpersonating, handleNl2sqlOnlyChange, stopStreaming, handleHumanFeedback,
      detectMessageType, preprocessStreamingContent,
      thinkingPreview,
      hasFinalMessage,
      currentPreviewType,
      ensureValidToken,
      refreshToken,
    };
  },
};
</script>

<style scoped>
/* ========== 基础布局样式 ========== */
.chat-container { flex: 1; overflow-y: auto; padding: 20px; background: var(--theme-header-bg); border-radius: 8px; margin-bottom: 20px; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 24px; padding: 40px 20px; }
.messages-area { display: flex; flex-direction: column; gap: 16px; }
.message { display: flex; gap: 12px; max-width: 80%; }
.message.user { align-self: flex-end; flex-direction: row-reverse; }
.message.assistant { align-self: flex-start; }
.message-avatar { flex-shrink: 0; }
.message-content { flex: 1; }

/* 🔑 消息文本基础样式 */
.message-text {
  padding: 12px 16px;
  border-radius: 12px;
  line-height: 1.5;
  word-wrap: break-word;
  position: relative;
  margin-left: 4px;
}
.message.user .message-text { background: var(--theme-primary); color: white; }
.message.assistant .message-text { background: var(--theme-header-bg); color: var(--theme-text-primary); border: 1px solid rgba(0, 0, 0, 0.08); }
.message-time { font-size: 12px; color: var(--theme-text-secondary); margin-top: 4px; padding: 0 4px; text-align: right; }
.message.user .message-time { color: rgba(255, 255, 255, 0.8); }

/* 🔑 ========== Markdown 容器通用样式（普通消息 + 思考预览） ========== */
.message-text .markdown-container,
.thinking-preview .markdown-container {
  line-height: 1.6;
  font-size: 14px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  white-space: normal;
  word-wrap: break-word;
}

/* 助手消息的 Markdown 颜色 */
.message.assistant .message-text .markdown-container {
  color: #1f2933;
}

/* 用户消息的 Markdown 颜色（深色背景适配） */
.message.user .message-text .markdown-container {
  color: white;
}

/* 🔑 统一块级元素间距：确保 1./。开头也有标准间距 */
.message-text .markdown-container > *,
.thinking-preview .markdown-container > * {
  margin: 0;
}
.message-text .markdown-container > * + *,
.thinking-preview .markdown-container > * + * {
  margin-top: 12px;
}

/* 🔑 标题样式 */
.message-text .markdown-container h1,
.message-text .markdown-container h2,
.message-text .markdown-container h3,
.message-text .markdown-container h4,
.message-text .markdown-container h5,
.message-text .markdown-container h6 {
  margin: 16px 0 8px 0;
  font-weight: 600;
  line-height: 1.4;
}
.message-text .markdown-container h1 { font-size: 1.5em; }
.message-text .markdown-container h2 {
  font-size: 1.3em;
  border-bottom: 1px solid #eaecef;
  padding-bottom: 0.3em;
}
.message-text .markdown-container h3 { font-size: 1.2em; }
.message-text .markdown-container h4 { font-size: 1.1em; }

/* 用户消息标题颜色适配 */
.message.user .message-text .markdown-container h1,
.message.user .message-text .markdown-container h2,
.message.user .message-text .markdown-container h3,
.message.user .message-text .markdown-container h4,
.message.user .message-text .markdown-container h5,
.message.user .message-text .markdown-container h6 {
  color: white;
  border-bottom-color: rgba(255, 255, 255, 0.3);
}

/* 🔑 段落样式（确保 。开头也有间距） */
.message-text .markdown-container p,
.thinking-preview .markdown-container p {
  margin: 8px 0 !important;
  line-height: 1.6;
  text-align: justify;
  text-justify: inter-ideograph;
}
.message-text .markdown-container > p:first-child,
.thinking-preview .markdown-container > p:first-child {
  margin-top: 4px !important;
}
.message-text .markdown-container li > p,
.thinking-preview .markdown-container li > p {
  margin: 0 !important;
  display: inline;
}

/* 🔑 ========== 列表样式（核心修复：嵌套缩进累加） ========== */
/* 首层列表 */
.message-text .markdown-container ol,
.message-text .markdown-container ul,
.thinking-preview .markdown-container ol,
.thinking-preview .markdown-container ul {
  margin: 8px 0 !important;
  padding-left: 24px !important;
  list-style-position: outside !important;
}

/* 🔑 嵌套列表：每层额外 +20px 缩进 */
.message-text .markdown-container ol ol,
.message-text .markdown-container ol ul,
.message-text .markdown-container ul ol,
.message-text .markdown-container ul ul,
.thinking-preview .markdown-container ol ol,
.thinking-preview .markdown-container ol ul,
.thinking-preview .markdown-container ul ol,
.thinking-preview .markdown-container ul ul {
  padding-left: 44px !important;
}

/* 🔑 三级嵌套 */
.message-text .markdown-container ol ol ol,
.message-text .markdown-container ol ol ul,
.message-text .markdown-container ol ul ol,
.message-text .markdown-container ol ul ul,
.message-text .markdown-container ul ol ol,
.message-text .markdown-container ul ol ul,
.message-text .markdown-container ul ul ol,
.message-text .markdown-container ul ul ul {
  padding-left: 64px !important;
}

/* 🔑 列表项样式 - 🔑 关键：给 li 添加左边距 */
.message-text .markdown-container li,
.thinking-preview .markdown-container li {
  margin: 4px 0 4px 12px !important;  /* 🔑 左边距 12px */
  line-height: 1.6;
}

/* 🔑 有序列表数字样式 */
.message-text .markdown-container ol,
.thinking-preview .markdown-container ol {
  counter-reset: item;
}
.message-text .markdown-container ol li::marker,
.thinking-preview .markdown-container ol li::marker {
  font-weight: 600;
  color: #409eff;
}

/* 🔑 代码块样式 */
.message-text .markdown-container pre,
.thinking-preview .markdown-container pre {
  margin: 12px 0;
  padding: 12px 16px;
  background: rgba(0, 0, 0, 0.04);
  border-radius: 6px;
  overflow: auto;
  border: 1px solid rgba(0, 0, 0, 0.08);
}
.message-text .markdown-container code,
.thinking-preview .markdown-container code {
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 0.95em;
  background: rgba(175, 184, 193, 0.2);
  padding: 2px 4px;
  border-radius: 3px;
}
.message-text .markdown-container pre code,
.thinking-preview .markdown-container pre code {
  background: transparent;
  padding: 0;
  font-size: 0.9em;
}

/* 用户消息代码块适配 */
.message.user .message-text .markdown-container pre {
  background: rgba(0, 0, 0, 0.2);
  border-color: rgba(255, 255, 255, 0.2);
}
.message.user .message-text .markdown-container code {
  background: rgba(255, 255, 255, 0.2);
}

/* 🔑 引用块 */
.message-text .markdown-container blockquote,
.thinking-preview .markdown-container blockquote {
  margin: 12px 0;
  padding: 8px 16px;
  border-left: 4px solid #409eff;
  background: #f8f9fa;
  border-radius: 0 4px 4px 0;
  color: #606266;
}
.message.user .message-text .markdown-container blockquote {
  border-left-color: #66b1ff;
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.9);
}

/* 🔑 表格 */
.message-text .markdown-container table,
.thinking-preview .markdown-container table {
  margin: 12px 0;
  border-collapse: collapse;
  width: 100%;
  font-size: 13px;
}
.message-text .markdown-container table th,
.message-text .markdown-container table td,
.thinking-preview .markdown-container table th,
.thinking-preview .markdown-container table td {
  padding: 8px 12px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  text-align: left;
}
.message-text .markdown-container table th,
.thinking-preview .markdown-container table th {
  background: rgba(0, 0, 0, 0.03);
  font-weight: 600;
}
.message.user .message-text .markdown-container table th {
  background: rgba(0, 0, 0, 0.2);
}
.message.user .message-text .markdown-container table td,
.message.user .message-text .markdown-container table th {
  border-color: rgba(255, 255, 255, 0.2);
}

/* 🔑 链接 */
.message-text .markdown-container a,
.thinking-preview .markdown-container a {
  color: #409eff;
  text-decoration: none;
}
.message-text .markdown-container a:hover,
.thinking-preview .markdown-container a:hover {
  text-decoration: underline;
}

/* 🔑 图片 */
.message-text .markdown-container img,
.thinking-preview .markdown-container img {
  max-width: 100%;
  height: auto;
  border-radius: 4px;
  margin: 8px 0;
}

/* 🔑 水平线 */
.message-text .markdown-container hr,
.thinking-preview .markdown-container hr {
  margin: 16px 0;
  border: none;
  border-top: 1px solid #e1e4e8;
}

/* 🔑 ========== 思考预览专用样式 ========== */
.thinking-preview .markdown-container {
  color: #303133;  /* 🔑 与最终消息保持一致 */
  white-space: normal;  /* 🔑 移除 pre-wrap，使用正常换行 */
  font-size: 14px;  /* 🔑 明确字体大小 */
  line-height: 1.6;  /* 🔑 与最终消息一致 */
}
.thinking-preview .markdown-container h1,
.thinking-preview .markdown-container h2,
.thinking-preview .markdown-container h3,
.thinking-preview .markdown-container h4,
.thinking-preview .markdown-container h5,
.thinking-preview .markdown-container h6 {
  margin: 16px 0 8px 0;  /* 🔑 与最终消息一致 */
  font-weight: 600;
  line-height: 1.4;
}
.thinking-preview .markdown-container h1 { font-size: 1.5em; }
.thinking-preview .markdown-container h2 { font-size: 1.3em; border-bottom: 1px solid #eaecef; padding-bottom: 0.3em; }
.thinking-preview .markdown-container h3 { font-size: 1.2em; }
.thinking-preview .markdown-container h4 { font-size: 1.1em; }

/* ========== 输入区域样式 ========== */
.input-area { background: var(--theme-header-bg); border-radius: 8px; padding: 16px; border: 1px solid rgba(0, 0, 0, 0.1); }
.input-controls { margin-bottom: 12px; border-bottom: 1px solid rgba(0, 0, 0, 0.06); }
.input-controls-header { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; cursor: pointer; user-select: none; color: var(--theme-text-secondary); font-size: 14px; }
.input-controls-header:hover { color: var(--theme-primary); }
.input-controls-title { font-weight: 500; }
.input-controls-toggle-btn { flex-shrink: 0; }
.input-controls-toggle-btn .input-controls-toggle-icon { margin-right: 4px; transition: transform 0.2s ease; }
.input-controls-toggle-btn.collapsed .input-controls-toggle-icon { transform: rotate(-90deg); }
.input-controls-body { padding-bottom: 12px; }
.switch-group { display: flex; flex-wrap: wrap; gap: 20px; align-items: center; }
.switch-item { display: flex; align-items: center; gap: 8px; }
.switch-label { font-size: 14px; color: var(--theme-text-secondary); }
.send-button { width: 48px; height: 48px; }
.stop-button-inline { width: 48px; height: 48px; }
.input-container { display: flex; gap: 12px; align-items: flex-end; }
.hint { font-size: 12px; color: var(--theme-text-secondary); }

/* ========== Markdown 报告样式 ========== */
.markdown-report-message { background: var(--theme-header-bg); border: 1px solid rgba(0, 0, 0, 0.08); border-radius: 12px; padding: 16px; margin-bottom: 16px; }
.markdown-report-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid rgba(0, 0, 0, 0.06); }
.markdown-report-content { margin-top: 16px; }
.report-info { display: flex; align-items: center; gap: 12px; color: var(--theme-primary); font-size: 16px; font-weight: 500; }
.report-format-inline { margin-left: 8px; }

/* 报告 Markdown 容器 - 与普通消息对齐 */
.markdown-report-content { line-height: 1.6; color: var(--theme-text-primary); font-size: 14px; }
.markdown-report-content .markdown-container {
  line-height: 1.4;
  white-space: normal;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  font-size: 14px;
}
.markdown-report-content pre { background: rgba(0, 0, 0, 0.04); padding: 10px 12px; border-radius: 6px; overflow: auto; margin: 0; border: none; }
.markdown-report-content code { font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace; background: transparent; padding: 0; }

/* ========== 报告全屏样式 ========== */
.report-fullscreen-overlay { position: fixed; inset: 0; z-index: 9999; background: rgba(0, 0, 0, 0.7); display: flex; align-items: center; justify-content: center; padding: 24px; }
.report-fullscreen-container { width: 100%; max-width: 1200px; height: 90vh; background: var(--theme-header-bg); border-radius: 12px; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3); }
.report-fullscreen-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 24px; border-bottom: 1px solid rgba(0, 0, 0, 0.08); background: rgba(0, 0, 0, 0.02); flex-shrink: 0; }
.report-fullscreen-title { font-size: 18px; font-weight: 600; color: var(--theme-text-primary); }
.report-fullscreen-close { flex-shrink: 0; }
.report-fullscreen-content { flex: 1; overflow: auto; padding: 24px; }
.report-fullscreen-body { min-height: 100%; }
.report-fullscreen-body .markdown-container { line-height: 1.4; white-space: normal; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; font-size: 14px; }
.report-fullscreen-body pre { background: rgba(0, 0, 0, 0.04); padding: 10px 12px; border-radius: 6px; overflow: auto; margin: 0; border: none; }
.report-fullscreen-body code { font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace; background: transparent; padding: 0; }

/* ========== 思考状态样式 ========== */
.thinking-state { animation: fadeIn 0.3s ease; }
@keyframes fadeIn { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
.thinking-avatar { position: relative; }
.avatar-pulse { animation: pulse 2s infinite ease-in-out; box-shadow: 0 0 0 0 var(--theme-primary); }
@keyframes pulse { 0% { box-shadow: 0 0 0 0 var(--theme-primary); } 70% { box-shadow: 0 0 0 10px transparent; } 100% { box-shadow: 0 0 0 0 transparent; } }
.thinking-container { padding: 12px 16px; background: var(--theme-header-bg); border-radius: 12px; border: 1px solid rgba(0, 0, 0, 0.08); max-width: 100%; min-height: 40px; }
.thinking-preview { font-size: 14px; color: var(--theme-text-secondary); line-height: 1.5; white-space: pre-wrap; word-break: break-word; display: flex; align-items: flex-start; gap: 4px; }
.preview-text { flex: 1; }
.typing-cursor { color: var(--theme-primary); font-weight: bold; animation: blink 1s infinite; flex-shrink: 0; }
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
.thinking-placeholder { display: flex; align-items: center; gap: 8px; color: var(--theme-text-secondary); font-size: 14px; }
@media (max-width: 768px) { .thinking-preview { font-size: 13px; } .thinking-placeholder { font-size: 13px; } }
</style>