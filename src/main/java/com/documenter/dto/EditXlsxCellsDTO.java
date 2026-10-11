package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 对一个明确 XLSX 版本执行受控的单元格编辑。 */
@Data
public class EditXlsxCellsDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @Valid
    @NotEmpty(message = "至少需要一个单元格编辑操作")
    @Size(max = 500, message = "单次最多执行 500 个单元格编辑操作")
    private List<Operation> operations;

    @Data
    public static class Operation {

        @NotNull(message = "操作类型不能为空")
        @Pattern(regexp = "^(SET_VALUE|SET_FORMULA|CLEAR)$", message = "不支持的单元格操作")
        private String type;

        @NotNull(message = "工作表名称不能为空")
        @Size(min = 1, max = 31, message = "工作表名称长度必须在 1 到 31 之间")
        private String sheetName;

        @NotNull(message = "单元格地址不能为空")
        @Pattern(regexp = "^\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6}$", message = "单元格地址必须使用 A1 格式")
        private String cell;

        /** SET_VALUE 使用，明确区分普通文本与公式。 */
        @Pattern(regexp = "^(STRING|NUMBER|BOOLEAN|DATE)$", message = "不支持的单元格值类型")
        private String valueType;

        /** SET_VALUE 使用；字符串允许为空，数字、布尔和日期不能为空。 */
        @Size(max = 32767, message = "单元格值过长")
        private String value;

        /** SET_FORMULA 使用；可带或不带开头的等号。 */
        @Size(max = 8192, message = "公式过长")
        private String formula;
    }
}
