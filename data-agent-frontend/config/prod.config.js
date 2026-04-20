// 生产环境配置
const API_TARGET = '';

export default {
  base: '/zqsjAgents/',
  build: {
    outDir: 'zqsjAgents',
  },
  agentScope: {
    apiTarget: API_TARGET,
  },
};
