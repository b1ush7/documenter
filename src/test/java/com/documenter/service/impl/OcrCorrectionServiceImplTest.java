package com.documenter.service.impl;

import com.documenter.configuration.OcrProperties;
import com.documenter.dto.SaveOcrCorrectionsDTO;
import com.documenter.entity.OcrCorrection;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.OcrCorrectionMapper;
import com.documenter.service.DocumentVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrCorrectionServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long FILE_ID = 10L;
    private static final int VERSION = 2;
    private static final String FINGERPRINT = "a".repeat(64);

    private final OcrCorrectionMapper mapper = mock(OcrCorrectionMapper.class);
    private final DocumentVersionService versions = mock(DocumentVersionService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OcrProperties properties = new OcrProperties();
    private final OcrCorrectionServiceImpl service = new OcrCorrectionServiceImpl(
            mapper, versions, objectMapper, properties);

    @Test
    void savesNewCorrectionAndReadsItBack() throws Exception {
        when(mapper.selectOne(any())).thenReturn(null).thenReturn(savedEntity());

        var request = request("人工校正文本");
        request.getPages().getFirst().setTables(List.of(tableCorrection(
                new int[][]{{0, 0, 1, 2}}, 1, 2,
                List.of("人工确认"))));
        var saved = service.save(USER_ID, FILE_ID, VERSION, request);
        var loaded = service.get(USER_ID, FILE_ID, VERSION, FINGERPRINT);

        assertEquals("人工校正文本", saved.pages().getFirst().text());
        assertEquals(1, saved.pages().getFirst().tables().size());
        assertEquals("人工确认", saved.pages().getFirst().tables().getFirst()
                .cells().getFirst().text());
        assertEquals("人工校正文本", loaded.pages().getFirst().text());
        verify(versions, org.mockito.Mockito.times(2))
                .downloadVersion(USER_ID, FILE_ID, VERSION);
        verify(mapper).insert(any(OcrCorrection.class));
    }

    @Test
    void updatesExistingCorrectionWithoutCreatingVersion() throws Exception {
        OcrCorrection existing = savedEntity();
        when(mapper.selectOne(any())).thenReturn(existing);

        service.save(USER_ID, FILE_ID, VERSION, request("修订后文本"));

        verify(mapper).updateById(existing);
        assertEquals("修订后文本", objectMapper.readTree(existing.getCorrectionsJson())
                .path(0).path("text").asText());
    }

    @Test
    void rejectsDuplicatePagesAndUnknownFingerprint() {
        var duplicate = request("A");
        duplicate.getPages().add(page(1, "B"));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.save(USER_ID, FILE_ID, VERSION, duplicate)).getCode());

        when(mapper.selectOne(any())).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.get(USER_ID, FILE_ID, VERSION, FINGERPRINT)).getCode());
    }

    @Test
    void rejectsOverlappingOrIncompleteCorrectedTable() {
        var overlapping = request("文本");
        overlapping.getPages().getFirst().setTables(List.of(tableCorrection(
                new int[][]{
                        {0, 0, 1, 2},
                        {0, 1, 1, 1}
                }, 1, 2, List.of("A", "B"))));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.save(USER_ID, FILE_ID, VERSION, overlapping)).getCode());

        var incomplete = request("文本");
        incomplete.getPages().getFirst().setTables(List.of(tableCorrection(
                new int[][]{{0, 0, 1, 1}}, 2, 2, List.of("A"))));
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.save(USER_ID, FILE_ID, VERSION, incomplete)).getCode());
    }

    private static SaveOcrCorrectionsDTO request(String text) {
        var request = new SaveOcrCorrectionsDTO();
        request.setFingerprint(FINGERPRINT);
        request.setPages(new java.util.ArrayList<>(java.util.List.of(page(1, text))));
        return request;
    }

    private static SaveOcrCorrectionsDTO.PageCorrection page(int pageNo, String text) {
        var page = new SaveOcrCorrectionsDTO.PageCorrection();
        page.setPageNo(pageNo);
        page.setText(text);
        return page;
    }

    private static OcrCorrection savedEntity() throws Exception {
        var entity = new OcrCorrection();
        entity.setUserId(USER_ID);
        entity.setFileId(FILE_ID);
        entity.setVersionNo(VERSION);
        entity.setFingerprint(FINGERPRINT);
        entity.setCorrectionsJson(new ObjectMapper().writeValueAsString(
                List.of(new com.documenter.vo.OcrCorrectionVO.Page(
                        1, "人工校正文本", List.of()))));
        entity.setUpdateTime(LocalDateTime.of(2026, 10, 17, 20, 0));
        return entity;
    }

    private static SaveOcrCorrectionsDTO.TableCorrection tableCorrection(
            int[][] cells, int rows, int columns, List<String> texts) {
        var table = new SaveOcrCorrectionsDTO.TableCorrection();
        table.setTableIndex(0);
        table.setRows(rows);
        table.setColumns(columns);
        var result = new java.util.ArrayList<SaveOcrCorrectionsDTO.CellCorrection>();
        for (int index = 0; index < cells.length; index++) {
            var cell = new SaveOcrCorrectionsDTO.CellCorrection();
            cell.setRow(cells[index][0]);
            cell.setColumn(cells[index][1]);
            cell.setRowSpan(cells[index][2]);
            cell.setColumnSpan(cells[index][3]);
            cell.setText(texts.get(index));
            result.add(cell);
        }
        table.setCells(result);
        return table;
    }
}
