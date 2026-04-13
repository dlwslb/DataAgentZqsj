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
    <div class="login-box">
      <div class="login-header">
        <h1 class="login-title">政企商机智能体</h1>
        <p class="login-subtitle">欢迎登录系统</p>
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
            v-model="loginForm.username"
            placeholder="请输入用户名"
            size="large"
            :prefix-icon="User"
            clearable
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            :prefix-icon="Lock"
            show-password
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
            {{ loading ? '登录中...' : '登 录' }}
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script lang="ts">
  import { defineComponent, ref, reactive } from 'vue';
  import { useRouter } from 'vue-router';
  import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
  import { User, Lock } from '@element-plus/icons-vue';
  import authService from '@/services/auth';

  export default defineComponent({
    name: 'Login',
    components: {
      User,
      Lock,
    },
    setup() {
      const router = useRouter();
      const loginFormRef = ref<FormInstance>();
      const loading = ref(false);

      const loginForm = reactive({
        username: '',
        password: '',
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
            router.push('/agents');
          }
        } catch (error: any) {
          ElMessage.error(error.response?.data?.message || error.message || '登录失败，请检查用户名和密码');
        } finally {
          loading.value = false;
        }
      };

      return {
        loginFormRef,
        loginForm,
        loginRules,
        loading,
        handleLogin,
        User,
        Lock,
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
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    font-family:
      -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  }

  .login-box {
    width: 420px;
    padding: 40px;
    background: white;
    border-radius: 12px;
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1);
  }

  .login-header {
    text-align: center;
    margin-bottom: 40px;
  }

  .login-title {
    font-size: 28px;
    font-weight: 600;
    color: #1f2937;
    margin: 0 0 10px 0;
  }

  .login-subtitle {
    font-size: 14px;
    color: #6b7280;
    margin: 0;
  }

  .login-form {
    margin-bottom: 20px;
  }

  .login-button {
    width: 100%;
    height: 44px;
    font-size: 16px;
    font-weight: 500;
  }

  .login-footer {
    text-align: center;
    margin-top: 20px;
  }

  .footer-text {
    font-size: 12px;
    color: #9ca3af;
    margin: 0;
  }

  @media (max-width: 768px) {
    .login-box {
      width: 90%;
      padding: 30px 20px;
    }

    .login-title {
      font-size: 24px;
    }
  }
</style>
