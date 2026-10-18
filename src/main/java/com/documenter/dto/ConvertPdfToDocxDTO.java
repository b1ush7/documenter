package com.documenter.dto;

import com.documenter.enums.PdfConversionMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 将一个明确 PDF 版本转换为可编辑 DOCX 新版本。 */
@Data
public class ConvertPdfToDocxDTO {

    @NotNull(message = "PDF 转换取向不能为空")
    private PdfConversionMode mode;

    @Size(max = 500, message = "转换说明过长")
    private String instruction;

    /**
     * 例如 eng 或 chi_sim；为空时保持原有行为，遇到无原生文字页面返回 422。
     */
    @Size(max = 32, message = "OCR 语言标识过长")
    private String ocrLanguage;
}
