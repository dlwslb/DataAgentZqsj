<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->
<template>
  <BaseLayout>
    <div class="user-management-page">
      <main class="main-content" style="display: flex; gap: 20px; padding: 20px; overflow-x: auto;">
        <!-- 左侧租户树 -->
        <div style="width: 250px; flex-shrink: 0;">
          <el-card shadow="never" style="height: calc(100vh - 140px); overflow-y: auto">
            <template #header>
              <div style="display: flex; justify-content: space-between; align-items: center">
                <span>租户列表</span>
                <el-button size="small" @click="loadTenants" :icon="Refresh" circle />
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
              v-if="filteredTenantTree.length > 0"
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
            <div v-else style="text-align: center; color: #909399; padding: 20px 0;">
              暂无租户数据
            </div>
          </el-card>
        </div>

        <!-- 右侧用户管理内容 -->
        <div style="flex: 1; min-width: 0;">
        <!-- 操作区域 -->
        <div class="action-section">
          <el-card>
            <div class="action-content">
              <div class="action-info">
                <div class="title-row">
                  <h1 class="content-title">用户管理中心</h1>
                  <span class="title-divider">|</span>
                  <p class="content-subtitle">管理系统用户账户、角色和权限</p>
                </div>
                 <div class="stats-row">
                  <div class="stat-item">
                    <div class="stat-number">{{ totalCount }}</div>
                    <div class="stat-label">总用户数</div>
                  </div>
                  <div class="stat-item">
                    <div class="stat-number">{{ enabledCount }}</div>
                    <div class="stat-label">已启用</div>
                  </div>
                  <div class="stat-item">
                    <div class="stat-number">{{ disabledCount }}</div>
                    <div class="stat-label">已禁用</div>
                  </div>
                  <div class="stat-item">
                    <div class="stat-number">{{ adminCount }}</div>
                    <div class="stat-label">管理员</div>
                  </div>
                </div>
              </div>
              <div class="action-buttons">
                <el-input
                  v-model="searchKeyword"
                  placeholder="搜索用户名/昵称..."
                  clearable
                  @keyup.enter="handleSearch"
                  style="width: 200px"
                >
                  <template #prefix>
                    <el-icon><Search /></el-icon>
                  </template>
                </el-input>
                <el-button type="primary" @click="handleSearch">搜索</el-button>
                <el-button @click="handleReset">重置</el-button>
                <el-button :icon="Refresh" @click="loadUsers">刷新</el-button>
                <el-button type="primary" :icon="Plus" @click="openCreateDialog">
                  新增用户
                </el-button>
              </div>
            </div>
          </el-card>
        </div>

        <!-- 用户列表 -->
        <div class="table-section" v-loading="loading">
          <el-table :data="users" stripe style="width: 100%" :height="tableHeight">
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="username" label="用户名" width="150">
              <template #default="{ row }">
                <div class="user-cell">
                  <el-avatar :size="32" :icon="UserFilled" />
                  <span>{{ row.username }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="nickname" label="昵称" width="120" />
            <el-table-column prop="email" label="邮箱" width="180" show-overflow-tooltip />
            <el-table-column prop="phone" label="电话" width="130" />
            <el-table-column prop="province" label="省份" width="100" show-overflow-tooltip />
            <el-table-column label="AI每日限制" width="110">
              <template #default="{ row }">
                <el-tag v-if="!row.aiDailyLimit" type="success" size="small">不限</el-tag>
                <span v-else :style="{ color: getTodayUsed(row) >= row.aiDailyLimit ? '#f56c6c' : '', fontWeight: 600 }"
                      :title="'今日已用 ' + getTodayUsed(row) + ' 次，次日自动重置'">
                  {{ getTodayUsed(row) }}/{{ row.aiDailyLimit }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="agentId" label="绑定智能体" width="120">
              <template #default="{ row }">
                <span
                  v-if="row.agentId"
                  :class="['agent-link', { 'disabled-link': !isSuperAdmin }]"
                  @click="goToAgentRun(row.agentId, row.id)"
                  :title="!isSuperAdmin ? '仅超级管理员可访问' : ''"
                >
                  {{ getAgentName(row.agentId) }}
                </span>
                <span v-else class="text-muted">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="role" label="角色" width="100">
              <template #default="{ row }">
                <el-tag :type="row.role === 'super_admin' ? 'danger' : 'info'" size="small">
                  {{ row.role === 'super_admin' ? '超级管理员' : (row.role === 'admin' ? '管理员' : '普通用户') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.status === 1"
                  @change="handleStatusChange(row)"
                  :disabled="row.username === 'admin'"
                />
              </template>
            </el-table-column>
            <el-table-column prop="loginIp" label="登录IP" width="130" show-overflow-tooltip />
            <el-table-column prop="loginDate" label="最后登录" width="170">
              <template #default="{ row }">
                {{ formatDate(row.loginDate) }}
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
            <el-table-column prop="createTime" label="创建时间" width="170">
              <template #default="{ row }">
                {{ formatDate(row.createTime) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="openEditDialog(row)">
                  <el-icon><Edit /></el-icon> 编辑
                </el-button>
                <el-button type="warning" link size="small" @click="openResetPasswordDialog(row)">
                  <el-icon><Key /></el-icon> 重置密码
                </el-button>
                <el-button type="danger" link size="small" @click="handleDelete(row)" 
                           v-if="row.role != 'super_admin'">
                  <el-icon><Delete /></el-icon> 删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pagination-container">
            <el-pagination
              v-model:current-page="currentPage"
              v-model:page-size="pageSize"
              :page-sizes="[10, 20, 50, 100]"
              :total="totalCount"
              layout="total, sizes, prev, pager, next, jumper"
              @size-change="handleSizeChange"
              @current-change="handleCurrentChange"
            />
          </div>
        </div>

        <!-- 新增/编辑用户对话框 -->
        <el-dialog
          v-model="dialogVisible"
          :title="isEdit ? '编辑用户' : '新增用户'"
          width="500px"
          :close-on-click-modal="false"
        >
          <el-form :model="userForm" :rules="formRules" ref="formRef" label-width="100px">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="userForm.username" placeholder="请输入用户名" />
            </el-form-item>
            <el-form-item label="密码" prop="password" v-if="!isEdit">
              <el-input v-model="userForm.password" type="password" placeholder="请输入密码（8-20位，不含特殊符号）" show-password @input="userForm.password = filterSqlInjection($event)" />
            </el-form-item>
            <el-form-item label="昵称" prop="nickname">
              <el-input v-model="userForm.nickname" placeholder="请输入昵称" />
            </el-form-item>
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="userForm.email" placeholder="请输入邮箱" />
            </el-form-item>
            <el-form-item label="电话" prop="phone">
              <el-input v-model="userForm.phone" placeholder="请输入电话" />
            </el-form-item>
            <el-form-item label="OA账号" prop="managerAOa">
              <el-input
                  v-model="userForm.managerAOa"
                  placeholder="请输入OA账号（客户经理 A 角 OA 工号）"
                  clearable
                  @input="userForm.managerAOa = userForm.managerAOa.trim()"
              />
            </el-form-item>
            <el-form-item label="省份" prop="province">
              <el-select
                v-model="userForm.province"
                placeholder="请选择省份（可多选）"
                filterable
                clearable
                multiple
                collapse-tags
                collapse-tags-tooltip
                :max-collapse-tags="3"
                style="width: 100%;"
                @change="handleProvinceChange"
              >
                <el-option
                  v-for="p in provinceList"
                  :key="p"
                  :label="p"
                  :value="p"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="租户" prop="tenantId">
              <el-select 
                v-model="userForm.tenantId" 
                placeholder="请选择租户" 
                filterable 
                clearable 
                style="width: 100%;"
              >
                <el-option
                  v-for="tenant in tenantTree"
                  :key="tenant.id"
                  :label="tenant.label"
                  :value="tenant.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="绑定智能体" prop="agentId">
              <el-select v-model="userForm.agentId" placeholder="请选择绑定的智能体" filterable clearable style="width: 100%;">
                <el-option
                  v-for="agent in agentList"
                  :key="agent.id"
                  :label="agent.name"
                  :value="agent.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="AI每日限制" prop="aiDailyLimit">
              <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap;">
                <el-radio-group v-model="userForm.aiLimitType">
                  <el-radio label="unlimited">不限</el-radio>
                  <el-radio label="custom">自定义限制</el-radio>
                </el-radio-group>
                <el-input-number
                  v-if="userForm.aiLimitType === 'custom'"
                  v-model="userForm.aiDailyLimit"
                  :min="1"
                  :max="1000000"
                  :step="1"
                  placeholder="每日限制次数"
                  style="width: 160px;"
                />
              </div>
              <div v-if="userForm.aiLimitType === 'custom'" style="color: #909399; font-size: 12px; line-height: 1.4; margin-top: 4px;">
                每调用一次 AI 扣减 1 次，用完后当日拒绝调用，次日自动重置为该限额
              </div>
            </el-form-item>
            <el-form-item label="角色" prop="role">
              <el-select v-model="userForm.role" style="width: 100%;">
                <el-option label="普通用户" value="user" />
                <el-option label="管理员" value="admin" />
                <el-option label="超级管理员" value="super_admin" />
              </el-select>
            </el-form-item>
            <el-form-item label="备注" prop="remark">
              <el-input v-model="userForm.remark" type="textarea" :rows="2" placeholder="请输入备注" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="dialogVisible = false">取消</el-button>
            <el-button type="primary" @click="handleSubmit" :loading="submitting">确定</el-button>
          </template>
        </el-dialog>

        <!-- 重置密码对话框 -->
        <el-dialog v-model="passwordDialogVisible" title="重置密码" width="450px" :close-on-click-modal="false">
          <el-form :model="passwordForm" :rules="passwordRules" ref="passwordFormRef" label-width="100px">
            <el-form-item label="密码方式">
              <el-radio-group v-model="passwordForm.useDefault">
                <el-radio :label="true">使用默认密码</el-radio>
                <el-radio :label="false">手动输入密码</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="默认密码" v-if="passwordForm.useDefault">
              <el-input :model-value="defaultPassword" disabled />
            </el-form-item>
            <template v-else>
              <el-form-item label="新密码" prop="password">
                <el-input v-model="passwordForm.password" type="password" placeholder="请输入新密码（8-20位，不含特殊符号）" show-password @input="passwordForm.password = filterSqlInjection($event)" />
              </el-form-item>
              <el-form-item label="确认密码" prop="confirmPassword">
                <el-input v-model="passwordForm.confirmPassword" type="password" placeholder="请确认密码" show-password @input="passwordForm.confirmPassword = filterSqlInjection($event)" />
              </el-form-item>
            </template>
          </el-form>
          <template #footer>
            <el-button @click="passwordDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="handleResetPassword" :loading="resetting">确定</el-button>
          </template>
        </el-dialog>
        </div>
      </main>
    </div>
  </BaseLayout>
</template>

<script>
import { defineComponent, ref, reactive, computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox, ElIcon } from 'element-plus';
import { Plus, Search, Refresh, Edit, Delete, Key, UserFilled } from '@element-plus/icons-vue';
import BaseLayout from '@/layouts/BaseLayout.vue';
import userService from '@/services/user';
import authService from '@/services/auth';
import { agentScopeApi } from '@/services/agentScope';
import { apiClient } from '@/services/common';

export default defineComponent({
  name: 'UserManagement',
  components: { BaseLayout, ElIcon, Plus, Search, Refresh, Edit, Delete, Key, UserFilled },
  setup() {
    const router = useRouter();
    const loading = ref(false);
    const dialogVisible = ref(false);
    const passwordDialogVisible = ref(false);
    const isEdit = ref(false);
    const submitting = ref(false);
    const resetting = ref(false);
    const formRef = ref();
    const passwordFormRef = ref();

    // 检查是否为超级管理员
    const userInfo = authService.getUserInfo();
    const isSuperAdmin = computed(() => userInfo?.role === 'super_admin');

    const users = ref([]);
    const searchKeyword = ref('');
    const currentUserId = ref(null);
    const tableHeight = ref('calc(100vh - 380px)');
    const defaultPassword = 'tt8886166';
    
    // 分页
    const currentPage = ref(1);
    const pageSize = ref(10);
    const totalCount = ref(0);
    
    // 智能体列表
    const agentList = ref([]);
    
    // 租户树相关
    const tenantTree = ref([]);
    const selectedTenantId = ref(null);
    const tenantSearchKeyword = ref('');

    // SQL注入过滤正则
    const SQL_INJECTION_REGEX = /['";<>\\%]/g;

    // 全国省份列表
    const provinceList = [
      '全国',
      '北京', '天津', '上海', '重庆',
      '河北', '山西', '辽宁', '吉林', '黑龙江',
      '江苏', '浙江', '安徽', '福建', '江西', '山东',
      '河南', '湖北', '湖南', '广东', '海南',
      '四川', '贵州', '云南', '陕西', '甘肃', '青海', '台湾',
      '内蒙古', '广西', '西藏', '宁夏', '新疆',
      '香港', '澳门'
    ];

    const filterSqlInjection = (value) => {
      return value.replace(SQL_INJECTION_REGEX, '');
    };

    // 「全国」与具体省份互斥：选中「全国」则清空其他，选了具体省份则剔除「全国」
    const handleProvinceChange = (val) => {
      if (!Array.isArray(val) || val.length <= 1) return;
      if (val[val.length - 1] === '全国') {
        userForm.province = ['全国'];
      } else if (val.includes('全国')) {
        userForm.province = val.filter(p => p !== '全国');
      }
    };

    // 历史数据兼容：把旧格式全称（如「北京市」「内蒙古自治区」）归一化成当前选项的简称，保证回显能匹配上选项
    const normalizeProvinceName = (name) => {
      const trimmed = String(name || '').trim();
      if (!trimmed) return '';
      if (provinceList.includes(trimmed)) return trimmed;
      const aliases = {
        全国: '全国',
        北京市: '北京', 天津市: '天津', 上海市: '上海', 重庆市: '重庆',
        河北省: '河北', 山西省: '山西', 辽宁省: '辽宁', 吉林省: '吉林', 黑龙江省: '黑龙江',
        江苏省: '江苏', 浙江省: '浙江', 安徽省: '安徽', 福建省: '福建', 江西省: '江西', 山东省: '山东',
        河南省: '河南', 湖北省: '湖北', 湖南省: '湖南', 广东省: '广东', 海南省: '海南',
        四川省: '四川', 贵州省: '贵州', 云南省: '云南', 陕西省: '陕西', 甘肃省: '甘肃', 青海省: '青海', 台湾省: '台湾',
        内蒙古自治区: '内蒙古', 广西壮族自治区: '广西', 西藏自治区: '西藏', 宁夏回族自治区: '宁夏', 新疆维吾尔自治区: '新疆',
        香港特别行政区: '香港', 澳门特别行政区: '澳门',
      };
      return aliases[trimmed] || trimmed;
    };

    const updateTableHeight = () => {
      tableHeight.value = 'calc(100vh - 320px)';
    };

    const userForm = reactive({
      username: '',
      password: '',
      nickname: '',
      email: '',
      phone: '',
      managerAOa: '',
      province: [],
      aiLimitType: 'unlimited', // unlimited=不限，custom=自定义限制
      aiDailyLimit: null,
      tenantId: null,
      agentId: null,
      role: 'user',
      remark: '',
    });

    const passwordForm = reactive({
      useDefault: true,
      password: '',
      confirmPassword: '',
    });

    const enabledCount = computed(() => users.value.filter(u => u.status === 1).length);
    const disabledCount = computed(() => users.value.filter(u => u.status === 0).length);
    const adminCount = computed(() => users.value.filter(u => u.role === 'admin' || u.role === 'super_admin').length);

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

    const formRules = {
      username: [
        { required: true, message: '请输入用户名', trigger: 'blur' },
        { min: 3, max: 20, message: '用户名长度在 3 到 20 个字符', trigger: 'blur' },
      ],
      password: [
        { required: true, message: '请输入密码', trigger: 'blur' },
        { min: 8, max: 20, message: '密码长度在 8 到 20 个字符', trigger: 'blur' },
      ],
      nickname: [
        { required: true, message: '请输入昵称', trigger: 'blur' },
      ],
      email: [
        { required: true, message: '请输入邮箱', trigger: 'blur' },
        { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' },
      ],
      phone: [
        { required: true, message: '请输入电话', trigger: 'blur' },
        { pattern: /^[\d\-+ ]{5,20}$/, message: '请输入正确的电话号码', trigger: 'blur' },
      ],
      managerAOa: [
        { max: 64, message: 'OA账号不能超过 64 个字符', trigger: 'blur' },
      ],
      province: [
        {
          required: true,
          validator: (_rule, value, callback) => {
            if (Array.isArray(value) ? value.length === 0 : !value) {
              callback(new Error('请选择省份'));
            } else {
              callback();
            }
          },
          trigger: 'change',
        },
      ],
      role: [
        { required: true, message: '请选择角色', trigger: 'change' },
      ],
      remark: [
        { max: 500, message: '备注不能超过 500 个字符', trigger: 'blur' },
      ],
    };

    const passwordRules = {
      password: [
        { required: true, message: '请输入新密码', trigger: 'blur' },
        { min: 8, max: 20, message: '密码长度在 8 到 20 个字符', trigger: 'blur' },
      ],
      confirmPassword: [
        { required: true, message: '请确认密码', trigger: 'blur' },
        {
          validator: (_rule, value, callback) => {
            if (value !== passwordForm.password) {
              callback(new Error('两次输入的密码不一致'));
            } else {
              callback();
            }
          },
          trigger: 'blur',
        },
      ],
    };

    const loadUsers = async () => {
      loading.value = true;
      try {
        const params = {
          page: currentPage.value,
          pageSize: pageSize.value,
        };
        if (searchKeyword.value) {
          params.keyword = searchKeyword.value;
        }
        if (selectedTenantId.value) {
          params.tenantId = selectedTenantId.value;
        }
        const result = await userService.getUsers(params);
        users.value = result.list;
        totalCount.value = result.total;
      } catch (error) {
        ElMessage.error(error.message || '加载用户列表失败');
      } finally {
        loading.value = false;
      }
    };
    
    // 加载智能体列表
    const loadAgentList = async () => {
      try {
        const response = await agentScopeApi.list();
        // axios返回的数据在 response.data 中
        const data = response?.data?.data || response?.data || [];
        agentList.value = Array.isArray(data) ? data : [];
      } catch (error) {
        console.error('加载智能体列表失败:', error);
        agentList.value = [];
      }
    };
    
    // 加载租户列表
    const loadTenants = async () => {
      try {
        console.log('🔍 开始加载租户列表...');
        const response = await apiClient.get('/api/users/tenants');
        console.log('📦 租户接口响应:', response.data);
        if (response.data.code === 0) {
          // 将后端返回的 name 字段转换为 el-tree 需要的 label 字段
          tenantTree.value = (response.data.data || []).map(tenant => ({
            id: tenant.id,
            label: tenant.name
          }));
          console.log('✅ 租户列表加载成功，数量:', tenantTree.value.length);
        } else {
          console.error('❌ 租户接口返回错误:', response.data.message);
          ElMessage.warning(response.data.message || '获取租户列表失败');
        }
      } catch (error) {
        console.error('❌ 加载租户列表失败:', error);
        ElMessage.error('加载租户列表失败: ' + (error.message || '未知错误'));
        tenantTree.value = [];
      }
    };
    
    // 处理租户树节点点击
    const handleTenantClick = (data) => {
      selectedTenantId.value = data.id;
      currentPage.value = 1; // 重置到第一页
      loadUsers();
    };
    
    // 用户今日已用 AI 调用次数（aiUsageDate 不是今天则视为已重置，归 0）
    const getTodayUsed = (row) => {
      const now = new Date();
      const todayStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
      return row.aiUsageDate === todayStr ? (row.aiUsedCount || 0) : 0;
    };

    // 根据ID获取智能体名称
    const getAgentName = (agentId) => {
      if (!agentId) return '-';
      const agent = agentList.value.find(a => a.id === agentId);
      return agent ? agent.name : '-';
    };
    
    // 跳转到智能体运行页面（先模拟用户登录）
    const goToAgentRun = async (agentId, userId) => {
      if (!agentId) return;
      
      // 严格权限检查：仅超级管理员可以模拟
      if (!isSuperAdmin.value) {
        ElMessage.error('禁止访问：此功能仅限超级管理员使用');
        return;
      }
      
      try {
        // 调用后端接口获取目标用户的临时Token
        await authService.impersonateUser(userId);
        // 跳转到智能体运行页面
        router.push(`/daren-agent/${agentId}/run`);
      } catch (error) {
        ElMessage.error(error.message || '模拟登录失败');
      }
    };

    const handleSearch = () => {
      currentPage.value = 1;
      loadUsers();
    };

    const handleReset = () => {
      searchKeyword.value = '';
      selectedTenantId.value = null;
      currentPage.value = 1;
      loadUsers();
    };

    const handleSizeChange = (val) => {
      pageSize.value = val;
      currentPage.value = 1;
      loadUsers();
    };

    const handleCurrentChange = (val) => {
      currentPage.value = val;
      loadUsers();
    };

    const openCreateDialog = () => {
      isEdit.value = false;
      Object.assign(userForm, {
        username: '',
        password: '',
        nickname: '',
        email: '',
        phone: '',
        managerAOa: '',
        province: [],
        aiLimitType: 'unlimited',
        aiDailyLimit: null,
        tenantId: null,
        agentId: null,
        role: 'user',
        remark: '',
      });
      dialogVisible.value = true;
    };

    const openEditDialog = (user) => {
      isEdit.value = true;
      currentUserId.value = user.id;
      // 后端 province 存的是逗号分隔字符串,前端表单用数组承载；旧数据全称归一化为简称后再回显
      const provinceArr = user.province
        ? String(user.province).split(',').map(normalizeProvinceName).filter(Boolean)
        : [];
      Object.assign(userForm, {
        username: user.username,
        password: '',
        nickname: user.nickname || '',
        email: user.email || '',
        phone: user.phone || '',
        managerAOa: user.managerAOa || '',
        province: provinceArr,
        aiLimitType: user.aiDailyLimit > 0 ? 'custom' : 'unlimited',
        aiDailyLimit: user.aiDailyLimit > 0 ? user.aiDailyLimit : null,
        tenantId: user.tenantId || null,
        agentId: user.agentId || null,
        role: user.role || 'user',
        remark: user.remark || '',
      });
      dialogVisible.value = true;
    };

    const openResetPasswordDialog = (user) => {
      currentUserId.value = user.id;
      passwordForm.useDefault = true;
      passwordForm.password = '';
      passwordForm.confirmPassword = '';
      passwordDialogVisible.value = true;
    };

    const handleSubmit = async () => {
      if (!formRef.value) return;
      await formRef.value.validate(async (valid) => {
        if (valid) {
          submitting.value = true;
          // 多选省份:数组 → 逗号串接字符串提交给后端
          const provinceValue = Array.isArray(userForm.province)
            ? userForm.province.filter(Boolean).join(',')
            : (userForm.province || '');
          // AI 每日限制：不限 → null；自定义 → 校验后取输入值
          if (userForm.aiLimitType === 'custom' && (!userForm.aiDailyLimit || userForm.aiDailyLimit < 1)) {
            ElMessage.error('请输入有效的每日限制次数');
            submitting.value = false;
            return;
          }
          const aiDailyLimitValue = userForm.aiLimitType === 'custom' ? userForm.aiDailyLimit : null;
          try {
            if (isEdit.value && currentUserId.value) {
              await userService.updateUser(currentUserId.value, {
                username: userForm.username,
                nickname: userForm.nickname,
                email: userForm.email,
                phone: userForm.phone,
                managerAOa: userForm.managerAOa,
                province: provinceValue,
                aiDailyLimit: aiDailyLimitValue,
                tenantId: userForm.tenantId,
                agentId: userForm.agentId,
                role: userForm.role,
                remark: userForm.remark,
              });
              ElMessage.success('更新用户成功');
            } else {
              await userService.createUser({
                username: userForm.username,
                password: userForm.password,
                nickname: userForm.nickname,
                email: userForm.email,
                phone: userForm.phone,
                managerAOa: userForm.managerAOa,
                province: provinceValue,
                aiDailyLimit: aiDailyLimitValue,
                tenantId: userForm.tenantId,
                agentId: userForm.agentId,
                role: userForm.role,
                remark: userForm.remark,
              });
              ElMessage.success('创建用户成功');
            }
            dialogVisible.value = false;
            loadUsers();
          } catch (error) {
            ElMessage.error(error.message || '操作失败');
          } finally {
            submitting.value = false;
          }
        }
      });
    };

    const handleResetPassword = async () => {
      resetting.value = true;
      try {
        const finalPassword = passwordForm.useDefault ? defaultPassword : passwordForm.password;
        await userService.resetPassword(currentUserId.value, finalPassword);
        ElMessage.success('密码重置成功');
        passwordDialogVisible.value = false;
      } catch (error) {
        ElMessage.error(error.message || '重置密码失败');
      } finally {
        resetting.value = false;
      }
    };

    const handleStatusChange = async (user) => {
      if (user.username === 'admin') {
        ElMessage.warning('不能禁用管理员账户');
        return;
      }
      try {
        await userService.toggleStatus(user.id, user.status === 1 ? 0 : 1);
        ElMessage.success(user.status === 1 ? '用户已禁用' : '用户已启用');
        loadUsers();
      } catch (error) {
        ElMessage.error(error.message || '操作失败');
      }
    };

    const handleDelete = async (user) => {
      try {
        await ElMessageBox.confirm(`确定要删除用户 "${user.username}" 吗？此操作不可恢复。`, '删除确认', {
          confirmButtonText: '删除',
          cancelButtonText: '取消',
          type: 'warning',
        });
        await userService.deleteUser(user.id);
        ElMessage.success('删除成功');
        loadUsers();
      } catch (error) {
        if (error !== 'cancel') {
          ElMessage.error(error.message || '删除失败');
        }
      }
    };

    const formatDate = (dateStr) => {
      if (!dateStr) return '-';
      const date = new Date(dateStr);
      return date.toLocaleString('zh-CN');
    };

    onMounted(() => {
      loadUsers();
      loadAgentList();
      loadTenants();
      updateTableHeight();
      window.addEventListener('resize', updateTableHeight);
    });

    onUnmounted(() => {
      window.removeEventListener('resize', updateTableHeight);
    });

    return {
      loading,
      dialogVisible,
      passwordDialogVisible,
      isEdit,
      submitting,
      resetting,
      formRef,
      passwordFormRef,
      users,
      searchKeyword,
      defaultPassword,
      filterSqlInjection,
      handleProvinceChange,
      provinceList,
      agentList,
      userForm,
      passwordForm,
      formRules,
      passwordRules,
      totalCount,
      enabledCount,
      disabledCount,
      adminCount,
      tableHeight,
      currentPage,
      pageSize,
      totalCount,
      // 租户树相关
      tenantTree,
      selectedTenantId,
      tenantSearchKeyword,
      filteredTenantTree,
      loadUsers,
      loadAgentList,
      loadTenants,
      handleTenantClick,
      getTodayUsed,
      getAgentName,
      goToAgentRun,
      handleSearch,
      handleReset,
      handleSizeChange,
      handleCurrentChange,
      openCreateDialog,
      openEditDialog,
      openResetPasswordDialog,
      handleSubmit,
      handleResetPassword,
      handleStatusChange,
      handleDelete,
      formatDate,
      UserFilled,
      Plus,
      Search,
      Refresh,
      Edit,
      Delete,
      Key,
      isSuperAdmin,
    };
  },
});
</script>

<style scoped>
.user-management-page {
  min-height: calc(100vh - 4rem);
  background: transparent;
}

.main-content {
  padding: 1.5rem;
}

.action-section {
  margin-bottom: 1.5rem;
}

.action-content {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 1rem;
}

.action-info {
  flex: 1;
  min-width: 300px;
}

.stats-row {
  display: flex;
  gap: 1.5rem;
  margin-bottom: 1rem;
}

.stat-item {
  text-align: center;
}

.stat-number {
  font-size: 1.5rem;
  font-weight: 700;
  color: #3b82f6;
}

.stat-label {
  font-size: 0.75rem;
  color: #6b7280;
  margin-top: 0.25rem;
}

.action-info .content-title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: #1f2937;
  display: inline;
}

.action-info .content-subtitle {
  margin: 0;
  color: #6b7280;
  font-size: 0.875rem;
  display: inline;
}

.title-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.75rem;
}

.title-divider {
  color: #d1d5db;
  font-size: 1rem;
}

.action-buttons {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}

.table-section {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  margin-bottom: 1rem;
  padding: 1rem;
  box-sizing: border-box;
}

.pagination-container {
  display: flex;
  justify-content: flex-end;
  margin-top: 1rem;
}

:deep(.el-table) {
  border-radius: 4px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

:deep(.el-table) {
  border-radius: 4px;
}

:deep(.el-table th) {
  background-color: #f9fafb;
  color: #374151;
  font-weight: 600;
}

:deep(.el-button + .el-button) {
  margin-left: 0.25rem;
}

.agent-link {
  color: #409eff;
  cursor: pointer;
  text-decoration: none;
}

.agent-link:hover {
  color: #66b1ff;
  text-decoration: underline;
}

/* 禁用状态的链接（非超级管理员） */
.agent-link.disabled-link {
  color: #c0c4cc;
  cursor: not-allowed;
  text-decoration: none;
}

.agent-link.disabled-link:hover {
  color: #c0c4cc;
  text-decoration: none;
}

.text-muted {
  color: #909399;
}

/* 租户树样式 */
.custom-tree-node {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  padding-right: 8px;
}

.custom-tree-node:hover {
  background-color: #f5f7fa;
}
</style>
