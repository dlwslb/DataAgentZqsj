// 开发环境配置
const API_TARGET = 'http://localhost:58065';
const AGENT_SCOPE_API_TARGET = 'http://localhost:58064';
const DATA_AGENT_SCOPE2_API_TARGET = 'http://localhost:58063';

export default {
  base: '',
  build: {
    outDir: 'zqsjAgents',
  },
  server: {
    port: 3900,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: API_TARGET,
        changeOrigin: true,
      },
      '/nl2sql': {
        target: API_TARGET,
        changeOrigin: true,
      },
      '/uploads': {
        target: API_TARGET,
        changeOrigin: true,
      },
    },
  },
  agentScope: {
    apiTarget: AGENT_SCOPE_API_TARGET,
  },
  dataAgentScope2: {
    apiTarget: DATA_AGENT_SCOPE2_API_TARGET,
  },
};
