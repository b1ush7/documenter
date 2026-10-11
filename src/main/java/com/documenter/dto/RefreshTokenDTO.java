package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新 Token 请求（TODO 3.1）。
 *
 * <p>按已确定的方案，Refresh Token 由前端通过 JSON 传递并保存在 localStorage，
 * 不作为 HttpOnly Cookie，因此无需 CSRF 配置。
 */
@Data
public class RefreshTokenDTO {

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
