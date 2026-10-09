package com.documenter.vo;

import lombok.Data;

import java.util.List;

/**
 * 任务详情出参（TODO 3.4）：任务本体 + 步骤列表。
 */
@Data
public class TaskDetailVO {

    private TaskVO task;
    private List<TaskStepVO> steps;

    public static TaskDetailVO of(TaskVO task, List<TaskStepVO> steps) {
        TaskDetailVO detail = new TaskDetailVO();
        detail.setTask(task);
        detail.setSteps(steps);
        return detail;
    }
}
