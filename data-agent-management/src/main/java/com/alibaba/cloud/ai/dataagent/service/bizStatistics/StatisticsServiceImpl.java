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
package com.alibaba.cloud.ai.dataagent.service.bizStatistics;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.dataagent.entity.bizStatistics.WeekKnowRespVO;
import com.alibaba.cloud.ai.dataagent.entity.bizStatistics.WeekParamsVO;
import com.alibaba.cloud.ai.dataagent.entity.bizStatistics.WeekRepRespVO;
import com.alibaba.cloud.ai.dataagent.entity.bizStatistics.WeekWinRespVO;
import com.alibaba.cloud.ai.dataagent.mapper.bizStatistics.BizStatisticsMapper;
import com.alibaba.cloud.ai.dataagent.util.date.DateUtils;
import com.alibaba.cloud.ai.dataagent.util.date.LocalDateTimeUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@Validated
public class StatisticsServiceImpl implements StatisticsService {

    @Resource
    private BizStatisticsMapper bizStatisticsMapper;

    //公开市场标讯推送
    @Override
    public Map getDayProvinceText(WeekParamsVO bean) {
        // dateTime 转化成 now
        Map map = new HashMap();
        String dayDate = null;
        List<String> dateTime = bean.getPublishTime();
        if(dateTime==null||dateTime.size()==0){
            // dayDate: 如果传了参数就用参数日期，否则用前一天
            LocalDateTime now = LocalDateTime.now();
            dayDate = now.minusDays(1).toLocalDate().format(DateTimeFormatter.ofPattern(DateUtils.FORMAT_YEAR_MONTH_DAY));
        }else{
            // 如果开始和结束日期相同，取开始日期；否则拼接
            String startDate = dateTime.get(0).substring(0, 10);
            String endDate = dateTime.get(1).substring(0, 10);
            dayDate = startDate.equals(endDate) ? startDate : startDate + "至" + endDate;
        }
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put("province", bean.getProvince());
        if(dateTime!=null && dateTime.size()>0){
            paramMap.put("publishStartTime", dateTime.get(0));
            paramMap.put("publishEndTime", dateTime.get(1));
        }else{
            paramMap.put("publishStartTime", dayDate);
            paramMap.put("publishEndTime", dayDate);
        }

        // 新增当天数据
        String dayTitle = "【" + dayDate.replace("-", ".") + "公开市场标讯推送】";
        map.put("标题", dayTitle);

        // 获取当天采购意向数据
        List<WeekKnowRespVO> dayPurchaseList = bizStatisticsMapper.getDayPurchasePage(paramMap);
        List<WeekKnowRespVO> dayBiddingList = bizStatisticsMapper.getDayBiddingPage(paramMap);
        WeekKnowRespVO daySumKnow = bizStatisticsMapper.getSumKnowDayPage(paramMap);
        String dayPurOver300Cities = bizStatisticsMapper.getDayPurchaseOver300Cities(paramMap);
        String dayBidOver300Cities = bizStatisticsMapper.getDayBiddingOver300Cities(paramMap);

        // 采购意向
        String dayPurCities = dayPurchaseList.stream()
                .filter(item -> item.getPurchaseNum() != null && Integer.parseInt(item.getPurchaseNum()) > 0)
                .map(item -> item.getCity() + item.getPurchaseNum() + "个，" + (item.getPurchaseBudget() == null || "0".equals(item.getPurchaseBudget()) ? "金额未公开" : "金额" + item.getPurchaseBudget() + "万元"))
                .collect(Collectors.joining("; "));
        String template = "✅公开采购意向项目{}个，金额{}万元。超过 300 万项目{}个，涉及地市为{}。其中{}。";
        map.put("采购意向", StrUtil.format(template,
                daySumKnow.getPurchaseNum() != null ? daySumKnow.getPurchaseNum() : "0",
                daySumKnow.getPurchaseBudget() != null ? daySumKnow.getPurchaseBudget() : "0",
                daySumKnow.getPurchaseOver300Num() != null ? daySumKnow.getPurchaseOver300Num() : "0",
                StrUtil.isEmpty(dayPurOver300Cities) ? "无" : dayPurOver300Cities,
                StrUtil.isEmpty(dayPurCities) ? "无" : dayPurCities));

        // 招标公告
        String dayBidCities = dayBiddingList.stream()
                .filter(item -> item.getBiddingNum() != null && Integer.parseInt(item.getBiddingNum()) > 0)
                .map(item -> item.getCity() + item.getBiddingNum() + "个，" + (item.getBiddingBudget() == null || "0".equals(item.getBiddingBudget()) ? "金额未公开" : "金额" + item.getBiddingBudget() + "万元"))
                .collect(Collectors.joining("; "));
        template = "✅公开招标项目{}个，金额{}万元。超过 300 万项目{}个，涉及地市为{}。其中{}。";
        map.put("招标公告", StrUtil.format(template,
                daySumKnow.getBiddingNum() != null ? daySumKnow.getBiddingNum() : "0",
                daySumKnow.getBiddingBudget() != null ? daySumKnow.getBiddingBudget() : "0",
                daySumKnow.getBiddingOver300Num() != null ? daySumKnow.getBiddingOver300Num() : "0",
                StrUtil.isEmpty(dayBidOver300Cities) ? "无" : dayBidOver300Cities,
                StrUtil.isEmpty(dayBidCities) ? "无" : dayBidCities));

        // 中标公告
        List<WeekWinRespVO> dayWinList = bizStatisticsMapper.getDayWinBidList(paramMap);
        WeekWinRespVO dayWinSum = bizStatisticsMapper.getSumDayWinBid(paramMap);

        // 运营商各地市分布
        Map<String, long[]> operatorCityStats = dayWinList.stream()
                .filter(item -> Arrays.asList("联通", "移动", "电信", "广电", "铁搭").contains(item.getOperator()))
                .collect(Collectors.groupingBy(
                        WeekWinRespVO::getCity,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> new long[]{list.size(), list.stream().mapToLong(item -> item.getWinBidPrice().longValue()).sum()}
                        )
                ));

