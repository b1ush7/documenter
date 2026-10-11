package com.documenter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求（TODO 3.1）。
 *
 * <p>必须验证原密码：如果只凭已登录的 Access Token 就能改密，
 * 那么任何一个被窃取的 Token 都能直接夺取账号，风险远高于多输一次密码。
 *
 * <p>改密成功后服务端会撤销该用户的<b>全部</b>会话（包括当前会话），
 * 强制所有设备重新登录。这是刻意的：如果只保留当前会话，
 * 攻击者持有的旧会话仍可能存活到 Access Token 过期为止。
 */
@Data
public class ChangePasswordDTO {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "新密码长度必须为 8-64 位")
    private String newPassword;
}
