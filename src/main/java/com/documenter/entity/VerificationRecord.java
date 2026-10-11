package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 验证码记录（TODO 3.1）。
 *
 * <p>只保存验证码摘要，不保存明文。字段与 V1__baseline.sql 的 verification_record 对齐。
 */
@TableName("verification_record")
@Data
public class VerificationRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String identifier;
    private String scene;
    private String codeHash;
    private Integer sendCount;
    private Integer verifyAttempts;
    private Integer maxAttempts;
    private String status;
    private String requestIp;
    private String requestId;
    private LocalDateTime createTime;
    private LocalDateTime sentTime;
    private LocalDateTime expireTime;
    private LocalDateTime consumeTime;
}
