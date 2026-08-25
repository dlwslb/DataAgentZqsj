package com.alibaba.cloud.ai.dataagent.service.bizStatistics;

import com.alibaba.cloud.ai.dataagent.entity.bizStatistics.WeekParamsVO;

import java.util.Map;

public interface StatisticsService {

    //公开市场标讯推送 按时间范围
    Map getDayProvinceText(WeekParamsVO bean);
    //标讯通报 单日期查询
    Map getWeekProvinceText(WeekParamsVO ban);

}
