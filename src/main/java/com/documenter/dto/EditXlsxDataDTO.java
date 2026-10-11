package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** XLSX 矩形区域排序及自动筛选设置。 */
@Data
public class EditXlsxDataDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @NotEmpty(message = "至少需要一个数据操作")
    @Size(max = 20, message = "单次最多执行 20 个数据操作")
    private List<@NotNull(message = "数据操作不能为空") @Valid Operation> operations;

    @Data
    public static class Operation {
        @NotNull(message = "操作类型不能为空")
        @Pattern(regexp = "^(SORT_RANGE|SET_AUTO_FILTER|CLEAR_AUTO_FILTER)$",
                message = "不支持的数据操作")
        private String type;

        @NotNull(message = "工作表名称不能为空")
        @Size(min = 1, max = 31, message = "工作表名称长度必须在 1 到 31 之间")
        private String sheetName;

        @Pattern(regexp = "^\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6}:\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6}$",
                message = "区域必须使用 A1:C3 格式")
        private String range;

        /** SORT_RANGE 使用，绝对列名，例如 B。 */
        @Pattern(regexp = "^[A-Za-z]{1,3}$", message = "排序列必须使用 A、B、AA 等列名")
        private String keyColumn;

        @Pattern(regexp = "^(ASC|DESC)$", message = "排序方向只能是 ASC 或 DESC")
        private String direction;

        /** SORT_RANGE 使用；true 表示区域首行不参与排序。 */
        private Boolean hasHeader;
    }
}
