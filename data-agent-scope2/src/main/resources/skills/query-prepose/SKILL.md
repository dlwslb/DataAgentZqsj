---
name: query-prepose
description: 当用户要查询拟在建项目、审批项目、预招标项目、前期商机、项目储备时使用。这是比采购意向更早的阶段（项目立项、审批中）。仅查 bid_biz_prepose 表。
---

# 拟在建项目查询

## 适用场景
- "查一下拟在建项目"
- "立项中的项目有哪些"
- "项目储备清单"
- "预招标项目跟踪"
- "审批阶段的项目商机"

## 业务特点
- 时间上比"采购意向"更早（项目立项、审批阶段）
- 金额字段 `biddingBudget` 单位是**万元**（与 purchase_intention 一致）
- 适合做"早期商机挖掘"和"市场预测"

## 工具调用

调用 `query_biz_data` 工具，参数：

```json
{
  "bizType": "prepose",
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
    "minBudget": <最小预算，万元>,
    "maxBudget": <最大预算，万元>
  },
  "limit": <1-100，默认 20>
}
```

## 常用日期快捷方式（datePreset）

| 用户说法 | datePreset |
|---------|-----------|
| 今日拟在建 | `today` |
| 昨日拟在建 | `yesterday` |
| 本周拟在建 | `thisWeek` |
| 上周拟在建 | `lastWeek` |
| 本月拟在建 | `thisMonth` |
| 上月拟在建 | `lastMonth` |
| 最近 7 天 | `last7Days` |
| 最近 30 天 | `last30Days` |

**注意金额单位**：prepose 用**万元**。

## 必传参数
- `bizType` = "prepose"（固定）
- `province` = **必填**，用户授权省份（System Context 里的 authorizedProvince）

## 关键字段说明（bid_biz_prepose 表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | String | 公告标题 |
| `projectName` | String | 项目名称 |
| `province` / `city` / `district` | String | 省/市/县 |
| `industry` | String | 行业分类（大类）|
| `infoType` | String | 行业分类（中类）|
| `tenderer` | String | 招标单位 |
| `biddingBudget` | BigDecimal | **预算价格（万元）** ⚠️ 注意单位是万元 |
| `bidWay` | String | 招标方式 |
| `timeBidOpen` / `timeBidClose` | String | 开标时间/截止时间 |
| `timeGetFileStart` / `timeGetFileEnd` | String | 文件获取开始/截止 |
| `channel` | String | 公告类型 |
| `publishTime` | LocalDate | 发布时间 |
| `serviceTime` | String | 服务期限 |
| `procurementSystem` | String | 采购系统 |
| `product` | String | 产品 |
| `detailLink` | String | 公告原链接 |
| `webSourceName` | String | 网站来源 |
| `bidUrl` | String | 比地官网链接 |
| `bidCreateTime` | LocalDateTime | bid 创建时间 |
| `notes` | String | 备注 |
| `batchNo` | String | 批次号 |
| `collectStatus` | Boolean | 是否收藏 |
| `completed` | Boolean | 是否完整 |
| `written` / `writtenTime` | Boolean / LocalDateTime | 是否录入商机/时间 |
| `biddingJoined` | Boolean | 是否参与投标 |
| `column1-10` | String | 备用字段 |
| `kept` | Boolean | 是否保留 |
| `keywords` | String | 关键词 |
| `customerIn` | String | 是否名单制 |
| `tendererAddress` / `tendererContact` / `tendererPhone` | String | 招标单位地址/联系人/电话 |
| `agency` / `agencyContact` / `agencyPhone` | String | 代理机构/联系人/电话 |

## 结果处理

**情况 A：结果条数 ≤ 10**
- 直接 `format_report` 出表格

**情况 B：结果条数 > 10**
- `run_python` 按行业/单位聚合
- 突出"高预算项目"和"重点单位"

## 业务引导

- 拟在建 ≠ 一定招标 — 提示用户"项目可能延期、变更或取消"
- 跟踪后续 → 用 `query-purchase-intention` 看采购意向 → 再到 `query-bidding` 看招标

## 输出模板

```
## 拟在建项目查询结果

**查询条件**：省份=XX，地市=XX，行业=XX，时间=XX

**总数**：XX 条，**总预算**：XX 万元

### Top 10
| 项目名称 | 招标单位 | 预算金额（万元）| 预计开标时间 | 行业 |
|---|---|---|---|---|
| ... | ... | ... | ... | ... |

### 业务建议（默认不输出，仅当用户明确说"分析/解读/总结"时才附）
- 重点关注：XX 单位（XX 万元预算）
- 跟进方式：建议用 `query-purchase-intention` / `query-bidding` 跟踪后续进展
```

## 示例

用户："查询贵州贵阳最近立项的 IT 项目储备"
→ province="贵州省", city="贵阳市", industry="信息技术", startDate="2026-06-10", bizType="prepose"
