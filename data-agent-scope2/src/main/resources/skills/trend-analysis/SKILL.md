---
name: trend-analysis
description: 当用户要做趋势分析、对比分析、统计聚合、年度/月度汇总、Top 排名、增长率计算、行业对比、地区分布等多维度分析时使用。横跨 4 张业务表（中标/招标/采购意向/拟在建）。
---

# 趋势分析

## 适用场景
- "近三年中标金额的趋势"
- "XX 行业各地区的中标分布"
- "每月新增招标数量"
- "对比今年和去年的采购预算"
- "Top 10 中标单位排名"
- "XX 行业各企业的市场份额"
- "商机全生命周期跟踪（拟在建→采购意向→招标→中标）"

## 不适用
- 简单单表查询 → 用 `query-bid-winner` / `query-bidding` / `query-purchase-intention` / `query-prepose`
- 只想看清单 → 用上面 4 个 skill 之一

## 执行步骤

### 1. 明确分析维度
先想清楚：
- **时间维度**：年/月/季度
- **对象维度**：省份/城市/行业
- **指标维度**：数量/金额/同比/环比
- **范围**：哪类数据（中标/招标/采购意向/拟在建）—— bizType 参数

### 2. 数据获取
根据需要调用 1~N 次 `query_biz_data`：
- 数据规模小（<1000 条）→ 一次拉完
- 数据规模大 → 分批拉 + 用 `run_python` 合并

### 3. 调 `query_biz_data` 取数据

```json
{
  "bizType": "bid_winner | bidding | purchase_intention | prepose",
  "province": "<必填，参考 authorizedProvince>",
  "conditions": {
    "province": "<可选>",
    "city": "<可选>",
    "industry": "<可选>",
    "startDate": "yyyy-MM-dd",
    "endDate": "yyyy-MM-dd"
  },
  "limit": 100
}
```

### 4. 调 `run_python` 做分析

**趋势**：
```python
df['publishTime'] = pd.to_datetime(df['publishTime'])
monthly = df.groupby(df['publishTime'].dt.to_period('M'))['amount'].sum()
result = monthly.to_dict()
```

**Top 排名**：
```python
top = df.sort_values('amount', ascending=False).head(10)
result = top[['projectName', 'tenderer', 'amount', 'publishTime']].to_dict('records')
```

**地区分布**：
```python
region_dist = df.groupby('province')['amount'].agg(['sum', 'count'])
result = region_dist.reset_index().to_dict('records')
```

**同比/环比**：
```python
df['month'] = df['publishTime'].dt.to_period('M')
monthly = df.groupby('month')['amount'].sum()
yoy = monthly.pct_change(12)
result = {'monthly': monthly.to_dict(), 'yoy': yoy.to_dict()}
```

**多表对比（商机漏斗）**：
```python
# prepose → purchase_intention → bidding → bid_winner 全流程转化率
funnel = {
    'prepose': prepose_count,
    'purchase_intention': pi_count,
    'bidding': bid_count,
    'bid_winner': win_count,
    'conversion_rate': win_count / prepose_count
}
```

### 5. 生成报告
- `format_report` 生成 Markdown 表格
- 关键发现用自然语言总结

## 输出模板

```
## 趋势分析报告

**分析维度**：时间(近三年) × 行业(信息技术) × 地区(贵州)
**数据范围**：bid_biz_bidding（N=1,234 条）
**金额单位**：元（win_bid 用 winBidPrice，其他用 biddingBudget）

### 关键发现
1. 2024 年同比增长 35%，2025 年趋稳
2. 长三角地区占比 42%
3. 前 5 名企业占 60% 份额

### 数据明细

| 月份 | 数量 | 总金额（元） | 同比 |
|---|---|---|---|
| 2026-01 | 123 | 45,678,000 | +12% |
| ... | ... | ... | ... | ... |

### 业务建议
- XX 地区是热点
- XX 时间段适合加大投入
```

## 注意事项

- 数据不足时（<10 条）→ 告知"数据量不足，建议扩大时间范围"
- 异常值处理：金额为 0 或负数的记录建议过滤
- 时间字段必须用 `publishTime`，传 `yyyy-MM-dd` 字符串
- **金额单位差异**：`bid_biz_win_bid` 和 `bid_biz_bidding` 用**元**，`bid_biz_purchase_intention` 和 `bid_biz_prepose` 用**万元**——对比时记得单位换算！
- **bizType 决定金额字段**：win_bid → winBidPrice，其他 → biddingBudget
- **每次调用都要传 province**（用户授权范围内）
