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

@Schema(description = "管理后台 - 统计业务 Response VO")
@Data
@ExcelIgnoreUnannotated
@HeadFontStyle(fontHeightInPoints = 12, fontName = "微软雅黑", bold = BooleanEnum.FALSE)
@ContentFontStyle(fontHeightInPoints = 12, fontName = "微软雅黑")
@ContentStyle(verticalAlignment = VerticalAlignmentEnum.CENTER, horizontalAlignment = HorizontalAlignmentEnum.CENTER,
        borderLeft = BorderStyleEnum.THIN, borderRight = BorderStyleEnum.THIN, borderTop = BorderStyleEnum.THIN, borderBottom = BorderStyleEnum.THIN)
//内容居中
@HeadStyle(verticalAlignment = VerticalAlignmentEnum.CENTER, horizontalAlignment = HorizontalAlignmentEnum.CENTER,
        fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND, fillForegroundColor = 51)//表头居中
@ContentRowHeight(36)
@HeadRowHeight(36)
public class WeekWinRespVO {
    @Schema(description = "单位")
    @ExcelProperty("单位")
    private String city;

    @Schema(description = "招标人")
    @ExcelProperty("招标人")
    private String tenderer;

    @Schema(description = "行业")
    @ExcelProperty("行业")
    private String industry;

    @Schema(description = "中标项目名称")
    @ExcelProperty("中标项目名称")
    private String projectName;

    @Schema(description = "中标金额（万元）")//保留整数，四舍五入
    @ExcelProperty("中标金额（万元）")
    private BigDecimal winBidPrice;

    @Schema(description = "运营商")
    private String operator;

    @Schema(description = "总数量")
    private Integer totalNum;

    @Schema(description = "总金额")
    private BigDecimal totalPrice;

    @Schema(description = "运营商数量")
    private Integer operatorNum;

    @Schema(description = "运营商金额")
    private BigDecimal operatorPrice;

    @Schema(description = "联通金额")
    private BigDecimal unicomPrice;

    @Schema(description = "联通占比")
    private String unicomPercent;

}
