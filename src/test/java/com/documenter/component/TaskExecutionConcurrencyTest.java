package com.documenter.component;

import com.documenter.configuration.TaskExecutorConfig;
import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.entity.ProcessingTask;
import com.documenter.enums.TaskType;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import com.documenter.service.TaskHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskExecutionConcurrencyTest {

    @Test
    void busyCoordinatorsDoNotStarveWorkers() throws Exception {
        var properties = properties(2, 4);
        var config = new TaskExecutorConfig();
        var coordinators = config.documentTaskCoordinator(properties);
        var workers = config.documentTaskExecutor(properties);
        var tasks = mock(ProcessingTaskMapper.class);
        var steps = mock(TaskStepMapper.class);
        var handler = mock(TaskHandler.class);
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(2);
        var workerNames = new ConcurrentLinkedQueue<String>();
        when(handler.supports(TaskType.OCR)).thenReturn(true);
        when(handler.name()).thenReturn("test handler");
        when(tasks.selectById(anyLong())).thenAnswer(invocation -> {
            var task = new ProcessingTask();
            task.setId(invocation.getArgument(0));
            task.setTaskType("OCR");
            task.setMaxRetry(0);
            task.setTimeoutSeconds(10);
            return task;
        });
        when(tasks.markRunning(anyLong(), anyString())).thenReturn(1);
        when(tasks.finish(anyLong(), eq("SUCCEEDED"), eq(100), isNull(), isNull(), anyList()))
                .thenAnswer(invocation -> { finished.countDown(); return 1; });
        doAnswer(invocation -> {
            workerNames.add(Thread.currentThread().getName());
            started.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return null;
        }).when(handler).handle(any(), any());
        var engine = new TaskExecutionEngine(tasks, steps, new TaskHandlerRegistry(List.of(handler)),
                properties, coordinators, workers);
        try {
            engine.submit(10L);
            engine.submit(11L);

            assertTrue(started.await(3, TimeUnit.SECONDS), "处理器必须能在调度线程等待期间开始执行");
            release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertEquals(2, workerNames.size());
            assertTrue(workerNames.stream().allMatch(name -> name.startsWith("doc-task-")));
        } finally {
            release.countDown();
            coordinators.shutdown();
            workers.shutdown();
        }
    }

    @Test
    void fullOrStoppedWorkerPoolRejectsInsteadOfRunningOnSubmittingThread() throws Exception {
        var workers = new TaskExecutorConfig().documentTaskExecutor(properties(1, 1));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Runnable rejected = mock(Runnable.class);
        try {
            workers.execute(() -> {
                started.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            workers.execute(() -> {});

            assertThrows(RejectedExecutionException.class, () -> workers.execute(rejected));
            verifyNoInteractions(rejected);
        } finally {
            release.countDown();
            workers.shutdown();
        }
        assertThrows(RejectedExecutionException.class, () -> workers.execute(rejected));
        verifyNoInteractions(rejected);
    }

    private static TaskExecutorProperties properties(int threads, int capacity) {
        var properties = new TaskExecutorProperties();
        properties.setCorePoolSize(threads);
        properties.setMaxPoolSize(threads);
        properties.setQueueCapacity(capacity);
        properties.setShutdownAwait(Duration.ofSeconds(1));
        return properties;
    }
}
