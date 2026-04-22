<!--
 * AgentScope 智能体详情页
 * 对接 data-agent-scope 后端
-->

<template>
  <BaseLayout>
    <el-container style="margin-top: 20px; gap: 10px">
      <!-- Header -->
      <el-header style="background-color: white; margin-bottom: 20px">
        <el-row :gutter="20" align="middle">
          <el-col :span="1">
            <el-button type="primary" :icon="ArrowLeft" @click="goBack" circle />
          </el-col>
          <el-col :span="1">
            <el-avatar :size="48" :src="agent.avatar">
              {{ agent.name }}
            </el-avatar>
          </el-col>
          <el-col :span="20">
            <h2>{{ agent.name }}</h2>
            <el-tag :type="getStatusType(agent.status)" size="large">
              {{ getStatusText(agent.status) }}
            </el-tag>
            <el-button
              v-if="agent.status === 'draft'"
              type="success"
              size="small"
              style="margin-left: 10px"
              @click="handlePublish"
            >
              发布
            </el-button>
            <el-button
              v-if="agent.status === 'published'"
              type="warning"
              size="small"
              style="margin-left: 10px"
              @click="handleOffline"
            >
              下线
            </el-button>
            <el-button
              v-if="agent.status === 'offline'"
              type="primary"
              size="small"
              style="margin-left: 10px"
              @click="handleRepublish"
            >
              重新发布
            </el-button>
          </el-col>
        </el-row>
        <el-divider />
      </el-header>

      <el-container style="gap: 10px">
        <!-- 左侧菜单 -->
        <el-aside width="220px" style="background-color: white">
          <el-menu :default-active="activeMenuIndex" @select="handleMenuSelect">
            <el-menu-item-group title="配置">
              <el-menu-item index="basic">
                <el-icon><InfoFilled /></el-icon>
                基本信息
              </el-menu-item>
              <el-menu-item index="prompt">
                <el-icon><ChatLineSquare /></el-icon>
                Prompt配置
              </el-menu-item>
              <el-menu-item index="knowledge">
                <el-icon><Document /></el-icon>
                智能体知识配置
              </el-menu-item>
              <el-menu-item index="tools">
                <el-icon><SetUp /></el-icon>
                工具配置
              </el-menu-item>
            </el-menu-item-group>
            <el-menu-item-group title="操作">
              <el-menu-item index="go-run">
                <el-icon><VideoPlay /></el-icon>
                前往运行
              </el-menu-item>
              <el-menu-item index="access-api">
                <el-icon><Connection /></el-icon>
                访问API
              </el-menu-item>
            </el-menu-item-group>
          </el-menu>
        </el-aside>

        <!-- 右侧内容 -->
        <el-main style="background-color: white; padding: 0">
          <!-- 基本信息 -->
          <AgentBaseSetting
            v-if="activeMenuIndex === 'basic'"
            :agent="agent"
            :show-delete="true"
            @update="handleUpdate"
            @delete="handleDelete"
          />

          <!-- Prompt配置 -->
          <AgentPromptConfig
            v-else-if="activeMenuIndex === 'prompt'"
            :agent-id="agent.id"
            :agent-prompt="agent.prompt"
          />

          <!-- 智能体知识配置 -->
          <AgentScopeKnowledgeConfig
            v-else-if="activeMenuIndex === 'knowledge'"
            :agent-id="agent.id"
          />

          <!-- 工具配置 -->
          <ToolConfig
            v-else-if="activeMenuIndex === 'tools'"
            :agent-id="agent.id"
            :tool-names="agent.toolNames || ''"
            @save="handleToolSave"
          />

          <!-- 访问API -->
          <AgentScopeAccessApi
            v-else-if="activeMenuIndex === 'access-api'"
            :agent-id="agent.id"
          />

          <!-- 空状态 -->
          <div v-else style="padding: 40px; text-align: center; color: #909399">
            请选择左侧菜单
          </div>
        </el-main>
      </el-container>
    </el-container>
  </BaseLayout>
</template>

