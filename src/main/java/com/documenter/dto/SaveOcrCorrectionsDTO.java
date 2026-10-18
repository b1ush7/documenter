package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 保存一次 OCR 结果的逐页人工校正文本。 */
@Data
public class SaveOcrCorrectionsDTO {

    @NotBlank(message = "OCR 结果指纹不能为空")
    @Pattern(regexp = "^[a-f0-9]{64}$", message = "OCR 结果指纹格式错误")
    private String fingerprint;

    @Valid
    @NotEmpty(message = "至少需要一页校正文本")
    @Size(max = 100, message = "单次最多保存 100 页校正文本")
    private List<PageCorrection> pages;

    @Data
    public static class PageCorrection {

        @NotNull(message = "OCR 页码不能为空")
        @Min(value = 1, message = "OCR 页码必须大于 0")
        private Integer pageNo;

        /** 允许空字符串表示用户确认整页无文字。 */
        @NotNull(message = "校正文本不能为空")
        @Size(max = 2000000, message = "单页校正文本过长")
        private String text;

        /** 原页面 tables[] 的零基下标及人工确认后的单元格文本/结构。 */
        @Valid
        @Size(max = 20, message = "单页最多保存 20 个校正表格")
        private List<TableCorrection> tables;
    }

    @Data
    public static class TableCorrection {

        @NotNull(message = "OCR 表格序号不能为空")
        @Min(value = 0, message = "OCR 表格序号不能小于 0")
        private Integer tableIndex;

        @NotNull(message = "OCR 表格行数不能为空")
        @Min(value = 1, message = "OCR 表格至少需要一行")
        @Max(value = 200, message = "OCR 表格行数过多")
        private Integer rows;

        @NotNull(message = "OCR 表格列数不能为空")
        @Min(value = 1, message = "OCR 表格至少需要一列")
        @Max(value = 100, message = "OCR 表格列数过多")
        private Integer columns;

        @Valid
        @NotEmpty(message = "OCR 表格至少需要一个单元格")
        private List<CellCorrection> cells;
    }

    @Data
    public static class CellCorrection {

        @NotNull(message = "OCR 表格单元格行号不能为空")
        @Min(value = 0, message = "OCR 表格单元格行号不能小于 0")
        private Integer row;

        @NotNull(message = "OCR 表格单元格列号不能为空")
        @Min(value = 0, message = "OCR 表格单元格列号不能小于 0")
        private Integer column;

        @NotNull(message = "OCR 表格行跨度不能为空")
        @Min(value = 1, message = "OCR 表格行跨度必须大于 0")
        private Integer rowSpan;

        @NotNull(message = "OCR 表格列跨度不能为空")
        @Min(value = 1, message = "OCR 表格列跨度必须大于 0")
        private Integer columnSpan;

        @NotNull(message = "OCR 表格单元格文本不能为空")
        @Size(max = 2000000, message = "OCR 表格单元格文本过长")
        private String text;
    }
}
