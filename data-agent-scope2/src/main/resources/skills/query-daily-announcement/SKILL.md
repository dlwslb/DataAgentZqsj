---
name: query-daily-announcement
description: 【每日/当日标讯查询专用】当用户问题包含"今日/昨天/今日最新/昨日中标/今日招标/今日采购/今日商机"等关键词时，**必须优先使用本 skill**（不要用 query-bid-winner / query-bidding / query-purchase-intention / query-prepose）。本 skill 走 chatbi.bid_origin_announcement 表，按 channel 区分内容，**province 必传**（用户授权范围内的省份）。
---

# 每日标讯查询

## 适用场景
- "今日有哪些招标公告"
- "昨天的中标信息"
- "今天各省有哪些商机"
- "今天/昨日全国最新标讯"
- "今天贵州省的中标公告"

## 不适用
- 特定行业/公司/金额筛选（用对应专项 skill）
- 统计类查询（用 `trend-analysis`）
- 历史数据查询（非今日/昨日）

## 工作流程

### 第一步：判断 channel

**channel 字段在表里存的是具体子类型**（不是大类名），根据用户问题中的关键词判断：

| 用户说法关键词 | channel 值（传这个给工具）| 表里实际值 |
|--------------|-----------------|------------|
| 中标、中标信息、中标公告、中标结果 | `中标` | 实际匹配 `中标信息`、`合同公告` |
| 招标、招标公告、招标预告、招标文件 | `招标` | 实际匹配 `招标文件`、`招标预告`、`招标公告` |
| 采购、采购意向、政府采购 | `采购` | 实际匹配 `采购意向` |
| 审批项目、拟在建、前置商机、前期项目 | `前置商机` | 实际匹配 `审批项目`、`拟在建项目` |
| 没有明确类型（泛问今日） | 不传 channel（查全量） | - |

> **重要**：工具 SQL 会对 `channel` 字段做 LIKE 模糊匹配，传大类名"中标"就能匹配表里的"中标信息"和"合同公告"。不要试图精确传"中标信息"或"合同公告"这种子类型——传"中标"就行。

### 第二步：调用 Tool

调用 `query_biz_data` 工具，参数：

```json
{
  "bizType": "origin_announcement",
  "conditions": {
    "datePreset": "today",
    "channel": "<channel 值，如 '中标'、'招标'、'采购'、'前置商机'>",
    "province": "<省份，如 '贵州省'>",
    "city": "<地市，如 '贵阳市'>",
    "district": "<区县>",
    "title": "<标题关键词，模糊匹配>",
    "winTenderer": "<中标单位，模糊匹配>",
    "webSourceName": "<网站来源>"
  },
  "limit": 50
}
```

> 注意：**必须传 province**（用户授权范围内的省份）。本表（origin_announcement）无 tenant_id 字段，按 province 过滤。

## 关键字段（bid_origin_announcement 表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `publish_time` | date | **发布时间**（格式 yyyy-MM-dd，今日/昨日筛选用这个字段）|
| `title` | varchar | 标题（全文索引，不输出 product）|
| `province` | varchar | 省份简称，无"省"字（如"黑龙江"、"山东"）|
| `city` | varchar | 地市 |
| `district` | varchar | 区县 |
| `channel` | varchar | 公告类型：招标 / 中标 / 采购 / 前置商机 |
| `win_tenderer` | varchar | 中标单位 |
| `win_bid_amount` | varchar | 中标金额（已归一化为万元）|
| `budget_amount` | varchar | 预算金额（万元）|
| `web_source_name` | varchar | 网站来源 |
| `product` | text | 产品信息，**查询/输出时不强调此字段** |

## channel 类型说明

### channel = "招标"
- 包含：招标文件、招标预告、招标公告
- 代表：尚处于招标阶段的公告（未开标）
- 若用户同时关心"招标+中标"，分别调用两次，合并结果

### channel = "中标"
- 包含：中标信息、合同公告
- 代表：已确定中标方的公告
- 特有字段：`win_tenderer`（中标单位）、`win_bid_amount`（中标金额）

### channel = "采购"
- 包含：采购意向、政府采购意向
- 代表：招标前置阶段（预算/计划公示）

### channel = "前置商机"
- 包含：审批项目、拟在建项目
- 代表：项目最早期的储备阶段

## datePreset 快捷日期

| 用户说法 | datePreset |
|---------|-----------|
| 今日、今天 | `today` |
| 昨日、昨天 | `yesterday` |

> 注意：`publish_time` 是 date 类型，`datePreset` 会自动算好日期范围。

## 输出模板

```
## 今日/昨日标讯（共 N 条）

**筛选条件**：channel={channel}，省份={province}，时间={datePreset}

### 中标（X 条）
| 标题 | 中标单位 | 中标金额（万元）| 省份 | 发布时间 |
|------|---------|--------------|------|---------|
| ... | ... | ... | ... | ... |

### 招标（X 条）
| 标题 | 省份 | 发布时间 |
|------|------|---------|
| ... | ... | ... |
```

## 兜底逻辑
- channel 不明确 → 查全量（不传 channel 参数）
- 无数据 → "今日/昨日暂无相关标讯数据"
- `bizType=origin_announcement` **必传 province**（用户授权范围内），不再是 tenantId 逻辑
