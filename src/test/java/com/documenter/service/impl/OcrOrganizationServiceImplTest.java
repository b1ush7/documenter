package com.documenter.service.impl;

import com.documenter.configuration.AiModelProperties;
import com.documenter.dto.OcrOrganizeRequestDTO;
import com.documenter.exception.BusinessException;
import com.documenter.service.AgentService;
import com.documenter.service.OcrCorrectionService;
import com.documenter.vo.AgentRunResult;
import com.documenter.vo.OcrCorrectionVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrOrganizationServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int VERSION = 2;
    private static final String FINGERPRINT = "c".repeat(64);

    private final OcrCorrectionService corrections = mock(OcrCorrectionService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final AiModelProperties properties = new AiModelProperties();
    private final OcrOrganizationServiceImpl service =
            new OcrOrganizationServiceImpl(corrections, agentService, properties);

    @Test
    void wrapsCorrectedTextAndTablesAsDataForReadOnlyAi() {
        when(corrections.get(USER_ID, FILE_ID, VERSION, FINGERPRINT))
                .thenReturn(corrected());
        var expected = new AgentRunResult("整理结果", List.of(), List.of(), null,
                AgentRunResult.Termination.COMPLETED, false, List.of(), "notice");
        when(agentService.runConversation(eq(USER_ID), isNull(), isNull(), any(),
                eq(List.of()), any())).thenReturn(expected);
        var request = new OcrOrganizeRequestDTO();
        request.setInstruction("整理成会议纪要");

        AgentRunResult actual = service.organize(
                USER_ID, FILE_ID, VERSION, FINGERPRINT, request);

        assertEquals(expected, actual);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(agentService).runConversation(eq(USER_ID), isNull(), isNull(),
                prompt.capture(), eq(List.of()), any());
        assertTrue(prompt.getValue().contains("<<<DOCUMENT_CONTENT>>>"));
        assertTrue(prompt.getValue().contains("[表格 0：1 行 × 2 列]"));
        assertTrue(prompt.getValue().contains("r0c0 span=1x2"));
    }

    @Test
    void rejectsContextOverflowBeforeCallingModel() {
        when(corrections.get(USER_ID, FILE_ID, VERSION, FINGERPRINT))
                .thenReturn(new OcrCorrectionVO(FILE_ID, VERSION, FINGERPRINT,
                        List.of(new OcrCorrectionVO.Page(1, "x".repeat(200), List.of())),
                        null));
        properties.setMaxDocumentChars(50);

        assertEquals(413, assertThrows(BusinessException.class,
                () -> service.organize(USER_ID, FILE_ID, VERSION, FINGERPRINT,
                        new OcrOrganizeRequestDTO())).getCode());
        verify(agentService, org.mockito.Mockito.never()).runConversation(
                any(), any(), any(), any(), any(), any());
    }

    private static OcrCorrectionVO corrected() {
        return new OcrCorrectionVO(FILE_ID, VERSION, FINGERPRINT,
                List.of(new OcrCorrectionVO.Page(1, "会议正文", List.of(
                        new OcrCorrectionVO.Table(0, 1, 2, List.of(
                                new OcrCorrectionVO.Cell(0, 0, 1, 2, "人工表头")))))),
                null);
    }
}
