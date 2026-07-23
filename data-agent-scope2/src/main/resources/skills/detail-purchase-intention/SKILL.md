---
name: detail-purchase-intention
description: 【采购意向详情 - 强优先】当用户问**某个具体采购意向/采购计划/采购项目名**的详情时使用本 skill。**触发特征**:用户问题里**必须含具体采购意向名/采购项目名**(典型问法:"XX 采购意向的详情""XX 单位采购计划的内容""XX 采购意向的预算""查一下 XX 采购项目")。调 `get_purchase_intention_detail` 工具按 `keyword` 查 1 条全字段。**关键区分**:用户问"采购意向有哪些/最近有什么采购意向/采购意向汇总"用 `query-purchase-intention`(列表);**用户问"某个具体采购意向的详情"用本 skill**。
---

# 采购意向详情查询

## 适用场景
- "XX 单位采购意向的内容"
- "XX 采购意向的预算"
- "XX 采购意向的采购需求"
- "XX 采购意向的采购时间"
- "XX 采购意向的联系人"

## 不适用
- 采购意向列表/汇总 → 用 `query-purchase-intention`
- 招标公告 → 用 `detail-bidding`
- 中标结果 → 用 `detail-bid-winner`
- 前期项目 → 用 `detail-prepose`

## 工具调用

调用 `get_purchase_intention_detail` 工具，参数：

```json
{
  "id": "<采购意向记录主键 id，可选>",
  "keyword": "<项目名/标题/关键词，OR 模糊，可选>",
  "province": "<从 System Context 拿，必填，单值如北京/上海，多值如北京,上海>"
}
```

**id 和 keyword 二选一必传**：

| 场景 | 传什么 |
|------|--------|
| 你刚调了 `query_biz_data` 拿到 id | 传 `id`（最准）|
| 用户直接问"XX 采购意向的详情" | 传 `keyword`（项目名模糊）|

## 关键字段说明

工具返回 `detail` 字段（bid_biz_purchase_intention 表全字段），重点关注：

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | String | 公告标题 |
| `projectName` | String | 采购项目名称 |
| `tenderer` | String | 招标单位 |
| `biddingBudget` | BigDecimal | **招标预算金额（万元）** ⚠️ 注意单位是万元 |
| `timeBidOpen` | LocalDate | 预计采购开始时间 |
| `timeBidClose` | LocalDate | 预计采购结束时间 |
| `product` | String | **采购需求概况** |
| `channel` | String | 公告类型 |
| `publishTime` | LocalDate | 发布时间 |
| `detailLink` | String | 公告原链接 |
| `webSourceName` | String | 网站来源 |
| `industry` / `infoType` | String | 行业分类 |
| `keywords` | String | 关键词 |

完整字段列表见 `references/purchase-intention-detail-tables.md`（detail 专用精简版，不含列表专属的"Tool 字段映射"段）。

## 结果处理

**情况 A：found=true**
- 重点突出：采购单位 / 预算 / 采购需求 / 采购时间 / 公告链接
- 采购意向不是承诺 → 提示"实际招标可能有变化"

**情况 B：found=false**
- 告诉用户"未找到该采购意向记录"

**情况 C：error**
- 直接转发 error 原文

## 主动建议（业务引导）
- 查"采购意向详情"后 → 主动建议"采购意向可能变化，建议用 `detail-bidding` 跟踪后续招标公告"
- 预算金额 ≥ 100 万 → 提示"预算较大，可作为重点商机跟进"
- 采购时间在 1 个月内 → 提示"预计 XX 月开标，建议提前准备"

**⛔ 禁止幻觉加工**：不要编工具没返回的信息
- 不要编具体网址（如"登录政府采购网/采购信息网"）
- 不要编附件名（如"附件3 采购需求清单"）
- 不要编操作步骤（如"建议您先做需求登记..."）
- 不要加 💡/⚠️/📌/🎯/✅ 等装饰 emoji 当小标题
- 跨 skill 建议用 `detail-bidding` 这种 skill 名字直接说，不要自己写"建议您可以..."

## 输出模板

```
## XX 采购意向详情

**采购单位**：XX
**预算**：XX 万元
**采购需求**：XX
**预计采购时间**：XX 月 - XX 月

[详情链接](http://...)

### 业务建议（默认不输出，仅当用户明确说"分析/解读/总结"时才附）
- 采购意向不是承诺，实际招标可能有变化
- 想跟踪后续招标公告，可用 detail-bidding 跟进
```

## 示例

用户："查一下贵州省政府采购意向中那个医院信息化项目的预算"
→ 走 detail-purchase-intention，keyword="医院信息化"，从 detail 字段里抽 tenderer / biddingBudget / product / timeBidOpen
