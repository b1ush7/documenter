package com.documenter.service.impl;

import com.documenter.component.TaskExecutionEngine;
import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.entity.ProcessingTask;
import com.documenter.dto.CreateTaskDTO;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import com.documenter.service.FileService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskServiceImplTest {

    private final ProcessingTaskMapper tasks = mock(ProcessingTaskMapper.class);
    private final TaskServiceImpl service = new TaskServiceImpl(tasks, mock(TaskStepMapper.class),
            mock(FileService.class), mock(TaskExecutionEngine.class), new TaskExecutorProperties());

    @Test
    void submissionReportsImmediateQueueFailure() {
        var failed = task("FAILED");
        failed.setErrorCode("QUEUE_FULL");
        failed.setErrorMessage("任务队列已满，请稍后重试");
        when(tasks.insert(any(ProcessingTask.class))).thenAnswer(invocation -> {
            invocation.<ProcessingTask>getArgument(0).setId(10L);
            return 1;
        });
        when(tasks.selectById(10L)).thenReturn(failed);
        var request = new CreateTaskDTO();
        request.setTaskType("OCR");

        var result = service.submit(7L, request);

        assertEquals("FAILED", result.getStatus());
        assertEquals("QUEUE_FULL", result.getErrorCode());
        assertEquals("任务队列已满，请稍后重试", result.getErrorMessage());
    }

    @Test
    void pendingTaskClaimedDuringCancellationStillReceivesCancelFlag() {
        when(tasks.selectById(10L)).thenReturn(task("PENDING"), task("RUNNING"));
        when(tasks.finish(eq(10L), eq("CANCELLED"), eq(0), isNull(), isNull(),
                eq(List.of("PENDING")))).thenReturn(0);

        var result = service.cancel(7L, 10L);

        verify(tasks).requestCancel(10L);
        assertEquals("RUNNING", result.getStatus());
    }

    @Test
    void pendingTaskCancelledAtomicallyDoesNotNeedAnotherFlagWrite() {
        when(tasks.selectById(10L)).thenReturn(task("PENDING"), task("CANCELLED"));
        when(tasks.finish(eq(10L), eq("CANCELLED"), eq(0), isNull(), isNull(), anyList())).thenReturn(1);

        var result = service.cancel(7L, 10L);

        assertEquals("CANCELLED", result.getStatus());
        verify(tasks, never()).requestCancel(anyLong());
    }

    private static ProcessingTask task(String status) {
        ProcessingTask task = new ProcessingTask();
        task.setId(10L);
        task.setUserId(7L);
        task.setStatus(status);
        return task;
    }
}
