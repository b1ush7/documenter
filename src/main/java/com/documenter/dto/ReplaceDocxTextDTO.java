package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 对一个明确 DOCX 版本执行受控的段落文本替换。 */
@Data
public class ReplaceDocxTextDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @Valid
    @NotEmpty(message = "至少需要一个段落替换操作")
    @Size(max = 100, message = "单次最多替换 100 个段落")
    private List<Operation> operations;

    @Data
    public static class Operation {

        @NotNull(message = "块 ID 不能为空")
        @Pattern(regexp = "^body(?:/t\\d+/r\\d+/c\\d+)*/p\\d+$",
                message = "块 ID 必须指向正文或表格单元格中的段落")
        private String blockId;

        /** 允许空字符串，用于清空段落；不允许 null。 */
        @NotNull(message = "替换文本不能为空")
        @Size(max = 100000, message = "单个段落文本过长")
        private String text;
    }
}
