# DataAgent Scope 2.0

> 基于 **AgentScope Java 2.0 GA** + **HarnessAgent** + **Skill 机制** 的企业级数据查询 Agent

## 与 1.0 的关系

| 维度 | `data-agent-scope` (1.0) | `data-agent-scope2` (2.0) |
|------|------|------|
| Agent 入口 | `ReActAgent` | `HarnessAgent` |
| 扩展机制 | Hook（单一回调）| Middleware（五阶段）|
| 工作区 | 自实现 | 框架内置（AGENTS.md / skills/ / plans/）|
| 长期记忆 | `LongTermMemory` + `LongTermMemoryMode.BOTH` | 分层记忆 + 自动压缩 + MEMORY.md |
| 计划 | `PlanNotebook` + 自实现 `DatabasePlanStorage` | Plan Mode（计划文件持久化）|
| 工具执行 | 自实现 `ToolRegistry` 注解扫描 | `Toolkit` 注解扫描（兼容 1.0）|
| **技能** | ❌ 无 | ✅ **核心特性**——Markdown 工作流模板 |
| 会话恢复 | `BoundedRedisMemory` 自实现 | `AgentStateStore` 标准化（file/redis）|
| 协议 | A2A/AG-UI starter | 同上 + 新增 Agent Protocol |

**关键设计**：1.0 把"流程编排"写在 Hook 里，2.0 把"成功模式"沉淀成 Skill，让 agent 自学。

## 核心特性：Skill 驱动

**Skill = Markdown 工作流模板**。把"什么场景下怎么查数据"写进 `SKILL.md`，agent 自动按需加载执行。

```
src/main/resources/skills/
├── query-bid-winner/             # 中标信息查询
│   ├── SKILL.md                  # 描述使用场景 + 执行步骤
│   └── references/
│       └── bid-tables.md         # 表结构参考
├── query-bidding/                # 招标信息查询
│   ├── SKILL.md
│   └── references/
│       └── bidding-tables.md
├── query-purchase-intention/     # 采购意向查询
│   ├── SKILL.md
│   └── references/
│       └── purchase-tables.md
└── trend-analysis/               # 趋势分析
    ├── SKILL.md
    └── scripts/
        └── trend.py
```

**Skill vs Tool**：
- **Tool** = 原子能力（Java 方法）：`query_biz_data` / `run_python` / `format_report`
- **Skill** = 工作流模板（Markdown）："中标查询"skill 告诉 agent "调 `query_biz_data(bizType=bid_winner)` + 用 `format_report` 整理"
- **Agent** 看到用户问题 → 选 skill → 按 skill 步骤调 tool

## 快速开始

### 1. 准备环境

- JDK 17+
- Maven 3.9+
- MySQL 5.7+ （仅生产用）
- Redis （仅生产用）
- DashScope API Key

### 2. 配置

`application.yml` 关键配置：

```yaml
agentscope:
  model:
    default: dashscope:qwen-plus   # 也可写 openai:gpt-4.1 / anthropic:claude-sonnet-4-5
  state-store:
    type: file                      # dev: file, prod: redis
  skill:
    type: classpath                 # dev: classpath, prod: mysql
```

### 3. 启动

```bash
# 在父项目根目录
./mvnw -pl data-agent-scope2 -am spring-boot:run
```

或者：

```bash
cd data-agent-scope2
../mvnw spring-boot:run
```

启动后访问：
- API: `http://localhost:8082`
- Swagger UI: `http://localhost:8082/swagger-ui.html`
- 健康检查: `http://localhost:8082/api/health`

### 4. 调用

**SSE 流式**：
```bash
curl -X POST http://localhost:8082/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "alice",
    "sessionId": "demo-001",
    "tenantId": "default",
    "message": "查询上海最近一个月信息技术行业的中标项目"
  }'
```

**阻塞式**：
```bash
curl -X POST http://localhost:8082/api/chat/block \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "alice",
    "sessionId": "demo-001",
    "message": "近三年信息技术行业的中标趋势"
  }'
```

## 多租户

通过 `RuntimeContext` 透传 `userId` / `sessionId` / `tenantId`：

| 字段 | 用途 |
|------|------|
| `userId` | 用户隔离（不同用户的 skill 走 `workspace/<userId>/skills/`）|
| `sessionId` | 会话隔离（同一用户不同会话状态独立）|
| `tenantId` | 租户隔离（多组织场景）|

## 添加新 Skill

**步骤 1**：创建目录
```bash
mkdir -p src/main/resources/skills/my-new-skill/references
```

**步骤 2**：写 `SKILL.md`
```markdown
---
name: my-new-skill
description: 当用户要……时使用。description 越精准，agent 越能正确选 skill。
---

# My New Skill

## 执行步骤
1. 调用工具 xxx
2. 处理结果

## 输出模板
……
```

**步骤 3**（可选）：放 references / scripts

**步骤 4**：重启服务即可，agent 自动加载

**Skill 描述模板**：
```markdown
---
name: <动作-对象>
description: 当用户要<动作 A>、<动作 B>、<动作 C>时使用。包括<场景 1>、<场景 2>、<场景 3>。
---
```

description 关键要素：
- **动词**：查询/分析/生成/对比
- **对象**：具体业务实体
- **场景**：典型使用场景

## 切换到 MySQL Skill 仓库

`application.yml`：
```yaml
agentscope:
  skill:
    type: mysql
    mysql:
      database: data_agent_v2
      table: agentscope_skill
      create-if-not-exist: true
      writeable: true
```

## 升级路径（来自 1.0）

如果你之前用的是 `data-agent-scope`（基于 AgentScope 1.0.12），可参考以下路径迁移：

1. **第一阶段**：新 module 跑通，1.0 继续服务
2. **第二阶段**：用 1.0 的 `data-agent-management` 业务配置引导部分流量到 2.0
3. **第三阶段**：1.0 工具的 Python 沙箱、SQL 生成等可以**复用**（远程调用或迁移为 2.0 的 tool）
4. **第四阶段**：完全切到 2.0，1.0 模块归档

### 1.0 工具迁移到 2.0

| 1.0 自实现 | 2.0 直接用 |
|------------|------------|
| `BoundedRedisMemory` | `RedisAgentStateStore`（标准接口）|
| `OceanBaseLongTermMemory` | `BailianMemory` / `Mem0` / `ReMe` |
| `DatabasePlanStorage` | Plan Mode（自动落盘）|
| 4 个 Hook（`DirectResponseHook` 等）| Middleware 五阶段 + Permission System |
| `ToolRegistry` 注解扫描 | `Toolkit` 注解扫描（API 兼容）|
| HITL 自实现 | `PermissionGate` + `LocalApprovalGate` |
| 消息流自实现 | 28 种类型化事件 |

## 注意事项

- **Jackson 兼容性**：2.0 默认 Jackson 3.x，本项目用 exclusion 切换到 Spring Boot 自带的 2.x
- **WebFlux**：2.0 默认响应式，本项目用 `spring-boot-starter-webflux`
- **状态存储**：dev 用 `JsonFileAgentStateStore`，prod 必须用 Redis
- **Skill 描述**：description 决定 agent 用不用这个 skill，必须精准

## 路线图

- [ ] 接入 1.0 的真实数据查询（替换 mock tool）
- [ ] MySQL Skill 仓库 + 后台管理
- [ ] 自学习闭环（agent 自己起草新 skill）
- [ ] Channel 接入（钉钉/飞书/企业微信）
- [ ] Studio 可视化调试
