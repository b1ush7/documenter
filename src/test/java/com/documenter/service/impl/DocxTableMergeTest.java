package com.documenter.service.impl;

import com.documenter.dto.EditDocxTableDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import com.documenter.vo.FileDownload;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocxTableMergeTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int VERSION = 1;

    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final VersionWriter writer = mock(VersionWriter.class);
    private final DocxDocumentServiceImpl service = new DocxDocumentServiceImpl(
            versions, storage, writer, mock(FileService.class),
            mock(DocxTemplateGenerationService.class));

    @Test
    void mergesRectangleAndPreservesContentInReopenableDocx() throws Exception {
        prepare(tableDocument());

        service.editTable(USER_ID, FILE_ID, VERSION, request(
                mergeOperation("body/t0/r0/c0", "body/t0/r1/c1")));

        try (XWPFDocument document = open(saved())) {
            XWPFTable table = document.getTables().getFirst();
            assertEquals(1, table.getRow(0).getTableCells().size());
            assertEquals(1, table.getRow(1).getTableCells().size());
            var top = table.getRow(0).getCell(0);
            var bottom = table.getRow(1).getCell(0);
            assertEquals(2, top.getCTTc().getTcPr().getGridSpan().getVal().intValue());
            assertEquals(2, bottom.getCTTc().getTcPr().getGridSpan().getVal().intValue());
            assertEquals(STMerge.RESTART, top.getCTTc().getTcPr().getVMerge().getVal());
            assertEquals(STMerge.CONTINUE, bottom.getCTTc().getTcPr().getVMerge().getVal());
            String mergedText = top.getText();
            assertTrue(mergedText.indexOf("A") < mergedText.indexOf("B"));
            assertTrue(mergedText.indexOf("B") < mergedText.indexOf("C"));
            assertTrue(mergedText.indexOf("C") < mergedText.indexOf("D"));
        }
        verify(writer).appendVersion(eq(FILE_ID), eq(USER_ID), any(),
                eq(ChangeType.MANUAL_EDIT), any(), any(), eq(VERSION));
    }

    @Test
    void splitsHorizontalMergeAndRestoresColumnCell() throws Exception {
        prepare(horizontalMergedDocument());

        service.editTable(USER_ID, FILE_ID, VERSION,
                request(splitOperation("body/t0/r0/c0")));

        try (XWPFDocument document = open(saved())) {
            var row = document.getTables().getFirst().getRow(0);
            assertEquals(2, row.getTableCells().size());
            assertEquals("合并内容", row.getCell(0).getText());
            assertEquals("", row.getCell(1).getText());
            assertFalse(row.getCell(0).getCTTc().getTcPr().isSetGridSpan());
        }
    }

    @Test
    void splitsVerticalMergeWithoutLosingRows() throws Exception {
        prepare(verticalMergedDocument());

        service.editTable(USER_ID, FILE_ID, VERSION,
                request(splitOperation("body/t0/r0/c0")));

        try (XWPFDocument document = open(saved())) {
            XWPFTable table = document.getTables().getFirst();
            assertEquals(2, table.getNumberOfRows());
            assertEquals("合并内容", table.getRow(0).getCell(0).getText());
            assertEquals("", table.getRow(1).getCell(0).getText());
            assertFalse(table.getRow(0).getCell(0).getCTTc().getTcPr().isSetVMerge());
            assertFalse(table.getRow(1).getCell(0).getCTTc().getTcPr().isSetVMerge());
        }
    }

    @Test
    void splitsRectangleAndRestoresEveryPhysicalCell() throws Exception {
        prepare(rectangleMergedDocument());

        service.editTable(USER_ID, FILE_ID, VERSION,
                request(splitOperation("body/t0/r0/c0")));

        try (XWPFDocument document = open(saved())) {
            XWPFTable table = document.getTables().getFirst();
            assertEquals(2, table.getNumberOfRows());
            for (var row : table.getRows()) {
                assertEquals(2, row.getTableCells().size());
                for (var cell : row.getTableCells()) {
                    assertFalse(cell.getCTTc().getTcPr().isSetGridSpan());
                    assertFalse(cell.getCTTc().getTcPr().isSetVMerge());
                }
            }
            assertEquals("A\nB\nC\nD", table.getRow(0).getCell(0).getText());
            assertEquals("", table.getRow(0).getCell(1).getText());
            assertEquals("", table.getRow(1).getCell(0).getText());
            assertEquals("", table.getRow(1).getCell(1).getText());
        }
    }

    @Test
    void rejectsCrossTableAndMixedStructuralEditsBeforeLoading() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.editTable(USER_ID, FILE_ID, VERSION, request(
                        mergeOperation("body/t0/r0/c0", "body/t1/r0/c0")))).getCode());

        var delete = new EditDocxTableDTO.Operation();
        delete.setType("DELETE_ROW");
        delete.setBlockId("body/t0/r0");
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.editTable(USER_ID, FILE_ID, VERSION, request(
                        mergeOperation("body/t0/r0/c0", "body/t0/r1/c1"), delete))).getCode());
        verify(versions, never()).downloadVersion(any(), any(), any());
        verify(storage, never()).store(any(byte[].class), anyString());
    }

    @Test
    void rejectsNonRectangularMergeWithoutSaving() throws Exception {
        prepare(tableDocument());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.editTable(USER_ID, FILE_ID, VERSION, request(
                        mergeOperation("body/t0/r1/c0", "body/t0/r0/c1")))).getCode());
        verify(storage, never()).store(any(byte[].class), anyString());
    }

    @Test
    void rejectsMergeIntoExistingMergeOrNestedTableWithoutSaving() throws Exception {
        prepare(tableWithHorizontalMergeAndSecondRow());
        assertEquals(422, assertThrows(BusinessException.class,
                () -> service.editTable(USER_ID, FILE_ID, VERSION, request(
                        mergeOperation("body/t0/r1/c0", "body/t0/r1/c1")))).getCode());

        byte[] nested = tableDocumentWithNestedTable();
        when(versions.downloadVersion(USER_ID, FILE_ID, 2)).thenReturn(download(nested));
        assertEquals(422, assertThrows(BusinessException.class,
                () -> service.editTable(USER_ID, FILE_ID, 2, request(
                        mergeOperation("body/t0/r0/c0", "body/t0/r1/c1")))).getCode());
        verify(storage, never()).store(any(byte[].class), anyString());
    }

    @Test
    void rejectsSplitOfUnmergedOrContinuationCell() throws Exception {
        prepare(tableDocument());
        assertEquals(422, assertThrows(BusinessException.class,
                () -> service.editTable(USER_ID, FILE_ID, VERSION,
                        request(splitOperation("body/t0/r0/c0")))).getCode());

        byte[] vertical = verticalMergedDocument();
        when(versions.downloadVersion(USER_ID, FILE_ID, 2)).thenReturn(download(vertical));
        assertEquals(422, assertThrows(BusinessException.class,
                () -> service.editTable(USER_ID, FILE_ID, 2,
                        request(splitOperation("body/t0/r1/c0")))).getCode());
        verify(storage, never()).store(any(byte[].class), anyString());
    }

    private void prepare(byte[] source) {
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION)).thenReturn(download(source));
        when(storage.store(any(byte[].class), eq("docx"))).thenReturn("merge.docx");
        var created = new DocumentVersion();
        created.setVersionNo(2);
        created.setChangeType(ChangeType.MANUAL_EDIT.name());
        when(writer.appendVersion(eq(FILE_ID), eq(USER_ID), any(),
                eq(ChangeType.MANUAL_EDIT), any(), any(), eq(VERSION))).thenReturn(created);
    }

    private byte[] saved() {
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(bytes.capture(), eq("docx"));
        return bytes.getValue();
    }

    private static byte[] tableDocument() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("A");
            table.getRow(0).getCell(1).setText("B");
            table.getRow(1).getCell(0).setText("C");
            table.getRow(1).getCell(1).setText("D");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] horizontalMergedDocument() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(1, 2);
            var merged = table.getRow(0).getCell(0);
            merged.setText("合并内容");
            merged.getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.TWO);
            table.getRow(0).removeCell(1);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] verticalMergedDocument() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 1);
            table.getRow(0).getCell(0).setText("合并内容");
            table.getRow(0).getCell(0).getCTTc().addNewTcPr()
                    .addNewVMerge().setVal(STMerge.RESTART);
            table.getRow(1).getCell(0).getCTTc().addNewTcPr()
                    .addNewVMerge().setVal(STMerge.CONTINUE);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] rectangleMergedDocument() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 2);
            var top = table.getRow(0).getCell(0);
            top.setText("A");
            top.getParagraphs().getFirst().createRun().addBreak();
            top.getParagraphs().getFirst().createRun().setText("B");
            top.getParagraphs().getFirst().createRun().addBreak();
            top.getParagraphs().getFirst().createRun().setText("C");
            top.getParagraphs().getFirst().createRun().addBreak();
            top.getParagraphs().getFirst().createRun().setText("D");
            top.getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.TWO);
            top.getCTTc().getTcPr().addNewVMerge().setVal(STMerge.RESTART);
            var bottom = table.getRow(1).getCell(0);
            bottom.getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.TWO);
            bottom.getCTTc().getTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
            table.getRow(0).removeCell(1);
            table.getRow(1).removeCell(1);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] tableWithHorizontalMergeAndSecondRow() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 2);
            var merged = table.getRow(0).getCell(0);
            merged.setText("合并内容");
            merged.getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.TWO);
            table.getRow(0).removeCell(1);
            table.getRow(1).getCell(0).setText("C");
            table.getRow(1).getCell(1).setText("D");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] tableDocumentWithNestedTable() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("A");
            table.getRow(0).getCell(1).setText("B");
            table.getRow(1).getCell(0).setText("C");
            table.getRow(1).getCell(1).setText("D");
            var nested = table.getRow(0).getCell(1).getCTTc().addNewTbl();
            var nestedRow = nested.addNewTr();
            nestedRow.addNewTc().addNewP();
            document.write(output);
            return output.toByteArray();
        }
    }

    private static FileDownload download(byte[] bytes) {
        FileAsset asset = new FileAsset();
        asset.setId(FILE_ID);
        asset.setUserId(USER_ID);
        asset.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        asset.setExtension("docx");
        return new FileDownload(asset, new ByteArrayResource(bytes), bytes.length,
                asset.getContentType(), "source.docx");
    }

    private static XWPFDocument open(byte[] bytes) throws Exception {
        return new XWPFDocument(new ByteArrayInputStream(bytes));
    }

    private static EditDocxTableDTO request(EditDocxTableDTO.Operation... operations) {
        var request = new EditDocxTableDTO();
        request.setInstruction("调整合并单元格");
        request.setOperations(List.of(operations));
        return request;
    }

    private static EditDocxTableDTO.Operation mergeOperation(String start, String end) {
        var operation = new EditDocxTableDTO.Operation();
        operation.setType("MERGE_CELLS");
        operation.setBlockId(start);
        operation.setEndBlockId(end);
        return operation;
    }

    private static EditDocxTableDTO.Operation splitOperation(String target) {
        var operation = new EditDocxTableDTO.Operation();
        operation.setType("SPLIT_CELL");
        operation.setBlockId(target);
        return operation;
    }
}
