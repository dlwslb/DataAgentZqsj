/*
 * Copyright 2024-2025 the original author or authors.
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
 */

// 定义通用响应结构
export interface ApiResponse<T = unknown> {
  success: boolean;
  message: string;
  data?: T;
}

export interface PageResponse<T = unknown> {
  success: boolean;
  message: string;
  data: T;
  total: number;
  pageNum: number;
  pageSize: number;
  totalPages: number;
}

// 创建统一的 axios 实例，自动添加 base 路径
import axios from 'axios';

const apiClient = axios.create({
  baseURL: import.meta.env.BASE_URL || '/',
});

let isRefreshing = false;
let refreshSubscribers: Array<(token: string) => void> = [];

function onRefreshed(token: string) {
  refreshSubscribers.forEach(cb => cb(token));
  refreshSubscribers = [];
}

function addRefreshSubscriber(cb: (token: string) => void) {
  refreshSubscribers.push(cb);
}

// 请求拦截器 - 自动添加认证令牌和用户信息
apiClient.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    
    // 从 localStorage 获取用户信息并添加到请求头
    const userInfoStr = localStorage.getItem('userInfo');
    if (userInfoStr) {
      try {
        const userInfo = JSON.parse(userInfoStr);
        if (userInfo.id) {
          config.headers['User-ID'] = String(userInfo.id);
        }
        if (userInfo.tenantId) {
          config.headers['Tenant-ID'] = String(userInfo.tenantId);
        }
      } catch (error) {
        console.error('Failed to parse user info:', error);
      }
    }
    
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// 响应拦截器 - 处理认证错误和自动刷新
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    // 登录接口不处理401跳转，避免登录失败时页面闪烁
    if (originalRequest.url?.includes('/api/auth/login')) {
      return Promise.reject(error);
    }

    if (error.response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        return new Promise((resolve) => {
          addRefreshSubscriber((token: string) => {
            originalRequest.headers.Authorization = `Bearer ${token}`;
            resolve(apiClient(originalRequest));
          });
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      const refreshToken = localStorage.getItem('refreshToken');
      if (refreshToken) {
        try {
          // 使用 apiClient 而不是原始 axios，确保 baseURL 和拦截器生效
          const res = await apiClient.post('/api/auth/refresh-token', { refreshToken });
          const { accessToken, refreshToken: newRefreshToken } = res.data.data;
          
          localStorage.setItem('accessToken', accessToken);
          localStorage.setItem('refreshToken', newRefreshToken);
          
          onRefreshed(accessToken);
          
          originalRequest.headers.Authorization = `Bearer ${accessToken}`;
          return apiClient(originalRequest);
        } catch (err) {
          localStorage.removeItem('accessToken');
          localStorage.removeItem('refreshToken');
          localStorage.removeItem('userInfo');
          window.location.href = '/login';
        } finally {
          isRefreshing = false;
        }
      } else {
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        localStorage.removeItem('userInfo');
        window.location.href = '/login';
      }
    }

    return Promise.reject(error);
  }
);

export { apiClient };
