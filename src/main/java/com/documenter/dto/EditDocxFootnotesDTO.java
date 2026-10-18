package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 对正文脚注执行新增、修改或删除，整批只创建一个版本。 */
@Data
public class EditDocxFootnotesDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @NotEmpty(message = "至少需要一个脚注操作")
    @Size(max = 50, message = "单次最多执行 50 个脚注操作")
    private List<@NotNull(message = "脚注操作不能为空") @Valid Operation> operations;

    @Data
    public static class Operation {

        @NotNull(message = "操作类型不能为空")
        @Pattern(regexp = "^(ADD|UPDATE|DELETE)$", message = "脚注操作只支持 ADD、UPDATE、DELETE")
        private String type;

        /** ADD 必填，必须是正文或表格单元格中的段落。 */
        @Pattern(regexp = "^$|^(?:body)(?:/t\\d+/r\\d+/c\\d+)*/p\\d+$",
                message = "脚注引用目标必须是正文段落")
        private String targetBlockId;

        /** UPDATE / DELETE 必填。 */
        private Integer footnoteId;

        /** ADD / UPDATE 必填，DELETE 不传。 */
        @Size(max = 100000, message = "脚注文字过长")
        private String text;
    }
}
