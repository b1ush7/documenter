package com.documenter.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 使用已保存的人工校正 OCR 内容发起只读 AI 整理。 */
@Data
public class OcrOrganizeRequestDTO {

    @Size(max = 500, message = "AI 整理要求过长")
    private String instruction;
}
