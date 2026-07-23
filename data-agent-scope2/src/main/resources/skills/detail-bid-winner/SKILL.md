---
name: detail-bid-winner
description: 【中标信息详情 - 强优先】当用户问**某个具体中标项目/项目标题/项目编号**的中标信息时使用本 skill。**触发特征**:用户问题里**必须含具体项目名/项目编号**(典型问法:"XX 项目的中标""XX 的中标信息""查一下 XX 中标""XX 标的中标信息""XX 中标公示")。调 `get_bid_winner_detail` 工具按 `keyword` 查 1 条全字段。**关键区分**:用户问"中标有哪些/最近有什么中标/中标汇总/中标统计/XX 行业中标分布"用 `query-bid-winner`(列表,按行业/地区/时间维度汇总);**用户问"某个具体项目的中标"用本 skill**(按项目名查 1 条)。
---

# 中标信息详情查询

## 适用场景
- "XX 项目的中标结果"
- "XX 标的中标单位是谁"
- "XX 项目的中标金额"
- "XX 中标公示的原文"
- "XX 中标的代理商/中标联系人"

## 不适用
- 中标列表/汇总 → 用 `query-bid-winner`
- 招标公告 → 用 `detail-bidding`
- 采购意向 → 用 `detail-purchase-intention`
- 前期项目 → 用 `detail-prepose`

## 工具调用

调用 `get_bid_winner_detail` 工具，参数：

```json
{
  "id": "<中标记录主键 id，可选>",
  "keyword": "<项目名/标题/关键词，OR 模糊，可选>",
  "province": "<从 System Context 拿，必填，单值如北京/上海，多值如北京,上海>"
}
```

**id 和 keyword 二选一必传**：

| 场景 | 传什么 |
|------|--------|
| 用户说"就是刚才那个中标" / 你刚调了 `query_biz_data` 拿到 id | 传 `id`（最准）|
| 用户直接问"XX 项目的中标详情" | 传 `keyword`（项目名模糊）|
| 两个都传 | 优先用 `id` |

## 关键字段说明

工具返回 `detail` 字段（bid_biz_win_bid 表全字段），重点关注：

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | String | 公告标题 |
| `projectName` | String | 项目名称 |
| `winTenderer` | String | **中标单位**（注意：是 winTenderer 不是 tenderer）|
| `winTendererContact` / `winTendererPhone` | String | **中标联系人/电话** |
| `winBidPrice` | BigDecimal | **中标金额** |
| `tenderer` | String | 招标人 |
| `agency` | String | 代理机构 |
| `biddingBudget` | BigDecimal | 预算金额 |
| `topGrade` | String | 优标级别（优标/次优标/非优标）|
| `operator` | String | 运营商归属 |
| `operatorWinStatus` | Boolean | 运营商是否中标 |
| `secondTenderer` / `thirdTenderer` | String | 第二/第三候选人 |
| `publishTime` | LocalDate | 发布时间（中标时间）|
| `detailLink` | String | 公告原链接 |
| `webSourceName` | String | 网站来源 |

完整字段列表见 `references/bid-detail-tables.md`（detail 专用精简版，不含列表专属的"Tool 字段映射"段）。

## 结果处理

**情况 A：found=true**
- 重点突出：中标单位 / 中标金额 / 中标联系人 / 招标人 / 公告链接
- 中标金额和预算对比 → 给出"折扣率"（winBidPrice / biddingBudget）

**情况 B：found=false**
- 告诉用户"未找到该中标记录"
- 不要换表重查

**情况 C：error**
- 直接转发 error 原文

## 主动建议
- 查"中标详情"后 → 主动建议"如果想跟踪下一批中标，可用 `query-bid-winner` 查"
- 中标金额 < 预算 → 提示"中标金额低于预算 XX%，可参考分析价格策略"
- `operatorWinStatus` 是 true → 提示"运营商中标，可作竞品分析"

**⛔ 禁止幻觉加工**：不要编工具没返回的信息
- 不要编具体网址（如"登录中国政府采购网/比地招标网"）
- 不要编附件名（如"附件3 中标公示"）
- 不要编操作步骤（如"建议您联系招标代理..."）
- 不要加 💡/⚠️/📌/🎯/✅ 等装饰 emoji 当小标题
- 跨 skill 建议用 `query-bid-winner` 这种 skill 名字直接说，不要自己写"建议您可以..."

## 输出模板

```
## XX 项目中标详情

**中标单位**：XX 公司
**中标金额**：XX 万元（预算 XX 万元，折扣率 XX%）
**招标人**：XX 单位
**代理机构**：XX

**联系方式**：
- 中标联系人：XXX / 电话：XXX

**运营商中标**：是/否
**优标级别**：优标/次优标/非优标

[详情链接](http://...)

### 业务建议（默认不输出，仅当用户明确说"分析/解读/总结"时才附）
- 中标金额比预算低 XX%，价格优势明显
- 想看更多类似中标，可用 query-bid-winner 跟踪
```

## 示例

用户："查一下贵州贵阳数据中心建设项目中标了谁"
→ 走 detail-bid-winner，keyword="贵州贵阳数据中心建设项目"，从 detail 字段里抽 winTenderer / winBidPrice / winTendererPhone
