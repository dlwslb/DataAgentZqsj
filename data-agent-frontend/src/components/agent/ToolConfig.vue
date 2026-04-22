<!--
 * AgentScope 工具配置组件
 * 显示已注册工具列表，支持勾选启用/禁用
-->
<template>
  <div class="tool-config">
    <div class="tool-config-header">
      <h3>工具配置</h3>
      <p>为智能体选择可调用的工具，未选择时默认使用全部工具</p>
    </div>

    <div v-if="loading" class="loading-state">
      <el-skeleton :rows="4" animated />
    </div>

    <div v-else-if="tools.length === 0" class="empty-state">
      <el-empty description="暂无可用工具">
        <template #image>
          <el-icon size="48"><SetUp /></el-icon>
        </template>
      </el-empty>
    </div>

    <template v-else>
      <!-- 操作栏 -->
      <div class="tool-toolbar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索工具名称..."
          prefix-icon="Search"
          clearable
          style="width: 240px"
        />
        <div class="toolbar-actions">
          <el-button size="small" @click="selectAll">全选</el-button>
          <el-button size="small" @click="deselectAll">清空</el-button>
          <el-tag type="info" size="small">已选 {{ selectedTools.length }} / {{ filteredTools.length }}</el-tag>
        </div>
      </div>

      <!-- 工具选择 -->
      <el-checkbox-group v-model="selectedTools" class="tool-checkbox-group">
        <el-checkbox
          v-for="tool in filteredTools"
          :key="tool.name"
          :label="tool.name"
          :value="tool.name"
          border
          class="tool-checkbox-item"
        >
          <div class="tool-info">
            <div class="tool-header">
              <span class="tool-name">{{ tool.name }}</span>
              <el-tag size="small" type="info">{{ tool.provider }}</el-tag>
              <el-tag size="small" type="success">{{ tool.params.length }} 参数</el-tag>
            </div>
            <div class="tool-desc">{{ tool.description }}</div>
          </div>
        </el-checkbox>
      </el-checkbox-group>
    </template>

    <div class="tool-actions">
      <el-button @click="selectAll">全选</el-button>
      <el-button @click="deselectAll">清空</el-button>
      <el-button type="primary" @click="saveConfig" :loading="saving">保存配置</el-button>
    </div>
  </div>
</template>

<script lang="ts">
  import { defineComponent, ref, onMounted, watch, computed } from 'vue';
  import { ElMessage } from 'element-plus';
  import { SetUp } from '@element-plus/icons-vue';
  import { agentScopeApi, ToolMeta } from '@/services/agentScope';

  export default defineComponent({
    name: 'ToolConfig',
    components: { SetUp },
    props: {
      agentId: {
        type: Number,
        required: true,
      },
      toolNames: {
        type: String,
        default: '',
      },
    },
    emits: ['save'],
    setup(props, { emit }) {
      const loading = ref(true);
      const saving = ref(false);
      const tools = ref<ToolMeta[]>([]);
      const selectedTools = ref<string[]>([]);
      const searchKeyword = ref('');

      const filteredTools = computed(() => {
        if (!searchKeyword.value.trim()) return tools.value;
        const keyword = searchKeyword.value.trim().toLowerCase();
        return tools.value.filter(
          (t) =>
            t.name.toLowerCase().includes(keyword) ||
            t.description.toLowerCase().includes(keyword) ||
            t.provider.toLowerCase().includes(keyword),
        );
      });

      const loadTools = async () => {
        loading.value = true;
        try {
          const response = await agentScopeApi.listTools();
          tools.value = response.data?.data || response.data || [];
        } catch (error) {
          ElMessage.error('加载工具列表失败');
          tools.value = [];
        } finally {
          loading.value = false;
        }
      };

      watch(
        () => props.toolNames,
        (val) => {
          if (val && val.trim()) {
            selectedTools.value = val.split(',').map((s) => s.trim()).filter(Boolean);
          } else {
            selectedTools.value = [];
          }
        },
        { immediate: true },
      );

      const selectAll = () => {
        selectedTools.value = filteredTools.value.map((t) => t.name);
      };

      const deselectAll = () => {
        selectedTools.value = [];
      };

      const saveConfig = async () => {
        saving.value = true;
        try {
          const toolNamesStr = selectedTools.value.join(',');
          await agentScopeApi.update(props.agentId, { toolNames: toolNamesStr });
          ElMessage.success('工具配置保存成功');
          emit('save', toolNamesStr);
        } catch (error) {
          ElMessage.error('保存失败');
        } finally {
          saving.value = false;
        }
      };

      onMounted(loadTools);

      return {
        loading,
        saving,
        tools,
        selectedTools,
        searchKeyword,
        filteredTools,
        selectAll,
        deselectAll,
        saveConfig,
      };
    },
  });
</script>

<style scoped>
  .tool-config {
    padding: 20px;
  }

  .tool-config-header {
    margin-bottom: 20px;
  }

  .tool-config-header h3 {
    font-size: 16px;
    font-weight: 600;
    color: #303133;
    margin: 0 0 8px 0;
  }

  .tool-config-header p {
    font-size: 13px;
    color: #909399;
    margin: 0;
  }

  .tool-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16px;
    gap: 12px;
    flex-wrap: wrap;
  }

  .toolbar-actions {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .tool-checkbox-group {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }

  .tool-checkbox-group .el-checkbox {
    margin-right: 0;
  }

  .tool-checkbox-item {
    height: auto !important;
    padding: 12px 16px !important;
  }

  .tool-checkbox-item :deep(.el-checkbox__label) {
    white-space: normal;
    line-height: 1.4;
  }

  .tool-info {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .tool-header {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  }

  .tool-name {
    font-family: 'Courier New', monospace;
    font-weight: 600;
    font-size: 13px;
  }

  .tool-desc {
    font-size: 12px;
    color: #909399;
    line-height: 1.4;
    word-break: break-word;
  }

  .tool-actions {
    display: flex;
    gap: 12px;
    justify-content: flex-end;
    margin-top: 20px;
    padding-top: 16px;
    border-top: 1px solid #ebeef5;
  }

  .loading-state,
  .empty-state {
    padding: 40px 20px;
  }
</style>
