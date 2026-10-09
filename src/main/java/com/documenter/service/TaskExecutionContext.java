package com.documenter.service;

import com.documenter.enums.TaskStepStatus;

/**
 * 任务执行上下文（TODO 3.4）。
 *
 * <p>处理器通过它上报进度、记录步骤，以及检查是否被请求取消。
 * 有了这层抽象，处理器不需要知道任务表结构，也不需要直接操作 Mapper。
 */
public interface TaskExecutionContext {

    Long taskId();

    /** 当前步骤序号，供处理器按序创建步骤记录。 */
    int nextStepNo();

    /** 开启一个步骤，返回步骤名以便配对结束。 */
    void startStep(String stepName, String detail);

    /** 结束当前步骤。 */
    void finishStep(TaskStepStatus status, String detail, String errorMessage);

    /** 更新整体进度（0-100）。 */
    void reportProgress(int progress);

    /**
     * 是否已被请求取消。
     *
     * <p>处理器应在耗时操作的边界调用它并主动返回，避免用户取消后仍长时间占用线程。
     */
    boolean isCancelRequested();

    /** 若已请求取消则抛出取消异常，用于在深层调用中快速退出。 */
    void assertNotCancelled();
}
