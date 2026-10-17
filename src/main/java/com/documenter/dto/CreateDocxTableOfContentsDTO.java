package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 在 DOCX 中插入或刷新基于标题/大纲级别的 TOC 域。 */
@Data
public class CreateDocxTableOfContentsDTO {

    @Pattern(regexp = "^(START|END)$", message = "目录位置只支持 START 或 END")
    private String position = "END";

    @Min(value = 1, message = "目录标题级别必须大于 0")
    @Max(value = 9, message = "目录标题级别不能超过 9")
    private Integer maxLevel = 3;

    private Boolean hyperlinks = Boolean.TRUE;

    @Size(max = 100, message = "目录标题过长")
    private String title = "目录";

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;
}