<script lang="ts">
  import { ref, onMounted } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage } from 'element-plus';
  import { ArrowLeft, InfoFilled, ChatLineSquare, VideoPlay, Connection, Document, SetUp } from '@element-plus/icons-vue';
  import BaseLayout from '@/layouts/BaseLayout.vue';
  import AgentBaseSetting from '@/components/agent/BaseSetting.vue';
  import AgentPromptConfig from '@/components/agent/PromptConfig.vue';
  import AgentAccessApi from '@/components/agent/AccessApi.vue';
  import AgentScopeAccessApi from '@/components/agent/AgentScopeAccessApi.vue';
  import AgentScopeKnowledgeConfig from '@/components/agent/AgentScopeKnowledgeConfig.vue';
  import ToolConfig from '@/components/agent/ToolConfig.vue';
  import { agentScopeApi, AgentScope } from '@/services/agentScope';

  export default {
    name: 'AgentScopeDetail',
    components: {
      BaseLayout,
      AgentBaseSetting,
      AgentPromptConfig,
      AgentAccessApi,
      AgentScopeAccessApi,
      AgentScopeKnowledgeConfig,
      ToolConfig,
      ArrowLeft,
      InfoFilled,
      ChatLineSquare,
      VideoPlay,
      Connection,
      Document,
      SetUp,
    },
    setup() {
      const router = useRouter();
      const activeMenuIndex = ref('basic');
      const agent = ref<AgentScope>({
        id: 0,
        name: '加载中...',
        description: '',
        avatar: '',
        status: 'draft',
        prompt: '',
        category: '',
        tags: '',
        createTime: '',
        updateTime: '',
      });

      const loadAgent = async () => {
        const id = parseInt(router.currentRoute.value.params.id as string);
        if (!id) {
          ElMessage.error('无效的 Agent ID');
          return;
        }

        try {
          const response = await agentScopeApi.get(id);
          agent.value = response.data?.data || response.data || response;
        } catch (error) {
          ElMessage.error('加载失败');
          console.error('加载 Agent 失败:', error);
        }
      };

      const handleMenuSelect = (index: string) => {
        activeMenuIndex.value = index;
        if (index === 'go-run') {
          router.push(`/agent-scope/${agent.value.id}/run`);
        }
      };

      const handleUpdate = async (updatedAgent: any) => {
        try {
          await agentScopeApi.update(agent.value.id, updatedAgent);
          ElMessage.success('保存成功');
          await loadAgent();
        } catch (error) {
          ElMessage.error('保存失败');
        }
      };

      const handleDelete = async () => {
        try {
          await agentScopeApi.delete(agent.value.id);
          ElMessage.success('删除成功');
          router.push('/agent-scope');
        } catch (error) {
          ElMessage.error('删除失败');
        }
      };

      const handlePublish = async () => {
        try {
          await agentScopeApi.publish(agent.value.id);
          ElMessage.success('发布成功');
          await loadAgent();
        } catch (error) {
          ElMessage.error('发布失败');
        }
      };

      const handleOffline = async () => {
        try {
          await agentScopeApi.offline(agent.value.id);
          ElMessage.success('下线成功');
          await loadAgent();
        } catch (error) {
          ElMessage.error('下线失败');
        }
      };

      const handleRepublish = async () => {
        try {
          await agentScopeApi.republish(agent.value.id);
          ElMessage.success('重新发布成功');
          await loadAgent();
        } catch (error) {
          ElMessage.error('重新发布失败');
        }
      };

      const handleToolSave = async (toolNames: string) => {
        agent.value.toolNames = toolNames;
        await loadAgent();
      };

      const goBack = () => {
        router.push('/agent-scope');
      };

      const getStatusType = (status: string) => {
        return status === 'published' ? 'success' : status === 'offline' ? 'info' : 'warning';
      };

      const getStatusText = (status: string) => {
        return status === 'published' ? '已发布' : status === 'offline' ? '已下线' : '草稿';
      };

      onMounted(loadAgent);

      return {
        activeMenuIndex,
        agent,
        goBack,
        handleMenuSelect,
        handleUpdate,
        handleDelete,
        handlePublish,
        handleOffline,
        handleRepublish,
        handleToolSave,
        getStatusType,
        getStatusText,
      };
    },
  };
</script>

<style scoped>
  .el-aside {
    border-radius: 8px;
  }

  .el-main {
    border-radius: 8px;
  }
</style>
