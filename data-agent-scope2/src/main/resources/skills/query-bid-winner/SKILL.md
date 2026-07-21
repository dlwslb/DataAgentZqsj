---
name: query-bid-winner
description: 【中标结果查询 - 别和"招标"搞混】当用户查询的是**已经开标后的中标结果**（含"中标"二字但**不含"招标"**）时使用本 skill。例如："中标信息/中标公示/中标候选人/中标金额/中标单位/中标公告/中标结果/中标通知/广西中标/江苏中标"。**关键区分**：用户说"招标公告/招标预告/招标文件"用 `query-bidding`；用户说"中标"**一定**用本 skill（不要误用 `query-bidding`）。表 `chatbi.bid_biz_win_bid`。【注意】"今日/昨天"的中标用 `query-daily-announcement`（原始每日标讯表）。
---

# 中标信息查询

## 适用场景
- "XX 市最近一个月信息技术行业的中标项目有哪些"
- "XX 公司最近中过哪些标"
- "查询 XX 项目的招标结果（中标了谁）"
- "XX 行业 2025 年的中标统计"
- "按金额排序的中标项目"
- "XX 运营商中标情况"

## 不适用
- 招标公告（未开标时）→ 用 `query-bidding`
- 采购意向（招标计划阶段）→ 用 `query-purchase-intention`
- 拟在建/审批项目 → 用 `query-prepose`

## 工具调用

调用 `query_biz_data` 工具，参数：

```json
{
  "bizType": "bid_winner",
  "province": "<从 System Context 拿，必填，单值如北京/上海，多值如北京,上海>",
  "datePreset": "<快捷日期预设，可选，优先于 startDate/endDate>",
  "conditions": {
    "province": "<省份，如 '贵州省'>",
    "city": "<地市，如 '贵阳市'>",
    "district": "<区县，如 '南明区'>",
    "industry": "<行业大类，如 '信息技术'>",
    "infoType": "<行业中类，如 '软件开发'>",
    "tenderer": "<招标单位，模糊匹配>",
    "winTenderer": "<中标单位，模糊匹配>",
    "channel": "<公告类型，如 '公开招标'>",
    "webSourceName": "<网站来源，如 '中国政府采购网'>",
    "keyword": "<项目名/标题/关键词，模糊匹配>",
    "startDate": "yyyy-MM-dd",
    "endDate": "yyyy-MM-dd",
    "minBudget": <最小中标金额，元>,
    "maxBudget": <最大中标金额，元>
  },
  "limit": <1-100，默认 20>
}
```

## 常用日期快捷方式（datePreset）

**优先用 datePreset**——LLM 自己算日期容易出错（跨天/跨月/跨年时区），用预设更稳：

| 用户说法 | datePreset |
|---------|-----------|
| 今日中标 / 今天的中标 | `today` |
| 昨日中标 | `yesterday` |
| 本周中标 / 这周的中标 | `thisWeek` |
| 上周中标 | `lastWeek` |
| 本月中标 / 这个月的中标 | `thisMonth` |
| 上月中标 | `lastMonth` |
| 最近 7 天 / 近一周 | `last7Days` |
| 最近 30 天 / 近一个月 | `last30Days` |

## 必传参数
- `bizType` = "bid_winner"（固定）
- `province` = **必填**，用户授权省份（System Context 里的 authorizedProvince）

## 关键字段说明（bid_biz_win_bid 表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | String | 公告标题 |
| `projectName` | String | 项目名称 |
| `province` / `city` / `district` | String | 省/市/县（区），三级独立 |
| `industry` | String | 行业大类 |
| `infoType` | String | 行业中类 |
| `tenderer` | String | 招标单位（注意：不是中标单位）|
| `winTenderer` | String | **中标单位**（win_bid 表独有）|
| `winBidPrice` | BigDecimal | **中标金额**（元，win_bid 表独有，Tool 归一化为 amount）|
| `biddingBudget` | BigDecimal | 预算金额 |
| `channel` | String | 公告类型 |
| `publishTime` | LocalDate | 发布时间 |
| `product` | String | 产品/采购需求 |
| `agency` | String | 代理机构 |
| `operator` | String | 运营商归属 |
| `operatorWinStatus` | Boolean | 运营商是否中标 |
| `topGrade` | String | 优标级别（优标/次优标/非优标）|

## 结果处理

**情况 A：结果条数 ≤ 10**
- 直接用 `format_report` 生成表格报告
- 不做统计分析

**情况 B：结果条数 > 10**
- 先用 `run_python` 做聚合分析（按 region/industry/month 分组统计）
- 再用 `format_report` 生成摘要 + Top10 表格

## 兜底逻辑
- 工具返回 error → 告知用户"查询失败：<错误信息>"
- 结果为空 → 告知用户"未找到相关中标信息"
- 条件不全 → 主动追问缺失的关键条件

## 输出模板

```
## 中标信息查询结果

**查询条件**：省份=XX，地市=XX，行业=XX，时间=XX

**总数**：XX 条

### Top 10
| 项目名称 | 招标单位 | 中标单位 | 中标金额 | 发布时间 |
|---|---|---|---|---|
| ... | ... | ... | ... | ... |

### 统计摘要（数据多时）
- 按地区：XX 地区最多（XX 条）
- 按行业：XX 行业最热门
- 总中标金额：XX 万元
- 平均中标金额：XX 万元
```

## 示例

用户："查询上海最近一个月信息技术行业的中标项目"
→ province="上海", industry="信息技术", startDate="2026-06-10", bizType="bid_winner"
