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
package com.alibaba.cloud.ai.dataagent.entity.bizStatistics;

import com.alibaba.excel.annotation.ExcelIgnoreUnannotated;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.*;
import com.alibaba.excel.enums.BooleanEnum;
import com.alibaba.excel.enums.poi.BorderStyleEnum;
import com.alibaba.excel.enums.poi.FillPatternTypeEnum;
import com.alibaba.excel.enums.poi.HorizontalAlignmentEnum;
import com.alibaba.excel.enums.poi.VerticalAlignmentEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 周报")
@Data
@ExcelIgnoreUnannotated
@HeadFontStyle(fontHeightInPoints = 12, fontName = "微软雅黑", bold = BooleanEnum.TRUE, color = 9)
@ContentFontStyle(fontHeightInPoints = 12, fontName = "微软雅黑")
@ContentStyle(verticalAlignment = VerticalAlignmentEnum.CENTER, horizontalAlignment = HorizontalAlignmentEnum.CENTER,
        borderLeft = BorderStyleEnum.THIN, borderRight = BorderStyleEnum.THIN, borderTop = BorderStyleEnum.THIN, borderBottom = BorderStyleEnum.THIN)
//内容居中
@HeadStyle(verticalAlignment = VerticalAlignmentEnum.CENTER, horizontalAlignment = HorizontalAlignmentEnum.CENTER, fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND  )//表头居中
@ContentRowHeight(36)
@HeadRowHeight(36)
public class WeekRepRespVO {
    @Schema(description = "营销单位")
    @ExcelProperty({"营销单位"})
    @HeadStyle(fillForegroundColor = 48)
    private String city;

    @Schema(description = "本周累计-运行商中标-中标额")
    @ExcelProperty({"本周累计", "运行商中标", "中标额（万元）"})
    @HeadStyle(fillForegroundColor = 48)
    private BigDecimal weekOperatorPrice;

    @Schema(description = "本周累计-运行商中标-数量")
    @ExcelProperty({"本周累计", "运行商中标", "数量（个）"})
    @HeadStyle(fillForegroundColor = 48)
    private String weekOperatorNum;

    @Schema(description = "本周累计-运行商中标-联通-金额-中标额")
    @ExcelProperty({"本周累计", "其中：联通", "金额", "中标额"})
    @HeadStyle(fillForegroundColor = 48)
    private BigDecimal weekUnicomPrice;

    @Schema(description = "本周累计-运行商中标-联通-金额-金额份额")
    @ExcelProperty({"本周累计", "其中：联通", "金额", "金额份额"})
    @HeadStyle(fillForegroundColor = 48)
    private String weekUnicomPricePercent;

    @Schema(description = "本周累计-运行商中标-联通-数量")
    @ExcelProperty({"本周累计", "其中：联通", "数量", "数量"})
    @HeadStyle(fillForegroundColor = 48)
    private String weekUnicomNum;

    @Schema(description = "本周累计-运行商中标-联通-数量-数量份额")
    @ExcelProperty({"本周累计", "其中：联通", "数量", "数量份额"})
    @HeadStyle(fillForegroundColor = 48)
    private String weekUnicomNumPercent;

    @Schema(description = "本周累计-当周丢标情况-丢标金额")
    @ExcelProperty({"本周累计", "当周丢标情况", "丢标金额"})
    @HeadStyle(fillForegroundColor = 48)
    private BigDecimal weekLostPrice;

    @Schema(description = "本周累计-当周丢标情况-丢标数量")
    @ExcelProperty({"本周累计", "当周丢标情况", "丢标数量"})
    @HeadStyle(fillForegroundColor = 48)
    private String weekLostNum;

    @Schema(description = "本周累计-当周丢标情况-商机未知数量")
    @ExcelProperty({"本周累计", "当周丢标情况", "商机未知数量"})
    @HeadStyle(fillForegroundColor = 48)
    private String weekUnBizNum;

    @Schema(description = "本月累计-运营商中标-金额")
    @ExcelProperty({"本月累计", "运行商中标", "中标额"})
    @HeadStyle(fillForegroundColor = 44)
    @HeadFontStyle(color = 8)
    private String monthOperatorPrice;

    @Schema(description = "本月累计-运营商中标-数量")
    @ExcelProperty({"本月累计", "运行商中标", "中标数量"})
    @HeadStyle(fillForegroundColor = 44)
    @HeadFontStyle(color = 8)
    private String monthOperatorNum;

    @Schema(description = "本月累计-联通-金额")
    @ExcelProperty({"本月累计", "其中：联通", "金额", "中标额"})
    @HeadStyle(fillForegroundColor = 44)
    @HeadFontStyle(color = 8)
    private BigDecimal monthUnicomPrice;

    @Schema(description = "本月累计-联通-金额份额")
    @ExcelProperty({"本月累计", "其中：联通", "金额", "金额份额"})
    @HeadStyle(fillForegroundColor = 44)
    @HeadFontStyle(color = 8)
    private String monthPricePercent;

    @Schema(description = "本月累计-联通-份额月环比（PP)")
    @ExcelProperty({"本月累计", "其中：联通", "金额", "金额份额月环比（PP）"})
    @HeadStyle(fillForegroundColor = 44)
    @HeadFontStyle(color = 8)
    private String monthUnicomPPPrice;

