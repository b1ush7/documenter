package com.documenter.service.impl;

import com.documenter.component.DocxTemplateRenderer;
import com.documenter.component.InitialVersionWriter;
import com.documenter.component.UploadContentInspector;
import com.documenter.component.UploadRateLimiter;
import com.documenter.configuration.StorageProperties;
import com.documenter.dto.GenerateDocxDTO;
import com.documenter.entity.DocTemplate;
import com.documenter.entity.DocTemplateVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocTemplateMapper;
import com.documenter.mapper.DocTemplateVersionMapper;
import com.documenter.mapper.FileAssetMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real template renderer, local storage and generated-file service; database and version writes are substituted. */
class DocxTemplateGenerationServiceTest {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    @TempDir Path directory;
    private final DocTemplateMapper templates = mock(DocTemplateMapper.class);
    private final DocTemplateVersionMapper versions = mock(DocTemplateVersionMapper.class);
    private final FileAssetMapper assets = mock(FileAssetMapper.class);
    private final InitialVersionWriter initial = mock(InitialVersionWriter.class);
    private final StorageProperties properties = new StorageProperties();
    private LocalFileStorage storage;
    private DocxTemplateGenerationService service;
    private DocTemplate template;
    private DocTemplateVersion source;
    private GenerateDocxDTO request;
    private byte[] original;

