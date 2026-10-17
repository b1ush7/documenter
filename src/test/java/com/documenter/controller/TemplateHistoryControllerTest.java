package com.documenter.controller;

import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.exception.GlobalExceptionHandler;
import com.documenter.service.DocTemplateService;
import com.documenter.service.ModelConfigService;
import com.documenter.vo.DocTemplateVO;
import com.documenter.vo.DocTemplateVersionVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TemplateHistoryControllerTest {
    private final DocTemplateService templates = mock(DocTemplateService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminConfigController(mock(ModelConfigService.class), templates))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void historyIsPagedAndDoesNotExposeStorageOrUnversionedMetadata() throws Exception {
        var page = new PageResult<DocTemplateVersionVO>();
        page.setTotal(1);
        page.setRecords(List.of(new DocTemplateVersionVO(7L, 3, "old.docx", "application/test", "docx", 3L,
                "RESTORE", 1, null)));
        when(templates.listVersions(eq(7L), any())).thenReturn(page);
        mvc.perform(get("/admin/templates/7/versions").param("page", "2").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].restoredFromVersion").value(1))
                .andExpect(jsonPath("$.data.records[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].variables").doesNotExist());
        verify(templates).listVersions(eq(7L), argThat(query -> query.getPage() == 2 && query.getSize() == 10));
    }

    @Test
    void invalidPagingIsRejectedBeforeServiceCall() throws Exception {
        mvc.perform(get("/admin/templates/7/versions").param("size", "101"))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(templates);
    }

    @Test
    void historicalDownloadUsesHistoricalHeadersAndBytes() throws Exception {
        var carrier = new FileAsset();
        carrier.setContentType("application/pdf");
        byte[] bytes = {1, 2, 3};
        when(templates.downloadVersion(7L, 2)).thenReturn(FileDownload.of(carrier,
                new ByteArrayInputStream(bytes), 3, "template-v2.pdf"));
        mvc.perform(get("/admin/templates/7/versions/2/download"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 3))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("template-v2.pdf")))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void restoreRequiresExpectedVersionAndReturnsNewTemplateVersion() throws Exception {
        var restored = new DocTemplateVO();
        restored.setVersionNo(5);
        restored.setEnabled(true);
        restored.setVariables("[{\"name\":\"current\"}]");
        when(templates.restoreVersion(7L, 2, 4)).thenReturn(restored);
        mvc.perform(post("/admin/templates/7/versions/2/restore").param("expectVersion", "4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(5))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.variables").value(restored.getVariables()));
        mvc.perform(post("/admin/templates/7/versions/2/restore"))
                .andExpect(jsonPath("$.code").value(400));
        verify(templates, times(1)).restoreVersion(anyLong(), anyInt(), anyInt());
    }

    @Test
    void restoreConflictKeepsExistingBusinessErrorContract() throws Exception {
        when(templates.restoreVersion(7L, 2, 4)).thenThrow(new BusinessException(409, "模板已更新"));
        mvc.perform(post("/admin/templates/7/versions/2/restore").param("expectVersion", "4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(409));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERSION", "EXPECTED", "PAGE"})
    void nonNumericParametersAreBusinessValidationErrors(String parameter) throws Exception {
        var request = switch (parameter) {
            case "VERSION" -> get("/admin/templates/7/versions/not-a-number/download");
            case "EXPECTED" -> post("/admin/templates/7/versions/2/restore").param("expectVersion", "not-a-number");
            default -> get("/admin/templates/7/versions").param("page", "not-a-number");
        };
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(templates);
    }
}