    @Schema(description = "本月累计-联通-金额排名")
    @ExcelProperty({"本月累计", "其中：联通", "金额", "排名"})
    @HeadStyle(fillForegroundColor = 44)
    @HeadFontStyle(color = 8)
    private Integer monthUnicomPriceRank;

    @Schema(description = "本月累计-联通-数量")
    @ExcelProperty({"本月累计", "其中：联通", "数量", "中标数量"})
    @HeadStyle(fillForegroundColor = 44)
    @HeadFontStyle(color = 8)
    private String monthUnicomNum;

    @Schema(description = "本月累计-联通=数量份额")
    @ExcelProperty({"本月累计", "其中：联通", "数量", "数量份额"})
    @HeadStyle(fillForegroundColor = 44)
    @HeadFontStyle(color = 8)
    private String monthUnicomNumPercent;

    @Schema(description = "本月累计-联通=数量份额月环比（PP）")
    @ExcelProperty({"本月累计", "其中：联通", "数量", "数量份额月环比（PP）"})
    @HeadStyle(fillForegroundColor = 44)
    @HeadFontStyle(color = 8)
    private String monthUnicomPPNum;

    @Schema(description = "本月累计-联通-金额排名")
    @ExcelProperty({"本月累计", "其中：联通", "数量", "排名"})
    @HeadStyle(fillForegroundColor = 44)
    @HeadFontStyle(color = 8)
    private Integer monthUnicomNumRank;

    @Schema(description = "本年累计-公开市场开标-开标额")
    @ExcelProperty({"本年累计", "公开市场开标", "开标额（万元）"})
    @HeadStyle(fillForegroundColor = 30)
    private BigDecimal yearPublicPrice;

    @Schema(description = "本年累计-公开市场开标-数量（个）")
    @ExcelProperty({"本年累计", "公开市场开标", "数量（个）"})
    @HeadStyle(fillForegroundColor = 30)
    private String yearPublicNum;

    @Schema(description = "本年累计-运营商中标-金额")
    @ExcelProperty({"本年累计", "运营商中标", "金额", "中标额"})
    @HeadStyle(fillForegroundColor = 30)
    private BigDecimal yearOperatorPrice;

    @Schema(description = "本年累计-运营商中标-金额份额")
    @ExcelProperty({"本年累计", "运营商中标", "金额", "金额份额"})
    @HeadStyle(fillForegroundColor = 30)
    private String yearOperatorPricePercent;

    @Schema(description = "本年累计-运营商中标-数量")
    @ExcelProperty({"本年累计", "运营商中标", "数量", "中标数量"})
    @HeadStyle(fillForegroundColor = 30)
    private String yearOperatorNum;

    @Schema(description = "本年累计-运营商中标-数量份额")
    @ExcelProperty({"本年累计", "运营商中标", "数量", "数量份额"})
    @HeadStyle(fillForegroundColor = 30)
    private String yearOperatorNumPercent;

    @Schema(description = "本年累计-联通-金额")
    @ExcelProperty({"本年累计", "其中：联通", "金额", "中标额"})
    @HeadStyle(fillForegroundColor = 30)
    private BigDecimal yearUnicomPrice;

    @Schema(description = "本年累计-联通-金额份额")
    @ExcelProperty({"本年累计", "其中：联通", "金额", "金额份额"})
    @HeadStyle(fillForegroundColor = 30)
    private String yearUnicomPricePercent;

    @Schema(description = "本年累计-联通-排名")
    @ExcelProperty({"本年累计", "其中：联通", "金额", "排名"})
    @HeadStyle(fillForegroundColor = 30)
    private Integer yearUnicomPriceRank;

    @Schema(description = "本年累计-联通-数量")
    @ExcelProperty({"本年累计", "其中：联通", "数量", "中标数量"})
    @HeadStyle(fillForegroundColor = 30)
    private String yearUnicomNum;

    @Schema(description = "本年累计-联通-数量份额")
    @ExcelProperty({"本年累计", "其中：联通", "数量", "数量份额"})
    @HeadStyle(fillForegroundColor = 30)
    private String yearUnicomNumPercent;

    @Schema(description = "本年累计-联通-数量-排名")
    @ExcelProperty({"本年累计", "其中：联通", "数量", "排名"})
    @HeadStyle(fillForegroundColor = 30)
    private Integer yearUnicomNumRank;

    private String yearRadioTVNumPercent;
    private String yearMobilePricePercent;
    private String yearTelecomPricePercent;
    private String yearRadioTVPricePercent;
    private String yearMobileNumPercent;
    private String yearTelecomNumPercent;
    @Schema(description = "本月累计-运行商中标-移动-金额-金额份额")
    private String weekMobilePricePercent;
    @Schema(description = "本月累计-运行商中标-电信-金额-金额份额")
    private String weekTelecomPricePercent;
    @Schema(description = "本月累计-运行商中标-广电-金额-金额份额")
    private String weekRadioTVPricePercent;
    @Schema(description = "本周累计-运行商中标-移动-数量-数量份额")
    private String weekMobileNumPercent;
    @Schema(description = "本周累计-运行商中标-电信-数量-数量份额")
    private String weekTelecomNumPercent;
    @Schema(description = "本周累计-运行商中标-广电-数量-数量份额")
    private String weekRadioTVNumPercent;
    @Schema(description = "城市字典排序")
    private Integer citySort;

}
