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
  <div class="login-container">
    <!-- 背景动画 -->
    <div class="bg-animation">
      <div class="wave wave1"></div>
      <div class="wave wave2"></div>
      <div class="wave wave3"></div>
    </div>
    
    <!-- 粒子效果 -->
    <div class="particles" ref="particlesRef"></div>
    
    <div class="login-box">
      <!-- Logo区域 -->
      <div class="login-header">
        <div class="logo-container">
          <div class="logo-icon">
            <div class="logo-inner"></div>
            <div class="logo-ring"></div>
          </div>
        </div>
        <h1 class="login-title">
          <span class="title-text">滔滔</span>
          <span class="title-sub">TAO TAO</span>
        </h1>
        <p class="login-subtitle">智能对话，滔滔不绝</p>
      </div>

      <el-form
        ref="loginFormRef"
        :model="loginForm"
        :rules="loginRules"
        class="login-form"
        @keyup.enter="handleLogin"
      >
        <el-form-item prop="username">
          <el-input
            ref="usernameInputRef"
            v-model="loginForm.username"
            placeholder="请输入用户名"
            size="large"
            :prefix-icon="User"
            class="tech-input"
          >
            <template #suffix>
              <span v-if="loginForm.username" class="clear-icon" @click="loginForm.username = ''">
                <el-icon><CircleClose /></el-icon>
              </span>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            :prefix-icon="Lock"
            show-password
            class="tech-input"
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            class="login-button"
            @click="handleLogin"
          >
            <span v-if="!loading">登 入</span>
            <span v-else>登录中...</span>
          </el-button>
        </el-form-item>
      </el-form>
      
      <!-- 底部信息 -->
      <div class="login-footer">
        <div class="tech-line"></div>
        <p class="footer-text">Powered by daren</p>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
  import { defineComponent, ref, reactive, onMounted } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
  import { User, Lock, CircleClose } from '@element-plus/icons-vue';
  import authService from '@/services/auth';
  import { agentScopeApi } from '@/services/agentScope';

  export default defineComponent({
    name: 'Login',
    components: {
      User,
      Lock,
      CircleClose,
    },
    setup() {
      const router = useRouter();
      const loginFormRef = ref<FormInstance>();
      const usernameInputRef = ref();
      const loading = ref(false);

      const loginForm = reactive({
        username: '',
        password: '',
      });

      // 页面加载时自动聚焦到用户名输入框
      onMounted(() => {
        setTimeout(() => {
          usernameInputRef.value?.focus();
        }, 100);
      });

      const loginRules: FormRules = {
        username: [
          { required: true, message: '请输入用户名', trigger: 'blur' },
          { min: 3, max: 20, message: '用户名长度在 3 到 20 个字符', trigger: 'blur' },
        ],
        password: [
          { required: true, message: '请输入密码', trigger: 'blur' },
          { min: 6, max: 20, message: '密码长度在 6 到 20 个字符', trigger: 'blur' },
        ],
      };

      const handleLogin = async () => {
        if (!loginFormRef.value) return;

        try {
          await loginFormRef.value.validate();
          loading.value = true;

          const result = await authService.login({
            username: loginForm.username,
            password: loginForm.password,
          });

          if (result) {
            ElMessage.success('登录成功');
            
            // 根据角色跳转
            if (result.userInfo.role === 'admin' || result.userInfo.role === 'super_admin') {
              // 管理员跳转到智能体列表
              router.push('/daren-agent');
            } else {
              // 普通用户跳转到绑定的智能体对话页
              let agentId = result.userInfo.agentId;
              
              // 如果没有绑定智能体，获取第一个已发布的智能体
              if (!agentId) {
                try {
                  const response = await agentScopeApi.list();
                  const agents = response?.data?.data || response?.data || [];
                  if (agents.length > 0) {
                    // 获取第一个已发布的智能体
                    const publishedAgent = agents.find((a: any) => a.status === 'published') || agents[0];
                    agentId = publishedAgent.id;
                  } else {
                    ElMessage.warning('暂无可用的智能体');
                    return;
                  }
                } catch (e) {
                  console.error('获取智能体列表失败:', e);
                  ElMessage.error('获取智能体信息失败');
                  return;
                }
              }
              
              router.push(`/daren-agent/${agentId}/run`);
            }
          }
        } catch (error: any) {
          ElMessage.error(error.response?.data?.message || error.message || '登录失败，请检查用户名和密码');
        } finally {
          loading.value = false;
        }
      };

      return {
        loginFormRef,
        usernameInputRef,
        loginForm,
        loginRules,
        loading,
        handleLogin,
        User,
        Lock,
        CircleClose,
      };
    },
  });
