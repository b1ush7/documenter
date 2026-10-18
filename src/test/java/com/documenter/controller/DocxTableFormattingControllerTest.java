package com.documenter.controller;

import com.documenter.dto.EditDocxTableDTO;
import com.documenter.entity.User;
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

class DocxTableFormattingControllerTest {

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
    void acceptsRowHeightRepeatHeaderAndColumnWidth() throws Exception {
        var version = new VersionVO();
        version.setVersionNo(2);
        when(documents.editTable(eq(7L), eq(10L), eq(1), any())).thenReturn(version);

        mvc.perform(post("/file/10/versions/1/table-edits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operations":[
                                  {"type":"SET_ROW_HEIGHT","blockId":"body/t0/r0",
                                   "dimensionTwips":560},
                                  {"type":"REPEAT_HEADER_ROW","blockId":"body/t0/r0",
                                   "repeatHeader":true},
                                  {"type":"SET_COLUMN_WIDTH","blockId":"body/t0/r0/c0",
                                   "dimensionTwips":2400}
                                ]}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(2));
        verify(documents).editTable(eq(7L), eq(10L), eq(1),
                argThat((EditDocxTableDTO request) -> request.getOperations().size() == 3));
    }

    @Test
    void rejectsInvalidDimensionBeforeService() throws Exception {
        mvc.perform(post("/file/10/versions/1/table-edits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operations":[{"type":"SET_ROW_HEIGHT",
                                 "blockId":"body/t0/r0","dimensionTwips":0}]}
                                """))
                .andExpect(jsonPath("$.code").value(400));
        verify(documents, never()).editTable(any(), any(), any(), any());
    }

    @Test
    void acceptsMergeCellsInsideExistingTableEditContract() throws Exception {
        var version = new VersionVO();
        version.setVersionNo(2);
        when(documents.editTable(eq(7L), eq(10L), eq(1), any())).thenReturn(version);

        mvc.perform(post("/file/10/versions/1/table-edits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operations":[{
                                  "type":"MERGE_CELLS",
                                  "blockId":"body/t0/r0/c0",
                                  "endBlockId":"body/t0/r1/c1"
                                }]}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(2));
        verify(documents).editTable(eq(7L), eq(10L), eq(1),
                argThat((EditDocxTableDTO request) ->
                        "MERGE_CELLS".equals(request.getOperations().getFirst().getType())
                                && "body/t0/r1/c1".equals(request.getOperations()
                                .getFirst().getEndBlockId())));
    }
}