        String operatorCityDetail = operatorCityStats.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue((a, b) -> Long.compare(b[1], a[1])))
                .map(entry -> entry.getKey() + entry.getValue()[0] + "个，金额" + (entry.getValue()[1] > 0 ? entry.getValue()[1] + "万元" : "未公开"))
                .collect(Collectors.joining("; "));

        // 超过 300 万项目统计
        long over300Count = dayWinList.stream().filter(item -> item.getWinBidPrice() != null && item.getWinBidPrice().compareTo(new BigDecimal(300)) > 0).count();
        String over300Text = over300Count > 0 ? "超过300万项目" + over300Count + "个" : "今日暂无超过300万项目";
        String over300Cities = over300Count > 0 ?
                dayWinList.stream().filter(item -> item.getWinBidPrice() != null && item.getWinBidPrice().compareTo(new BigDecimal(300)) > 0)
                        .map(WeekWinRespVO::getCity).distinct().collect(Collectors.joining("、")) : "";
        String citiesPart = over300Count > 0 ? "，涉及地市为" + over300Cities : "";

        // 金额处理
        String totalPrice = dayWinSum.getTotalPrice() != null && !"0".equals(dayWinSum.getTotalPrice().toString()) ? dayWinSum.getTotalPrice() + "万元" : "金额未公开";
        String operatorPrice = dayWinSum.getOperatorPrice() != null && !"0".equals(dayWinSum.getOperatorPrice().toString()) ? dayWinSum.getOperatorPrice() + "万元" : "金额未公开";

        // 运营商金额
        BigDecimal unicomAmount = dayWinList.stream().filter(item -> "联通".equals(item.getOperator()))
                .map(WeekWinRespVO::getWinBidPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        String unicomAmountText = unicomAmount.compareTo(BigDecimal.ZERO) > 0 ? unicomAmount + "万元" : "金额未公开";

        BigDecimal mobileAmount = dayWinList.stream().filter(item -> "移动".equals(item.getOperator()))
                .map(WeekWinRespVO::getWinBidPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        String mobileAmountText = mobileAmount.compareTo(BigDecimal.ZERO) > 0 ? mobileAmount + "万元" : "金额未公开";

        BigDecimal telecomAmount = dayWinList.stream().filter(item -> "电信".equals(item.getOperator()))
                .map(WeekWinRespVO::getWinBidPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        String telecomAmountText = telecomAmount.compareTo(BigDecimal.ZERO) > 0 ? telecomAmount + "万元" : "金额未公开";

        // 运营商统计
        long unicomCount = dayWinList.stream().filter(item -> "联通".equals(item.getOperator())).count();
        String unicomText = unicomCount > 0 ? "联通" + unicomCount + "个，金额" + unicomAmountText : "联通当日无中标";

        long mobileCount = dayWinList.stream().filter(item -> "移动".equals(item.getOperator())).count();
        String mobileText = mobileCount > 0 ? "移动" + mobileCount + "个，金额" + mobileAmountText : "移动当日无中标";

        long telecomCount = dayWinList.stream().filter(item -> "电信".equals(item.getOperator())).count();
        String telecomText = telecomCount > 0 ? "电信" + telecomCount + "个，金额" + telecomAmountText : "电信当日无中标";

        template = "✅公开市场中标{}个，{}。其中运营商中标{}个，{}。{}{}。联通中标总金额占比运营商{}。{}；{}；{}；运营商中标项目涉及{}个地市，其中{}。";
        map.put("中标公告", StrUtil.format(template,
                dayWinSum.getTotalNum() != null ? dayWinSum.getTotalNum() : "0",
                totalPrice,
                dayWinSum.getOperatorNum() != null ? dayWinSum.getOperatorNum() : "0",
                operatorPrice,
                over300Text,
                citiesPart,
                dayWinSum.getUnicomPercent() != null ? dayWinSum.getUnicomPercent() : "0%",
                unicomText,
                mobileText,
                telecomText,
                operatorCityStats.size(),
                StrUtil.isEmpty(operatorCityDetail) ? "无" : operatorCityDetail));

        return map;
    }

    @Override
    public Map getWeekProvinceText(WeekParamsVO bean) {
        String dateTime = bean.getDateTime();
        // dateTime 转化成 now
        LocalDateTime now = LocalDateTime.now();
        if (dateTime != null) {
            now = LocalDateTimeUtils.parse(dateTime);
        }
        Map map = new HashMap();
        Map titles = getTitles(now);

        // dayDate: 如果传了参数就用参数日期，否则用前一天
        String dayDate = (dateTime != null)
                ? now.toLocalDate().format(DateTimeFormatter.ofPattern(DateUtils.FORMAT_YEAR_MONTH_DAY))
                : now.minusDays(1).toLocalDate().format(DateTimeFormatter.ofPattern(DateUtils.FORMAT_YEAR_MONTH_DAY));

        Map<String, String> paramMap = Map.of(
                "province", bean.getProvince(),
                "weekStartTime", DateUtil.format(DateUtils.getWeekStartTime(now), DateUtils.FORMAT_YEAR_MONTH_DAY),
                "weekEndTime", DateUtil.format(DateUtils.getWeekEndTime(now), DateUtils.FORMAT_YEAR_MONTH_DAY),
                "monthStartDate", DateUtil.format(DateUtils.getMonthStartTime(now), DateUtils.FORMAT_YEAR_MONTH_DAY),
                "monthEndDate", DateUtil.format(DateUtils.getMonthEndTime(now.toLocalDate()), DateUtils.FORMAT_YEAR_MONTH_DAY),
                "yearStartDate", DateUtil.format(DateUtils.getYearStartTime(now), DateUtils.FORMAT_YEAR_MONTH_DAY),
                "yearEndDate", DateUtil.format(DateUtils.getYearEndTime(now.toLocalDate()), DateUtils.FORMAT_YEAR_MONTH_DAY),
                "dayDate", dayDate);
        WeekRepRespVO sum = bizStatisticsMapper.getSumText(paramMap);
        List<WeekRepRespVO> cityList = bizStatisticsMapper.getWeekProvince(paramMap);
        // 筛选月价格百分比大于70%的城市
        List<String> priceCollect = cityList.stream()
                .filter(item -> !"-".equals(item.getMonthPricePercent()) && (Double.parseDouble(item.getMonthPricePercent().replace("%", "")) > 70))
                .sorted(Comparator.comparing(WeekRepRespVO::getMonthPricePercent).thenComparing(WeekRepRespVO::getCitySort))
                .map(WeekRepRespVO::getCity)
                .collect(Collectors.toList());
        // collect 转成用、分隔的字符串
        String priceJoin = StrUtil.join("、", priceCollect);
        List<String> numCollect = cityList.stream()
                .filter(item -> !"-".equals(item.getMonthUnicomNumPercent()) && Double.parseDouble(item.getMonthUnicomNumPercent().replace("%", "")) > 50)
                .sorted(Comparator.comparing(WeekRepRespVO::getMonthUnicomNumPercent).thenComparing(WeekRepRespVO::getCitySort))
                .map(WeekRepRespVO::getCity)
                .collect(Collectors.toList());
        // collect 转成用、分隔的字符串
        String numJoin = StrUtil.join("、", numCollect);

        String template = "{}：全省公开市场运营商中标共{}个，中标总金额{}；其中，联通中标{}个，中标金额{}，" +
                "中标金额份额{}（移动{}，电信{}，广电{}），联通中标数量份额{}（移动{}，电信{}，广电{}）。";
        map.put("本周累计", StrUtil.format(template, titles.get("week"), sum.getWeekOperatorNum(), getUnitPrice(sum.getWeekOperatorPrice()), sum.getWeekUnicomNum(), getUnitPrice(sum.getWeekUnicomPrice()),
                sum.getWeekUnicomPricePercent(), sum.getWeekMobilePricePercent(), sum.getWeekTelecomPricePercent(), sum.getWeekRadioTVPricePercent(),
                sum.getWeekUnicomNumPercent(), sum.getWeekMobileNumPercent(), sum.getWeekTelecomNumPercent(), sum.getWeekRadioTVNumPercent()));
        template = "{}：联通当月中标{}个，中标金额{}，中标金额份额{}，环比上月同期{}pp；中标数量份额{}，环比上月{}pp。金额份额：{}超过70%，数量份额：{}超过50%。";
        map.put("本月累计", StrUtil.format(template, titles.get("month"), sum.getMonthUnicomNum(), getUnitPrice(sum.getMonthUnicomPrice()), sum.getMonthPricePercent(),
                getPPText(sum.getMonthUnicomPPPrice()), sum.getMonthUnicomNumPercent(), getPPText(sum.getMonthUnicomPPNum()), priceJoin, numJoin));
        template = "{}：全省公开市场运营商中标累计{}个，金额{}，其中联通中标{}个，中标金额{}，中标金额份额{}（移动{}，电信{}，广电{}），" +
                "中标数量份额{}（{}，电信{}，广电{}）。金额份额：{}排名前三，{}排名后三；数量份额：{}排名前三，{}排名后三。";
        List<String> priceTop3 = cityList.stream()
                .sorted(Comparator.comparing(WeekRepRespVO::getYearUnicomPricePercent).reversed())
                .limit(3)
                .map(WeekRepRespVO::getCity)
                .collect(Collectors.toList());
        String priceTop3Join = StrUtil.join("、", priceTop3);
        List<String> priceLast3 = cityList.stream()
                .sorted(Comparator.comparing(WeekRepRespVO::getYearUnicomPricePercent))
                .limit(3)
                .map(WeekRepRespVO::getCity)
                .collect(Collectors.toList());
        String priceLast3Join = StrUtil.join("、", priceLast3);
        List<String> numTop3 = cityList.stream()
                .sorted(Comparator.comparing(WeekRepRespVO::getYearUnicomNumPercent).reversed())
                .limit(3)
                .map(WeekRepRespVO::getCity)
                .collect(Collectors.toList());
        String numTop3Join = StrUtil.join("、", numTop3);
        List<String> numLast3 = cityList.stream()
                .sorted(Comparator.comparing(WeekRepRespVO::getYearUnicomNumPercent))
                .limit(3)
                .map(WeekRepRespVO::getCity)
                .collect(Collectors.toList());
        String numLast3Join = StrUtil.join("、", numLast3);
        map.put("本年累计", StrUtil.format(template, titles.get("year"), sum.getYearPublicNum(), getUnitPrice(sum.getYearPublicPrice()), sum.getYearUnicomNum(),
                getUnitPrice(sum.getYearUnicomPrice()), sum.getYearUnicomPricePercent(), sum.getYearMobilePricePercent(), sum.getYearTelecomPricePercent(), sum.getYearRadioTVPricePercent(),
                sum.getYearUnicomNumPercent(), sum.getYearMobileNumPercent(), sum.getYearTelecomNumPercent(), sum.getYearRadioTVNumPercent(), priceTop3Join, priceLast3Join, numTop3Join, numLast3Join));

        template = "中标情况：本周全省联通中标{}个，中标金额{}。其中，{}，中标数量较多，{}中标金额1000万元以上，中标金额较大。";
        String winWeekNum = cityList.stream()
                .sorted(Comparator.comparing(WeekRepRespVO::getWeekUnicomNum).reversed())
                .limit(3).map(item -> item.getCity() + "中标" + item.getWeekUnicomNum() + "个").collect(Collectors.joining("，"));
        // 中标金额1000万元以上
        String winWeekPrice = cityList.stream()
                .filter(item -> item.getWeekUnicomPrice().compareTo(new BigDecimal(1000)) > 0)
                .sorted(Comparator.comparing(WeekRepRespVO::getWeekUnicomPrice).reversed())
                .limit(3).map(item -> item.getCity()).collect(Collectors.joining("、"));

        map.put("中标情况", StrUtil.format(template, sum.getWeekUnicomNum(), getUnitPrice(sum.getWeekUnicomPrice()), winWeekNum, winWeekPrice));
        // 丢标数量前三
        String lostWeekNum = cityList.stream()
                .sorted(Comparator.comparing(WeekRepRespVO::getWeekLostPrice).reversed())
                .limit(3).map(item -> item.getCity() + "丢标" + item.getWeekUnicomNum() + "个").collect(Collectors.joining("，"));
        template = "丢标情况：本周共计丢标{}个，丢标金额{}。其中，{}丢标数量较多。";
        map.put("丢标情况", StrUtil.format(template, sum.getWeekLostNum(), getUnitPrice(sum.getWeekLostPrice()), lostWeekNum));
        // 本周采购信息情况
        template = "本周全省共发布采购意向/招标信息{}个，涉及金额{}，其中，{}涉及金额过千万元。";
        List<WeekKnowRespVO> knowList = bizStatisticsMapper.getKnowWeekPage(paramMap);
        // 过滤千万元以上
        String knowCity = knowList.stream().filter(item -> item.getTotalBudget() != null && item.getTotalBudget().compareTo(new BigDecimal(1000)) > 0).map(WeekKnowRespVO::getCity).collect(Collectors.joining("、"));
        WeekKnowRespVO sumKnow = bizStatisticsMapper.getSumKnowWeekPage(paramMap);
        map.put("采购信息", StrUtil.format(template, sumKnow.getTotalNum(), getUnitPrice(sumKnow.getTotalKnowBudget()), StrUtil.isEmpty(knowCity) ? "无地区" : knowCity));
        template = "商机知晓：{}个发布的采购意向/招标信息中，{}个商机报送知晓，知晓率{}，{}个信息地市未知晓商机，其中，{}商机知晓率低于50%。";
        knowCity = knowList.stream().filter(item -> item.getTotalBudget() == null || Integer.parseInt(item.getTotalNumPercent().replace("%", "")) < 50).map(WeekKnowRespVO::getCity).collect(Collectors.joining("、"));
        map.put("商机知晓", StrUtil.format(template, sumKnow.getTotalNum(), sumKnow.getTotalKnowNum(), sumKnow.getTotalNumPercent(),
                sumKnow.getTotalNum() - sumKnow.getTotalKnowNum(), StrUtil.isEmpty(knowCity) ? "无地区" : knowCity));
        return map;
    }

    private Map getTitles(LocalDateTime now) {
        //now = LocalDateTimeUtils.buildTime(2024, 12, 11);
        return Map.of(
                "week", "本周累计（" + DateUtil.format(DateUtils.getWeekStartTime(now), "MM.dd") + "-" + DateUtil.format(DateUtils.getWeekEndTime(now), "MM.dd") + "）",
                "month", "本月累计（" + DateUtil.format(DateUtils.getMonthStartTime(now), "MM.dd") + "-" + DateUtil.format(DateUtils.getMonthEndTime(now.toLocalDate()), "MM.dd") + "）",
                "year", "本年累计（" + DateUtil.format(DateUtils.getYearStartTime(now), "MM.dd") + "-" + DateUtil.format(DateUtils.getYearEndTime(now.toLocalDate()), "MM.dd") + "）");
    }

    // 转亿元或万元
    private String getUnitPrice(BigDecimal price) {
        if (price == null) {
            return "0";
        }
        return price.compareTo(BigDecimal.valueOf(10000)) > 0 ?
                price.divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP) + "亿元" :
                price + "万元";
    }

    private String getPPText(String ppValue) {
        if (StrUtil.startWith(ppValue, "-")) {
            return ppValue.replace("-", "下降");
        } else {
            return "上升" + ppValue;
        }
    }
}
