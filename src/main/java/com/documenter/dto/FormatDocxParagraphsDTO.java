package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 设置 DOCX 段落的基础标题或列表格式。 */
@Data
public class FormatDocxParagraphsDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @Valid
    @NotEmpty(message = "至少需要一个段落格式操作")
    @Size(max = 100, message = "单次最多执行 100 个段落格式操作")
    private List<Operation> operations;

    @Data
    public static class Operation {

        @NotNull(message = "格式类型不能为空")
        @Pattern(regexp = "^(SET_HEADING|SET_NORMAL|SET_BULLET|SET_NUMBERED|CLEAR_LIST)$",
                message = "不支持的段落格式类型")
        private String type;

        @NotNull(message = "目标块 ID 不能为空")
        @Pattern(regexp = "^body(?:/t\\d+/r\\d+/c\\d+)*/p\\d+$",
                message = "目标块 ID 必须指向正文或表格单元格中的段落")
        private String blockId;

        /** 仅 SET_HEADING 使用，支持 1-9 级标题。 */
        @Min(value = 1, message = "标题级别不能小于 1")
        @Max(value = 9, message = "标题级别不能大于 9")
        private Integer level;
    }
}
