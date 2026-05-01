<!--
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->
<template>
  <div class="base-layout">
    <!-- 现代化头部导航 -->
    <header class="page-header">
      <div class="header-content">
        <div class="brand-section">
          <div class="brand-logo">
            <i class="bi bi-robot"></i>
            <span class="brand-text">{{ systemName }}</span>
          </div>
          <nav class="header-nav" v-if="isImpersonating">
            <!-- 模拟模式提示条 -->
            <el-alert
                v-if="isImpersonating"
                title="当前处于模拟用户模式"
                type="warning"
                :closable="false"
                show-icon
                style="border-radius: 0;"
            >
            </el-alert>
          </nav>
          <nav class="header-nav" v-if="isSuperAdmin">
            <div class="nav-item" :class="{ active: isAgentPage() }" @click="goToAgentList">
              <i class="bi bi-grid-3x3-gap"></i>
              <span>智能体列表</span>
            </div>
            <div class="nav-item" :class="{ active: isAgentScopePage() }" @click="goToAgentScopeList">
              <i class="bi bi-robot"></i>
              <span>AgentScope智能体列表</span>
            </div>
            <div class="nav-item" :class="{ active: isModelConfigPage() }" @click="goToModelConfig">
              <i class="bi bi-gear"></i>
              <span>模型配置</span>
            </div>
            <div class="nav-item" :class="{ active: isUserManagementPage() }" @click="goToUserManagement" v-if="isSuperAdmin">
              <i class="bi bi-people"></i>
              <span>用户管理</span>
            </div>
          </nav>
        </div>

        <!-- 用户菜单 -->
        <div class="user-section">
          <el-dropdown @command="handleCommand">
            <div class="user-info">
              <el-avatar :size="32" :icon="UserFilled" />
              <span class="username">{{ userInfo?.nickname || userInfo?.username || '用户' }}</span>
              <i class="bi bi-chevron-down"></i>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="theme">
                  <i class="bi bi-palette"></i>
                  换肤
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <i class="bi bi-box-arrow-right"></i>
                  {{ isImpersonating ? '退出模拟' : '退出登录' }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </header>

    <!-- 页面内容区域 -->
    <main class="page-content">
      <slot></slot>
    </main>

    <!-- 换肤弹窗 -->
    <el-dialog v-model="showThemeDialog" title="选择主题" width="900px" align-center class="theme-dialog">
      <div class="theme-grid">
        <div
          v-for="theme in themes"
          :key="theme.id"
          class="theme-item"
          :class="{ active: currentTheme === theme.id }"
          @click="selectTheme(theme.id)"
        >
          <div class="theme-preview" :style="{ background: theme.bgGradient }">
            <div class="theme-color-dot" :style="{ background: theme.primary }"></div>
          </div>
          <div class="theme-name">{{ theme.name }}</div>
          <div v-if="currentTheme === theme.id" class="theme-check">
            <el-icon><Check /></el-icon>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
  import { useRouter } from 'vue-router';
  import { ref, onMounted } from 'vue';
  import { ElMessageBox, ElMessage } from 'element-plus';
  import { UserFilled, Check } from '@element-plus/icons-vue';
  import systemConfigService from '@/services/systemConfig';
  import authService from '@/services/auth';

  const router = useRouter();
  const systemName = ref('Spring AI Alibaba Data Agent');
  const userInfo = ref(null);
  const isSuperAdmin = ref(false);
  // 检查是否在模拟模式
  const isImpersonating = ref(authService.isImpersonating());

  onMounted(async () => {
    systemName.value = await systemConfigService.getSystemName();
    userInfo.value = authService.getUserInfo();
    isSuperAdmin.value = userInfo.value?.role === 'super_admin';
  });

  // 导航方法
  const goToAgentList = () => {
    router.push('/agents');
  };

  const goToAgentScopeList = () => {
    router.push('/daren-agent');
  };

  const goToModelConfig = () => {
    router.push('/model-config');
  };

  const goToUserManagement = () => {
    router.push('/users');
  };

  const isAgentPage = () => {
    return (
      router.currentRoute.value.name === 'AgentList' ||
      router.currentRoute.value.name === 'AgentDetail' ||
      router.currentRoute.value.name === 'AgentCreate' ||
      router.currentRoute.value.name === 'AgentRun'
    );
  };

  const isAgentScopePage = () => {
    return (
      router.currentRoute.value.name === 'AgentScopeList' ||
      router.currentRoute.value.name === 'AgentScopeDetail' ||
      router.currentRoute.value.name === 'AgentScopeCreate' ||
      router.currentRoute.value.name === 'AgentScopeRun'
    );
  };

  const isModelConfigPage = () => {
    return router.currentRoute.value.name === 'ModelConfig';
  };

  const isUserManagementPage = () => {
    return router.currentRoute.value.name === 'UserManagement';
  };

  // 处理下拉菜单命令
  const handleCommand = (command) => {
    if (command === 'logout') {
      if(isImpersonating.value){
        exitImpersonation();
      }else{
        handleLogout();
      }
    } else if (command === 'theme') {
      showThemeDialog.value = true;
    }
  };

  // 换肤功能
  const showThemeDialog = ref(false);

  // 主题配置 - 包含完整的主题变量
  const themes = [
    {
      id: 'cyan-light',
      name: '透亮青',
      primary: '#06b6d4',
      primaryLight: '#22d3ee',
      primaryDark: '#0891b2',
      bgGradient: 'linear-gradient(135deg, #f8fafc 0%, #f0f9ff 50%, #e0f2fe 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(6, 182, 212, 0.1)',
      textPrimary: '#0f172a',
      textSecondary: '#475569',
    },
    {
      id: 'default',
      name: '科技深蓝',
      primary: '#3b82f6',
      primaryLight: '#60a5fa',
      primaryDark: '#2563eb',
      bgGradient: 'linear-gradient(135deg, #0c0c1e 0%, #1a1a3e 50%, #0f0f2a 100%)',
      headerBg: '#1a1a3e',
      navActive: 'rgba(59, 130, 246, 0.15)',
      textPrimary: '#ffffff',
      textSecondary: 'rgba(255, 255, 255, 0.7)',
    },
    {
      id: 'purple',
      name: '神秘紫',
      primary: '#8b5cf6',
      primaryLight: '#a78bfa',
      primaryDark: '#7c3aed',
      bgGradient: 'linear-gradient(135deg, #1a0a2e 0%, #2d1b4e 50%, #1a0a2e 100%)',
      headerBg: '#2d1b4e',
      navActive: 'rgba(139, 92, 246, 0.15)',
      textPrimary: '#ffffff',
      textSecondary: 'rgba(255, 255, 255, 0.7)',
    },
    {
      id: 'green',
      name: '清新绿',
      primary: '#10b981',
      primaryLight: '#34d399',
      primaryDark: '#059669',
      bgGradient: 'linear-gradient(135deg, #0a1f1a 0%, #1a3e2d 50%, #0a1f1a 100%)',
      headerBg: '#1a3e2d',
      navActive: 'rgba(16, 185, 129, 0.15)',
      textPrimary: '#ffffff',
      textSecondary: 'rgba(255, 255, 255, 0.7)',
    },
    {
      id: 'orange',
      name: '活力橙',
      primary: '#f59e0b',
      primaryLight: '#fbbf24',
      primaryDark: '#d97706',
      bgGradient: 'linear-gradient(135deg, #1a1000 0%, #3e2a00 50%, #1a1000 100%)',
      headerBg: '#3e2a00',
      navActive: 'rgba(245, 158, 11, 0.15)',
      textPrimary: '#ffffff',
      textSecondary: 'rgba(255, 255, 255, 0.7)',
    },
    {
      id: 'cyan',
      name: '深邃青',
      primary: '#06b6d4',
      primaryLight: '#22d3ee',
      primaryDark: '#0891b2',
      bgGradient: 'linear-gradient(135deg, #0a1a1f 0%, #1a3a42 50%, #0a1a1f 100%)',
      headerBg: '#1a3a42',
      navActive: 'rgba(6, 182, 212, 0.15)',
      textPrimary: '#ffffff',
      textSecondary: 'rgba(255, 255, 255, 0.7)',
    },
    {
      id: 'light',
      name: '简约白',
      primary: '#3b82f6',
      primaryLight: '#60a5fa',
      primaryDark: '#2563eb',
      bgGradient: 'linear-gradient(135deg, #f0f4f8 0%, #e2e8f0 50%, #f0f4f8 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(59, 130, 246, 0.1)',
      textPrimary: '#1e293b',
      textSecondary: '#64748b',
    },
    {
      id: 'rose-light',
      name: '玫瑰粉',
      primary: '#f43f5e',
      primaryLight: '#fb7185',
      primaryDark: '#e11d48',
      bgGradient: 'linear-gradient(135deg, #fff1f2 0%, #ffe4e6 50%, #fecdd3 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(244, 63, 94, 0.1)',
      textPrimary: '#881337',
      textSecondary: '#be123c',
    },
    {
      id: 'amber-light',
      name: '琥珀金',
      primary: '#f59e0b',
      primaryLight: '#fbbf24',
      primaryDark: '#d97706',
      bgGradient: 'linear-gradient(135deg, #fffbeb 0%, #fef3c7 50%, #fde68a 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(245, 158, 11, 0.1)',
      textPrimary: '#78350f',
      textSecondary: '#b45309',
    },
    {
      id: 'violet-light',
      name: '紫罗兰',
      primary: '#8b5cf6',
      primaryLight: '#a78bfa',
      primaryDark: '#7c3aed',
      bgGradient: 'linear-gradient(135deg, #f5f3ff 0%, #ede9fe 50%, #ddd6fe 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(139, 92, 246, 0.1)',
      textPrimary: '#4c1d95',
      textSecondary: '#6d28d9',
    },
    {
      id: 'teal-light',
      name: '青碧色',
      primary: '#14b8a6',
      primaryLight: '#2dd4bf',
      primaryDark: '#0d9488',
      bgGradient: 'linear-gradient(135deg, #f0fdfa 0%, #ccfbf1 50%, #99f6e4 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(20, 184, 166, 0.1)',
      textPrimary: '#134e4a',
      textSecondary: '#0f766e',
    },
    {
      id: 'emerald-light',
      name: '翡翠绿',
      primary: '#10b981',
      primaryLight: '#34d399',
      primaryDark: '#059669',
      bgGradient: 'linear-gradient(135deg, #ecfdf5 0%, #d1fae5 50%, #a7f3d0 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(16, 185, 129, 0.1)',
      textPrimary: '#064e3b',
      textSecondary: '#047857',
    },
    {
      id: 'sky-light',
      name: '天空蓝',
      primary: '#0ea5e9',
      primaryLight: '#38bdf8',
      primaryDark: '#0284c7',
      bgGradient: 'linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 50%, #bae6fd 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(14, 165, 233, 0.1)',
      textPrimary: '#0c4a6e',
      textSecondary: '#0369a1',
    },
    {
      id: 'indigo-light',
      name: '靛蓝紫',
      primary: '#6366f1',
      primaryLight: '#818cf8',
      primaryDark: '#4f46e5',
      bgGradient: 'linear-gradient(135deg, #eef2ff 0%, #e0e7ff 50%, #c7d2fe 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(99, 102, 241, 0.1)',
      textPrimary: '#1e1b4b',
      textSecondary: '#4338ca',
    },
    {
      id: 'pink-light',
      name: '樱花粉',
      primary: '#ec4899',
      primaryLight: '#f472b6',
      primaryDark: '#db2777',
      bgGradient: 'linear-gradient(135deg, #fdf2f8 0%, #fce7f3 50%, #fbcfe8 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(236, 72, 153, 0.1)',
      textPrimary: '#831843',
      textSecondary: '#be185d',
    },
    {
      id: 'lime-light',
      name: '柠檬绿',
      primary: '#84cc16',
      primaryLight: '#a3e635',
      primaryDark: '#65a30d',
      bgGradient: 'linear-gradient(135deg, #f7fee7 0%, #ecfccb 50%, #d9f99d 100%)',
      headerBg: '#ffffff',
      navActive: 'rgba(132, 204, 22, 0.1)',
      textPrimary: '#365314',
      textSecondary: '#4d7c0f',
    },
    {
      id: 'stone-dark',
      name: '炭灰色',
      primary: '#78716c',
      primaryLight: '#a8a29e',
      primaryDark: '#57534e',
      bgGradient: 'linear-gradient(135deg, #1c1917 0%, #292524 50%, #1c1917 100%)',
      headerBg: '#292524',
      navActive: 'rgba(120, 113, 108, 0.2)',
      textPrimary: '#fafaf9',
      textSecondary: '#d6d3d1',
    },
    {
      id: 'slate-dark',
      name: '石墨灰',
      primary: '#94a3b8',
      primaryLight: '#cbd5e1',
      primaryDark: '#64748b',
      bgGradient: 'linear-gradient(135deg, #0f172a 0%, #1e293b 50%, #0f172a 100%)',
      headerBg: '#1e293b',
      navActive: 'rgba(148, 163, 184, 0.15)',
      textPrimary: '#f8fafc',
      textSecondary: '#cbd5e1',
    },
    {
      id: 'red-dark',
      name: '暗夜红',
      primary: '#ef4444',
      primaryLight: '#f87171',
      primaryDark: '#dc2626',
      bgGradient: 'linear-gradient(135deg, #1a0505 0%, #2d1010 50%, #1a0505 100%)',
      headerBg: '#2d1010',
      navActive: 'rgba(239, 68, 68, 0.2)',
      textPrimary: '#fef2f2',
      textSecondary: '#fecaca',
    },
    {
      id: 'ocean-dark',
      name: '深海蓝',
      primary: '#0ea5e9',
      primaryLight: '#38bdf8',
      primaryDark: '#0284c7',
      bgGradient: 'linear-gradient(135deg, #0c4a6e 0%, #075985 50%, #0c4a6e 100%)',
      headerBg: '#075985',
      navActive: 'rgba(14, 165, 233, 0.2)',
      textPrimary: '#f0f9ff',
      textSecondary: '#bae6fd',
    },
  ];

  // 获取保存的主题，如果不存在则使用默认的 'light'（简约白）
  const getInitialTheme = () => {
    const savedTheme = localStorage.getItem('theme');
    const themeExists = themes.some(t => t.id === savedTheme);
    return themeExists ? savedTheme : 'cyan-light';
  };
  const currentTheme = ref(getInitialTheme());

  // 应用主题到 CSS 变量
  const applyTheme = (themeId) => {
    // 如果找不到主题，使用 'cyan-light'（透亮青）作为默认值
    const theme = themes.find(t => t.id === themeId) || themes.find(t => t.id === 'cyan-light');
    const root = document.documentElement;
    root.style.setProperty('--theme-primary', theme.primary);
    root.style.setProperty('--theme-primary-light', theme.primaryLight);
    root.style.setProperty('--theme-primary-dark', theme.primaryDark);
    root.style.setProperty('--theme-bg-gradient', theme.bgGradient);
    root.style.setProperty('--theme-header-bg', theme.headerBg);
    root.style.setProperty('--theme-nav-active', theme.navActive);
    root.style.setProperty('--theme-text-primary', theme.textPrimary);
    root.style.setProperty('--theme-text-secondary', theme.textSecondary);

    // Element Plus 主色跟随主题变化
    root.style.setProperty('--el-color-primary', theme.primary);
    root.style.setProperty('--el-color-primary-light-3', theme.primaryLight);
    root.style.setProperty('--el-color-primary-dark-2', theme.primaryDark);
  };

  const selectTheme = (themeId) => {
    currentTheme.value = themeId;
    localStorage.setItem('theme', themeId);
    applyTheme(themeId);
    ElMessage.success('主题已切换');
    showThemeDialog.value = false;
  };

  // 页面加载时应用保存的主题
  onMounted(() => {
    applyTheme(currentTheme.value);
  });

  // 退出登录
  const handleLogout = async () => {
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      });

      // 清除认证信息
      authService.logout();
      
      ElMessage.success('已退出登录');
      
      // 跳转到登录页
      router.push('/login');
    } catch (error) {
      // 用户取消操作
      console.log('取消退出');
    }
  };

  // 退出模拟模式
  const exitImpersonation = () => {
    authService.exitImpersonation();
    isImpersonating.value = false;
    ElMessage.success('已退出模拟模式');
    // 跳转到用户管理页面
    router.push('/users');
  };
