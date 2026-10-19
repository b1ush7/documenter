package com.documenter.component;

import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskRecoveryRunnerTest {
    @Test
    void failedTaskRecoveryRollsBackStepRecoveryAndNeverRequeues() {
        var tasks = mock(ProcessingTaskMapper.class);
        var steps = mock(TaskStepMapper.class);
        var engine = mock(TaskExecutionEngine.class);
        var manager = mock(PlatformTransactionManager.class);
        var transaction = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(tasks.failInterruptedTasks()).thenThrow(new DataAccessResourceFailureException("controlled outage"));
        var properties = new TaskExecutorProperties();
        properties.setRequeuePendingOnStartup(true);
        var runner = new TaskRecoveryRunner(tasks, properties, engine, steps, manager);

        assertThrows(DataAccessResourceFailureException.class, () -> runner.run(null));

        var order = inOrder(manager, steps, tasks);
        order.verify(manager).getTransaction(any());
        order.verify(steps).failInterruptedSteps();
        order.verify(tasks).failInterruptedTasks();
        order.verify(manager).rollback(transaction);
        verify(manager, never()).commit(any());
        verify(tasks, never()).selectPendingTasks();
        verifyNoInteractions(engine);
    }

    @Test
    void disabledRecoveryLeavesTasksAndStepsUntouched() {
        var tasks = mock(ProcessingTaskMapper.class);
        var steps = mock(TaskStepMapper.class);
        var engine = mock(TaskExecutionEngine.class);
        var manager = mock(PlatformTransactionManager.class);
        var properties = new TaskExecutorProperties();
        properties.setRecoverOnStartup(false);
        new TaskRecoveryRunner(tasks, properties, engine, steps, manager).run(null);
        verifyNoInteractions(tasks, steps, engine, manager);
    }
}
