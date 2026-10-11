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

/** 对一个明确 XLSX 版本执行行列插入或删除。 */
@Data
public class EditXlsxStructureDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @Valid
    @NotEmpty(message = "至少需要一个结构编辑操作")
    @Size(max = 100, message = "单次最多执行 100 个结构编辑操作")
    private List<@NotNull(message = "结构编辑操作不能为空") @Valid Operation> operations;

    @Data
    public static class Operation {

        @NotNull(message = "操作类型不能为空")
        @Pattern(regexp = "^(INSERT_ROWS|DELETE_ROWS|INSERT_COLUMNS|DELETE_COLUMNS)$",
                message = "不支持的 XLSX 结构操作")
        private String type;

        @NotNull(message = "工作表名称不能为空")
        @Size(min = 1, max = 31, message = "工作表名称长度必须在 1 到 31 之间")
        private String sheetName;

        /** 从 1 开始；行操作表示行号，列操作中 1 表示 A 列。 */
        @NotNull(message = "起始位置不能为空")
        @Min(value = 1, message = "起始位置必须大于 0")
        private Integer start;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量必须大于 0")
        @Max(value = 1000, message = "单次操作数量不能超过 1000")
        private Integer count;
    }
}
