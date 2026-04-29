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

import { apiClient } from './common';

export interface UserInfo {
  id: number;
  username: string;
  nickname: string;
  email: string;
  phone: string;
  remark: string;
  avatar: string;
  role: string;
  status: number;
  province: string;
  agentId: number | null;
  loginIp: string;
  loginDate: string;
  createTime: string;
  updateTime: string;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface CreateUserRequest {
  username: string;
  password: string;
  nickname?: string;
  email?: string;
  phone?: string;
  province?: string;
  agentId?: number | null;
  remark?: string;
  role?: string;
  avatar?: string;
}

export interface UpdateUserRequest {
  username?: string;
  nickname?: string;
  email?: string;
  phone?: string;
  province?: string;
  agentId?: number | null;
  remark?: string;
  role?: string;
  avatar?: string;
  status?: number;
}

class UserService {
  /**
   * 获取用户列表（分页）
   */
  async getUsers(page: number = 1, pageSize: number = 10, keyword?: string): Promise<PageResult<UserInfo>> {
    const params = new URLSearchParams();
    params.append('page', page.toString());
    params.append('pageSize', pageSize.toString());
    if (keyword) {
      params.append('keyword', keyword);
    }
    const response = await apiClient.get(`/api/users?${params.toString()}`);
    if (response.data.code === 0) {
      return response.data.data;
    }
    throw new Error(response.data.message || '获取用户列表失败');
  }

  /**
   * 获取用户列表（兼容旧接口）
   */
  async getUsersList(keyword?: string): Promise<UserInfo[]> {
    const params = keyword ? `?keyword=${encodeURIComponent(keyword)}` : '';
    const response = await apiClient.get(`/api/users${params}`);
    if (response.data.code === 0) {
      return response.data.data.list;
    }
    throw new Error(response.data.message || '获取用户列表失败');
  }

  /**
   * 获取单个用户
   */
  async getUser(id: number): Promise<UserInfo> {
    const response = await apiClient.get(`/api/users/${id}`);
    if (response.data.code === 0) {
      return response.data.data;
    }
    throw new Error(response.data.message || '获取用户信息失败');
  }

  /**
   * 创建用户
   */
  async createUser(data: CreateUserRequest): Promise<UserInfo> {
    const response = await apiClient.post('/api/users', data);
    if (response.data.code === 0) {
      return response.data.data;
    }
    throw new Error(response.data.message || '创建用户失败');
  }

  /**
   * 更新用户
   */
  async updateUser(id: number, data: UpdateUserRequest): Promise<void> {
    const response = await apiClient.put(`/api/users/${id}`, data);
    if (response.data.code !== 0) {
      throw new Error(response.data.message || '更新用户失败');
    }
  }

  /**
   * 重置密码
   */
  async resetPassword(id: number, password: string): Promise<void> {
    const response = await apiClient.put(`/api/users/${id}/reset-password`, { password });
    if (response.data.code !== 0) {
      throw new Error(response.data.message || '重置密码失败');
    }
  }

  /**
   * 删除用户
   */
  async deleteUser(id: number): Promise<void> {
    const response = await apiClient.delete(`/api/users/${id}`);
    if (response.data.code !== 0) {
      throw new Error(response.data.message || '删除用户失败');
    }
  }

  /**
   * 切换用户状态
   */
  async toggleStatus(id: number, status: number): Promise<void> {
    const response = await apiClient.put(`/api/users/${id}/status`, { status });
    if (response.data.code !== 0) {
      throw new Error(response.data.message || '切换状态失败');
    }
  }
}

export default new UserService();
