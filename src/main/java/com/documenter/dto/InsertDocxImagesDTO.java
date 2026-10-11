package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 将已有 PNG/JPEG 文件的明确版本以内嵌方式追加到 DOCX 段落。 */
@Data
public class InsertDocxImagesDTO {

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @Valid
    @NotEmpty(message = "至少需要一个图片插入操作")
    @Size(max = 20, message = "单次最多插入 20 张图片")
    private List<Operation> operations;

    @Data
    public static class Operation {

        @NotNull(message = "目标块 ID 不能为空")
        @Pattern(regexp = "^body(?:/t\\d+/r\\d+/c\\d+)*/p\\d+$",
                message = "目标块 ID 必须指向正文或表格单元格中的段落")
        private String blockId;

        @NotNull(message = "图片文件 ID 不能为空")
        @Positive(message = "图片文件 ID 必须为正数")
        private Long imageFileId;

        @NotNull(message = "图片版本号不能为空")
        @Positive(message = "图片版本号必须为正数")
        private Integer imageVersionNo;

        @NotNull(message = "图片宽度不能为空")
        @Min(value = 1, message = "图片宽度不能小于 1 像素")
        @Max(value = 10000, message = "图片宽度不能超过 10000 像素")
        private Integer widthPixels;

        @NotNull(message = "图片高度不能为空")
        @Min(value = 1, message = "图片高度不能小于 1 像素")
        @Max(value = 10000, message = "图片高度不能超过 10000 像素")
        private Integer heightPixels;
    }
}
