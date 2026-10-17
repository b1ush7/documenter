package com.documenter.controller;

import com.documenter.entity.User;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.*;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.VersionVO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocxPaginationControllerTest {
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

    @ParameterizedTest
    @ValueSource(strings = {"SET_PAGE_BREAK_BEFORE", "CLEAR_PAGE_BREAK_BEFORE"})
    void existingParagraphFormatRouteAcceptsPaginationTypesWithPinnedVersion(String type) throws Exception {
        var result = new VersionVO();
        result.setVersionNo(2);
        when(documents.formatParagraphs(eq(7L), eq(10L), eq(1), any())).thenReturn(result);
        mvc.perform(post("/file/10/versions/1/paragraph-formats").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[{\"type\":\"" + type + "\",\"blockId\":\"body/p1\"}]}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.versionNo").value(2));
        verify(documents).formatParagraphs(eq(7L), eq(10L), eq(1), argThat(request ->
                type.equals(request.getOperations().getFirst().getType())
                && "body/p1".equals(request.getOperations().getFirst().getBlockId())
                && request.getOperations().getFirst().getLevel() == null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"operations\":[null]}",
            "{\"operations\":[{\"type\":\"SET_PAGE_BREAK\",\"blockId\":\"body/p1\"}]}",
            "{\"operations\":[{\"type\":\"SET_PAGE_BREAK_BEFORE\",\"blockId\":\"header0/p0\"}]}",
            "{\"operations\":[{\"type\":\"SET_PAGE_BREAK_BEFORE\",\"blockId\":\"body/p1\",\"level\":0}]}"})
    void invalidShapeAndUnsupportedTargetsFailBeforeService(String body) throws Exception {
        mvc.perform(post("/file/10/versions/1/paragraph-formats").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(documents);
    }

    @Test
    void structureSerializesExplicitTrueFalseAndUnsetStates() throws Exception {
        var blocks = java.util.Arrays.asList(Boolean.TRUE, Boolean.FALSE, null).stream().map(value ->
                new DocxStructureVO.Block("body/p0", "PARAGRAPH", "text", null, null,
                        null, null, null, List.of(), null, value)).toList();
        when(documents.readStructure(7L, 10L, 1)).thenReturn(new DocxStructureVO(10L, 1, blocks));
        mvc.perform(get("/file/10/versions/1/structure"))
                .andExpect(jsonPath("$.data.blocks[0].pageBreakBefore").value(true))
                .andExpect(jsonPath("$.data.blocks[1].pageBreakBefore").value(false))
                .andExpect(jsonPath("$.data.blocks[2].pageBreakBefore").doesNotExist());
    }

    @Test
    void paginationStillRequiresAuthentication() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(post("/file/10/versions/1/paragraph-formats").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[{\"type\":\"SET_PAGE_BREAK_BEFORE\",\"blockId\":\"body/p1\"}]}"))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(documents);
    }
}
