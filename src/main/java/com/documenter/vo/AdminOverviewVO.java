package com.documenter.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 管理员全局使用量与任务状态概览。 */
@Data
public class AdminOverviewVO {

    private Long totalUsers;
    private Long enabledUsers;
    private Long disabledUsers;
    private Long adminUsers;
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
