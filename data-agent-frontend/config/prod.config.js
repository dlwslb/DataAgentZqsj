// 生产环境配置
// 生产环境使用相对路径（带项目名），由 Nginx 反向代理转发到后端
const API_TARGET = '/zqsjAgents';

export default {
  base: '/zqsjAgents/',
  build: {
    outDir: 'zqsjAgents',
  },
  agentScope: {
    apiTarget: API_TARGET,  // 带项目名的相对路径
  },
};
