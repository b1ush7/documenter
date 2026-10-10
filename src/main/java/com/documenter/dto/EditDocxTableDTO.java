package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 对一个明确 DOCX 版本执行受控的表格编辑。 */
@Data
public class EditDocxTableDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @Valid
    @NotEmpty(message = "至少需要一个表格编辑操作")
    @Size(max = 100, message = "单次最多执行 100 个表格编辑操作")
    private List<Operation> operations;

    @Data
    public static class Operation {

        @NotNull(message = "操作类型不能为空")
        @Pattern(regexp = "^(SET_CELL_TEXT|INSERT_ROW_BEFORE|INSERT_ROW_AFTER|DELETE_ROW)$",
                message = "不支持的表格操作")
        private String type;

        /** SET_CELL_TEXT 指向 CELL，其余操作指向 ROW。 */
        @NotNull(message = "目标块 ID 不能为空")
        @Size(max = 512, message = "目标块 ID 过长")
        private String blockId;

        /** SET_CELL_TEXT 使用，允许空字符串清空单元格。 */
        @Size(max = 100000, message = "单元格文本过长")
        private String text;

        /** 插入行使用；元素数量必须与参照行列数一致。 */
        @Size(max = 100, message = "单行不能超过 100 个单元格")
        private List<@NotNull(message = "单元格文本不能为空")
                @Size(max = 100000, message = "单元格文本过长") String> values;
    }
}
