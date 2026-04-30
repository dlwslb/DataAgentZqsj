<!--
  ~ Copyright 2024-2025 the original author or authors.
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~      https://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->
<template>
  <div id="app">
    <router-view />
  </div>
</template>

<script>
  import { onMounted } from 'vue';

export default {
    name: 'App',
    setup() {
      // 应用启动时初始化主题
      onMounted(() => {
        const savedTheme = localStorage.getItem('theme') || 'light';
        const themes = {
          default: {
            primary: '#3b82f6',
            primaryLight: '#60a5fa',
            primaryDark: '#2563eb',
            bgGradient: 'linear-gradient(135deg, #0c0c1e 0%, #1a1a3e 50%, #0f0f2a 100%)',
            headerBg: '#1a1a3e',
            navActive: 'rgba(59, 130, 246, 0.15)',
            textPrimary: '#ffffff',
            textSecondary: 'rgba(255, 255, 255, 0.7)',
          },
          light: {
            primary: '#3b82f6',
            primaryLight: '#60a5fa',
            primaryDark: '#2563eb',
            bgGradient: 'linear-gradient(135deg, #f0f4f8 0%, #e2e8f0 50%, #f0f4f8 100%)',
            headerBg: '#ffffff',
            navActive: 'rgba(59, 130, 246, 0.1)',
            textPrimary: '#1e293b',
            textSecondary: '#64748b',
          },
          purple: {
            primary: '#8b5cf6',
            primaryLight: '#a78bfa',
            primaryDark: '#7c3aed',
            bgGradient: 'linear-gradient(135deg, #1a0a2e 0%, #2d1b4e 50%, #1a0a2e 100%)',
            headerBg: '#2d1b4e',
            navActive: 'rgba(139, 92, 246, 0.15)',
            textPrimary: '#ffffff',
            textSecondary: 'rgba(255, 255, 255, 0.7)',
          },
          green: {
            primary: '#10b981',
            primaryLight: '#34d399',
            primaryDark: '#059669',
            bgGradient: 'linear-gradient(135deg, #0a1f1a 0%, #1a3e2d 50%, #0a1f1a 100%)',
            headerBg: '#1a3e2d',
            navActive: 'rgba(16, 185, 129, 0.15)',
            textPrimary: '#ffffff',
            textSecondary: 'rgba(255, 255, 255, 0.7)',
          },
          orange: {
            primary: '#f59e0b',
            primaryLight: '#fbbf24',
            primaryDark: '#d97706',
            bgGradient: 'linear-gradient(135deg, #1a1000 0%, #3e2a00 50%, #1a1000 100%)',
            headerBg: '#3e2a00',
            navActive: 'rgba(245, 158, 11, 0.15)',
            textPrimary: '#ffffff',
            textSecondary: 'rgba(255, 255, 255, 0.7)',
          },
          cyan: {
            primary: '#06b6d4',
            primaryLight: '#22d3ee',
            primaryDark: '#0891b2',
            bgGradient: 'linear-gradient(135deg, #0a1a1f 0%, #1a3a42 50%, #0a1a1f 100%)',
            headerBg: '#1a3a42',
            navActive: 'rgba(6, 182, 212, 0.15)',
            textPrimary: '#ffffff',
            textSecondary: 'rgba(255, 255, 255, 0.7)',
          },
        };

        const theme = themes[savedTheme] || themes.light;
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
      });
    },
  };
</script>

<style>
  #app {
    min-height: 100vh;
    background: var(--theme-bg-gradient);
    transition: background 0.3s ease;
  }
</style>
