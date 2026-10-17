package com.documenter.controller;

import com.documenter.entity.User;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.*;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocxHeaderFooterControllerTest {
    private final DocxDocumentService documents = mock(DocxDocumentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new FileController(mock(FileService.class),
                        mock(DocumentVersionService.class), documents, mock(PdfDocumentService.class),
                        mock(PdfToDocxService.class),
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
    void existingTextEndpointAcceptsHeaderFooterAndHeaderTableParagraphs() throws Exception {
        var output = new VersionVO();
        output.setVersionNo(2);
        when(documents.replaceParagraphText(eq(7L), eq(10L), eq(1), any())).thenReturn(output);
        mvc.perform(post("/file/10/versions/1/paragraph-text").contentType(MediaType.APPLICATION_JSON).content("""
                {"operations":[{"blockId":"header0/p1","text":"页眉"},
                 {"blockId":"footer1/p0","text":""},
                 {"blockId":"header0/t0/r0/c0/p0","text":"表格段落"}]}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(2));
        verify(documents).replaceParagraphText(eq(7L), eq(10L), eq(1), argThat(request ->
                request.getOperations().size() == 3
                && "header0/p1".equals(request.getOperations().getFirst().getBlockId())
                && "".equals(request.getOperations().get(1).getText())
                && "header0/t0/r0/c0/p0".equals(request.getOperations().get(2).getBlockId())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"header/p0", "header-1/p0", "footer0/p-1", "header0/t0/r0/c0", "../header0/p0"})
    void invalidPathsFailBeforeService(String path) throws Exception {
        mvc.perform(post("/file/10/versions/1/paragraph-text").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[{\"blockId\":\"" + path + "\",\"text\":\"x\"}]}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(documents);
    }

    @Test
    void nullOperationIsAnInputError() throws Exception {
        mvc.perform(post("/file/10/versions/1/paragraph-text").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[null]}"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(documents);
    }

    @Test
    void headerReplacementStillRequiresAuthentication() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(post("/file/10/versions/1/paragraph-text").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operations\":[{\"blockId\":\"header0/p0\",\"text\":\"x\"}]}"))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(documents);
    }
}
