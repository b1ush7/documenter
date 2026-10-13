package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建公共模板的元数据部分（TODO 3.2）。
 *
 * <p>模板文件通过 multipart 的 {@code file} 字段单独提交，
 * 因此本类只承载元数据，便于在 Service 层独立校验。
 */
@Data
public class SaveDocTemplateDTO {

    @NotBlank(message = "模板标识不能为空")
    @Size(max = 64, message = "模板标识不能超过 64 个字符")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "模板标识只能包含字母、数字、下划线和连字符")
    private String code;

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 128, message = "模板名称不能超过 128 个字符")
    private String name;

    @Size(max = 32, message = "分类不能超过 32 个字符")
    private String category;

    @Size(max = 500, message = "用途说明不能超过 500 个字符")
    private String description;

    /**
     * 变量定义 JSON 数组字符串。
     *
     * <p>这里只做长度限制，结构由 {@code TemplateVariables} 校验：
     * 非法结构会在生成文档时才暴露，因此必须在存入前拦下。
     */
    @Size(max = 20000, message = "变量定义过长")
    private String variables;
}
