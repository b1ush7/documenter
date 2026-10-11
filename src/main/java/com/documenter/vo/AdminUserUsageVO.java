package com.documenter.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 管理员查看的单用户账号与资源使用量。 */
@Data
public class AdminUserUsageVO {

    private Long userId;
    private String username;
    private String role;
    private String status;
    private BigDecimal balance;
    private Long activeFiles;
    private Long deletedFiles;
    private Long storageBytes;
    private Long totalTasks;
    private Long pendingTasks;
    private Long runningTasks;
    private Long succeededTasks;
    private Long failedTasks;
    private Long cancelledTasks;
    /** FAILED / (SUCCEEDED + FAILED)，范围 0～1，保留四位小数。 */
    private BigDecimal taskFailureRate;
}
