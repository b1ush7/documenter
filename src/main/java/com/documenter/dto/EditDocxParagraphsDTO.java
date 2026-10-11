package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 对一个明确 DOCX 版本执行段落插入或删除。 */
@Data
public class EditDocxParagraphsDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @Valid
    @NotEmpty(message = "至少需要一个段落编辑操作")
    @Size(max = 100, message = "单次最多执行 100 个段落编辑操作")
    private List<Operation> operations;

    @Data
    public static class Operation {

        @NotNull(message = "操作类型不能为空")
        @Pattern(regexp = "^(INSERT_BEFORE|INSERT_AFTER|DELETE)$",
                message = "段落操作只支持 INSERT_BEFORE、INSERT_AFTER、DELETE")
        private String type;

        @NotNull(message = "目标块 ID 不能为空")
        @Pattern(regexp = "^body(?:/t\\d+/r\\d+/c\\d+)*/p\\d+$",
                message = "目标块 ID 必须指向正文或表格单元格中的段落")
        private String blockId;

        /** 插入操作必填；DELETE 操作必须不传。 */
        @Size(max = 100000, message = "单个段落文本过长")
        private String text;
    }
}
