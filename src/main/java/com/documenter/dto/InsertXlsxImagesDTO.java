package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/** 将当前用户图片的明确版本内嵌到 XLSX，以单元格左上角定位。 */
@Data
public class InsertXlsxImagesDTO {
    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @NotEmpty(message = "至少需要一个图片插入操作")
    @Size(max = 20, message = "单次最多插入 20 张图片")
    private List<@NotNull @Valid Operation> operations;

    @Data
    public static class Operation {
        @NotBlank(message = "工作表名称不能为空")
        private String sheetName;
        @NotBlank(message = "锚点单元格不能为空")
        private String cell;
        @NotNull @Positive
        private Long imageFileId;
        @NotNull @Positive
        private Integer imageVersionNo;
        @NotNull @Min(1) @Max(10000)
        private Integer widthPixels;
        @NotNull @Min(1) @Max(10000)
        private Integer heightPixels;
    }
}
