package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 文件重命名请求（TODO 3.3「重命名」）。
 *
 * <p>只改展示名，不改存储标识，因此不会移动磁盘上的文件。
 */
@Data
public class RenameFileDTO {

    @NotBlank(message = "新文件名不能为空")
    @Size(max = 200, message = "文件名过长")
    private String displayName;
}
