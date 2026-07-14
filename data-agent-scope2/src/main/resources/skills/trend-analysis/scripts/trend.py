"""
趋势分析脚本示例 - agent 可以通过 execute_shell_command 调用

实际使用：通过 run_python tool 即可，不需要直接调脚本
这里作为 skill 文档的参考实现
"""
import pandas as pd
import json
import sys

# 从 stdin 读入数据
data = json.load(sys.stdin)
df = pd.DataFrame(data)

if df.empty:
    print(json.dumps({"error": "empty data"}))
    sys.exit(0)

# 时间字段处理
df['date'] = pd.to_datetime(df['date'])

# 按月聚合
df['month'] = df['date'].dt.to_period('M').astype(str)
monthly = df.groupby('month').agg(
    count=('id', 'count'),
    total_amount=('amount', 'sum')
).reset_index()

# 同比
monthly['yoy'] = monthly['total_amount'].pct_change(12).fillna(0).round(4)

result = {
    "summary": {
        "total_records": len(df),
        "date_range": [str(df['date'].min()), str(df['date'].max())],
        "total_amount": float(df['amount'].sum())
    },
    "monthly": monthly.to_dict('records')
}

print(json.dumps(result, ensure_ascii=False, default=str))
