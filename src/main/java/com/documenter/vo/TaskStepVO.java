package com.documenter.vo;

import com.documenter.entity.TaskStep;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务步骤出参（TODO 3.4）。
 */
@Data
public class TaskStepVO {

    private Integer stepNo;
    private String stepName;
    private String status;
    private String detail;
    private String errorMessage;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private Long durationMs;

    public static TaskStepVO from(TaskStep step) {
        TaskStepVO vo = new TaskStepVO();
        vo.setStepNo(step.getStepNo());
        vo.setStepName(step.getStepName());
        vo.setStatus(step.getStatus());
        vo.setDetail(step.getDetail());
        vo.setErrorMessage(step.getErrorMessage());
        vo.setStartTime(step.getStartTime());
        vo.setFinishTime(step.getFinishTime());
        vo.setDurationMs(step.getDurationMs());
        return vo;
    }
}