</script>

<style scoped>
  .base-layout {
    min-height: 100vh;
    background: var(--theme-bg-gradient);
  }

  .page-header {
    background: var(--theme-header-bg);
    border-bottom: 1px solid rgba(226, 232, 240, 0.5);
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
    position: sticky;
    top: 0;
    z-index: 100;
  }

  .header-content {
    width: 100%;
    padding: 0 1.5rem;
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 4rem;
  }

  .brand-section {
    display: flex;
    align-items: center;
    gap: 2rem;
  }

  .brand-logo {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    font-size: 1.25rem;
    font-weight: 600;
    color: var(--theme-text-primary);
  }

  .brand-logo i {
    font-size: 1.5rem;
    color: var(--theme-primary);
  }

  .header-nav {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }

  .nav-item {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.5rem 1rem;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s ease;
    color: var(--theme-text-secondary);
    font-weight: 500;
  }

  .nav-item:hover {
    background: rgba(0, 0, 0, 0.05);
    color: var(--theme-text-primary);
  }

  .nav-item.active {
    background: var(--theme-nav-active);
    color: var(--theme-primary);
  }

  .nav-item i {
    font-size: 1rem;
  }

  /* 用户区域 */
  .user-section {
    display: flex;
    align-items: center;
  }

  .user-info {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.5rem 1rem;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s ease;
    color: var(--theme-text-secondary);
  }

  .user-info:hover {
    background: rgba(0, 0, 0, 0.05);
    color: var(--theme-text-primary);
  }

  .username {
    font-weight: 500;
    font-size: 0.875rem;
    color: var(--theme-text-primary);
  }

  .user-info i {
    font-size: 0.75rem;
  }

  .page-content {
    flex: 1;
    padding: 0;
  }

  /* 换肤弹窗样式 */
  .theme-grid {
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    gap: 16px;
    padding: 10px 0;
  }

  .theme-item {
    position: relative;
    cursor: pointer;
    text-align: center;
    transition: transform 0.2s;
  }

  .theme-item:hover {
    transform: scale(1.05);
  }

  .theme-item.active .theme-preview {
    box-shadow: 0 0 0 3px var(--theme-primary);
  }

  .theme-preview {
    width: 100%;
    height: 60px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);
    transition: box-shadow 0.2s;
  }

  .theme-color-dot {
    width: 20px;
    height: 20px;
    border-radius: 50%;
    box-shadow: 0 0 10px rgba(255, 255, 255, 0.5);
  }

  .theme-name {
    margin-top: 10px;
    font-size: 14px;
    color: var(--theme-text-secondary);
    font-weight: 500;
  }

  .theme-item.active .theme-name {
    color: var(--theme-primary);
  }

  .theme-check {
    position: absolute;
    top: -5px;
    right: -5px;
    width: 24px;
    height: 24px;
    background: var(--theme-primary);
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: white;
    font-size: 14px;
  }

  /* 弹窗样式覆盖 */
  :deep(.theme-dialog) {
    --el-dialog-bg-color: var(--theme-header-bg);
    --el-text-color-primary: var(--theme-text-primary);
    --el-text-color-regular: var(--theme-text-secondary);
  }
</style>
