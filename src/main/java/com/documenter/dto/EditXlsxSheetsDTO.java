package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 新增、重命名或删除 XLSX 工作表。 */
@Data
public class EditXlsxSheetsDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @NotEmpty(message = "至少需要一个工作表操作")
    @Size(max = 50, message = "单次最多执行 50 个工作表操作")
    private List<@NotNull(message = "工作表操作不能为空") @Valid Operation> operations;

    @Data
    public static class Operation {
        @NotNull(message = "操作类型不能为空")
        @Pattern(regexp = "^(CREATE|RENAME|DELETE)$", message = "不支持的工作表操作")
        private String type;

        @NotNull(message = "工作表名称不能为空")
        @Size(min = 1, max = 31, message = "工作表名称长度必须在 1 到 31 之间")
        private String sheetName;

        @Size(min = 1, max = 31, message = "新工作表名称长度必须在 1 到 31 之间")
        private String newName;

        /** CREATE 使用，从 1 开始；不传时追加到末尾。 */
        @Min(value = 1, message = "工作表位置必须大于 0")
        private Integer position;
    }
}
