package com.documenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审计日志（TODO 3.2「记录管理操作」、TODO 3.4「补齐请求 ID」）。
 *
 * <p>{@code detail} 只允许写脱敏后的业务信息，<b>禁止</b>写入密码、密钥、
 * 模型 Key 或文件正文。这一条在执行者侧（AuditService）和调用方都要遵守。
 */
@TableName("audit_log")
@Data
public class AuditLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 操作者用户名快照：用户改名或删除后仍可追溯。 */
    private String username;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private String result;
    private String requestIp;
    private String requestId;
    private LocalDateTime createTime;
}
