package com.documenter.component;

import com.documenter.entity.ProcessingTask;
import com.documenter.enums.PdfConversionMode;
import com.documenter.exception.BusinessException;
import com.documenter.service.PdfToDocxService;
import com.documenter.service.TaskExecutionContext;
import com.documenter.vo.VersionVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConvertTaskHandlerTest {

    private final PdfToDocxService converter = mock(PdfToDocxService.class);
    private final TaskExecutionContext context = mock(TaskExecutionContext.class);
    private final ConvertTaskHandler handler = new ConvertTaskHandler(converter);

    @Test
    void convertsBoundPdfThroughRealServiceAndRecordsOutputVersion() {
        ProcessingTask task = task("请按版式优先转换");
        when(context.attemptNo()).thenReturn(2);
        VersionVO output = new VersionVO();
        output.setVersionNo(6);
        when(converter.convert(eq(7L), eq(10L), eq(3), any(), eq(20L), eq(2)))
                .thenReturn(output);

        handler.handle(task, context);

        ArgumentCaptor<com.documenter.dto.ConvertPdfToDocxDTO> request =
                ArgumentCaptor.forClass(com.documenter.dto.ConvertPdfToDocxDTO.class);
        verify(converter).convert(eq(7L), eq(10L), eq(3), request.capture(), eq(20L), eq(2));
        assertEquals(PdfConversionMode.LAYOUT, request.getValue().getMode());
        verify(context).completeWithVersion(6);
        verify(context).reportProgress(100);
    }

    @Test
    void editableModeIsDefaultAndMissingSourceVersionFailsBeforeConversion() {
        assertEquals(PdfConversionMode.EDITABLE, ConvertTaskHandler.conversionMode(null));
        ProcessingTask invalid = task("普通转换");
        invalid.setInputVersion(null);

        assertEquals(400, assertThrows(BusinessException.class,
                () -> handler.handle(invalid, context)).getCode());
        verifyNoInteractions(converter);
    }

    private static ProcessingTask task(String instruction) {
        ProcessingTask task = new ProcessingTask();
        task.setId(20L);
        task.setUserId(7L);
        task.setFileId(10L);
        task.setInputVersion(3);
        task.setInstruction(instruction);
        return task;
    }
}
