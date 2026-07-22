# bid-tables（详情专用版）

> ⚠️ **这是 detail 专属字段表,只含"核心字段"段,不含"Tool 字段映射"段**（Tool 字段映射是给 `query_biz_data` 列表工具的,detail 工具用 `id`/`keyword` 不需要）。
> 字段信息同步自 `../query-bid-winner/references/bid-tables.md`,如 query 那边的字段更新,**手动同步**本表（或重新跑本脚本生成）。

# bid_biz_win_bid（中标信息表）真实表结构

> 字段全部来自 `BizWinBidDO`，继承 `TenantBaseDO`（含 tenant_id / create_time / update_time / deleted 等基础字段）

## 核心字段

| 字段 | 类型 | 说明                  |
|------|------|---------------------|
| `id` | Long | 主键                  |
| `docId` | String | bid 公告 id           |
| `channel` | String | 公告类型                |
| `province` | String | 省份                  |
| `city` | String | 地市                  |
| `district` | String | 区县                  |
| `publishTime` | LocalDate | 发布时间（中标时间）          |
| `title` | String | 公告标题                |
| `projectId` / `projectCode` / `projectNo` | String | 项目 id/代码/序号         |
| `projectName` | String | 项目名称                |
| `winTenderer` | String | **中标单位**            |
| `winTendererCode` | String | 中标单位代码              |
| `winTendererManager` | String | 中标单位联系人             |
| `winTendererPhone` | String | 中标单位联系人电话           |
| `winTendererEmail` | String | 中标单位联系人邮箱           |
| `winBidPrice` | BigDecimal | **中标金额（万元）**        |
| `product` | String | 产品                  |
| `industry` | String | 行业分类（大类）            |
| `infoType` | String | 行业分类（中类）            |
| `operator` | String | 运营商归属               |
| `operatorWinStatus` | Boolean | 运营商中标情况             |
| `operatorSort` | Integer | 运营商排序               |
| `detailLink` | String | 公告原链接               |
| `webSourceName` | String | 网站来源名称              |
| `bidUrl` | String | 比地官网链接              |
| `secondTenderer` | String | 第二候选人               |
| `thirdTenderer` | String | 第三候选人               |
| `collectStatus` | Boolean | 是否收藏                |
| `notes` | String | 备注                  |
| `completed` | Boolean | 是否完整                |
| `tenderer` | String | 招标人                 |
| `tendererAddress` | String | 招标单位地址              |
| `tendererContact` | String | 招标单位联系人             |
| `tendererPhone` | String | 招标单位联系电话            |
| `agency` | String | 代理机构                |
| `agencyContact` | String | 代理机构联系人             |
| `agencyPhone` | String | 代理机构联系电话            |
| `biddingBudget` | BigDecimal | 预算金额（万元）            |
| `timeBidOpen` | String | 开标时间 yyyy-MM-dd     |
| `timeGetFileEnd` | String | 文件获取截止时间            |
| `biddingScale` | String | 招标范围及规模             |
| `batchNo` | String | 批次号                 |
| `pushStatus` / `pushTime` | Integer / LocalDateTime | 推送状态/时间             |
| `kept` | Boolean | 是否保留                |
| `keywords` | String | 关键词                 |
| `timeContractEnd` | String | 合同结束时间              |
| `marketingUnit` | String | 营销单位                |
| `publishTimePurchase` | LocalDate | 招标意向发布时间            |
| `publishTimeBidding` | LocalDate | 招标信息发布时间            |
| `detailLinkBidding` | String | 招标公告地址              |
| `editstatus` | String | 数据清洗编辑状态（1已编辑 0未编辑） |
| `topGrade` | String | 优标级别（优标/次优标/非优标）    |
| `natureName` | String | 自然客户名称              |
| `managerAOa` | String | 首席客户经理 A 角 OA 工号    |
| `customerIn` | String | 是否名单制               |
| `field` | String | 领域                  |
| `listGridCus` | String | 名单客户网格名称            |
| `listLevel` | String | 层级                  |
| `abandoned` | String | 是否弃标                |
| `abandonedNo` | String | 弃标申请单号              |
| `serviceStart` / `serviceEnd` | String | 服务开始/结束时间           |
| `serviceDay` | Integer | 服务天数                |
| `servicePeriod` | String | 服务期限                |
| `yewuleixing` | String | 业务类型                |
| `joinStatus` | String | 项目参与情况              |
| `lostReason` | String | 弃标/漏单原因分类           |

## 基础字段（来自 TenantBaseDO）

| 字段 | 类型 | 说明 |
|------|------|------|
| `tenantId` | Long | **租户 ID（必传，WHERE 过滤）** |
| `creator` / `updater` | String | 创建/更新人 |
| `createTime` / `updateTime` | LocalDateTime | 创建/更新时间 |
| `deleted` | Boolean | **逻辑删除（WHERE deleted=0）** |
