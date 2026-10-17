package com.documenter.controller;

import com.documenter.entity.User;
import com.documenter.service.AiReplyService;
import com.documenter.vo.AiReplyVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiReplyStreamingControllerTest {

    private final AiReplyService service = mock(AiReplyService.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AiReplyController(service, executor)).build();
        var user = new User();
        user.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    @Test
    void streamsStartedDeltaAndCompletedEvents() throws Exception {
        AiReplyVO reply = new AiReplyVO(null, "COMPLETED", "NONE",
                null, null, null, List.of());
        when(service.createStreaming(eq(7L), eq(10L), any(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Consumer<AiReplyService.StreamEvent> events =
                            invocation.getArgument(3, java.util.function.Consumer.class);
                    events.accept(new AiReplyService.StreamEvent(
                            AiReplyService.StreamEvent.Type.STARTED, 99L, null));
                    events.accept(new AiReplyService.StreamEvent(
                            AiReplyService.StreamEvent.Type.TEXT_DELTA, 99L, "增量"));
                    return reply;
                });

        MvcResult result = mvc.perform(post("/ai/sessions/10/replies/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"解释文档\",\"idempotencyKey\":\"stream-1\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(3000);

        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:started"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"replyId\":99"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:delta"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"text\":\"增量\""));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("event:completed"));
    }
}
