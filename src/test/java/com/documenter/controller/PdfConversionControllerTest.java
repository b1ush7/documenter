package com.documenter.controller;

import com.documenter.entity.User;
import com.documenter.enums.PdfConversionMode;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.*;
import com.documenter.vo.VersionVO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PdfConversionControllerTest {

    private final PdfToDocxService converter = mock(PdfToDocxService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FileController(mock(FileService.class),
                        mock(DocumentVersionService.class), mock(DocxDocumentService.class),
                        mock(PdfDocumentService.class), converter,
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
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void convertsBoundPdfUsingAuthenticatedOwner() throws Exception {
        VersionVO version = new VersionVO();
        version.setVersionNo(4);
        version.setExtension("docx");
        when(converter.convert(eq(7L), eq(10L), eq(3), any(), isNull(), isNull()))
                .thenReturn(version);

        mvc.perform(post("/file/10/versions/3/pdf/convert-to-docx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"LAYOUT\",\"instruction\":\"保留版式\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(4))
                .andExpect(jsonPath("$.data.extension").value("docx"));

        verify(converter).convert(eq(7L), eq(10L), eq(3),
                argThat(request -> request.getMode() == PdfConversionMode.LAYOUT),
                isNull(), isNull());
    }

    @Test
    void rejectsMissingModeBeforeServiceCall() throws Exception {
        mvc.perform(post("/file/10/versions/3/pdf/convert-to-docx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(converter);
    }
}
