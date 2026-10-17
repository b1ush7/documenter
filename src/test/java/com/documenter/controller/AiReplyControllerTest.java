package com.documenter.controller;

import com.documenter.entity.User;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.AiReplyService;
import com.documenter.vo.AiMessageVO;
import com.documenter.vo.AiReplyVO;
import com.documenter.vo.AgentRunResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiReplyControllerTest {
    private final AiReplyService service = mock(AiReplyService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new AiReplyController(service, org.mockito.Mockito.mock(
                                java.util.concurrent.ExecutorService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var user = new User();
        user.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void createUsesAuthenticatedIdentityAndValidatedRequest() throws Exception {
        when(service.create(eq(7L), eq(1L), any())).thenReturn(reply("PENDING", null));

        mvc.perform(post("/ai/sessions/1/replies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"edit\",\"versionNo\":1,\"idempotencyKey\":\"request-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.planStatus").value("PENDING"));
        verify(service).create(eq(7L), eq(1L), argThat(request ->
                request.getVersionNo() == 1 && "request-1".equals(request.getIdempotencyKey())));
    }

    @Test
    void quotaErrorsRetainReplyIdentityAndUsageInResponseData() throws Exception {
        for (var termination : List.of(AgentRunResult.Termination.QUOTA_EXCEEDED,
                AgentRunResult.Termination.QUOTA_UNAVAILABLE)) {
            var message = new AiMessageVO();
            message.setId(2L);
            message.setContent("quota denied");
            var result = new AgentRunResult("quota denied", List.of(), List.of(),
                    new AgentRunResult.Usage(1, 0, 0, 0, java.math.BigDecimal.ZERO, false, 10),
                    termination, true);
            when(service.create(eq(7L), eq(1L), any())).thenReturn(
                    new AiReplyVO(message, "FAILED", "NONE", 1, null, result, List.of()));

            mvc.perform(post("/ai/sessions/1/replies").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"edit\",\"versionNo\":1,\"idempotencyKey\":\"request-1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(
                            termination == AgentRunResult.Termination.QUOTA_EXCEEDED ? 429 : 503))
                    .andExpect(jsonPath("$.data.message.id").value(2))
                    .andExpect(jsonPath("$.data.runStatus").value("FAILED"))
                    .andExpect(jsonPath("$.data.result.usage.modelCalls").value(1))
                    .andExpect(jsonPath("$.data.result.termination").value(termination.name()));
        }
    }

    @Test
    void missingIdempotencyKeyIsRejectedBeforeServiceCall() throws Exception {
        mvc.perform(post("/ai/sessions/1/replies").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"edit\"}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void unauthenticatedQueryCannotReadReply() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/ai/sessions/1/replies/2")).andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(service);
    }

    @Test
    void confirmOnlyPassesServerIdentityAndPathIds() throws Exception {
        when(service.confirm(7L, 1L, 2L)).thenReturn(reply("CONFIRMED", 3));
        mvc.perform(post("/ai/sessions/1/replies/2/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":999,\"versionNo\":99,\"plan\":{\"operations\":[]}}"))
                .andExpect(jsonPath("$.data.outputVersion").value(3));
        verify(service).confirm(7L, 1L, 2L);
    }

    @Test
    void getAndDiscardRoutesUseTheSameOwnedReply() throws Exception {
        when(service.get(7L, 1L, 2L)).thenReturn(reply("PENDING", null));
        when(service.discard(7L, 1L, 2L)).thenReturn(reply("DISCARDED", null));
        mvc.perform(get("/ai/sessions/1/replies/2")).andExpect(jsonPath("$.data.planStatus").value("PENDING"));
        mvc.perform(post("/ai/sessions/1/replies/2/discard"))
                .andExpect(jsonPath("$.data.planStatus").value("DISCARDED"));
    }

    private static AiReplyVO reply(String planStatus, Integer outputVersion) {
        var message = new AiMessageVO();
        message.setId(2L);
        message.setRole("ASSISTANT");
        return new AiReplyVO(message, "COMPLETED", planStatus, 1, outputVersion, null, List.of());
    }
}
