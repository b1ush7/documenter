package com.documenter.controller;

import com.documenter.entity.User;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.*;
import com.documenter.vo.DocxTableOfContentsVO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocxTableOfContentsControllerTest {

    private final DocxDocumentService documents = mock(DocxDocumentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FileController(mock(FileService.class),
                        mock(DocumentVersionService.class), documents, mock(PdfDocumentService.class),
                        mock(PdfToDocxService.class), mock(XlsxDocumentService.class),
                        mock(ImageDocumentService.class), mock(PagePreviewService.class),
                        mock(CompatibilityVerificationService.class), mock(DocumentComparisonService.class),
                        mock(DocumentExportService.class)))
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
    void readsTocStatus() throws Exception {
        when(documents.readTableOfContents(7L, 10L, 3)).thenReturn(
                new DocxTableOfContentsVO(10L, 3, true, "body/p2",
                        "TOC \\o \"1-3\" \\h", true));

        mvc.perform(get("/file/10/versions/3/table-of-contents"))
                .andExpect(jsonPath("$.data.present").value(true))
                .andExpect(jsonPath("$.data.blockId").value("body/p2"))
                .andExpect(jsonPath("$.data.updateFieldsOnOpen").value(true));
    }

    @Test
    void createsOrRefreshesToc() throws Exception {
        var version = new VersionVO();
        version.setVersionNo(4);
        when(documents.createOrRefreshTableOfContents(eq(7L), eq(10L), eq(3), any()))
                .thenReturn(version);

        mvc.perform(post("/file/10/versions/3/table-of-contents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":\"START\",\"maxLevel\":3,\"hyperlinks\":true}"))
                .andExpect(jsonPath("$.data.versionNo").value(4));
    }

    @Test
    void invalidPositionFailsValidation() throws Exception {
        mvc.perform(post("/file/10/versions/3/table-of-contents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":\"MIDDLE\"}"))
                .andExpect(jsonPath("$.code").value(400));
        verify(documents, never()).createOrRefreshTableOfContents(any(), any(), any(), any());
    }
}
