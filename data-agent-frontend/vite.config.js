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
import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import compression from 'vite-plugin-compression';
import { resolve } from 'path';
import devConfig from './config/dev.config.js';
import prodConfig from './config/prod.config.js';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const isProd = mode === 'production';
  const config = isProd ? prodConfig : devConfig;

  return {
    plugins: [vue(), compression({ algorithm: 'gzip' })],
    base: config.base,
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
    },
    server: {
      ...config.server,
      historyApiFallback: true,
    },
    build: {
      outDir: config.build.outDir,
      assetsDir: 'assets',
    },
    define: {
      // 生产环境使用空字符串（相对路径），开发环境使用完整 URL
      'import.meta.env.VITE_AGENT_SCOPE_API_TARGET': JSON.stringify(
        config.agentScope?.apiTarget !== undefined ? config.agentScope.apiTarget : 'http://localhost:58064'
      ),
      // SSE 2.0（data-agent-scope2）后端地址：合并自 .env，统一由 config/ 注入
      'import.meta.env.VITE_DATA_AGENT_SCOPE2_API_TARGET': JSON.stringify(
        config.dataAgentScope2?.apiTarget !== undefined ? config.dataAgentScope2.apiTarget : 'http://localhost:58063'
      ),
    },
  };
});
