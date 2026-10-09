package com.documenter.vo;

import com.documenter.entity.ProcessingTask;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务出参（TODO 3.4）。
 *
 * <p>包含前端轮询所需的状态与进度字段；错误信息已由服务端脱敏，
 * 不含模型密钥与堆栈。
 */
@Data
public class TaskVO {

    private Long id;
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
    /** 已耗时毫秒；未结束时按当前时间计算，便于前端展示。 */
    private Long elapsedMs;

    public static TaskVO from(ProcessingTask task) {
        TaskVO vo = new TaskVO();
        vo.setId(task.getId());
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
        vo.setElapsedMs(elapsed(task));
        return vo;
    }

    /** 计算耗时：已完成按 start→finish，未完成按 start→now。 */
    private static Long elapsed(ProcessingTask task) {
        if (task.getStartTime() == null) {
            return null;
        }
        LocalDateTime end = task.getFinishTime() != null ? task.getFinishTime() : LocalDateTime.now();
        return java.time.Duration.between(task.getStartTime(), end).toMillis();
    }
}