    @BeforeEach
    void setUp() throws Exception {
        properties.getLocal().setRoot(directory.resolve("storage").toString());
        properties.getLocal().setTemp(directory.resolve("tmp").toString());
        storage = spy(new LocalFileStorage(properties));
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Hello {{title}}");
            doc.createTable(1, 1).getRow(0).getCell(0).setText("Optional: {{optional}}");
            doc.write(bytes);
            original = bytes.toByteArray();
        }
        String key = storage.store(original, "docx");
        template = new DocTemplate();
        template.setId(11L);
        template.setName("周报.v1");
        template.setEnabled(true);
        template.setContentType(DOCX);
        template.setVersionNo(3);
        template.setStorageKey(key);
        template.setVariables("[{\"name\":\"title\",\"required\":true},{\"name\":\"optional\"}]");
        source = new DocTemplateVersion();
        source.setTemplateId(11L);
        source.setVersionNo(3);
        source.setStorageKey(key);
        source.setContentType(DOCX);
        source.setExtension("docx");
        source.setSizeBytes((long) original.length);
        when(templates.selectById(11L)).thenReturn(template);
        when(versions.findVersion(11L, 3)).thenReturn(source);
        when(assets.insert(any(FileAsset.class))).thenAnswer(call -> {
            call.<FileAsset>getArgument(0).setId(80L);
            return 1;
        });
        var files = new FileServiceImpl(assets, storage, properties, mock(UploadRateLimiter.class),
                mock(UploadContentInspector.class), initial);
        service = new DocxTemplateGenerationService(templates, versions, storage, files, new DocxTemplateRenderer(), properties);
        request = new GenerateDocxDTO();
        request.setTemplateId(11L);
        request.setTemplateVersion(3);
        request.setVariables(Map.of("title", "本周进展"));
        clearInvocations(storage);
    }

    @Test
    void createsIndependentOwnedVersionOneAndPreservesTemplateBytes() throws Exception {
        request.setInstruction("用于周会");
        var output = service.generate(7L, request);
        assertEquals(80L, output.getId());
        assertEquals("GENERATED", output.getSource());
        assertEquals(1, output.getLatestVersion());
        assertEquals("周报.v1.docx", output.getDisplayName());
        var capture = ArgumentCaptor.forClass(FileAsset.class);
        verify(assets).insert(capture.capture());
        var saved = capture.getValue();
        assertEquals(7L, saved.getUserId());
        assertNotEquals(source.getStorageKey(), saved.getStorageKey());
        try (var document = new XWPFDocument(storage.read(saved.getStorageKey()))) {
            assertEquals("Hello 本周进展", document.getParagraphs().getFirst().getText());
            assertEquals("Optional: ", document.getTables().getFirst().getRow(0).getCell(0).getText());
        }
        try (var input = storage.read(source.getStorageKey())) { assertArrayEquals(original, input.readAllBytes()); }
        verify(initial).createInitialVersion(same(saved), eq(7L), eq(ChangeType.MANUAL_EDIT),
                eq("模板 ID=11，内容版本=3；用于周会"), eq("本地生成 DOCX"));
        verify(templates, never()).updateById(any(DocTemplate.class));
        verify(versions, never()).insert(any(DocTemplateVersion.class));
    }

    @Test
    void acceptsExplicitOutputNameAndUsesPinnedHistoryRatherThanMutablePointer() throws Exception {
        template.setStorageKey("not-the-history-key");
        request.setFileName("report.DOCX");
        assertEquals("report.DOCX", service.generate(7L, request).getDisplayName());
        verify(storage).read(source.getStorageKey());
        verify(storage, never()).read("not-the-history-key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ID", "VERSION", "TITLE", "BLOCKS"})
    void incompleteOrMixedRequestsFailBeforeReadingTemplate(String reason) {
        Long userId = 7L;
        switch (reason) {
            case "USER" -> userId = null;
            case "ID" -> request.setTemplateId(null);
            case "VERSION" -> request.setTemplateVersion(null);
            case "TITLE" -> request.setTitle("mixed mode");
            case "BLOCKS" -> request.setBlocks(List.of());
            default -> fail();
        }
        Long caller = userId;
        assertEquals(400, assertThrows(BusinessException.class, () -> service.generate(caller, request)).getCode());
        verifyNoInteractions(storage, assets, initial);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "DISABLED", "STALE", "NOT_DOCX", "NO_HISTORY", "WRONG_HISTORY_TYPE", "NO_METADATA", "NO_FILE", "OVERSIZE", "DAMAGED"})
    void unavailableSourcesAndInvalidDocumentsNeverCreateOutput(String reason) throws Exception {
        int expected = switch (reason) {
            case "MISSING", "DISABLED", "NO_HISTORY" -> 404;
            case "STALE" -> 409;
            case "NOT_DOCX", "WRONG_HISTORY_TYPE" -> 400;
            case "OVERSIZE" -> 413;
            case "DAMAGED" -> 422;
            default -> 500;
        };
        switch (reason) {
            case "MISSING" -> when(templates.selectById(11L)).thenReturn(null);
            case "DISABLED" -> template.setEnabled(false);
            case "STALE" -> request.setTemplateVersion(2);
            case "NOT_DOCX" -> template.setContentType("application/pdf");
            case "NO_HISTORY" -> when(versions.findVersion(11L, 3)).thenReturn(null);
            case "WRONG_HISTORY_TYPE" -> source.setExtension("pdf");
            case "NO_METADATA" -> source.setSizeBytes(null);
            case "NO_FILE" -> source.setStorageKey("missing.docx");
            case "OVERSIZE" -> properties.getUpload().setMaxSizeBytes(original.length - 1L);
            case "DAMAGED" -> {
                Files.write(storage.resolvePath(source.getStorageKey()), new byte[]{1, 2});
                source.setSizeBytes(2L);
            }
            default -> fail();
        }
        assertEquals(expected, assertThrows(BusinessException.class, () -> service.generate(7L, request)).getCode());
        noOutput();
    }

    @Test
    void actualReadSizeCannotBypassLimitWithIncorrectMetadata() throws Exception {
        source.setSizeBytes(1L);
        properties.getUpload().setMaxSizeBytes(original.length - 1L);
        assertEquals(413, assertThrows(BusinessException.class, () -> service.generate(7L, request)).getCode());
        noOutput();
    }

    @Test
    void invalidVariablesLeaveNoOutput() {
        request.setVariables(Map.of("title", 123));
        assertEquals(400, assertThrows(BusinessException.class, () -> service.generate(7L, request)).getCode());
        noOutput();
    }

    @ParameterizedTest
    @ValueSource(strings = {"VERSION", "VARIABLES", "DISABLED", "DELETED"})
    void changesObservedDuringRenderAreRejectedBeforeSaving(String change) {
        var latest = new DocTemplate();
        latest.setId(11L);
        latest.setEnabled(true);
        latest.setContentType(DOCX);
        latest.setVersionNo(3);
        latest.setVariables(template.getVariables());
        switch (change) {
            case "VERSION" -> latest.setVersionNo(4);
            case "VARIABLES" -> latest.setVariables("[]");
            case "DISABLED" -> latest.setEnabled(false);
            case "DELETED" -> latest = null;
            default -> fail();
        }
        when(templates.selectById(11L)).thenReturn(template, latest);
        int code = change.equals("VERSION") || change.equals("VARIABLES") ? 409 : 404;
        assertEquals(code, assertThrows(BusinessException.class, () -> service.generate(7L, request)).getCode());
        noOutput();
    }

    @Test
    void outputSizeLimitIsCheckedBeforeFileWrite() {
        var renderer = mock(DocxTemplateRenderer.class);
        properties.getUpload().setMaxSizeBytes(original.length);
        when(renderer.render(any(), anyString(), anyMap())).thenReturn(new byte[original.length + 1]);
        var files = mock(com.documenter.service.FileService.class);
        var bounded = new DocxTemplateGenerationService(templates, versions, storage, files, renderer, properties);
        assertEquals(413, assertThrows(BusinessException.class, () -> bounded.generate(7L, request)).getCode());
        verifyNoInteractions(files);
        noOutput();
    }

    @Test
    void databaseSaveFailureCleansOnlyGeneratedArtifact() throws Exception {
        when(assets.insert(any(FileAsset.class))).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.generate(7L, request));
        var generated = ArgumentCaptor.forClass(String.class);
        verify(storage).delete(generated.capture());
        assertNotEquals(source.getStorageKey(), generated.getValue());
        assertFalse(storage.exists(generated.getValue()));
        assertTrue(storage.exists(source.getStorageKey()));
        try (var input = storage.read(source.getStorageKey())) { assertArrayEquals(original, input.readAllBytes()); }
        verifyNoInteractions(initial);
    }

    private void noOutput() {
        verifyNoInteractions(assets, initial);
        verify(storage, never()).store(any(byte[].class), anyString());
        verify(storage, never()).delete(anyString());
    }
}
