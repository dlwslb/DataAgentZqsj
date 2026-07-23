---
name: detail-bidding
description: 【招标信息详情 - 强优先】当用户问**某个具体招标项目/项目标题/项目编号**的招标信息时使用本 skill。**触发特征**:用户问题里**必须含具体项目名/项目编号**(典型问法:"XX 项目的招标""查一下 XX 招标的联系方式""XX 招标的投标截止时间""XX 招标公告的内容")。调 `get_bidding_detail` 工具按 `keyword` 查 1 条全字段。**关键区分**:用户问"招标有哪些/最近有什么招标/招标汇总/招标统计"用 `query-bidding`(列表);**用户问"某个具体项目的招标"用本 skill**。
---

# 招标信息详情查询

## 适用场景
- "XX 项目的招标公告内容"
- "查一下 XX 招标的招标人电话/代理电话"
- "XX 招标的投标截止时间是什么时候"
- "XX 招标的招标方式和预算"
- "XX 招标的详情链接"

## 不适用
- 招标列表/汇总 → 用 `query-bidding`
- 中标结果 → 用 `detail-bid-winner`
- 采购意向 → 用 `detail-purchase-intention`
- 前期项目 → 用 `detail-prepose`

## 工具调用

调用 `get_bidding_detail` 工具，参数：

```json
{
  "id": "<招标记录主键 id，可选>",
  "keyword": "<项目名/标题/关键词，OR 模糊，可选>",
  "province": "<从 System Context 拿，必填，单值如北京/上海，多值如北京,上海>"
}
```

**id 和 keyword 二选一必传**：

| 场景 | 传什么 |
|------|--------|
| 用户说"就是刚才那个招标" / 你刚调了 `query_bid_data` 拿到 id | 传 `id`（最准，按主键查 1 条）|
| 用户直接问"XX 项目的招标详情"，没调过列表 | 传 `keyword`（项目名/标题模糊，自动取 1 条）|
| 两个都传 | 优先用 `id`（更准），`keyword` 当兜底 |

## 关键字段说明

工具返回 `detail` 字段（bid_biz_bidding 表全字段），重点关注：

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | String | 公告标题 |
| `projectName` | String | 项目名称 |
| `projectId` | String | 招标编号 |
| `tenderer` | String | 招标人（招标单位）|
| `tendererContact` / `tendererPhone` | String | 招标联系人/电话 |
| `agency` | String | 代理机构 |
| `agencyContact` / `agencyPhone` | String | 代理机构联系人/电话 |
| `biddingBudget` | BigDecimal | **预算价格** |
| `bidWay` | String | **招标方式**（公开招标/邀请招标等）|
| `timeGetFileEnd` | String | **投标截止时间** yyyy-MM-dd |
| `timeBidOpen` | String | 开标时间 |
| `detailLink` | String | 详情链接 |
| `channel` | String | 公告类型 |
| `publishTime` | LocalDate | 发布时间 |
| `bidUrl` | String | 比地官网链接 |
| `biddingScale` | String | 招标范围及规模 |
| `keywords` | String | 关键词 |
| `webSourceName` | String | 网站来源 |

完整字段列表见 `references/bidding-detail-tables.md`（detail 专用精简版，不含列表专属的"Tool 字段映射"段）。

## 结果处理

工具返回 3 种情况：

**情况 A：found=true（找到 1 条）**
- 直接用 `format_report` 把 `detail` 里的关键字段呈现给用户
- 重点突出：招标单位 / 联系人电话 / 投标截止 / 预算 / 详情链接
- 不要把所有字段都堆给用户，挑用户问的几个字段重点说

**情况 B：found=false（未找到）**
- 告诉用户"未找到该招标项目"，让用户确认项目名/编号
- 不要换表重查（这是详情，不是列表）

**情况 C：error**
- 直接转发 error 原文，不加"根据XX规则/系统拒绝"之类的话

## 主动建议（业务引导）
- 查"招标详情"后 → 主动建议"如果想跟踪中标结果，可用 `detail-bid-winner` 查"
- 投标截止日期快到 → 提示"投标截止时间是 XX，建议尽快确认"
- 详情里有 `detailLink` → 给用户"原文链接：[详情](URL)"

**⛔ 禁止幻觉加工**：不要编工具没返回的信息
- 不要编具体网址（如"登录中国政府采购网/比地招标网/某某招标平台"）
- 不要编附件名（如"附件3 技术参数响应表/评分细则"）
- 不要编操作步骤（如"建议您先注册账号..."）
- 不要加 💡/⚠️/📌/🎯/✅ 等装饰 emoji 当小标题
- 跨 skill 建议用 `detail-bid-winner` 这种 skill 名字直接说，不要自己写"建议您可以..."

## 输出模板

```
## XX 项目招标详情

**招标编号**：XXX
**招标人**：XX 单位
**预算**：XX 万元
**招标方式**：公开招标
**投标截止**：2026-XX-XX

**联系方式**：
- 招标人联系人：XXX / 电话：XXX
- 代理机构：XXX / 电话：XXX

[详情链接](http://...)

### 业务建议
- 投标截止 XX 月 XX 日，请尽快确认投标意向
- 想看后续中标结果，可用 detail-bid-winner 跟踪
```

## 示例

用户："查一下贵阳市第一人民医院信息化建设项目招标的联系人电话"
→ 走 detail-bidding，keyword="贵阳市第一人民医院信息化建设项目"，从 detail 字段里抽 tendererContact / tendererPhone / agencyContact
