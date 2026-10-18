package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.vo.DocumentEditPlan;
import com.documenter.vo.FileDownload;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class XlsxFormulaPlanTest {
    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter writer = mock(VersionWriter.class);
    private final StorageProperties properties = new StorageProperties();
    private final XlsxDocumentServiceImpl service = new XlsxDocumentServiceImpl(versions, storage, writer, properties);

    @Test
    void validationReturnsActualOldContentEvenOutsideStructureReadLimitWithoutSaving() throws Exception {
        prepare();
        properties.getXlsx().setMaxReadCells(1);
        assertTrue(service.readStructure(7L, 10L, 1).truncated());
        var plan = plan("A2", "SUM(A1)");
        var before = service.validateFormulaPlan(7L, 10L, 1, plan);
        assertEquals("old value", before.get("数据!A2").value());
        var blank = service.validateFormulaPlan(7L, 10L, 1, plan("D9", "SUM(A1)"));
        assertEquals("BLANK", blank.get("数据!D9").type());
        verifyNoInteractions(storage, writer);
    }

    @Test
    void confirmedFormulaKeepsStyleAndUnchangedFormulaAndWritesOneAiVersion() throws Exception {
        prepare();
        service.editFormulaPlan(7L, 10L, 1, new DocumentEditPlan("batch", List.of(
                operation("A2", "SUM(A1)"), operation("D9", "IF(A1>10,\"yes\",\"no\")")), null), "汇总");
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(bytes.capture(), eq("xlsx"));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.getValue()))) {
            var sheet = workbook.getSheet("数据");
            assertEquals("SUM(A1)", sheet.getRow(1).getCell(0).getCellFormula());
            assertTrue(sheet.getRow(1).getCell(0).getCellStyle().getWrapText());
            assertEquals("A1*2", sheet.getRow(0).getCell(1).getCellFormula());
            assertEquals(12, workbook.getCreationHelper().createFormulaEvaluator().evaluate(sheet.getRow(1).getCell(0)).getNumberValue());
            assertEquals("yes", workbook.getCreationHelper().createFormulaEvaluator().evaluate(sheet.getRow(8).getCell(3)).getStringValue());
        }
        verify(writer).appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.AI_EDIT), eq("汇总"), any(), eq(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Missing!A1", "SUM(", "XFE1", "[external.xlsx]Sheet1!A1", "HYPERLINK(\"https://example.invalid\",\"x\")"})
    void invalidOrExternalFormulasFailBothPreviewAndConfirmationWithoutSaving(String formula) throws Exception {
        prepare();
        assertThrows(BusinessException.class, () -> service.validateFormulaPlan(7L, 10L, 1, plan("A2", formula)));
        assertThrows(BusinessException.class, () -> service.editFormulaPlan(7L, 10L, 1, plan("A2", formula), "test"));
        verifyNoInteractions(storage, writer);
    }

    @Test
    void failedSecondOperationDoesNotSaveFirstFormula() throws Exception {
        prepare();
        var plan = new DocumentEditPlan("mixed", List.of(
                operation("A2", "SUM(A1)"), operation("D9", "Missing!A1")), null);
        assertThrows(BusinessException.class, () -> service.editFormulaPlan(7L, 10L, 1, plan, "test"));
        verifyNoInteractions(storage, writer);
    }

    @Test
    void ownershipFailureStopsValidationAndConfirmationBeforeStorage() throws Exception {
        prepare();
        when(versions.downloadVersion(7L, 10L, 1)).thenThrow(new BusinessException(404, "文件不存在"));
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.validateFormulaPlan(7L, 10L, 1, plan("A2", "SUM(A1)"))).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.editFormulaPlan(7L, 10L, 1, plan("A2", "SUM(A1)"), "test")).getCode());
        verifyNoInteractions(storage, writer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROTECTED", "MERGED", "ARRAY"})
    void formulaPlansRespectProtectedSheetsMergedCellsAndArrayGroups(String restriction) throws Exception {
        prepare(workbook -> {
            var sheet = workbook.getSheet("数据");
            switch (restriction) {
                case "PROTECTED" -> sheet.protectSheet("secret");
                case "MERGED" -> sheet.addMergedRegion(org.apache.poi.ss.util.CellRangeAddress.valueOf("A2:B2"));
                case "ARRAY" -> sheet.setArrayFormula("A1*2", org.apache.poi.ss.util.CellRangeAddress.valueOf("C2:D2"));
                default -> throw new IllegalArgumentException(restriction);
            }
        });
        var plan = plan("MERGED".equals(restriction) ? "B2" : "ARRAY".equals(restriction) ? "C2" : "A2", "SUM(A1)");
        assertThrows(BusinessException.class, () -> service.validateFormulaPlan(7L, 10L, 1, plan));
        assertThrows(BusinessException.class, () -> service.editFormulaPlan(7L, 10L, 1, plan, "test"));
        verifyNoInteractions(storage, writer);
    }

    @Test
    void conflictAndOuterTransactionRollbackCleanStoredArtifact() throws Exception {
        prepare();
        when(storage.delete("ai.xlsx")).thenReturn(true);
        when(writer.appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.AI_EDIT), any(), any(), eq(1)))
                .thenThrow(new BusinessException(409, "版本冲突"));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.editFormulaPlan(7L, 10L, 1, plan("A2", "SUM(A1)"), "test")).getCode());
        verify(storage).delete("ai.xlsx");
        clearInvocations(storage);
        var saved = new DocumentVersion();
        saved.setVersionNo(2);
        when(writer.appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.AI_EDIT), any(), any(), eq(1))).thenReturn(saved);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.editFormulaPlan(7L, 10L, 1, plan("A2", "SUM(A1)"), "test");
            verify(storage, never()).delete(anyString());
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(storage).delete("ai.xlsx");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private void prepare() throws Exception {
        prepare(workbook -> { });
    }

    private void prepare(java.util.function.Consumer<XSSFWorkbook> editor) throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue(12);
            row.createCell(1).setCellFormula("A1*2");
            var existing = sheet.createRow(1).createCell(0);
            existing.setCellValue("old value");
            var style = workbook.createCellStyle();
            style.setWrapText(true);
            existing.setCellStyle(style);
            editor.accept(workbook);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var asset = new FileAsset();
        asset.setExtension("xlsx");
        when(versions.downloadVersion(7L, 10L, 1)).thenReturn(new FileDownload(asset,
                new ByteArrayResource(bytes), bytes.length, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "test.xlsx"));
        when(storage.store(any(byte[].class), eq("xlsx"))).thenReturn("ai.xlsx");
        var saved = new DocumentVersion();
        saved.setVersionNo(2);
        when(writer.appendVersion(eq(10L), eq(7L), any(), eq(ChangeType.AI_EDIT), any(), any(), eq(1))).thenReturn(saved);
    }
    private static DocumentEditPlan.Operation operation(String cell, String formula) {
        return new DocumentEditPlan.Operation("SET_FORMULA", "数据!" + cell, formula, "说明");
    }
    private static DocumentEditPlan plan(String cell, String formula) {
        return new DocumentEditPlan("公式", List.of(operation(cell, formula)), null);
    }
}
