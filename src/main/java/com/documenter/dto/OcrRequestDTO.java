package com.documenter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
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

    /** true 时在 OCR 前转灰度并按 1%/99% 分位拉伸对比度，不改变坐标和尺寸。 */
    private boolean enhance;

    /** 在所选区域内顺时针旋转 0/90/180/270 度；结果坐标仍相对原图。 */
    private int rotationDegrees;

    /** 中值去噪，适合轻度散点噪声。 */
    private boolean denoise;

    /** 轻度反差锐化，不保证恢复严重失焦文字。 */
    private boolean sharpen;

    /** Gamma 提亮低照度图片。 */
    private boolean lowLight;

    /** 相对于实际渲染页面或原始图片的像素矩形；为空时识别整页。 */
    @Valid
    private Region region;

    @Data
    public static class Region {

        @Min(value = 0, message = "OCR 区域横坐标不能小于 0")
        private int x;

        @Min(value = 0, message = "OCR 区域纵坐标不能小于 0")
        private int y;

        @Min(value = 1, message = "OCR 区域宽度必须大于 0")
        private int width;

        @Min(value = 1, message = "OCR 区域高度必须大于 0")
        private int height;
    }
}
