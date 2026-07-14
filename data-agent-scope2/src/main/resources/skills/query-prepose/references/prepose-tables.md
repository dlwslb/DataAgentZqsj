# bid_biz_prepose（拟在建及审批项目表）真实表结构

> 字段全部来自 `BizPreposeDO`，继承 `TenantBaseDO`（注意：原 DO 注释里 `extends TenantBaseDO`，但 import 的是 `BaseDO`，实际以 `BaseDO` 为准）

## 核心字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 主键 |
| `docId` | String | bid 公告 ID |
| `title` | String | 公告标题 |
| `channel` | String | 公告类型 |
| `publishTime` | LocalDate | 发布时间 |
| `province` / `city` / `district` | String | 省/市/县 |
| `projectId` / `projectCode` / `projectNo` | String | 项目 ID/代码/序号 |
| `projectName` | String | 项目名称 |
| `biddingBudget` | BigDecimal | **预算价格（万元）** ⚠️ 单位是万元 |
| `tenderer` / `tendererAddress` / `tendererContact` / `tendererPhone` | String | 招标单位/地址/联系人/电话 |
| `agency` / `agencyContact` / `agencyPhone` | String | 代理机构/联系人/电话 |
| `product` | String | 产品 |
| `industry` | String | 行业分类（大类）|
| `infoType` | String | 行业分类（中类）|
| `bidWay` | String | 招标方式 |
| `timeBidOpen` / `timeBidClose` | String | 开标时间/截止时间 |
| `timeGetFileStart` / `timeGetFileEnd` | String | 文件获取开始/截止 |
| `detailLink` | String | 公告原链接 |
| `webSourceName` | String | 网站来源 |
| `serviceTime` | String | 服务期限 |
| `procurementSystem` | String | 采购系统 |
| `bidUrl` | String | 比地官网链接 |
| `bidCreateTime` | LocalDateTime | bid 创建时间 |
| `notes` | String | 备注 |
| `batchNo` | String | 批次号 |
| `collectStatus` | Boolean | 是否收藏 |
| `completed` | Boolean | 是否完整 |
| `written` / `writtenTime` | Boolean / LocalDateTime | 是否录入商机/时间 |
| `biddingJoined` | Boolean | 是否参与投标 |
| `column1-10` | String | 备用字段 |
| `customerId` | Long | 客户 ID |
| `pushStatus` / `pushTime` | Integer / LocalDateTime | 推送状态/时间 |
| `kept` | Boolean | 是否保留 |
| `keywords` | String | 关键词 |
| `deptId` | Long | 部门 ID |
| `editstatus` | String | 数据清洗编辑状态 |
| `customerIn` | String | 是否名单制 |

## ⚠️ 字段缺失（相对其他 3 张表）

prepose 表**没有**以下字段（其他 3 张表有的）：
- `flow*` 系列流程字段（信息化/地区变更/商机变更/弃标/丢标/派发）
- `marketingUnit`（营销单位）
- `listLevel` / `listGridCus`（层级/名单客户网格）
- `abandoned` / `abandonedNo`（是否弃标/申请单号）
- `joinStatus`（项目参与情况）
- `lostReason`（弃标/漏单原因）
- `field`（领域）
- `yewuleixing`（业务类型）
- `natureName` / `natureId` / `listId`（自然/名单客户 ID）
- `managerAOa`（首席客户经理 A 角 OA 工号）
- `sendDownCityManager` / `sendDownKeyAccountManager`（下放地市/客户经理）

Tool 调 prepose 时**不要传这些字段**（SQL 会因为列不存在报错）。

## Tool 字段映射

| conditions key | 数据库列 | 类型 |
|----------------|----------|------|
| `province` | `province` | String |
| `city` | `city` | String |
| `district` | `district` | String |
| `industry` | `industry` | String |
| `infoType` | `info_type` | String |
| `tenderer` | `tenderer` | String (LIKE) |
| `keyword` | `title` / `project_name` / `keywords` | String (OR 模糊) |
| `channel` | `channel` | String |
| `webSourceName` | `web_source_name` | String |
| `startDate` / `endDate` | `publish_time` | LocalDate |
| `minBudget` / `maxBudget` | `bidding_budget` | BigDecimal（**单位是万元**）|

**返回结果归一化**：`biddingBudget` → 统一为 `amount` 字段返回。
