---
name: query-purchase-intention
description: 当用户要查询采购意向、采购计划、采购预告、政府采购意向时使用。这是招标的前置阶段，通常是预算/计划公示阶段。仅查 bid_biz_purchase_intention 表。
---

# 采购意向查询

## 适用场景
- "最近的政府采购意向"
- "XX 单位今年有什么采购计划"
- "XX 行业的采购预算"
- "政府采购意向公开"
- "挖掘潜在商机（采购意向 → 后续招标跟踪）"

## 业务特点
- 时间上比"招标公告"更早（通常提前 1-3 个月公示）
- 数据相对"招标"更模糊（意向 ≠ 确定）
- 适合做"商机挖掘"和"市场预测"

## 工具调用

调用 `query_biz_data` 工具，参数：

```json
{
  "bizType": "purchase_intention",
  "province": "<从 System Context 拿，必填，单值如北京/上海，多值如北京,上海>",
  "datePreset": "<快捷日期预设，可选，优先于 startDate/endDate>",
  "conditions": {
    "province": "<省份>",
    "city": "<地市>",
    "district": "<区县>",
    "industry": "<行业大类>",
    "infoType": "<行业中类>",
    "tenderer": "<招标单位，模糊匹配>",
    "channel": "<公告类型>",
    "webSourceName": "<网站来源>",
    "keyword": "<项目名/标题/关键词，模糊匹配>",
    "startDate": "yyyy-MM-dd",
    "endDate": "yyyy-MM-dd",
    "minBudget": <最小预算，元（过滤条件仍按元，Tool 内部转万元过滤）>,
    "maxBudget": <最大预算，元（过滤条件仍按元，Tool 内部转万元过滤）>
  },
  "limit": <1-100，默认 20>
}
```

## 常用日期快捷方式（datePreset）

| 用户说法 | datePreset |
|---------|-----------|
| 今日采购意向 | `today` |
| 昨日采购意向 | `yesterday` |
| 本周采购意向 | `thisWeek` |
| 上周采购意向 | `lastWeek` |
| 本月采购意向 | `thisMonth` |
| 上月采购意向 | `lastMonth` |
| 最近 7 天 | `last7Days` |
| 最近 30 天 | `last30Days` |

**注意金额单位**：purchase_intention 用**元**（区别于 bid_winner/bidding 的元）。

## 必传参数
- `bizType` = "purchase_intention"（固定）
- `province` = **必填**，用户授权省份（System Context 里的 authorizedProvince）

## 关键字段说明（bid_biz_purchase_intention 表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | String | 公告标题 |
| `projectName` | String | **采购项目名称** |
| `province` / `city` / `district` | String | 省/市/县 |
| `industry` | String | 行业分类（大类）|
| `infoType` | String | 行业分类（中类）|
| `tenderer` | String | 招标单位 |
| `biddingBudget` | BigDecimal | **招标预算金额**（DB 存元，Tool 输出已转**万元**，保留 2 位小数）|
| `timeBidOpen` | LocalDate | 预计采购开始时间 |
| `timeBidClose` | LocalDate | 预计采购结束时间 |
| `product` | String | **采购需求概况** |
| `channel` | String | 公告类型 |
| `publishTime` | LocalDate | 发布时间 |
| `detailLink` | String | 公告原链接 |
| `webSourceName` | String | 网站来源 |
| `bidUrl` | String | 比地官网链接 |
| `keywords` | String | 关键词 |
| `notes` | String | 备注 |
| `marketingUnit` | String | 营销单位 |
| `listLevel` / `listGridCus` | String | 层级/名单客户网格 |
| `yewuleixing` | String | 业务类型 |
| `customerIn` | String | 是否名单制 |
| `managerAOa` | String | 首席客户经理 A 角 OA 工号 |
| `field` | String | 领域 |
| `kept` | Boolean | 是否保留 |
| `completed` | Boolean | 是否完整 |

## 结果处理

**情况 A：结果条数 ≤ 10**
- 直接用 `format_report` 生成表格
- 突出 `tenderer` / `biddingBudget` / `timeBidOpen`

**情况 B：结果条数 > 10**
- 按 `industry` / `tenderer` 分组聚合
- 突出"高预算单位"

## 业务引导（重要）

采购意向不是承诺，实际招标可能有变化：
- 查"采购意向" → **主动建议**："如果您想看后续招标进展，可用 `query-bidding` 跟踪"
- 用户问"这个项目多久后会招标" → 参考 `timeBidOpen` / `timeBidClose` 字段

## 输出模板

```
## 采购意向查询结果

**查询条件**：省份=XX，地市=XX，行业=XX，时间=XX

**总数**：XX 条，**总预算**：XX 万元

### Top 10
| 招标单位 | 项目名称 | 预算金额（万元） | 预计采购时间 | 行业 |
|---|---|---|---|---|
| ... | ... | ... | ... | ... |

### 业务建议（默认不输出，仅当用户明确说"分析/解读/总结"时才附）
- 重点关注：XX 单位（XX 万元预算）
- 跟进方式：建议用 `query-bidding` 跟踪招标动态
```

## 示例

用户："查询贵州贵阳最近一个月的政府采购意向"
→ province="贵州省", city="贵阳市", startDate="2026-06-10", bizType="purchase_intention"
