package com.documenter.vo;

import lombok.Data;

import java.util.List;

/** 管理员任务详情：带所有者的任务信息与执行步骤。 */
@Data
public class AdminTaskDetailVO {

    private AdminTaskVO task;
    private List<TaskStepVO> steps;

    public static AdminTaskDetailVO of(AdminTaskVO task, List<TaskStepVO> steps) {
        AdminTaskDetailVO detail = new AdminTaskDetailVO();
        detail.setTask(task);
        detail.setSteps(steps);
        return detail;
    }
}
