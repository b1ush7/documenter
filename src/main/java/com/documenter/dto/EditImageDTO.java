package com.documenter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** PNG/JPEG 的受控像素编辑及格式转换。 */
@Data
public class EditImageDTO {
    @Size(max = 2000, message = "编辑说明过长")
    private String instruction;

    @Pattern(regexp = "^(PNG|JPEG)$", message = "输出格式只能是 PNG 或 JPEG")
    private String outputFormat;

    @DecimalMin(value = "0.1", message = "JPEG 质量不能小于 0.1")
    @DecimalMax(value = "1.0", message = "JPEG 质量不能大于 1")
    private Double jpegQuality;

    @NotNull(message = "operations 不能为 null")
    @Size(max = 20, message = "单次最多执行 20 个图片操作")
    private List<@NotNull(message = "图片操作不能为空") @Valid Operation> operations = List.of();

    @Data
    public static class Operation {
        @NotNull(message = "操作类型不能为空")
        @Pattern(regexp = "^(CROP|RESIZE|ROTATE)$", message = "不支持的图片操作")
        private String type;
        @Min(value = 0, message = "x 不能为负数")
        private Integer x;
        @Min(value = 0, message = "y 不能为负数")
        private Integer y;
        @Min(value = 1, message = "宽度必须大于 0")
        private Integer width;
        @Min(value = 1, message = "高度必须大于 0")
        private Integer height;
        /** 顺时针旋转，只支持 90/180/270 度。 */
        private Integer degrees;
    }
}
