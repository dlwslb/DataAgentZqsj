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

// 路由模块化配置
const routes = [
  // 登录页面
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: {
      title: '登录',
      requiresAuth: false,
    },
  },

  // 首页重定向
  {
    path: '/',
    redirect: '/agents',
  },

  // 智能体管理模块
  {
    path: '/agents',
    name: 'AgentList',
    component: () => import('@/views/AgentList.vue'),
    meta: {
      title: '智能体列表',
      module: 'agent',
    },
  },
  {
    path: '/agent/create',
    name: 'AgentCreate',
    component: () => import('@/views/AgentCreate.vue'),
    meta: {
      title: '创建智能体',
      module: 'agent',
    },
  },
  {
    path: '/agent/:id',
    name: 'AgentDetail',
    component: () => import('@/views/AgentDetail.vue'),
    meta: {
      title: '智能体详情',
      module: 'agent',
    },
  },

  {
    path: '/agent/:id/run',
    name: 'AgentRun',
    component: () => import('@/views/AgentRun.vue'),
    meta: {
      title: '运行智能体',
      module: 'agent',
    },
  },

  // AgentScope 智能体模块
  {
    path: '/agent-scope',
    name: 'AgentScopeList',
    component: () => import('@/views/AgentScopeList.vue'),
    meta: {
      title: 'AgentScope 智能体',
      module: 'agent-scope',
    },
  },
  {
    path: '/agent-scope/create',
    name: 'AgentScopeCreate',
    component: () => import('@/views/AgentScopeCreate.vue'),
    meta: {
      title: '创建 AgentScope 智能体',
      module: 'agent-scope',
    },
  },
  {
    path: '/agent-scope/:id',
    name: 'AgentScopeDetail',
    component: () => import('@/views/AgentScopeDetail.vue'),
    meta: {
      title: 'AgentScope 智能体详情',
      module: 'agent-scope',
    },
  },
  {
    path: '/agent-scope/:id/run',
    name: 'AgentScopeRun',
    component: () => import('@/views/AgentScopeRun.vue'),
    meta: {
      title: '运行 AgentScope 智能体',
      module: 'agent-scope',
    },
  },

  // 模型配置模块
  {
    path: '/model-config',
    name: 'ModelConfig',
    component: () => import('@/views/ModelConfig.vue'),
    meta: {
      title: '模型配置',
      module: 'config',
    },
  },

  // 404页面
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
    meta: {
      title: '页面未找到',
      module: 'error',
    },
  },
];

export default routes;
