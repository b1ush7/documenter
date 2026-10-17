package com.documenter.controller;

import com.documenter.entity.User;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.*;
import com.documenter.vo.FileAssetVO;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TemplateGenerationControllerTest {
    private final DocxDocumentService documents = mock(DocxDocumentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FileController(mock(FileService.class),
                        mock(DocumentVersionService.class), documents, mock(PdfDocumentService.class),
                        mock(XlsxDocumentService.class), mock(ImageDocumentService.class),
                        mock(PagePreviewService.class), mock(CompatibilityVerificationService.class),
                        mock(DocumentComparisonService.class), mock(DocumentExportService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var user = new User();
        user.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void existingGenerationRouteAcceptsTemplateModeAndUsesAuthenticatedOwner() throws Exception {
        var output = new FileAssetVO();
        output.setId(80L);
        output.setLatestVersion(1);
        output.setSource("GENERATED");
        when(documents.generate(eq(7L), any())).thenReturn(output);
        mvc.perform(post("/file/docx/generate").contentType(MediaType.APPLICATION_JSON).content("""
                {"templateId":11,"templateVersion":3,"fileName":"周报.docx",
                 "variables":{"title":"本周进展","optional":null},"instruction":"用于周会"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(80)).andExpect(jsonPath("$.data.latestVersion").value(1));
        verify(documents).generate(eq(7L), argThat(request -> request.getTemplateId() == 11L
                && request.getTemplateVersion() == 3 && "本周进展".equals(request.getVariables().get("title"))
                && request.getVariables().containsKey("optional") && request.getVariables().get("optional") == null
                && request.getTitle() == null && request.getBlocks() == null));
    }

    @Test
    void numericVariableIsNotSilentlyCoercedToTextBeforeBusinessValidation() throws Exception {
        mvc.perform(post("/file/docx/generate").contentType(MediaType.APPLICATION_JSON).content("""
                {"templateId":11,"templateVersion":3,"variables":{"title":123}}
                """));
        verify(documents).generate(eq(7L), argThat(request -> request.getVariables().get("title") instanceof Number));
    }

    @Test
    void oldStructuredGenerationContractRemainsAvailable() throws Exception {
        mvc.perform(post("/file/docx/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"标题\",\"blocks\":[{\"type\":\"PARAGRAPH\",\"text\":\"正文\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(documents).generate(eq(7L), argThat(request -> request.getTemplateId() == null
                && request.getTemplateVersion() == null && request.getVariables() == null
                && request.getBlocks().size() == 1 && "标题".equals(request.getTitle())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"templateId\":0,\"templateVersion\":3}",
            "{\"templateId\":11,\"templateVersion\":-1}",
            "{\"templateId\":11,\"templateVersion\":3,\"variables\":[]}", "{", ""})
    void invalidTopLevelFieldsFailBeforeService(String body) throws Exception {
        mvc.perform(post("/file/docx/generate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(documents);
    }

    @Test
    void anonymousGenerationCannotReachService() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(post("/file/docx/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":11,\"templateVersion\":3}"))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(documents);
    }
}
