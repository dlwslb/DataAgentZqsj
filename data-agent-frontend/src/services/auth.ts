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
  tenantId: number;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  userInfo: UserInfo;
}

class AuthService {
  async login(request: LoginRequest): Promise<LoginResponse | null> {
    try {
      const response = await apiClient.post('/api/auth/login', request);
      if (response.data.code === 0 && response.data.data) {
        const loginData = response.data.data;
        localStorage.setItem('accessToken', loginData.accessToken);
        localStorage.setItem('refreshToken', loginData.refreshToken);
        localStorage.setItem('userInfo', JSON.stringify(loginData.userInfo));
        return loginData;
      }
      throw new Error(response.data.message || '登录失败');
    } catch (error: any) {
      console.error('Login error:', error);
      throw error;
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
}

export default new AuthService();
