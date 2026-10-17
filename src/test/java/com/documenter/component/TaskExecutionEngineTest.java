package com.documenter.component;

import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.entity.ProcessingTask;
import com.documenter.entity.TaskStep;
import com.documenter.enums.TaskType;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import com.documenter.service.TaskHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskExecutionEngineTest {

    private final ProcessingTaskMapper tasks = mock(ProcessingTaskMapper.class);
    private final TaskStepMapper steps = mock(TaskStepMapper.class);
    private final ThreadPoolTaskExecutor workers = mock(ThreadPoolTaskExecutor.class);
    private final ThreadPoolTaskExecutor coordinators = mock(ThreadPoolTaskExecutor.class);
    private final TaskHandler handler = mock(TaskHandler.class);
    private final ProcessingTask task = new ProcessingTask();
    private final List<TaskStep> insertedSteps = new ArrayList<>();
    private TaskExecutionEngine engine;

    @BeforeEach
    void setUp() {
        task.setId(10L);
        task.setUserId(7L);
        task.setTaskType("OCR");
        task.setStatus("PENDING");
        task.setProgress(35);
        task.setMaxRetry(2);
        task.setTimeoutSeconds(0);
        when(tasks.selectById(10L)).thenReturn(task);
        when(tasks.markRunning(eq(10L), anyString())).thenReturn(1);
        when(tasks.finish(anyLong(), anyString(), anyInt(), any(), any(), anyList())).thenReturn(1);
        when(handler.supports(TaskType.OCR)).thenReturn(true);
        when(handler.name()).thenReturn("test handler");
        when(steps.selectMaxStepNo(10L)).thenAnswer(invocation ->
                insertedSteps.stream().mapToInt(TaskStep::getStepNo).max().orElse(0));
        when(steps.insert(any(TaskStep.class))).thenAnswer(invocation -> {
            TaskStep step = invocation.getArgument(0);
            if (insertedSteps.stream().anyMatch(s -> s.getStepNo().equals(step.getStepNo()))) {
                throw new DuplicateKeyException("uk_task_step_no");
            }
            step.setId((long) insertedSteps.size() + 1);
            insertedSteps.add(step);
            return 1;
        });
        engine = new TaskExecutionEngine(tasks, steps, new TaskHandlerRegistry(List.of(handler)),
                new TaskExecutorProperties(), coordinators, workers);
    }

    @Test
    void pendingLegacyImageTaskFailsWithoutStartingAnyHandlerOrRetry() {
        task.setTaskType("IMAGE_GEN");
        when(handler.supports(TaskType.IMAGE_GEN)).thenReturn(true);
        clearInvocations(handler);
        engine.execute(10L);
        verify(tasks).finish(eq(10L), eq("FAILED"), eq(35), eq("TASK_TYPE_DISABLED"), anyString(), anyList());
        verifyNoInteractions(workers, steps, handler);
        verify(tasks, never()).updateById(any(ProcessingTask.class));
    }

    @Test
    void timeoutCancelsWorkerAndIsNotRetried() throws Exception {
        Future<?> future = mock(Future.class);
        doReturn(future).when(workers).submit(any(Runnable.class));
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException());

        engine.execute(10L);

        verify(workers).submit(any(Runnable.class));
        verify(future).cancel(true);
        verify(tasks).finish(eq(10L), eq("FAILED"), eq(35), eq("TIMEOUT"), anyString(), anyList());
    }

    @Test
    void cancellationKeepsActualProgressInsteadOfClaimingCompletion() throws Exception {
        when(tasks.selectCancelRequested(10L)).thenReturn(false, true);
        Future<?> future = mock(Future.class);
        doReturn(future).when(workers).submit(any(Runnable.class));
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException());

        engine.execute(10L);

        verify(tasks).finish(eq(10L), eq("CANCELLED"), eq(35), isNull(), isNull(), anyList());
        verify(future).cancel(true);
        verify(workers).submit(any(Runnable.class));
    }

    @Test
    void interruptedCoordinatorCancelsWorkerWithoutRetry() throws Exception {
        Future<?> future = mock(Future.class);
        doReturn(future).when(workers).submit(any(Runnable.class));
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException());
        try {
            engine.execute(10L);

            assertTrue(Thread.currentThread().isInterrupted());
            verify(future).cancel(true);
            verify(workers).submit(any(Runnable.class));
            verify(tasks).finish(eq(10L), eq("FAILED"), eq(35), eq("INTERRUPTED"), anyString(), anyList());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void workerQueueRejectionBecomesTerminalFailure() {
        when(workers.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("full"));

        assertDoesNotThrow(() -> engine.execute(10L));

        verify(workers).submit(any(Runnable.class));
        verify(tasks).finish(eq(10L), eq("FAILED"), eq(35), eq("QUEUE_FULL"), anyString(), anyList());
    }

    @Test
    void retriesKeepUniqueStepNumbersAndCloseSuccessfulStep() {
        task.setTimeoutSeconds(10);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            com.documenter.service.TaskExecutionContext context = invocation.getArgument(1);
            context.startStep("work", "attempt " + context.attemptNo());
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary failure");
            }
            return null;
        }).when(handler).handle(eq(task), any());
        runWorkerInline();

        engine.execute(10L);

        assertEquals(2, calls.get());
        assertEquals(List.of(1, 2), insertedSteps.stream().map(TaskStep::getStepNo).toList());
        verify(steps).updateById(argThat((TaskStep step) ->
                Long.valueOf(2).equals(step.getId()) && "SUCCEEDED".equals(step.getStatus())));
        verify(tasks).finish(eq(10L), eq("SUCCEEDED"), eq(100), isNull(), isNull(), anyList());
    }

    @Test
    void invalidTaskTypeDoesNotLeaveTaskRunning() {
        task.setTaskType("UNKNOWN");

        assertDoesNotThrow(() -> engine.execute(10L));

        verify(tasks).finish(eq(10L), eq("FAILED"), eq(35), eq("INVALID_TASK_TYPE"), anyString(), anyList());
        verifyNoInteractions(workers);
    }

    @Test
    void missingHandlerWithNullProgressStillFinishesTask() {
        task.setTaskType("CONVERT");
        task.setProgress(null);

        assertDoesNotThrow(() -> engine.execute(10L));

        verify(tasks).finish(eq(10L), eq("FAILED"), eq(0), eq("NO_HANDLER"), anyString(), anyList());
    }

    @Test
    void coordinatorRejectionOnlyFailsPendingTask() {
        doThrow(new RejectedExecutionException("full")).when(coordinators).execute(any(Runnable.class));

        assertDoesNotThrow(() -> engine.submit(10L));

        verify(tasks).finish(eq(10L), eq("FAILED"), eq(0), eq("QUEUE_FULL"), anyString(),
                eq(List.of("PENDING")));
        verifyNoInteractions(workers);
    }

    @Test
    void alreadyCancelledTaskDoesNotStartHandler() {
        when(tasks.selectCancelRequested(10L)).thenReturn(true);

        engine.execute(10L);

        verifyNoInteractions(workers);
        verify(tasks).finish(eq(10L), eq("CANCELLED"), eq(35), isNull(), isNull(), anyList());
    }

    @Test
    void handlerReturningAfterCancellationCannotBeMarkedSuccessful() {
        task.setTimeoutSeconds(10);
        when(tasks.selectCancelRequested(10L)).thenReturn(false, true);
        runWorkerInline();

        engine.execute(10L);

        verify(tasks).finish(eq(10L), eq("CANCELLED"), eq(35), isNull(), isNull(), anyList());
    }

    @Test
    void taskAlreadyClaimedByAnotherExecutorIsNotProcessed() {
        when(tasks.markRunning(eq(10L), anyString())).thenReturn(0);

        engine.execute(10L);

        verifyNoInteractions(workers);
        verify(tasks, never()).finish(anyLong(), anyString(), anyInt(), any(), any(), anyList());
    }

    private void runWorkerInline() {
        when(workers.submit(any(Runnable.class))).thenAnswer(invocation -> {
            CompletableFuture<Void> future = new CompletableFuture<>();
            try {
                invocation.<Runnable>getArgument(0).run();
                future.complete(null);
            } catch (RuntimeException failure) {
                future.completeExceptionally(failure);
            }
            return future;
        });
    }
}
