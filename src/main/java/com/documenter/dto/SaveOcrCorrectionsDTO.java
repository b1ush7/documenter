package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 保存一次 OCR 结果的逐页人工校正文本。 */
@Data
public class SaveOcrCorrectionsDTO {

    @NotBlank(message = "OCR 结果指纹不能为空")
    @Pattern(regexp = "^[a-f0-9]{64}$", message = "OCR 结果指纹格式错误")
    private String fingerprint;

    @Valid
    @NotEmpty(message = "至少需要一页校正文本")
    @Size(max = 100, message = "单次最多保存 100 页校正文本")
    private List<PageCorrection> pages;

    @Data
    public static class PageCorrection {

        @NotNull(message = "OCR 页码不能为空")
        @Min(value = 1, message = "OCR 页码必须大于 0")
        private Integer pageNo;

        /** 允许空字符串表示用户确认整页无文字。 */
        @NotNull(message = "校正文本不能为空")
        @Size(max = 2000000, message = "单页校正文本过长")
        private String text;
    }
}
