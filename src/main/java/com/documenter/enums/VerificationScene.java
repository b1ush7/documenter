package com.documenter.enums;

/**
 * 验证码用途（TODO 3.1）。
 *
 * <p>与 verification_record.scene 的取值一一对应，数据库侧有同名 CHECK 约束，
 * 增减取值时必须同步修改迁移脚本。
 */
public enum VerificationScene {

    /** 注册：校验手机号归属。 */
    REGISTER,

    /** 验证码登录：不常设密码或忘记密码时的登录方式。 */
    LOGIN,

    /** 找回密码：验证通过后允许重设密码。 */
    RESET_PASSWORD;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
