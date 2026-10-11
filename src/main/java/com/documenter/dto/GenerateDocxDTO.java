package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 使用受限结构块在本地生成新的 DOCX 文件。 */
@Data
public class GenerateDocxDTO {

    @Size(max = 200, message = "文件名过长")
    private String fileName;

    @Size(max = 1000, message = "文档标题过长")
    private String title;

    @Size(max = 2000, message = "生成说明过长")
    private String instruction;

    @Valid
    @Size(max = 200, message = "单次最多生成 200 个结构块")
    private List<Block> blocks;

    @Data
    public static class Block {

        @NotNull(message = "结构块类型不能为空")
        @Pattern(regexp = "^(HEADING|PARAGRAPH|BULLET_LIST|NUMBERED_LIST|TABLE|IMAGE)$",
                message = "不支持的 DOCX 结构块类型")
        private String type;

        @Size(max = 100000, message = "结构块文本过长")
        private String text;

        @Min(value = 1, message = "标题级别不能小于 1")
        @Max(value = 9, message = "标题级别不能大于 9")
        private Integer level;

        @Size(max = 100, message = "单个列表最多包含 100 项")
        private List<@Size(max = 100000, message = "列表项文本过长") String> items;

        @Size(max = 100, message = "单个表格最多包含 100 行")
        private List<@Size(min = 1, max = 20, message = "表格每行应包含 1 到 20 个单元格")
                List<@Size(max = 100000, message = "单元格文本过长") String>> rows;

        @Positive(message = "图片文件 ID 必须为正数")
        private Long imageFileId;

        @Positive(message = "图片版本号必须为正数")
        private Integer imageVersionNo;

        @Min(value = 1, message = "图片宽度不能小于 1 像素")
        @Max(value = 10000, message = "图片宽度不能超过 10000 像素")
        private Integer widthPixels;

        @Min(value = 1, message = "图片高度不能小于 1 像素")
        @Max(value = 10000, message = "图片高度不能超过 10000 像素")
        private Integer heightPixels;
    }
}
