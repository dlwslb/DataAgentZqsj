-- 强制 project_name 字段必选
-- 执行后重新"初始化数据源"

-- 1. 将 project_name 设为主键（从注释层面强调重要性）
ALTER TABLE `bid_biz_win_bid`
MODIFY COLUMN `id` bigint COMMENT '主键ID，每条记录的唯一标识，注意：这个字段只是技术主键，查询时应该使用 project_name（招标项目名称）作为业务主键';

-- 2. 明确 project_name 是必选字段
ALTER TABLE `bid_biz_win_bid`
MODIFY COLUMN `project_name` varchar(800)
COMMENT '【必选字段】招标项目名称(标名)，项目全称，中标项目名称，招标名称，标名。注意：这是招标信息最核心的业务主键，任何与招标相关的查询都必须包含此字段。标名是指整个招标项目的名称，不是具体的产品内容。所有查询都应返回此字段以标识具体项目。';

-- 3. 弱化其他字段的必要性
ALTER TABLE `bid_biz_win_bid`
MODIFY COLUMN `product` text
COMMENT '【可选字段】产品信息，项目产品，招标产品。注意：这是招标采购的具体产品或服务内容，是可选的补充信息，不是必选字段。主要标识是 project_name（标名）。';

ALTER TABLE `bid_biz_win_bid`
MODIFY COLUMN `win_tenderer` varchar(2000)
COMMENT '【可选字段】中标人，中标单位，中标公司名称。注意：这是中标单位信息，用于筛选特定单位。主要标识是 project_name（标名）。';

ALTER TABLE `bid_biz_win_bid`
MODIFY COLUMN `publish_time` date
COMMENT '【可选字段】发布日期。注意：这是招标公告发布的日期，用于时间范围筛选。主要标识是 project_name（标名）。';

-- 4. 优化其他字段注释
ALTER TABLE `bid_biz_win_bid`
MODIFY COLUMN `win_bid_price` decimal(14,2)
COMMENT '中标金额(元)，实际中标价格，中标价格，中标金额，项目实际中标价格。注意：用于比较大小、排序（如最大的、最小的）。查询时应与 project_name 一起返回。';

ALTER TABLE `bid_biz_win_bid`
MODIFY COLUMN `bidding_budget` decimal(14,2)
COMMENT '预算金额(元)，招标预算价格，项目预算。注意：这是招标预算，不是实际中标金额(win_bid_price)。查询时应与 project_name 一起返回。';
