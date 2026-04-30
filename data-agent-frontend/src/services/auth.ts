/*
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
 */

import { apiClient, ApiResponse } from './common';
import { sm3 } from 'sm-crypto';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface UserInfo {
  id: number;
  username: string;
  nickname: string;
  email: string;
  avatar: string;
  role: string;
  agentId: number | null;
  tenantId: number;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  userInfo: UserInfo;
}

class AuthService {
  /**
   * 使用SM3对密码进行前端加密
   * 符合中国国家密码标准GM/T 0004-2012
   */
  private encryptPassword(password: string): string {
    return sm3(password);
  }

  async login(request: LoginRequest): Promise<LoginResponse | null> {
    try {
      // 使用SM3对密码进行前端加密（第一次加密）
      const encryptedPassword = this.encryptPassword(request.password);
      
      // 获取客户端IP（这里只是示例，实际IP应由后端获取）
      const loginIp = 'client';
      
      const response = await apiClient.post('/api/auth/login', {
        username: request.username,
        password: encryptedPassword,
        loginIp: loginIp,
      });
      if (response.data.code === 0 && response.data.data) {
        const loginData = response.data.data;
        localStorage.setItem('accessToken', loginData.accessToken);
        localStorage.setItem('refreshToken', loginData.refreshToken);
        localStorage.setItem('userInfo', JSON.stringify(loginData.userInfo));
        return loginData;
      }
      // code不为0时的错误信息
      throw new Error(response.data.message || '登录失败，请检查用户名和密码');
    } catch (error: any) {
      console.error('Login error:', error);
      // 从各种可能的来源提取错误信息
      let errorMessage = '登录失败，请检查用户名和密码';
      
      if (error.response?.data?.message) {
        // axios错误响应中的message
        errorMessage = error.response.data.message;
      } else if (error.response?.data?.message) {
        // 可能的嵌套结构
        errorMessage = error.response.data.message;
      } else if (error.message) {
        errorMessage = error.message;
      }
      
      throw new Error(errorMessage);
    }
  }

  async getCurrentUser(): Promise<UserInfo | null> {
    const userInfoStr = localStorage.getItem('userInfo');
    if (userInfoStr) {
      try {
        return JSON.parse(userInfoStr);
      } catch (error) {
        return null;
      }
    }
    return null;
  }

  logout(): void {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('userInfo');
  }

  getToken(): string | null {
    return localStorage.getItem('accessToken');
  }

  getUserInfo(): UserInfo | null {
    const userInfoStr = localStorage.getItem('userInfo');
    if (userInfoStr) {
      try {
        return JSON.parse(userInfoStr);
      } catch (error) {
        return null;
      }
    }
    return null;
  }

  isAuthenticated(): boolean {
    return !!this.getToken();
  }

  /**
   * 模拟用户登录（生成临时Token）
   * 只有超级管理员可以调用
   */
  async impersonateUser(userId: number): Promise<LoginResponse | null> {
    try {
      // 保存当前超级管理员的token
      const originalToken = localStorage.getItem('accessToken');
      const originalUserInfo = localStorage.getItem('userInfo');
      if (originalToken && originalUserInfo) {
        localStorage.setItem('originalAccessToken', originalToken);
        localStorage.setItem('originalUserInfo', originalUserInfo);
      }
      
      const response = await apiClient.post('/api/auth/impersonate', {
        userId: userId,
      });
      
      if (response.data.code === 0 && response.data.data) {
        const loginData = response.data.data;
        // 保存临时Token和用户信息
        localStorage.setItem('accessToken', loginData.accessToken);
        localStorage.setItem('userInfo', JSON.stringify(loginData.userInfo));
        // 标记为模拟会话
        localStorage.setItem('isImpersonating', 'true');
        return loginData;
      }
      throw new Error(response.data.message || '模拟登录失败');
    } catch (error: any) {
      console.error('Impersonate error:', error);
      let errorMessage = '模拟登录失败';
      
      if (error.response?.data?.message) {
        errorMessage = error.response.data.message;
      } else if (error.message) {
        errorMessage = error.message;
      }
      
      throw new Error(errorMessage);
    }
  }

  /**
   * 退出模拟模式，恢复原始用户
   */
  exitImpersonation(): void {
    // 恢复原始用户的token
    const originalToken = localStorage.getItem('originalAccessToken');
    const originalUserInfo = localStorage.getItem('originalUserInfo');
    
    if (originalToken && originalUserInfo) {
      localStorage.setItem('accessToken', originalToken);
      localStorage.setItem('userInfo', originalUserInfo);
    }
    
    // 清除模拟相关标记
    localStorage.removeItem('isImpersonating');
    localStorage.removeItem('originalAccessToken');
    localStorage.removeItem('originalUserInfo');
  }

  /**
   * 检查是否在模拟模式
   */
  isImpersonating(): boolean {
    return localStorage.getItem('isImpersonating') === 'true';
  }
}

export default new AuthService();
