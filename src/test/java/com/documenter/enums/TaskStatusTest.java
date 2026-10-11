package com.documenter.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务状态机测试（TODO 3.4：PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED）。
 *
 * <p>状态机是纯逻辑，不需要数据库即可完整验证。它防的是这一类问题：
 * 一个已经成功的任务被后续的超时回调或重试逻辑覆盖成失败，
 * 导致用户看到的结果与实际产物不一致。
 */
class TaskStatusTest {

    @Test
    @DisplayName("PENDING 只能进入 RUNNING 或被取消")
    void pendingTransitions() {
        assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.RUNNING));
        assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.CANCELLED));

        // 不允许跳过 RUNNING 直接成功，否则无法区分「没执行」和「执行成功」
        assertFalse(TaskStatus.PENDING.canTransitionTo(TaskStatus.SUCCEEDED));
        assertFalse(TaskStatus.PENDING.canTransitionTo(TaskStatus.FAILED));
        // 自身流转无意义
        assertFalse(TaskStatus.PENDING.canTransitionTo(TaskStatus.PENDING));
    }

    @Test
    @DisplayName("RUNNING 可以成功、失败或被取消")
    void runningTransitions() {
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.SUCCEEDED));
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.FAILED));
        assertTrue(TaskStatus.RUNNING.canTransitionTo(TaskStatus.CANCELLED));

        // 不允许回退到 PENDING，否则会与已在执行的任务产生竞态
        assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.PENDING));
        assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.RUNNING));
    }

    @Test
    @DisplayName("终态不可再流转：成功的任务不会被后续回调改成失败")
    void terminalStatesAreImmutable() {
        for (TaskStatus terminal : new TaskStatus[]{
                TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED}) {
            assertTrue(terminal.isTerminal(), terminal + " 应为终态");
            for (TaskStatus target : TaskStatus.values()) {
                assertFalse(terminal.canTransitionTo(target),
                        terminal + " 不应能流转到 " + target);
            }
        }
    }

    @Test
    @DisplayName("非终态判定正确")
    void nonTerminalStates() {
        assertFalse(TaskStatus.PENDING.isTerminal());
        assertFalse(TaskStatus.RUNNING.isTerminal());
    }

    @Test
    @DisplayName("空目标被安全拒绝")
    void nullTargetRejected() {
        for (TaskStatus status : TaskStatus.values()) {
            assertFalse(status.canTransitionTo(null));
        }
    }

    @Test
    @DisplayName("枚举取值与数据库 CHECK 约束保持一致")
    void enumMatchesDatabaseConstraint() {
        // processing_task.status 的 CHECK 约束为这五个值，
        // 枚举增减时必须同步修改 V1__baseline.sql，否则写入会被数据库拒绝
        java.util.List<String> names = java.util.Arrays.stream(TaskStatus.values())
                .map(Enum::name)
                .toList();
        assertTrue(names.containsAll(
                java.util.List.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")));
        assertTrue(names.size() == 5,
                "状态数量变化时必须同步更新数据库 CHECK 约束与迁移脚本");
    }
}
