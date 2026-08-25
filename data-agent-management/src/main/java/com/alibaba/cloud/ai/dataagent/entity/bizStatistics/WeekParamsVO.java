package com.alibaba.cloud.ai.dataagent.entity.bizStatistics;

import lombok.Data;

import java.util.List;

@Data
public class WeekParamsVO {
    private String province;
    private String dateTime;
    private List<String> publishTime;
}
