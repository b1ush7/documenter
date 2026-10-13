package com.documenter.vo;

import com.documenter.entity.ProcessingTask;
import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;

/** 管理员任务出参；额外包含任务所属用户。 */
@Data
public class AdminTaskVO {

    private Long id;
    private Long userId;
    private Long fileId;
    private String taskType;
    private String status;
    private Integer progress;
    private String currentStep;
    private String instruction;
    private String errorCode;
    private String errorMessage;
    private Integer inputVersion;
    private Integer outputVersion;
    private Integer retryCount;
    private Integer maxRetry;
    private Boolean cancelRequested;
    private LocalDateTime createTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private Long elapsedMs;

    public static AdminTaskVO from(ProcessingTask task) {
        AdminTaskVO vo = new AdminTaskVO();
        vo.setId(task.getId());
        vo.setUserId(task.getUserId());
        vo.setFileId(task.getFileId());
        vo.setTaskType(task.getTaskType());
        vo.setStatus(task.getStatus());
        vo.setProgress(task.getProgress());
        vo.setCurrentStep(task.getCurrentStep());
        vo.setInstruction(task.getInstruction());
        vo.setErrorCode(task.getErrorCode());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setInputVersion(task.getInputVersion());
        vo.setOutputVersion(task.getOutputVersion());
        vo.setRetryCount(task.getRetryCount());
        vo.setMaxRetry(task.getMaxRetry());
        vo.setCancelRequested(task.getCancelRequested());
        vo.setCreateTime(task.getCreateTime());
        vo.setStartTime(task.getStartTime());
        vo.setFinishTime(task.getFinishTime());
        if (task.getStartTime() != null) {
            LocalDateTime end = task.getFinishTime() == null ? LocalDateTime.now() : task.getFinishTime();
            vo.setElapsedMs(Duration.between(task.getStartTime(), end).toMillis());
        }
        return vo;
    }
}
