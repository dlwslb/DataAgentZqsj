/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.mapper.bizStatistics;

import com.alibaba.cloud.ai.dataagent.entity.bizStatistics.*;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
import java.util.Map;

@Mapper
public interface BizStatisticsMapper {

    WeekRepRespVO getSumText(Map<String, String> paramMap);

    List<WeekRepRespVO> getWeekProvince(Map<String, String> paramMap);


    List<WeekKnowRespVO> getKnowWeekPage(Map<String, String> paramMap);

    WeekKnowRespVO getSumKnowWeekPage(Map<String, String> paramMap);



    /**
     * 获取当天的采购意向、招标统计汇总
     */
    WeekKnowRespVO getSumKnowDayPage(Map<String, String> paramMap);

    /**
     * 获取当天采购意向、招标按地市统计
     */
    List<WeekKnowRespVO> getDayPurchasePage(Map<String, String> paramMap);

    /**
     * 获取当天招标公告按地市统计
     */
    List<WeekKnowRespVO> getDayBiddingPage(Map<String, String> paramMap);

    /**
     * 获取当天中标统计汇总
     */
    WeekWinRespVO getSumDayWinBid(Map<String, String> paramMap);

    /**
     * 获取当天中标按地市列表
     */
    List<WeekWinRespVO> getDayWinBidList(Map<String, String> paramMap);

    /**
     * 获取当天采购意向超过 300 万的地市分布
     */
    String getDayPurchaseOver300Cities(Map<String, String> paramMap);

    /**
     * 获取当天招标公告超过 300 万的地市分布
     */
    String getDayBiddingOver300Cities(Map<String, String> paramMap);

}
