// 开发环境配置
const API_TARGET = 'http://localhost:58065';

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
};
