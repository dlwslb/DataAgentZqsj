---
name: detail-prepose
description: 【前期项目详情 - 强优先】当用户问**某个具体拟在建/项目储备/审批项目**的详情时使用本 skill。**触发特征**:用户问题里**必须含具体项目名/项目编号**(典型问法:"XX 拟在建项目的详情""XX 立项项目的预算""XX 项目储备的招标方式""查一下 XX 储备项目")。调 `get_prepose_detail` 工具按 `keyword` 查 1 条全字段。**关键区分**:用户问"拟在建有哪些/最近立项的项目/项目储备清单"用 `query-prepose`(列表);**用户问"某个具体拟在建/储备项目的详情"用本 skill**。
---

# 前期项目（拟在建）详情查询

## 适用场景
- "XX 拟在建项目的详情"
- "XX 立项项目的预算和招标方式"
- "XX 项目储备的联系人"
- "XX 审批项目的服务期限"
- "XX 拟在建项目的采购系统"

## 不适用
- 前期项目列表/汇总 → 用 `query-prepose`
- 采购意向 → 用 `detail-purchase-intention`
- 招标公告 → 用 `detail-bidding`
- 中标结果 → 用 `detail-bid-winner`

## 工具调用

调用 `get_prepose_detail` 工具，参数：

```json
{
  "id": "<前期项目记录主键 id，可选>",
  "keyword": "<项目名/标题/关键词，OR 模糊，可选>",
  "province": "<从 System Context 拿，必填，单值如北京/上海，多值如北京,上海>"
}
```

**id 和 keyword 二选一必传**：

| 场景 | 传什么 |
|------|--------|
| 你刚调了 `query_biz_data` 拿到 id | 传 `id`（最准）|
| 用户直接问"XX 拟在建项目的详情" | 传 `keyword`（项目名模糊）|

## 关键字段说明

工具返回 `detail` 字段（bid_biz_prepose 表全字段），重点关注：

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | String | 公告标题 |
| `projectName` | String | 项目名称 |
| `tenderer` | String | 招标单位 |
| `biddingBudget` | BigDecimal | **预算价格**（DB 存元，Tool 输出已转**万元**，保留 2 位小数）|
| `bidWay` | String | 招标方式 |
| `timeBidOpen` / `timeBidClose` | String | 开标时间/截止时间 |
| `timeGetFileStart` / `timeGetFileEnd` | String | 文件获取开始/截止 |
| `serviceTime` | String | 服务期限 |
| `procurementSystem` | String | 采购系统 |
| `product` | String | 产品 |
| `channel` | String | 公告类型 |
| `publishTime` | LocalDate | 发布时间 |
| `detailLink` | String | 公告原链接 |
| `webSourceName` | String | 网站来源 |
| `tendererContact` / `tendererPhone` | String | 招标单位联系人/电话 |
| `agency` / `agencyContact` / `agencyPhone` | String | 代理机构/联系人/电话 |

完整字段列表见 `references/prepose-detail-tables.md`（detail 专用精简版，不含列表专属的"Tool 字段映射"段）。

## 结果处理

**情况 A：found=true**
- 重点突出：立项单位 / 预算 / 招标方式 / 服务期限 / 联系人
- 拟在建 ≠ 一定招标 → 提示"项目可能延期、变更或取消"

**情况 B：found=false**
- 告诉用户"未找到该拟在建项目记录"

**情况 C：error**
- 直接转发 error 原文

## 主动建议（业务引导）
- 查"前期项目详情"后 → 主动建议"拟在建可能变化，建议按 采购意向→招标→中标 链跟踪"
- 立项后 3 个月内 → 提示"近期可能有采购意向或招标公告，可用 detail-purchase-intention / detail-bidding 跟踪"
- 预算金额 ≥ 100 万 → 提示"预算较大，可作为重点商机跟进"

**⛔ 禁止幻觉加工**：不要编工具没返回的信息
- 不要编具体网址（如"登录发改委审批平台/项目储备库"）
- 不要编附件名（如"附件3 可研报告"）
- 不要编操作步骤（如"建议您先做项目立项登记..."）
- 不要加 💡/⚠️/📌/🎯/✅ 等装饰 emoji 当小标题
- 跨 skill 建议用 `detail-purchase-intention` 这种 skill 名字直接说，不要自己写"建议您可以..."

## 输出模板

```
## XX 拟在建项目详情

**立项单位**：XX
**预算**：XX 万元
**招标方式**：公开招标/邀请招标
**服务期限**：XX 个月
**预计开标**：XX 月 - XX 月

**联系方式**：
- 立项单位联系人：XXX / 电话：XXX
- 代理机构：XXX / 电话：XXX

[详情链接](http://...)

### 业务建议（默认不输出，仅当用户明确说"分析/解读/总结"时才附）
- 拟在建 ≠ 一定招标，项目可能延期、变更或取消
- 想跟踪后续采购意向，可用 detail-purchase-intention 跟进
```

## 示例

用户："查一下 XX 高速公路智能化项目储备的预算和招标方式"
→ 走 detail-prepose，keyword="XX 高速公路智能化"，从 detail 字段里抽 tenderer / biddingBudget / bidWay / serviceTime

## ⭐ 兜底查询（详情数据二次匹配）

**业务背景**：详情数据可能有同步延迟。当主表没匹配到时，系统会从**标讯库**（每日实时抓取的公告）二次匹配，命中后会在响应里标注 `source: "origin_announcement_fallback"`（LLM 不需要识别这字段，按 sysPrompt 的"详情兜底"规则处理即可）。

**用户面前怎么回答**：
- ✅ 推荐："该项目的详细数据已从标讯库匹配到，详情信息如下："（直接展示 detail 字段）
- ✅ 可补充一句："此条信息来自标讯库，详情库后续会更新"
- ❌ **绝不可**说"主表/原始标讯库/表名/Skill 名"等内部技术概念
- ❌ 不要说"数据可能有误"（标讯是实时抓取的，不是错误）
