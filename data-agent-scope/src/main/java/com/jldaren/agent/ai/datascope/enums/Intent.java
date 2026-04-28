package com.jldaren.agent.ai.datascope.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户意图枚举 - 统一管理所有业务意图
 * 新增意图只需：1.加枚举 2.加匹配规则 3.改业务逻辑，无需动分类器核心
 */
@Getter
@AllArgsConstructor
public enum Intent {

    /** 生成报告/分析/总结 */
    REPORT_GENERATE("report_generate", "生成报告类意图"),

    /** 查询数据/搜索信息 */
    DATA_QUERY("data_query", "数据查询类意图"),

    /** 闲聊/问候/无明确意图 */
    CHAT("chat", "闲聊类意图"),

    /** 系统配置/任务管理 */
    CONFIG_UPDATE("config_update", "配置管理类意图"),

    /** 无法识别的意图 */
    UNKNOWN("unknown", "未知意图");

    /** 意图编码（用于日志/监控/配置） */
    private final String code;

    /** 意图描述（用于文档/排查） */
    private final String desc;

    /** 是否是「需要生成报告」的意图（业务快捷判断） */
    public boolean needReport() {
        return this == REPORT_GENERATE;
    }
}