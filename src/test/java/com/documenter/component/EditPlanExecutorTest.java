package com.documenter.component;

import com.documenter.exception.BusinessException;
import com.documenter.service.DocxDocumentService;
import com.documenter.vo.DocumentEditPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class EditPlanExecutorTest {

    @Test
    void absentOrEmptyPlanCannotCreateAVersion() {
        var service = mock(DocxDocumentService.class);
        var executor = new EditPlanExecutor(service, mock(com.documenter.service.XlsxDocumentService.class));

        assertEquals(422, assertThrows(BusinessException.class,
                () -> executor.execute(7L, 10L, 1, null, "test")).getCode());
        assertEquals(422, assertThrows(BusinessException.class,
                () -> executor.execute(7L, 10L, 1,
                        new DocumentEditPlan("empty", List.of(), null), "test")).getCode());
        verifyNoInteractions(service);
    }
}
