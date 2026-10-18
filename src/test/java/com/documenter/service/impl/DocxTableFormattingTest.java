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
import org.apache.poi.xwpf.usermodel.TableRowHeightRule;
import org.apache.poi.xwpf.usermodel.TableWidthType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocxTableFormattingTest {

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
    void setsRowHeightRepeatingHeaderAndColumnWidthAtomically() throws Exception {
        prepare(tableDocument(false));

        service.editTable(USER_ID, FILE_ID, VERSION, request(
                rowOperation("SET_ROW_HEIGHT", "body/t0/r0", 560, null),
                rowOperation("REPEAT_HEADER_ROW", "body/t0/r0", null, true),
                cellOperation("SET_COLUMN_WIDTH", "body/t0/r0/c0", 2400)));

        try (XWPFDocument document = open(saved())) {
            var table = document.getTables().getFirst();
            var header = table.getRow(0);
            assertEquals(560, header.getHeight());
            assertEquals(TableRowHeightRule.AT_LEAST, header.getHeightRule());
            assertEquals(1, header.getCtRow().getTrPr().sizeOfTblHeaderArray());
            for (var row : table.getRows()) {
                assertEquals(2400, row.getCell(0).getWidth());
                assertEquals(TableWidthType.DXA, row.getCell(0).getWidthType());
            }
        }
        verify(writer).appendVersion(eq(FILE_ID), eq(USER_ID), any(),
                eq(ChangeType.MANUAL_EDIT), any(), any(), eq(VERSION));
    }

    @Test
    void canDisableRepeatedHeader() throws Exception {
        prepare(tableDocumentWithRepeatedHeader());

        service.editTable(USER_ID, FILE_ID, VERSION,
                request(rowOperation("REPEAT_HEADER_ROW", "body/t0/r0", null, false)));

        try (XWPFDocument document = open(saved())) {
            assertEquals(0, document.getTables().getFirst().getRow(0).getCtRow()
                    .getTrPr().sizeOfTblHeaderArray());
        }
    }

    @Test
    void rejectsColumnWidthOnMergedGridAndInvalidFieldsWithoutSaving() throws Exception {
        prepare(tableDocument(false));
        byte[] merged = tableDocument(true);
        when(versions.downloadVersion(USER_ID, FILE_ID, 2)).thenReturn(download(merged));

        assertEquals(422, assertThrows(BusinessException.class,
                () -> service.editTable(USER_ID, FILE_ID, 2, request(
                        cellOperation("SET_COLUMN_WIDTH", "body/t0/r0/c0", 2400)))).getCode());
        assertThrows(BusinessException.class, () -> service.editTable(
                USER_ID, FILE_ID, VERSION,
                request(cellOperation("SET_CELL_TEXT", "body/t0/r0/c0", 100))));
        verify(storage, never()).store(any(byte[].class), anyString());
    }

    private void prepare(byte[] source) {
        when(versions.downloadVersion(USER_ID, FILE_ID, VERSION)).thenReturn(download(source));
        when(storage.store(any(byte[].class), eq("docx"))).thenReturn("table.docx");
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

    private static byte[] tableDocument(boolean mergedGrid) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("表头");
            table.getRow(0).getCell(1).setText("值");
            table.getRow(1).getCell(0).setText("A");
            table.getRow(1).getCell(1).setText("1");
            if (mergedGrid) table.getRow(1).removeCell(1);
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] tableDocumentWithRepeatedHeader() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("表头");
            table.getRow(0).getCell(1).setText("值");
            var properties = table.getRow(0).getCtRow().addNewTrPr();
            properties.addNewTblHeader();
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
        request.setInstruction("调整表格");
        request.setOperations(List.of(operations));
        return request;
    }

    private static EditDocxTableDTO.Operation rowOperation(
            String type, String blockId, Integer dimension, Boolean repeat) {
        var operation = new EditDocxTableDTO.Operation();
        operation.setType(type);
        operation.setBlockId(blockId);
        operation.setDimensionTwips(dimension);
        operation.setRepeatHeader(repeat);
        return operation;
    }

    private static EditDocxTableDTO.Operation cellOperation(
            String type, String blockId, Integer dimension) {
        var operation = new EditDocxTableDTO.Operation();
        operation.setType(type);
        operation.setBlockId(blockId);
        operation.setDimensionTwips(dimension);
        return operation;
    }
}
