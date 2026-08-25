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

/**
 * 本周采购意向、招标信息知晓清况
 */
@Data
@ExcelIgnoreUnannotated
@HeadFontStyle(fontHeightInPoints = 12, fontName = "微软雅黑", bold = BooleanEnum.FALSE)
@ContentFontStyle(fontHeightInPoints = 12, fontName = "微软雅黑")
@ContentStyle(verticalAlignment = VerticalAlignmentEnum.CENTER, horizontalAlignment = HorizontalAlignmentEnum.CENTER,
        borderLeft = BorderStyleEnum.THIN, borderRight = BorderStyleEnum.THIN, borderTop = BorderStyleEnum.THIN, borderBottom = BorderStyleEnum.THIN)
//内容居中
@HeadStyle(verticalAlignment = VerticalAlignmentEnum.CENTER, horizontalAlignment = HorizontalAlignmentEnum.CENTER,
        fillPatternType = FillPatternTypeEnum.SOLID_FOREGROUND, fillForegroundColor = 44)//表头居中
@ContentRowHeight(36)
@HeadRowHeight(36)
public class WeekKnowRespVO {

    @Schema(description = "营销单位")
    @ExcelProperty({"营销单位"})
    private String city;

    @Schema(description = "合计-发布个数")
    @ExcelProperty({"合计", "发布个数"})
    private Integer totalNum;

    @Schema(description = "合计-预算金额")
    @ExcelProperty({"合计", "预算金额"})
    private BigDecimal totalBudget;

    @Schema(description = "合计-商机知晓-知晓数量")
    @ExcelProperty({"合计", "商机知晓", "知晓数量"})
    private Integer totalKnowNum;

    @Schema(description = "合计-商机知晓-知晓金额")
    @ExcelProperty({"合计", "商机知晓", "知晓金额"})
    private BigDecimal totalKnowBudget;

    @Schema(description = "合计-商机知晓-知晓商机数量比例")
    @ExcelProperty({"合计", "商机知晓", "知晓商机数量比例"})
    private String totalNumPercent;

    @Schema(description = "其中：采购信息-发布个数")
    @ExcelProperty({"其中：采购信息", "发布个数"})
    private String purchaseNum;

    @Schema(description = "其中：采购信息-预算金额")
    @ExcelProperty({"其中：采购信息", "预算金额"})
    private String purchaseBudget;

    @Schema(description = "其中：采购信息-商机知晓-知晓数量")
    @ExcelProperty({"其中：采购信息", "商机知晓", "知晓数量"})
    private BigDecimal purchaseKnowNum;

    @Schema(description = "其中：采购信息-商机知晓-知晓金额")
    @ExcelProperty({"其中：采购信息", "商机知晓", "知晓金额"})
    private String purchaseKnowBudget;

    @Schema(description = "其中：采购信息-商机知晓-知晓商机数量比例")
    @ExcelProperty({"其中：采购信息", "商机知晓", "知晓商机数量比例"})
    private String purchaseNumPercent;

    @Schema(description = "其中：招标信息-发布个数")
    @ExcelProperty({"其中：招标信息", "发布个数"})
    private String biddingNum;

    @Schema(description = "其中：招标信息-预算金额")
    @ExcelProperty({"其中：招标信息", "预算金额"})
    private String biddingBudget;

    @Schema(description = "其中：招标信息-商机知晓-知晓数量")
    @ExcelProperty({"其中：招标信息", "商机知晓", "知晓数量"})
    private BigDecimal biddingKnowNum;

    @Schema(description = "其中：招标信息-商机知晓-知晓金额")
    @ExcelProperty({"其中：招标信息", "商机知晓", "知晓金额"})
    private String biddingKnowBudget;

    @Schema(description = "其中：招标信息 - 商机知晓 - 知晓商机数量比例")
    @ExcelProperty({"其中：招标信息", "商机知晓", "知晓商机数量比例"})
    private String biddingNumPercent;

    @Schema(description = "采购意向超过 300 万项目数")
    private Integer purchaseOver300Num;

    @Schema(description = "招标公告超过 300 万项目数")
    private Integer biddingOver300Num;

}
