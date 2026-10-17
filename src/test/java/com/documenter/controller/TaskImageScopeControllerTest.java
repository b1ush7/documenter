package com.documenter.controller;

import com.documenter.component.PlaceholderTaskHandler;
import com.documenter.entity.User;
import com.documenter.enums.TaskType;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.TaskService;
import com.documenter.vo.PageResult;
import com.documenter.vo.TaskDetailVO;
import com.documenter.vo.TaskVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskImageScopeControllerTest {
    private final TaskService tasks = mock(TaskService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(tasks))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var user = new User();
        user.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void retiredImageGenerationIsRejectedBeforeTaskService() throws Exception {
        mvc.perform(post("/task").contentType(MediaType.APPLICATION_JSON).content("{\"taskType\":\"IMAGE_GEN\"}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(tasks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OCR", "CONVERT", "AI_EDIT", "EXPORT"})
    void remainingTypesStillReachServiceWithAuthenticatedOwner(String type) throws Exception {
        mvc.perform(post("/task").contentType(MediaType.APPLICATION_JSON).content("{\"taskType\":\"" + type + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(tasks).submit(eq(7L), argThat(request -> type.equals(request.getTaskType())));
    }

    @Test
    void historicalImageTasksRemainFilterableAndReadable() throws Exception {
        var legacy = new TaskVO();
        legacy.setId(10L);
        legacy.setTaskType("IMAGE_GEN");
        legacy.setStatus("FAILED");
        var page = new PageResult<TaskVO>();
        page.setRecords(List.of(legacy));
        when(tasks.list(eq(7L), any())).thenReturn(page);
        when(tasks.detail(7L, 10L)).thenReturn(TaskDetailVO.of(legacy, List.of()));
        mvc.perform(get("/task/list").param("taskType", "IMAGE_GEN"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].taskType").value("IMAGE_GEN"));
        mvc.perform(get("/task/10"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.task.taskType").value("IMAGE_GEN"));
        verify(tasks).list(eq(7L), argThat(query -> "IMAGE_GEN".equals(query.getTaskType())));
    }

    @Test
    void placeholderNeverClaimsLegacyImageTypeButStillSupportsDocumentTasks() {
        var handler = new PlaceholderTaskHandler();
        assertFalse(handler.supports(TaskType.IMAGE_GEN));
        for (var type : List.of(TaskType.OCR, TaskType.CONVERT, TaskType.AI_EDIT, TaskType.EXPORT)) {
            assertTrue(handler.supports(type));
        }
    }

    @Test
    void queryingLegacyRecordsStillRequiresAuthentication() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/task/10")).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(tasks);
    }
}
