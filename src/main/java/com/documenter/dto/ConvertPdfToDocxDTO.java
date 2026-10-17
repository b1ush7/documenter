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
}
