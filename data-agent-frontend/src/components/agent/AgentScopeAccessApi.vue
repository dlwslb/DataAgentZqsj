<!--
 * AgentScope 访问 API 组件
-->

<template>
  <div class="access-api">
    <section class="section">
      <h3>访问 API Key</h3>
      <p class="desc">为该智能体生成并管理 API Key，用于外部系统访问。</p>

      <div class="card">
        <div class="row">
          <div class="label">API Key 状态</div>
          <el-switch
            v-model="apiKeyEnabled"
            :disabled="!apiKey"
            active-text="已启用"
            inactive-text="已禁用"
            @change="handleToggle"
          />
          <el-tag v-if="!apiKey" type="info" size="small" class="tag">未生成</el-tag>
        </div>

        <div class="row key-row">
          <div class="label">当前 Key</div>
          <el-input
            v-model="displayKey"
            class="key-input"
            readonly
            placeholder="尚未生成 API Key"
          />
          <el-button type="primary" @click="handleGenerate" :loading="loading.generate">
            {{ apiKey ? '重新生成' : '生成 Key' }}
          </el-button>
          <el-button @click="handleReset" :disabled="!apiKey" :loading="loading.reset">
            重置
          </el-button>
          <el-button @click="handleDelete" :disabled="!apiKey" :loading="loading.delete">
            删除
          </el-button>
          <el-button @click="handleCopy" :disabled="!apiKey || !canCopy">复制</el-button>
          <el-button @click="toggleMask" :disabled="!apiKey">
            {{ masked ? '显示' : '隐藏' }}
          </el-button>
        </div>

        <el-alert
          v-if="!canCopy && apiKey"
          type="info"
          :closable="false"
          show-icon
          title="为安全起见，已生成/重置时才显示完整 Key，之后仅显示掩码。如需复制请重新生成/重置。"
        />
      </div>
    </section>

    <section class="section">
      <h3>调用示例</h3>
      <p class="desc">使用 `X-API-Key` 请求头调用会话接口。</p>
      <el-tabs v-model="exampleTab">
        <el-tab-pane label="curl" name="curl">
          <pre class="code"><code>{{ curlExample }}</code></pre>
        </el-tab-pane>
        <el-tab-pane label="JavaScript" name="js">
          <pre class="code"><code>{{ jsExample }}</code></pre>
        </el-tab-pane>
        <el-tab-pane label="Python" name="py">
          <pre class="code"><code>{{ pyExample }}</code></pre>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<script lang="ts">
  import { defineComponent, ref, computed, onMounted } from 'vue';
  import { useRoute } from 'vue-router';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { agentScopeApi } from '@/services/agentScope';

  export default defineComponent({
    name: 'AgentScopeAccessApi',
    props: {
      agentId: {
        type: [Number, String],
        required: false,
        default: null,
      },
    },
    setup(props) {
      const route = useRoute();
      const loading = ref({
        fetch: false,
        generate: false,
        reset: false,
        delete: false,
        toggle: false,
      });
      const apiKey = ref<string | null>(null);
      const apiKeyEnabled = ref(false);
      const masked = ref(true);
      const canCopy = ref(false);
      const exampleTab = ref('curl');

      const resolvedAgentId = computed(() => {
        if (props.agentId !== null) {
          return Number(props.agentId);
        }
        return Number(route.params.id);
      });

      const displayKey = computed(() => {
        if (!apiKey.value) return '';
        if (masked.value) {
          return apiKey.value.substring(0, 4) + '***' + apiKey.value.substring(apiKey.value.length - 4);
        }
        return apiKey.value;
      });

      const baseUrl = computed(() => {
        return import.meta.env.VITE_AGENT_SCOPE_API_TARGET || 'http://localhost:58064';
      });

      const curlExample = computed(() => {
        return `curl -X POST "${baseUrl.value}/api/scope/agent/${resolvedAgentId.value}/chat" \\
  -H "Content-Type: application/json" \\
  -H "X-API-Key: ${apiKey.value || '<YOUR_API_KEY>'}" \\
  -d '{"message": "你好"}'`;
      });

      const jsExample = computed(() => {
        return `const response = await fetch(
  "${baseUrl.value}/api/scope/agent/${resolvedAgentId.value}/chat",
  {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-API-Key": "${apiKey.value || '<YOUR_API_KEY>'}",
    },
    body: JSON.stringify({ message: "你好" }),
  }
);
const data = await response.json();
console.log(data);`;
      });

      const pyExample = computed(() => {
        return `import requests

base_url = "${baseUrl.value}"
session_id = "<YOUR_SESSION_ID>"

headers = {
    "Content-Type": "application/json",
    "X-API-Key": "${apiKey.value || '<YOUR_API_KEY>'}",
}

response = requests.post(
    f"{base_url}/sessions/{session_id}/messages",
    headers=headers,
    json={"role": "user", "content": "你好", "messageType": "text"},
)
`;
      });

      const loadApiKey = async () => {
        loading.value.fetch = true;
        try {
          const res = await agentScopeApi.getApiKey(resolvedAgentId.value);
          apiKey.value = res?.data?.apiKey ?? res?.apiKey ?? null;
          apiKeyEnabled.value = Boolean(res?.data?.apiKeyEnabled ?? res?.apiKeyEnabled);
          masked.value = true;
          canCopy.value = false;
        } catch (e) {
          ElMessage.error('获取 API Key 失败');
        } finally {
          loading.value.fetch = false;
        }
      };

      const handleGenerate = async () => {
        loading.value.generate = true;
        try {
          const res = await agentScopeApi.generateApiKey(resolvedAgentId.value);
          apiKey.value = res.data?.apiKey ?? res.apiKey;
          apiKeyEnabled.value = Boolean(res.data?.apiKeyEnabled ?? res.apiKeyEnabled);
          masked.value = false;
          canCopy.value = true;
          ElMessage.success('已生成 API Key');
        } catch (e) {
          ElMessage.error('生成失败');
        } finally {
          loading.value.generate = false;
        }
      };

      const handleReset = async () => {
        if (!apiKey.value) {
          await handleGenerate();
          return;
        }
        loading.value.reset = true;
        try {
          const res = await agentScopeApi.resetApiKey(resolvedAgentId.value);
          apiKey.value = res.data?.apiKey ?? res.apiKey;
          apiKeyEnabled.value = Boolean(res.data?.apiKeyEnabled ?? res.apiKeyEnabled);
          masked.value = false;
          canCopy.value = true;
          ElMessage.success('已重置 API Key');
        } catch (e) {
          ElMessage.error('重置失败');
        } finally {
          loading.value.reset = false;
        }
      };

      const handleDelete = async () => {
        if (!apiKey.value) return;
        try {
          await ElMessageBox.confirm('确认删除当前 API Key？删除后需重新生成。', '提示', {
            confirmButtonText: '删除',
            cancelButtonText: '取消',
            type: 'warning',
          });
        } catch (e) {
          return;
        }

        loading.value.delete = true;
        try {
          await agentScopeApi.deleteApiKey(resolvedAgentId.value);
          apiKey.value = null;
          apiKeyEnabled.value = false;
          masked.value = true;
          canCopy.value = false;
          ElMessage.success('已删除 API Key');
        } catch (e) {
          ElMessage.error('删除失败');
        } finally {
          loading.value.delete = false;
        }
      };

      const handleCopy = async () => {
        if (!canCopy.value || !apiKey.value) {
          ElMessage.info('请重新生成或重置后复制完整 Key');
          return;
        }
        try {
          await navigator.clipboard.writeText(apiKey.value);
          ElMessage.success('已复制到剪贴板');
        } catch (e) {
          ElMessage.error('复制失败');
        }
      };

      const toggleMask = () => {
        if (!apiKey.value) return;
        masked.value = !masked.value;
      };

      const handleToggle = async (val: boolean) => {
        loading.value.toggle = true;
        try {
          await agentScopeApi.toggleApiKey(resolvedAgentId.value, val);
          apiKeyEnabled.value = val;
          masked.value = true;
          canCopy.value = false;
          ElMessage.success(val ? '已启用 API Key' : '已禁用 API Key');
        } catch (e) {
          apiKeyEnabled.value = !val;
          ElMessage.error('切换失败');
        } finally {
          loading.value.toggle = false;
        }
      };

      onMounted(() => {
        loadApiKey();
      });

      return {
        loading,
        apiKey,
        apiKeyEnabled,
        masked,
        canCopy,
        displayKey,
        exampleTab,
        curlExample,
        jsExample,
        pyExample,
        loadApiKey,
        handleGenerate,
        handleReset,
        handleDelete,
        handleCopy,
        toggleMask,
        handleToggle,
      };
    },
  });
</script>

<style scoped>
  .access-api {
    padding: 20px;
  }
  .section {
    margin-bottom: 30px;
  }
  .section h3 {
    margin: 0 0 8px;
    font-size: 16px;
    font-weight: 600;
  }
  .desc {
    margin: 0 0 16px;
    color: #909399;
    font-size: 14px;
  }
  .card {
    background: #f5f7fa;
    border-radius: 8px;
    padding: 20px;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 16px;
  }
  .key-row {
    flex-wrap: wrap;
  }
  .label {
    font-size: 14px;
    color: #606266;
    min-width: 80px;
  }
  .key-input {
    flex: 1;
    max-width: 400px;
  }
  .tag {
    margin-left: 8px;
  }
  .code {
    background: #1e1e1e;
    color: #d4d4d4;
    padding: 16px;
    border-radius: 8px;
    overflow-x: auto;
    font-size: 13px;
    line-height: 1.5;
    margin: 0;
  }
</style>
