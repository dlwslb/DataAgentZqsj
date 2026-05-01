<!--
 * AgentScope 知识配置组件
 * 基于 AgentKnowledgeConfig.vue 改造，对接 data-agent-scope 后端
-->

<template>
  <div style="padding: 20px; display: flex; gap: 20px">
    <!-- 左侧租户树 -->
    <div style="width: 250px; flex-shrink: 0">
      <el-card shadow="never" style="height: calc(100vh - 150px); overflow-y: auto">
        <template #header>
          <div style="display: flex; justify-content: space-between; align-items: center">
            <span>租户列表</span>
            <el-button size="small" @click="loadTenants" :icon="RefreshLeft" circle />
          </div>
        </template>
        <!-- 租户搜索框 -->
        <div style="padding: 0 10px 10px 10px">
          <el-input
            v-model="tenantSearchKeyword"
            placeholder="搜索租户"
            clearable
            size="small"
            :prefix-icon="Search"
          />
        </div>
        <el-tree
          :data="filteredTenantTree"
          node-key="id"
          default-expand-all
          :expand-on-click-node="false"
          @node-click="handleTenantClick"
        >
          <template #default="{ node, data }">
            <span class="custom-tree-node">
              <span>{{ data.label }}</span>
              <span v-if="selectedTenantId === data.id" style="color: #409eff">✓</span>
            </span>
          </template>
        </el-tree>
      </el-card>
    </div>

    <!-- 右侧知识列表 -->
    <div style="flex: 1">
      <div style="margin-bottom: 20px">
        <h2>智能体知识库</h2>
        <p style="color: #909399; font-size: 14px; margin-top: 5px">
          管理用于增强智能体能力的知识源。
        </p>
      </div>
      <el-divider />

    <div style="margin-bottom: 30px">
      <div style="display: flex; justify-content: space-between; align-items: center">
        <h3>知识列表</h3>
        <div style="display: flex; gap: 10px; align-items: center">
          <el-input
            v-model="queryParams.title"
            placeholder="请输入知识标题搜索"
            style="width: 300px"
            clearable
            @clear="handleSearch"
            @keyup.enter="handleSearch"
            size="large"
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-button
            @click="toggleFilter"
            size="large"
            :type="filterVisible ? 'primary' : ''"
            round
            :icon="FilterIcon"
          >
            筛选
          </el-button>
          <el-button @click="openCreateDialog" size="large" type="primary" round :icon="Plus">
            添加知识
          </el-button>
        </div>
      </div>
    </div>

    <!-- 筛选面板 -->
    <el-collapse-transition>
      <div v-show="filterVisible" style="margin-bottom: 20px">
        <el-card shadow="never">
          <el-form :inline="true" :model="queryParams">
            <el-form-item label="知识类型">
              <el-select
                v-model="queryParams.type"
                placeholder="全部类型"
                clearable
                @change="handleSearch"
                style="width: 150px"
              >
                <el-option label="文档" value="DOCUMENT" />
                <el-option label="问答对" value="QA" />
                <el-option label="常见问题" value="FAQ" />
              </el-select>
            </el-form-item>
            <el-form-item label="处理状态">
              <el-select
                v-model="queryParams.embeddingStatus"
                placeholder="全部状态"
                clearable
                @change="handleSearch"
                style="width: 150px"
              >
                <el-option label="COMPLETED" value="COMPLETED" />
                <el-option label="PROCESSING" value="PROCESSING" />
                <el-option label="FAILED" value="FAILED" />
                <el-option label="PENDING" value="PENDING" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button @click="clearFilters" :icon="RefreshLeft">清空筛选</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </div>
    </el-collapse-transition>

    <!-- 表格区域 -->
    <el-table :data="knowledgeList" style="width: 100%" border v-loading="loading">
      <el-table-column prop="title" label="标题" min-width="150px" />
      <el-table-column prop="type" label="类型" min-width="100px">
        <template #default="scope">
          <span v-if="scope.row.type === 'DOCUMENT'">文档</span>
          <span v-else-if="scope.row.type === 'QA'">问答对</span>
          <span v-else-if="scope.row.type === 'FAQ'">常见问题</span>
          <span v-else>{{ scope.row.type }}</span>
        </template>
      </el-table-column>
      <el-table-column label="分块策略" min-width="100px">
        <template #default="scope">
          <el-tag v-if="scope.row.splitterType === 'token'" type="primary" size="small" round>
            Token
          </el-tag>
          <el-tag v-else-if="scope.row.splitterType === 'recursive'" type="success" size="small" round>
            递归
          </el-tag>
          <el-tag v-else-if="scope.row.splitterType === 'sentence'" type="warning" size="small" round>
            句子
          </el-tag>
          <el-tag v-else-if="scope.row.splitterType === 'paragraph'" type="success" size="small" round>
            段落
          </el-tag>
          <el-tag v-else-if="scope.row.splitterType === 'semantic'" type="info" size="small" round>
            语义
          </el-tag>
          <span v-else style="color: #909399; font-size: 12px">-</span>
        </template>
      </el-table-column>
      <el-table-column label="处理状态" min-width="120px">
        <template #default="scope">
          <el-tag v-if="scope.row.embeddingStatus === 'COMPLETED'" type="success" round>
            {{ scope.row.embeddingStatus }}
          </el-tag>
          <el-tag v-else-if="scope.row.embeddingStatus === 'PROCESSING'" type="primary" round>
            {{ scope.row.embeddingStatus }}
          </el-tag>
          <el-tag v-else-if="scope.row.embeddingStatus === 'FAILED'" type="danger" round>
            {{ scope.row.embeddingStatus }}
          </el-tag>
          <el-tag v-else type="info" round>
            {{ scope.row.embeddingStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="召回状态" min-width="100px">
        <template #default="scope">
          <el-tag :type="scope.row.isRecall ? 'success' : 'info'" round>
            {{ scope.row.isRecall ? '已召回' : '未召回' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="200px">
        <template #default="scope">
          <el-button @click="editKnowledge(scope.row)" size="small" type="primary" round plain>
            编辑
          </el-button>
          <el-button
            v-if="scope.row.embeddingStatus === 'FAILED'"
            @click="handleRetry(scope.row)"
            size="small"
            type="info"
            round
            plain
          >
            重试
          </el-button>
          <el-button
            v-if="scope.row.isRecall"
            @click="toggleStatus(scope.row)"
            size="small"
            type="warning"
            round
            plain
          >
            取消召回
          </el-button>
          <el-button
            v-else
            @click="toggleStatus(scope.row)"
            size="small"
            type="success"
            round
            plain
          >
            召回
          </el-button>
          <el-button @click="deleteKnowledge(scope.row)" size="small" type="danger" round plain>
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    </div>  <!-- 右侧知识列表结束 -->
  </div>  <!-- 主容器结束 -->

  <!-- 添加/编辑知识弹窗 -->
  <el-dialog
    v-model="dialogVisible"
    :title="isEdit ? '编辑知识' : '添加新知识'"
    width="700"
    :close-on-click-modal="false"
  >
    <el-form :model="knowledgeForm" label-width="100px">
      <!-- 租户和用户选择（同一行） -->
      <el-row :gutter="10">
        <el-col :span="12">
          <el-form-item label="所属租户" prop="tenantId">
            <el-select
              v-model="knowledgeForm.tenantId"
              placeholder="请选择租户（可选）"
              clearable
              style="width: 100%"
              @change="handleTenantChange"
            >
              <el-option
                v-for="tenant in tenants"
                :key="tenant.id"
                :label="tenant.name"
                :value="tenant.id"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="指定人员" prop="userId">
            <el-select
              v-model="knowledgeForm.userId"
              placeholder="请选择人员（可选）"
              clearable
              style="width: 100%"
            >
              <el-option
                v-for="user in users"
                :key="user.id"
                :label="user.nickname || user.username"
                :value="user.id"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="知识类型" prop="type" required>
        <el-radio-group v-model="knowledgeForm.type" :disabled="isEdit">
          <el-radio-button value="DOCUMENT">文档 (文件上传)</el-radio-button>
          <el-radio-button value="QA">问答对 (Q&A)</el-radio-button>
          <el-radio-button value="FAQ">常见问题 (FAQ)</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <!-- 知识类型说明 -->
      <el-form-item v-if="knowledgeForm.type === 'QA'">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px">
          <template #title>
            <div style="line-height: 1.6">
              请录入具体的'分析需求'作为问题,并在答案中写出详细的'思考步骤'与'数据查找逻辑',以此教会 AI 如何拆解任务。
            </div>
          </template>
        </el-alert>
      </el-form-item>

      <el-form-item v-if="knowledgeForm.type === 'FAQ'">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px">
          <template #title>
            <div style="line-height: 1.6">
              请针对特定的'业务术语'、'指标口径'或'常见歧义'进行提问和定义,以此统一 AI 的判断标准。
            </div>
          </template>
        </el-alert>
      </el-form-item>

      <el-form-item v-if="knowledgeForm.type === 'DOCUMENT'">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px">
          <template #title>
            <div style="line-height: 1.6">
              请上传完整的'数据库表结构'、'码表映射字典'或'业务背景说明',供 AI 在分析时检索字段含义和数据关系。
            </div>
          </template>
        </el-alert>
      </el-form-item>

      <!-- 知识标题 -->
      <el-form-item label="知识标题" prop="title" required>
        <el-input v-model="knowledgeForm.title" placeholder="为这份知识起一个易于识别的名称" />
      </el-form-item>

      <!-- 分块策略选择 (仅文档类型) -->
      <el-form-item
        v-if="knowledgeForm.type === 'DOCUMENT' && !isEdit"
        label="分块策略"
        prop="splitterType"
      >
        <el-select
          v-model="knowledgeForm.splitterType"
          placeholder="请选择分块策略"
          style="width: 100%"
        >
          <el-option label="Token 分块" value="token" />
          <el-option label="递归分块" value="recursive" />
          <el-option label="句子分块" value="sentence" />
          <el-option label="段落分块" value="paragraph" />
          <el-option label="语义分块" value="semantic" />
        </el-select>
        <div style="margin-top: 8px; font-size: 12px; color: #909399">
          <div v-if="knowledgeForm.splitterType === 'token'">
            ⚡ 速度最快，按固定 token 数切分，适合代码和日志
          </div>
          <div v-else-if="knowledgeForm.splitterType === 'recursive'">
            📚 平衡之选，保留文档结构（段落、章节），适合技术文档
          </div>
          <div v-else-if="knowledgeForm.splitterType === 'sentence'">
            ✨ 保证句子完整性，语义不被截断，适合新闻和文章
          </div>
          <div v-else-if="knowledgeForm.splitterType === 'paragraph'">
            📝 按自然段落分块，保留段落完整性，适合博客、书籍等
          </div>
          <div v-else-if="knowledgeForm.splitterType === 'semantic'">
            🧠 基于语义相似度智能分块，自动识别主题边界，适合论文和长文
          </div>
        </div>
      </el-form-item>

      <!-- 文件上传区域 -->
      <el-form-item v-if="knowledgeForm.type === 'DOCUMENT'" label="上传文件" required>
        <div v-if="!isEdit" style="width: 100%">
          <el-upload
            :auto-upload="false"
            :limit="1"
            :on-change="handleFileChange"
            :on-remove="() => (fileList = [])"
            :file-list="fileList"
            drag
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">
              拖拽文件到此处或
              <em>点击选择文件</em>
            </div>
            <template #tip>
              <div class="el-upload__tip">
                支持 PDF, DOCX, TXT, MD
                等格式(注意：PDF,TXT等纯文本文件如果不是UTF-8编码可能导致读取失败)
              </div>
              <div v-if="fileList.length > 0" class="el-upload__tip" style="color: #409eff">
                文件大小: {{ formatFileSize(fileList[0].size) }}
              </div>
            </template>
          </el-upload>
        </div>
        <div v-else>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="文档类型知识不支持修改文件内容，如需修改请删除后重新创建"
          />
        </div>
      </el-form-item>

      <!-- Q&A / FAQ 输入区域 -->
      <template v-if="knowledgeForm.type === 'QA' || knowledgeForm.type === 'FAQ'">
        <el-form-item label="问题" prop="question" required>
          <el-input
            v-model="knowledgeForm.question"
            type="textarea"
            :rows="2"
            placeholder="输入用户可能会问的问题..."
          />
        </el-form-item>
        <el-form-item label="答案" prop="content" required>
          <el-input
            v-model="knowledgeForm.content"
            type="textarea"
            :rows="5"
            placeholder="输入标准答案..."
          />
        </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <div style="text-align: right">
        <el-button @click="closeDialog">取消</el-button>
        <el-button type="primary" @click="saveKnowledge" :loading="saveLoading">
          {{ isEdit ? '更新' : '添加并处理' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script lang="ts">
  import { defineComponent, ref, onMounted, Ref, reactive, computed } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    Plus,
    Search,
    Filter as FilterIcon,
    RefreshLeft,
    UploadFilled,
  } from '@element-plus/icons-vue';
  import { agentScopeApi, AgentScopeKnowledge, TenantInfo, UserInfo } from '@/services/agentScope';

  export default defineComponent({
    name: 'AgentScopeKnowledgeConfig',
    components: {
      Search,
      RefreshLeft,
      FilterIcon,
      UploadFilled,
    },
    props: {
      agentId: {
        type: Number,
        required: true,
      },
    },
    setup(props) {
      const knowledgeList: Ref<AgentScopeKnowledge[]> = ref([]);
      const loading: Ref<boolean> = ref(false);
      const dialogVisible: Ref<boolean> = ref(false);
      const isEdit: Ref<boolean> = ref(false);
      const saveLoading: Ref<boolean> = ref(false);
      const currentEditId: Ref<number | null> = ref(null);
      const filterVisible: Ref<boolean> = ref(false);
      const fileList: Ref<any[]> = ref([]);
      const currentFile: Ref<File | null> = ref(null);

      // 租户和用户相关
      const tenants: Ref<TenantInfo[]> = ref([]);
      const users: Ref<UserInfo[]> = ref([]);
      const selectedTenantId: Ref<number | null> = ref(null);
      const tenantTree: Ref<any[]> = ref([]);
      const tenantSearchKeyword: Ref<string> = ref('');

      // 过滤后的租户树
      const filteredTenantTree = computed(() => {
        if (!tenantSearchKeyword.value) {
          return tenantTree.value;
        }
        const keyword = tenantSearchKeyword.value.toLowerCase();
        return tenantTree.value.filter(tenant => 
          tenant.label.toLowerCase().includes(keyword)
        );
      });

      const queryParams = reactive({
        title: '',
        type: '',
        embeddingStatus: '',
      });

      const knowledgeForm: Ref<any> = ref({
        agentId: props.agentId,
        tenantId: null,
        userId: null,
        title: '',
        type: 'DOCUMENT',
        question: '',
        content: '',
        isRecall: 1,
        splitterType: 'token',
      });

      // ==================== 租户和用户管理 ====================

      /**
       * 加载租户列表
       */
      const loadTenants = async () => {
        try {
          const result = await agentScopeApi.getTenants();
          tenants.value = result.data?.data || result.data || [];
          
          // 构建租户树，在顶部添加"公共"选项
          tenantTree.value = [
            {
              id: null,  // 使用 null 表示公共（未绑定租户）
              label: '公共',
              isPublic: true,
            },
            ...tenants.value.map(tenant => ({
              id: tenant.id,
              label: tenant.name,
              isPublic: false,
            }))
          ];
        } catch (error) {
          ElMessage.error('加载租户列表失败');
        }
      };

      /**
       * 点击租户节点
       */
      const handleTenantClick = (data: any) => {
        selectedTenantId.value = data.id;
        loadKnowledgeList();
      };

      /**
       * 租户变化时加载用户列表
       */
      const handleTenantChange = async (tenantId: number | null) => {
        // 清空已选择的人员
        knowledgeForm.value.userId = null;
        
        // 如果租户ID为空，清空用户列表并返回
        if (!tenantId) {
          users.value = [];
          return;
        }
        
        try {
          const result = await agentScopeApi.getUsersByTenant(tenantId);
          users.value = result.data?.data || result.data || [];
        } catch (error) {
          ElMessage.error('加载用户列表失败');
          users.value = [];
        }
      };

      // ==================== 知识库管理 ====================

      const loadKnowledgeList = async () => {
        loading.value = true;
        try {
          let result;
          
          // 如果选择了租户，按租户查询
          if (selectedTenantId.value !== null && selectedTenantId.value !== undefined) {
            result = await agentScopeApi.knowledge.listByTenant(selectedTenantId.value, {
              type: queryParams.type || undefined,
              embeddingStatus: queryParams.embeddingStatus || undefined,
            });
          } else if (selectedTenantId.value === null) {
            // 如果 selectedTenantId 为 null，表示选择的是“公共”
            result = await agentScopeApi.knowledge.listPublicKnowledge({
              type: queryParams.type || undefined,
              embeddingStatus: queryParams.embeddingStatus || undefined,
            });
          } else {
            // 否则按 Agent 查询（默认行为）
            result = await agentScopeApi.knowledge.list(props.agentId, {
              type: queryParams.type || undefined,
              embeddingStatus: queryParams.embeddingStatus || undefined,
            });
          }
          
          knowledgeList.value = result.data?.data || result.data || result || [];
        } catch (error) {
          ElMessage.error('加载知识列表失败');
        } finally {
          loading.value = false;
        }
      };

      const handleSearch = () => {
        loadKnowledgeList();
      };

      const toggleFilter = () => {
        filterVisible.value = !filterVisible.value;
      };

      const clearFilters = () => {
        queryParams.type = '';
        queryParams.embeddingStatus = '';
        loadKnowledgeList();
      };

      const openCreateDialog = () => {
        isEdit.value = false;
        dialogVisible.value = true;
        resetForm();
      };

      const closeDialog = () => {
        dialogVisible.value = false;
        resetForm();
      };

      const editKnowledge = async (knowledge: AgentScopeKnowledge) => {
        isEdit.value = true;
        currentEditId.value = knowledge.id;
        knowledgeForm.value = {
          ...knowledge,
        };
        
        // 如果有租户ID，加载该租户下的人员列表
        if (knowledge.tenantId) {
          try {
            const result = await agentScopeApi.getUsersByTenant(knowledge.tenantId);
            users.value = result.data?.data || result.data || [];
          } catch (error) {
            console.error('加载用户列表失败:', error);
            users.value = [];
          }
        }
        
        dialogVisible.value = true;
      };

      const toggleStatus = (knowledge: AgentScopeKnowledge) => {
        const newStatus = !knowledge.isRecall;
        const actionName = newStatus ? '召回' : '取消召回';

        ElMessageBox.confirm(`确定要${actionName}知识 "${knowledge.title}" 吗？`, '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning',
        })
          .then(async () => {
            try {
              await agentScopeApi.knowledge.updateRecall(knowledge.id, newStatus);
              ElMessage.success(`${actionName}成功`);
              loadKnowledgeList();
            } catch (error) {
              ElMessage.error(`${actionName}失败`);
            }
          })
          .catch(() => {});
      };

      const handleRetry = async (knowledge: AgentScopeKnowledge) => {
        try {
          await agentScopeApi.knowledge.retryEmbedding(knowledge.id);
          ElMessage.success('重试请求已发送');
          loadKnowledgeList();
        } catch (error) {
          ElMessage.error('重试失败');
        }
      };

      const deleteKnowledge = (knowledge: AgentScopeKnowledge) => {
        ElMessageBox.confirm(`确定要删除知识 "${knowledge.title}" 吗？`, '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning',
        })
          .then(async () => {
            try {
              await agentScopeApi.knowledge.delete(knowledge.id);
              ElMessage.success('删除成功');
              loadKnowledgeList();
            } catch (error) {
              ElMessage.error('删除失败');
            }
          })
          .catch(() => {});
      };

      const handleFileChange = (file: any) => {
        currentFile.value = file.raw;
        fileList.value = [file];
      };

      const formatFileSize = (bytes: number) => {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
      };

      const saveKnowledge = async () => {
        if (!knowledgeForm.value.title || !knowledgeForm.value.title.trim()) {
          ElMessage.warning('请输入知识标题');
          return;
        }

        if (knowledgeForm.value.type === 'DOCUMENT' && !isEdit.value && !currentFile.value) {
          ElMessage.warning('请上传文件');
          return;
        }

        saveLoading.value = true;
        try {
          if (isEdit.value && currentEditId.value) {
            // 确保 tenantId 和 userId 即使为 null 也会被传递
            const updateData = {
              title: knowledgeForm.value.title,
              content: knowledgeForm.value.content,
              question: knowledgeForm.value.question,
              tenantId: knowledgeForm.value.tenantId ?? null,
              userId: knowledgeForm.value.userId ?? null,
            };
            console.log('更新数据:', updateData);
            await agentScopeApi.knowledge.update(currentEditId.value, updateData);
            ElMessage.success('更新成功');
          } else {
            await agentScopeApi.knowledge.create(props.agentId, {
              title: knowledgeForm.value.title,
              type: knowledgeForm.value.type,
              content: knowledgeForm.value.content,
              question: knowledgeForm.value.question,
              isRecall: knowledgeForm.value.isRecall,
              splitterType: knowledgeForm.value.splitterType,
              tenantId: knowledgeForm.value.tenantId,
              userId: knowledgeForm.value.userId,
            }, {
              params: {
                tenantId: knowledgeForm.value.tenantId,
                userId: knowledgeForm.value.userId || undefined,
              }
            });
            ElMessage.success('添加成功');
          }

          dialogVisible.value = false;
          loadKnowledgeList();
        } catch (error) {
          ElMessage.error(`${isEdit.value ? '更新' : '添加'}失败`);
        } finally {
          saveLoading.value = false;
        }
      };

      const resetForm = () => {
        knowledgeForm.value = {
          agentId: props.agentId,
          tenantId: selectedTenantId.value, // 默认使用当前选中的租户
          userId: null,
          title: '',
          type: 'DOCUMENT',
          question: '',
          content: '',
          isRecall: 1,
          splitterType: 'token',
        };
        currentEditId.value = null;
        fileList.value = [];
        currentFile.value = null;
        
        // 如果已选择租户，加载该租户下的用户
        if (selectedTenantId.value) {
          handleTenantChange(selectedTenantId.value);
        }
      };

      onMounted(() => {
        loadTenants();
        loadKnowledgeList();
      });

      return {
        Plus,
        Search,
        RefreshLeft,
        FilterIcon,
        UploadFilled,
        knowledgeList,
        loading,
        dialogVisible,
        isEdit,
        saveLoading,
        queryParams,
        knowledgeForm,
        filterVisible,
        fileList,
        // 租户和用户相关
        tenants,
        users,
        selectedTenantId,
        tenantTree,
        tenantSearchKeyword,
        filteredTenantTree,
        loadTenants,
        handleTenantClick,
        handleTenantChange,
        toggleFilter,
        clearFilters,
        loadKnowledgeList,
        handleSearch,
        openCreateDialog,
        closeDialog,
        editKnowledge,
        deleteKnowledge,
        saveKnowledge,
        toggleStatus,
        handleRetry,
        handleFileChange,
        formatFileSize,
      };
    },
  });
</script>

<style scoped>
  /* 无需额外样式，使用 ElementPlus 默认样式 */
</style>
