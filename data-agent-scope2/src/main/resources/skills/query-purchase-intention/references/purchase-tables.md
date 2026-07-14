# bid_biz_purchase_intention（采购意向表）真实表结构

> 字段全部来自 `BizPurchaseIntentionDO`，继承 `TenantBaseDO`

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
| `projectName` | String | **采购项目名称** |
| `biddingBudget` | BigDecimal | **招标预算金额**（万元，原 DO 注释说明）|
| `tenderer` | String | 招标单位 |
| `detailLink` | String | 公告原链接 |
| `webSourceName` | String | 网站来源 |
| `bidUrl` | String | 比地官网链接 |
| `industry` | String | 行业分类（大类）|
| `infoType` | String | 行业分类（中类）|
| `product` | String | **采购需求概况** |
| `customerId` | Long | 客户 ID |
| `collectStatus` | Boolean | 是否收藏 |
| `timeBidOpen` | LocalDate | 预计采购开始时间 |
| `timeBidClose` | LocalDate | 预计采购结束时间 |
| `notes` | String | 备注 |
| `completed` | Boolean | 是否完整 |
| `column1-10` | String | 备用字段 |
| `batchNo` | String | 批次号 |
| `pushStatus` / `pushTime` | Integer / LocalDateTime | 推送状态/时间 |
| `kept` | Boolean | 是否保留 |
| `keywords` | String | 关键词 |
| `deptId` | Long | 部门 ID |
| `flowsStasus*` / `flowsId*` / `flowsReason*` | String | 多个流程字段 |
| `field` | String | 领域 |
| `listGridCus` | String | 名单客户网格 |
| `listLevel` | String | 层级 |
| `abandoned` / `abandonedNo` | String | 弃标/申请单号 |
| `yewuleixing` | String | 业务类型 |
| `editstatus` | String | 数据清洗编辑状态 |
| `customerIn` | String | 是否名单制 |
| `natureName` / `natureId` / `listId` | String | 自然/名单客户 ID |
| `managerAOa` | String | 首席客户经理 A 角 OA 工号 |
| `sendDownCityManager` / `sendDownKeyAccountManager` | Integer | 下放地市/客户经理 |

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
| `minBudget` / `maxBudget` | `bidding_budget` | BigDecimal |

**返回结果归一化**：`biddingBudget` → 统一为 `amount` 字段返回。
