package com.documenter.service.impl;

import com.documenter.entity.ProcessingTask;
import com.documenter.entity.TaskStep;
import com.documenter.enums.TaskStepStatus;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskExecutionContextImplTest {

    private final ProcessingTaskMapper tasks = mock(ProcessingTaskMapper.class);
    private final TaskStepMapper steps = mock(TaskStepMapper.class);
    private TaskExecutionContextImpl context;

    @BeforeEach
    void setUp() {
        var task = new ProcessingTask();
        task.setId(10L);
        when(steps.selectMaxStepNo(10L)).thenReturn(4);
        when(steps.insert(any(TaskStep.class))).thenAnswer(invocation -> {
            invocation.<TaskStep>getArgument(0).setId(20L);
            return 1;
        });
        context = new TaskExecutionContextImpl(tasks, steps, task, 2);
    }

    @Test
    void stepNumbersContinueAfterPersistedSteps() {
        assertEquals(5, context.nextStepNo());

        context.startStep("retry", null);

        verify(steps).insert(argThat((TaskStep step) -> step.getStepNo() == 5));
        assertEquals(6, context.nextStepNo());
    }

    @Test
    void timedOutAttemptCannotOverwriteFailedStepOrReportLateProgress() {
        context.startStep("work", null);
        context.failCurrentStep("timeout");

        context.finishStep(TaskStepStatus.SUCCEEDED, "late result", null);
        assertThrows(TaskExecutionContextImpl.TaskCancelledException.class, () -> context.reportProgress(100));
        assertThrows(TaskExecutionContextImpl.TaskCancelledException.class, () -> context.startStep("late step", null));

        verify(steps).updateById(argThat((TaskStep step) -> "FAILED".equals(step.getStatus())));
        verify(steps, times(1)).updateById(any(TaskStep.class));
        verify(tasks, times(1)).updateById(any(ProcessingTask.class));
        assertTrue(context.isCancelRequested());
    }

    @Test
    void cancelledAttemptCannotReportLateProgress() {
        context.startStep("work", null);
        context.skipCurrentStep("cancelled");

        assertThrows(TaskExecutionContextImpl.TaskCancelledException.class, () -> context.reportProgress(100));
        context.finishStep(TaskStepStatus.SUCCEEDED, null, null);

        verify(steps).updateById(argThat((TaskStep step) -> "SKIPPED".equals(step.getStatus())));
        verify(steps, times(1)).updateById(any(TaskStep.class));
    }

    @Test
    void interruptedWorkerObservesCancellationWithoutDatabaseRead() {
        Thread.currentThread().interrupt();
        try {
            assertTrue(context.isCancelRequested());
            assertThrows(TaskExecutionContextImpl.TaskCancelledException.class, context::assertNotCancelled);
            verifyNoInteractions(tasks);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void failedProgressWriteCanBeRetriedWithSameValue() {
        when(tasks.updateById(any(ProcessingTask.class)))
                .thenThrow(new IllegalStateException("temporary database failure")).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> context.reportProgress(40));
        context.reportProgress(40);
        context.reportProgress(40);

        verify(tasks, times(2)).updateById(argThat((ProcessingTask task) -> task.getProgress() == 40));
    }

    @Test
    void outputVersionIsWrittenOnlyForRunningTask() {
        when(tasks.setOutputVersion(10L, 3)).thenReturn(1);

        context.completeWithVersion(3);

        verify(tasks).setOutputVersion(10L, 3);
    }

    @Test
    void outputVersionRejectsInvalidValueAndStoppedTask() {
        assertThrows(IllegalArgumentException.class, () -> context.completeWithVersion(0));
        context.failCurrentStep("failed");

        assertThrows(TaskExecutionContextImpl.TaskCancelledException.class,
                () -> context.completeWithVersion(2));
        verify(tasks, never()).setOutputVersion(anyLong(), anyInt());
    }
}
