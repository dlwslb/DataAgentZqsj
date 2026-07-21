---
name: query-bidding
description: 【招标信息查询 - 招标未开标阶段】当用户查询**招标公告/招标预告/招标文件/招标项目/招标计划/招标编号**（**不含"中标"**）时使用本 skill。例如："招标公告/招标项目/招标编号/今日招标/本周招标"。【关键区分】**用户说"中标"不要用本 skill**，用 `query-bid-winner`（bid_biz_win_bid 表）。表 `chatbi.bid_biz_bidding`。
---

# 招标信息查询

## 适用场景
- "最近有哪些招标公告"
- "XX 项目的招标信息"
- "XX 地区 XX 行业的招标预告"
- "查一下 XX 公司的招标动态"
- "查看当前还在投标期内的项目"
- "按预算金额排序的招标项目"

## 不适用
- 中标结果（已开标）→ 用 `query-bid-winner`
- 采购意向（更早阶段）→ 用 `query-purchase-intention`
- 拟在建/审批项目 → 用 `query-prepose`

## 工具调用

调用 `query_biz_data` 工具，参数：

```json
{
  "bizType": "bidding",
  "province": "<从 System Context 拿，必填，单值如北京/上海，多值如北京,上海>",
  "datePreset": "<快捷日期预设，可选，优先于 startDate/endDate>",
  "conditions": {
    "province": "<省份>",
    "city": "<地市>",
    "district": "<区县>",
    "industry": "<行业大类>",
    "infoType": "<行业中类>",
    "tenderer": "<招标单位，模糊匹配>",
    "channel": "<公告类型，如 '公开招标'>",
    "webSourceName": "<网站来源>",
    "keyword": "<项目名/标题/关键词，模糊匹配>",
    "startDate": "yyyy-MM-dd",
    "endDate": "yyyy-MM-dd",
    "minBudget": <最小预算金额，元>,
    "maxBudget": <最大预算金额，元>
  },
  "limit": <1-100，默认 20>
}
```

## 常用日期快捷方式（datePreset）

| 用户说法 | datePreset |
|---------|-----------|
| 今日招标 / 今天的招标 | `today` |
| 昨日招标 | `yesterday` |
| 本周招标 | `thisWeek` |
| 上周招标 | `lastWeek` |
| 本月招标 | `thisMonth` |
| 上月招标 | `lastMonth` |
| 最近 7 天 | `last7Days` |
| 最近 30 天 | `last30Days` |

## 必传参数
- `bizType` = "bidding"（固定）
- `province` = **必填**，用户授权省份（System Context 里的 authorizedProvince）

## 关键字段说明（bid_biz_bidding 表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | String | 公告标题 |
| `projectName` | String | 项目名称 |
| `projectId` | String | 招标编号 |
| `province` / `city` / `district` | String | 省/市/县 |
| `industry` | String | 所属行业 |
| `infoType` | String | 行业分类（中类）|
| `tenderer` | String | 招标人 |
| `tendererCode` | String | 招标单位代码 |
| `tendererAddress` | String | 招标单位地址 |
| `tendererContact` | String | 招标联系人 |
| `tendererPhone` | String | 招标联系电话 |
| `agency` | String | 代理机构 |
| `agencyContact` / `agencyPhone` | String | 代理机构联系人/电话 |
| `biddingBudget` | BigDecimal | **预算价格**（元）|
| `bidWay` | String | **招标方式**（公开招标/邀请招标/竞争性谈判等）|
| `channel` | String | 公告类型 |
| `publishTime` | LocalDate | 发布时间 |
| `timeBidOpen` | String | 开标时间 yyyy-MM-dd |
| `timeBidClose` | String | 开标结束时间 |
| `timeGetFileStart` | String | 文件获取开始时间 |
| `timeGetFileEnd` | String | **投标截止时间** |
| `biddingScale` | String | 招标范围及规模 |
| `bidCreateTime` | LocalDateTime | bid 创建时间 |
| `product` | String | 产品 |
| `detailLink` | String | 详情链接 |
| `webSourceName` | String | 网站来源 |
| `bidUrl` | String | 比地官网链接 |
| `keywords` | String | 关键词 |
| `marketingUnit` | String | 营销单位 |
| `projectSite` | String | 项目实施地点 |
| `isOperatorTenderer` | String | 是否为运营商招标 |
| `written` / `writtenTime` | Boolean / String | 是否已录入商机系统/时间 |
| `biddingJoined` | Boolean | 是否已参与投标 |
| `abandoned` / `abandonedNo` | String | 是否弃标/申请单号 |
| `joinStatus` | String | 项目参与情况 |
| `lostReason` | String | 弃标/漏单原因分类 |
| `listLevel` / `listGridCus` | String | 层级/名单客户网格名称 |
| `yewuleixing` | String | 业务类型 |
| `editstatus` | String | 数据清洗编辑状态 |
| `field` | String | 领域 |
| `colorType` | ColorType 枚举 | 红黄牌 |

## 结果处理

**情况 A：结果条数 ≤ 10**
- 直接用 `format_report` 生成表格

**情况 B：结果条数 > 10**
- 用 `run_python` 聚合（按地区/行业/月份）
- 突出"还能投标"的（用 `timeGetFileEnd` > 当前时间）
- 用 `format_report` 生成摘要 + Top10 表格

## 主动建议
- 查"招标中"的项目 → WHERE 业务上用 `timeGetFileEnd > 当前日期` 过滤（Tool 当前不支持，需要 Skill 提示 LLM 在 `endDate` 里传未来日期达到"未截止"效果；或者提示用户这是已发布的所有招标）
- 查"我能不能投这个标" → 突出 `timeGetFileEnd` 截止时间 + 提示参考招标范围 `biddingScale`

## 输出模板

```
## 招标信息查询结果

**查询条件**：省份=XX，地市=XX，行业=XX，时间=XX

**总数**：XX 条

| 项目名称 | 招标单位 | 预算金额 | 招标方式 | 投标截止 | 发布时间 |
|---|---|---|---|---|---|
| ... | ... | ... | ... | ... | ... |
```

## 示例

用户："查询贵州贵阳最近一个月公开招标的 IT 项目"
→ province="贵州省", city="贵阳市", industry="信息技术", startDate="2026-06-10", bizType="bidding"
