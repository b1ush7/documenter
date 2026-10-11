package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 为 XLSX 单元格或矩形区域设置基础格式。 */
@Data
public class FormatXlsxCellsDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @NotEmpty(message = "至少需要一个格式操作")
    @Size(max = 100, message = "单次最多执行 100 个格式操作")
    private List<@NotNull(message = "格式操作不能为空") @Valid Operation> operations;

    @Data
    public static class Operation {
        @NotNull(message = "工作表名称不能为空")
        @Size(min = 1, max = 31, message = "工作表名称长度必须在 1 到 31 之间")
        private String sheetName;

        @NotNull(message = "格式区域不能为空")
        @Pattern(regexp = "^\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6}(?::\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6})?$",
                message = "格式区域必须使用 A1 或 A1:C3 格式")
        private String range;

        @Size(max = 100, message = "字体名称过长")
        private String fontName;

        @DecimalMin(value = "1.0", message = "字体大小不能小于 1")
        @DecimalMax(value = "409.0", message = "字体大小不能大于 409")
        private Double fontSize;

        private Boolean bold;
        private Boolean italic;

        @Pattern(regexp = "^#?[0-9A-Fa-f]{6}$", message = "字体颜色必须是 6 位十六进制 RGB")
        private String fontColor;

        @Pattern(regexp = "^(GENERAL|LEFT|CENTER|RIGHT|FILL|JUSTIFY|CENTER_SELECTION|DISTRIBUTED)$",
                message = "不支持的水平对齐方式")
        private String horizontalAlignment;

        @Pattern(regexp = "^(TOP|CENTER|BOTTOM|JUSTIFY|DISTRIBUTED)$",
                message = "不支持的垂直对齐方式")
        private String verticalAlignment;

        private Boolean wrapText;

        @Pattern(regexp = "^#?[0-9A-Fa-f]{6}$", message = "填充颜色必须是 6 位十六进制 RGB")
        private String fillColor;

        @Size(min = 1, max = 100, message = "数字格式长度必须在 1 到 100 之间")
        private String numberFormat;

        @Pattern(regexp = "^(NONE|THIN|MEDIUM|DASHED|DOTTED|THICK|DOUBLE)$",
                message = "不支持的边框样式")
        private String borderStyle;
    }
}