</script>

<style scoped>
  .login-container {
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
    /* 新背景：蓝调深色 + 中心微亮 */
    background: radial-gradient(circle at 50% 40%, rgba(30, 58, 138, 0.45) 0%, transparent 60%),
                linear-gradient(135deg, #020617 0%, #0f172a 40%, #020617 100%);
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
    position: relative;
    overflow: hidden;
  }

  /* 背景动画 */
  .bg-animation {
    position: absolute;
    width: 100%;
    height: 100%;
    top: 0;
    left: 0;
    overflow: hidden;
  }

  .wave {
    position: absolute;
    width: 200%;
    height: 200%;
    /* 增大亮度和扩散范围，让光晕更通透 */
    background: radial-gradient(ellipse at center, rgba(59, 130, 246, 0.24) 0%, transparent 70%);
    animation: wave-float 20s ease-in-out infinite;
  }

  .wave1 {
    top: -100%; left: -50%; animation-delay: 0s;
  }
  .wave2 {
    top: -80%; left: -30%; animation-delay: -5s;
    background: radial-gradient(ellipse at center, rgba(139, 92, 246, 0.18) 0%, transparent 70%);
  }
  .wave3 {
    top: -60%; left: -10%; animation-delay: -10s;
    background: radial-gradient(ellipse at center, rgba(6, 182, 212, 0.15) 0%, transparent 70%);
  }

  @keyframes wave-float {
    0%, 100% { transform: translate(0, 0) rotate(0deg); }
    25% { transform: translate(-5%, 5%) rotate(2deg); }
    50% { transform: translate(0, 10%) rotate(0deg); }
    75% { transform: translate(5%, 5%) rotate(-2deg); }
  }

  /* 登录卡片 */
  .login-box {
    width: 420px;
    padding: 50px 40px;
    /* 卡片背景提亮、边框更明显 */
    background: rgba(15, 23, 42, 0.85);
    backdrop-filter: blur(24px);
    border-radius: 20px;
    box-shadow:
      0 0 40px rgba(59, 130, 246, 0.22),
      0 0 80px rgba(139, 92, 246, 0.12),
      inset 0 0 60px rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(59, 130, 246, 0.45);
    position: relative;
    z-index: 10;
  }

  /* Logo区域 */
  .login-header {
    text-align: center;
    margin-bottom: 40px;
  }

  .logo-container {
    display: flex;
    justify-content: center;
    margin-bottom: 24px;
  }

  .logo-icon {
    position: relative;
    width: 80px;
    height: 80px;
  }

  .logo-inner {
    position: absolute;
    top: 50%;
    left: 50%;
    width: 50px;
    height: 50px;
    transform: translate(-50%, -50%);
    /* 渐变更鲜艳 */
    background: linear-gradient(135deg, #3b82f6 0%, #8b5cf6 50%, #06b6d4 100%);
    border-radius: 16px;
    /* 辉光增强 */
    box-shadow:
      0 0 30px rgba(59, 130, 246, 0.6),
      0 0 60px rgba(139, 92, 246, 0.35),
      0 0 80px rgba(6, 182, 212, 0.18);
  }

  .logo-ring {
    position: absolute;
    top: 50%;
    left: 50%;
    width: 70px;
    height: 70px;
    transform: translate(-50%, -50%);
    border: 2px solid rgba(59, 130, 246, 0.65);
    border-radius: 50%;
    animation: ring-pulse 2s ease-in-out infinite;
  }

  @keyframes ring-pulse {
    0%, 100% { transform: translate(-50%, -50%) scale(1); opacity: 1; }
    50% { transform: translate(-50%, -50%) scale(1.12); opacity: 0.7; }
  }

  .login-title {
    display: flex;
    flex-direction: column;
    align-items: center;
    margin: 0 0 8px 0;
  }

  .title-text {
    font-size: 42px;
    font-weight: 700;
    /* 渐变更亮 */
    background: linear-gradient(135deg, #3b82f6 0%, #06b6d4 45%, #8b5cf6 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    background-clip: text;
    letter-spacing: 8px;
    /* 增大辉光 */
    text-shadow: 0 0 40px rgba(59, 130, 246, 0.6), 0 0 70px rgba(139, 92, 246, 0.25);
  }

  .title-sub {
    font-size: 12px;
    font-weight: 400;
    color: rgba(255, 255, 255, 0.55);
    letter-spacing: 6px;
    margin-top: 4px;
  }

  .login-subtitle {
    font-size: 14px;
    color: rgba(255, 255, 255, 0.7);
    margin: 0;
    letter-spacing: 2px;
  }

  .login-form {
    margin-bottom: 30px;
  }

  /* 输入框样式 */
  .tech-input :deep(.el-input__wrapper) {
    background: rgba(255, 255, 255, 0.08);
    border: 1px solid rgba(59, 130, 246, 0.5);
    box-shadow: none;
    border-radius: 12px;
    padding: 8px 16px;
    transition: all 0.3s ease;
  }

  .tech-input :deep(.el-input__wrapper:hover),
  .tech-input :deep(.el-input__wrapper.is-focus) {
    border-color: rgba(59, 130, 246, 1);
    box-shadow: 0 0 20px rgba(59, 130, 246, 0.35);
    background: rgba(255, 255, 255, 0.12);
  }

  .tech-input :deep(.el-input__inner) {
    color: rgba(255, 255, 255, 1);
    font-size: 15px;
  }
  .tech-input :deep(.el-input__inner::placeholder) {
    color: rgba(255, 255, 255, 0.55);
  }
  .tech-input :deep(.el-input__prefix) {
    color: rgba(59, 130, 246, 1);
  }

  /* 清除图标样式 */
  .tech-input :deep(.el-input__suffix) {
    display: flex;
    align-items: center;
    cursor: pointer;
  }

  .clear-icon {
    display: flex;
    align-items: center;
    color: rgba(255, 255, 255, 0.65);
    transition: color 0.2s;
  }

  .clear-icon:hover {
    color: rgba(255, 255, 255, 1);
  }

  /* 登录按钮 */
  .login-button {
    width: 100%;
    height: 50px;
    font-size: 16px;
    font-weight: 600;
    letter-spacing: 8px;
    border: none;
    border-radius: 12px;
    /* 渐变更亮，增加青色过渡 */
    background: linear-gradient(135deg, #3b82f6 0%, #8b5cf6 55%, #06b6d4 100%);
    box-shadow:
      0 4px 24px rgba(59, 130, 246, 0.5),
      0 0 40px rgba(139, 92, 246, 0.25),
      0 0 60px rgba(6, 182, 212, 0.12);
    transition: all 0.3s ease;
  }

  .login-button:hover {
    transform: translateY(-2px);
    box-shadow:
      0 6px 30px rgba(59, 130, 246, 0.6),
      0 0 60px rgba(139, 92, 246, 0.35),
      0 0 80px rgba(6, 182, 212, 0.18);
  }

  .login-button:active {
    transform: translateY(0);
  }

  /* 底部信息 */
  .login-footer {
    text-align: center;
    margin-top: 20px;
  }

  .tech-line {
    width: 60px;
    height: 2px;
    background: linear-gradient(90deg, transparent, rgba(59, 130, 246, 1), transparent);
    margin: 0 auto 16px;
  }

  .footer-text {
    font-size: 12px;
    color: rgba(255, 255, 255, 0.45);
    margin: 0;
    letter-spacing: 1px;
  }

  @media (max-width: 768px) {
    .login-box {
      width: 90%;
      padding: 40px 30px;
    }
    .title-text {
      font-size: 36px;
    }
  }
</style>

