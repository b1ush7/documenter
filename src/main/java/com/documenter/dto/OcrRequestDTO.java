package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 对明确 PDF 或图片版本执行 OCR。 */
@Data
public class OcrRequestDTO {

    /** 例如 eng、chi_sim、eng+chi_sim；为空时使用部署默认值。 */
    @Size(max = 32, message = "OCR 语言标识过长")
    private String language;

    /** PDF 页码；为空时识别全部页，图片只允许第 1 页。 */
    @Size(max = 50, message = "单次最多选择 50 页")
    private List<@Min(value = 1, message = "页码必须大于 0")
            @Max(value = 1000, message = "页码过大") Integer> pages;

    /** PDF 渲染 DPI；图片直接使用原始像素。 */
    @Min(value = 100, message = "OCR DPI 不能小于 100")
    @Max(value = 300, message = "OCR DPI 不能大于 300")
    private Integer dpi;
}
