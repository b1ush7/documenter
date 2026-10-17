package com.documenter.service.impl;

import com.documenter.component.TaskExecutionEngine;
import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.entity.ProcessingTask;
import com.documenter.entity.FileAsset;
import com.documenter.dto.CreateTaskDTO;
import com.documenter.mapper.ProcessingTaskMapper;
import com.documenter.mapper.TaskStepMapper;
import com.documenter.service.FileService;
import com.documenter.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskServiceImplTest {

    private final ProcessingTaskMapper tasks = mock(ProcessingTaskMapper.class);
    private final TaskStepMapper steps = mock(TaskStepMapper.class);
    private final FileService files = mock(FileService.class);
    private final TaskExecutionEngine engine = mock(TaskExecutionEngine.class);
    private final TaskServiceImpl service = new TaskServiceImpl(tasks, steps,
            files, engine, new TaskExecutorProperties());

    @Test
    void imageGenerationCannotBeSubmittedEvenWhenBeanValidationIsBypassed() {
        var request = new CreateTaskDTO();
        request.setTaskType("IMAGE_GEN");
        assertEquals(400, assertThrows(BusinessException.class, () -> service.submit(7L, request)).getCode());
        verifyNoInteractions(tasks, steps, files, engine);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED", "PENDING", "SUCCEEDED"})
    void legacyImageGenerationCannotCreateAnotherTaskThroughRetry(String status) {
        var legacy = task(status);
        legacy.setTaskType("IMAGE_GEN");
        when(tasks.selectById(10L)).thenReturn(legacy);
        assertEquals(400, assertThrows(BusinessException.class, () -> service.retry(7L, 10L)).getCode());
        verify(tasks, never()).insert(any(ProcessingTask.class));
        verifyNoInteractions(files, engine);
    }

    @Test
    void legacyImageTaskStillHasReadableDetailsAndSteps() {
        var legacy = task("FAILED");
        legacy.setTaskType("IMAGE_GEN");
        when(tasks.selectById(10L)).thenReturn(legacy);
        when(steps.selectByTaskId(10L)).thenReturn(List.of());
        var result = service.detail(7L, 10L);
        assertEquals("IMAGE_GEN", result.getTask().getTaskType());
        verifyNoInteractions(files, engine);
    }

    @Test
    void legacyImageTaskDoesNotBypassOwnershipChecks() {
        var legacy = task("FAILED");
        legacy.setTaskType("IMAGE_GEN");
        when(tasks.selectById(10L)).thenReturn(legacy);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.detail(8L, 10L)).getCode());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.retry(8L, 10L)).getCode());
        verifyNoInteractions(steps, files, engine);
    }

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
        when(files.requireOwned(7L, 5L)).thenReturn(file());
        var request = new CreateTaskDTO();
        request.setTaskType("EXPORT");
        request.setFileId(5L);

        var result = service.submit(7L, request);

        assertEquals("FAILED", result.getStatus());
        assertEquals("QUEUE_FULL", result.getErrorCode());
        assertEquals("任务队列已满，请稍后重试", result.getErrorMessage());
    }

    @Test
    void realTaskTypesRequireAFileAndAnExistingInputVersion() {
        var noFile = new CreateTaskDTO();
        noFile.setTaskType("CONVERT");
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.submit(7L, noFile)).getCode());

        when(files.requireOwned(7L, 5L)).thenReturn(file());
        var stale = new CreateTaskDTO();
        stale.setTaskType("EXPORT");
        stale.setFileId(5L);
        stale.setInputVersion(3);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.submit(7L, stale)).getCode());
        verify(tasks, never()).insert(any(ProcessingTask.class));
    }

    @Test
    void historicalTypesCannotBeSubmittedEvenWhenDtoValidationIsBypassed() {
        for (String type : List.of("OCR", "AI_EDIT")) {
            var request = new CreateTaskDTO();
            request.setTaskType(type);
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> service.submit(7L, request)).getCode());
        }
        verifyNoInteractions(tasks, steps, files, engine);
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

    private static FileAsset file() {
        FileAsset asset = new FileAsset();
        asset.setId(5L);
        asset.setUserId(7L);
        asset.setLatestVersion(2);
        return asset;
    }
}
