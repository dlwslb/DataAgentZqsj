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
 * 项目丢标情况返回列表
 */
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
public class WeekLostRespVO {

    @Schema(description = "单位")
    @ExcelProperty("单位")
    private String city;

    @Schema(description = "运营商")
    @ExcelProperty("运营商")
    private String operator;

    @Schema(description = "招标人")
    @ExcelProperty("招标人")
    private String tenderer;

    @Schema(description = "行业")
    @ExcelProperty("行业")
    private String industry;

    @Schema(description = "丢标项目名称")
    @ExcelProperty("丢标项目名称")
    private String projectName;

    @Schema(description = "中标金额（万元）")//保留整数，四舍五入
    @ExcelProperty("中标金额（万元）")
    private BigDecimal winBidPrice;

    @Schema(description = "是否报商机")
    @ExcelProperty("是否报商机")
    private String relateBusiness;

}
