# bid_biz_bidding（招标信息表）真实表结构

> 字段全部来自 `BizBiddingDO`，继承 `TenantBaseDO`

## 核心字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 主键 |
| `docId` | String | bid 公告 ID |
| `channel` | String | 公告类型 |
| `publishTime` | LocalDate | 发布时间 |
| `province` / `city` / `district` | String | 省/市/县 |
| `title` | String | 公告标题 |
| `projectId` | String | 招标编号 |
| `projectCode` / `projectNo` | String | 项目代码/序号 |
| `projectName` | String | 项目名称 |
| `biddingBudget` | BigDecimal | **预算价格（元）** |
| `bidWay` | String | **招标方式** |
| `detailLink` | String | 详情链接 |
| `webSourceName` | String | 网站来源 |
| `bidUrl` | String | 比地官网链接 |
| `industry` | String | 所属行业 |
| `infoType` | String | 行业分类（中类）|
| `product` | String | 产品 |
| `tenderer` / `tendererCode` / `tendererAddress` | String | 招标人/单位代码/地址 |
| `tendererContact` / `tendererPhone` | String | 招标联系人/电话 |
| `agency` / `agencyContact` / `agencyPhone` | String | 代理机构/联系人/电话 |
| `timeBidOpen` / `timeBidClose` | String | 开标时间/结束时间 |
| `timeGetFileStart` / `timeGetFileEnd` | String | 文件获取开始/**投标截止** |
| `biddingScale` | String | 招标范围及规模 |
| `bidCreateTime` | LocalDateTime | bid 创建时间 |
| `customerId` | Long | 客户 ID |
| `collectStatus` | Boolean | 是否收藏 |
| `notes` | String | 备注 |
| `completed` | Boolean | 是否完整 |
| `bidBusId` | Long | bidBusniss id |
| `keyBusiness` | String | 重点业务 |
| `batchNo` | String | 批次号 |
| `pushStatus` / `pushTime` | Integer / LocalDateTime | 推送状态/时间 |
| `kept` | Boolean | 是否保留 |
| `keywords` | String | 关键词 |
| `deptId` | Long | 部门 ID |
| `flowsStasus*` / `flowsId*` / `flowsReason*` | String | 多个流程字段（信息化/地区变更/商机变更/弃标/丢标/派发）|
| `marketingUnit` | String | 营销单位 |
| `projectSite` | String | 项目实施地点 |
| `written` / `writtenTime` | Boolean / String | 是否录入商机/时间 |
| `biddingJoined` | Boolean | 是否参与投标 |
| `abandoned` / `abandonedNo` | String | 弃标/申请单号 |
| `joinStatus` | String | 项目参与情况 |
| `lostReason` | String | 弃标/漏单原因 |
| `field` | String | 领域 |
| `listLevel` / `listGridCus` | String | 层级/名单客户网格 |
| `yewuleixing` | String | 业务类型 |
| `editstatus` | String | 数据清洗编辑状态 |
| `isOperatorTenderer` | String | 是否运营商招标 |
| `colorType` | ColorType 枚举 | 红黄牌 |
| `publishTimePurchase` | LocalDate | 采购意向发布时间 |
| `customerIn` | String | 是否名单制 |
| `natureName` / `natureId` / `listId` | String | 自然客户/名单客户 ID |
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
| `minBudget` / `maxBudget` | `bidding_budget` | BigDecimal（bidding 表用这个，不是 win_bid_price）|

**返回结果归一化**：`biddingBudget` → 统一为 `amount` 字段返回。
