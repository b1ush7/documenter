package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 控制指定 DOCX 分节是否链接到上一节的页眉或页脚。 */
@Data
public class UpdateDocxHeaderFooterLinkDTO {

    @NotNull(message = "分节序号不能为空")
    @Min(value = 1, message = "分节序号必须大于 0")
    @Max(value = 1000, message = "分节序号过大")
    private Integer sectionNo;

    @NotBlank(message = "页眉页脚位置不能为空")
    @Pattern(regexp = "^(HEADER|FOOTER)$", message = "位置只支持 HEADER 或 FOOTER")
    private String location;

    @NotBlank(message = "页眉页脚类型不能为空")
    @Pattern(regexp = "^(DEFAULT|FIRST|EVEN)$", message = "类型只支持 DEFAULT、FIRST、EVEN")
    private String type;

    @NotNull(message = "链接状态不能为空")
    private Boolean linkedToPrevious;

    /** 取消链接时必填；重新链接时必须为空。 */
    @Size(max = 100000, message = "页眉页脚文字过长")
    private String text;

    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;
}
