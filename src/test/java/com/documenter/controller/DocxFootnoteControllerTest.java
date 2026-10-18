package com.documenter.controller;

import com.documenter.dto.EditDocxFootnotesDTO;
import com.documenter.entity.User;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.*;
import com.documenter.vo.DocxFootnotesVO;
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

class DocxFootnoteControllerTest {

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
    void listsReferencedFootnotesForExplicitVersion() throws Exception {
        when(documents.readFootnotes(7L, 10L, 3)).thenReturn(
                new DocxFootnotesVO(10L, 3, List.of(
                        new DocxFootnotesVO.Footnote(5, "来源", List.of("body/p1")))));

        mvc.perform(get("/file/10/versions/3/footnotes"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.footnotes[0].id").value(5))
                .andExpect(jsonPath("$.data.footnotes[0].referencedBy[0]").value("body/p1"));
    }

    @Test
    void editsFootnotesWithAuthenticatedOwner() throws Exception {
        var version = new VersionVO();
        version.setVersionNo(4);
        when(documents.editFootnotes(eq(7L), eq(10L), eq(3), any())).thenReturn(version);

        mvc.perform(post("/file/10/versions/3/footnotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operations":[{"type":"ADD","targetBlockId":"body/p0",
                                 "text":"新增来源"}]}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(4));
        verify(documents).editFootnotes(eq(7L), eq(10L), eq(3),
                argThat((EditDocxFootnotesDTO request) ->
                        "ADD".equals(request.getOperations().getFirst().getType())
                                && "body/p0".equals(request.getOperations().getFirst().getTargetBlockId())));
    }

    @Test
    void invalidFootnoteOperationFailsBeforeService() throws Exception {
        mvc.perform(post("/file/10/versions/3/footnotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[{\"type\":\"MOVE\"}]}"))
                .andExpect(jsonPath("$.code").value(400));
        verify(documents, never()).editFootnotes(any(), any(), any(), any());
    }
}
