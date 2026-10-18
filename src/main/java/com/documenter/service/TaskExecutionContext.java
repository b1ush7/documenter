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

    /**
     * 当前执行尝试的序号，从 1 开始（TODO 3.4）。
     *
     * <p>与 {@link #taskId()} 一起构成「任务级版本幂等键」。
     * 处理器调用 {@code versionWriter.appendVersion(..., taskId, attemptNo)} 后，
     * 同一尝试内部的失败重入不会重复产出内容相同的版本，
     * 同时也不会重复分配版本号。
     * 自动重试沿用同一序号；用户显式重试会创建新的任务 ID。
     *
     * <p>提供默认实现是为了让既有的处理器实现与测试替身无需立即实现本方法。
     * 返回 0 表示「无尝试上下文」，此时版本写入退化为非幂等的普通追加——
     * 因此真实处理器应当<b>优先使用本方法返回的值</b>，而不是自己编造序号。
     */
    default int attemptNo() {
        return 0;
    }

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

    /**
     * 记录本次任务产出的明确版本号。
     *
     * <p>真实处理器在追加版本后必须调用本方法，否则任务虽然成功，前端仍无法通过
     * outputVersion 定位结果。任务状态仍由执行引擎统一收尾。
     */
    default void completeWithVersion(Integer versionNo) {
        if (versionNo == null || versionNo < 1) {
            throw new IllegalArgumentException("任务输出版本号必须为正数");
        }
    }
}
