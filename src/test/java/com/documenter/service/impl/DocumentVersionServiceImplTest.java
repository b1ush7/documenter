package com.documenter.service.impl;

import com.documenter.entity.DocumentVersion;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocumentVersionMapper;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.service.VersionWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentVersionServiceImplTest {
    private final DocumentVersionMapper versions = mock(DocumentVersionMapper.class);
    private final FileAssetMapper assets = mock(FileAssetMapper.class);
    private final FileService files = mock(FileService.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final DocumentVersionServiceImpl service = new DocumentVersionServiceImpl(
            versions, assets, files, storage);

    @Test
    void downloadsHistoricalFormatUsingVersionMetadataWithoutChangingCurrentAsset() throws Exception {
        FileAsset current = currentJpeg();
        DocumentVersion original = originalPng();
        when(files.requireOwned(7L, 10L)).thenReturn(current);
        when(versions.selectOne(any())).thenReturn(original);
        when(storage.exists("original.png")).thenReturn(true);
        when(storage.read("original.png")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        var download = service.downloadVersion(7L, 10L, 1);

        assertEquals("image/png", download.contentType());
        assertEquals("photo-v1.png", download.downloadFileName());
        assertEquals("png", download.asset().getExtension());
        assertEquals("original.png", download.asset().getStorageKey());
        assertNotSame(current, download.asset());
        assertEquals("jpg", current.getExtension());
        try (var input = download.resource().getInputStream()) {
            assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
        }
    }

    @Test
    void appendedVersionSynchronizesCurrentContentAndFormat() {
        prepareAppend();

        var version = service.appendVersion(10L, 7L,
                new VersionWriter.VersionContent("converted.png", 25, "new-sha", "image/png", "png"),
                ChangeType.CONVERT, "转换", "PNG", 2);

        assertEquals(3, version.getVersionNo());
        assertEquals("image/png", version.getContentType());
        assertEquals("png", version.getExtension());
        var update = ArgumentCaptor.forClass(FileAsset.class);
        verify(assets).updateById(update.capture());
        assertEquals("converted.png", update.getValue().getStorageKey());
        assertEquals(25, update.getValue().getSizeBytes());
        assertEquals("new-sha", update.getValue().getSha256());
        assertEquals("image/png", update.getValue().getContentType());
        assertEquals("png", update.getValue().getExtension());
        assertEquals("photo.png", update.getValue().getDisplayName());
    }

    @Test
    void restoresFormatAlongWithHistoricalContent() {
        prepareAppend();
        when(files.requireOwned(7L, 10L)).thenReturn(currentJpeg());
        when(versions.selectOne(any())).thenReturn(originalPng());

        var restored = service.restore(7L, 10L, 1, 2);

        assertEquals(3, restored.getVersionNo());
        assertEquals("image/png", restored.getContentType());
        assertEquals("png", restored.getExtension());
        var inserted = ArgumentCaptor.forClass(DocumentVersion.class);
        verify(versions).insert(inserted.capture());
        assertEquals("original.png", inserted.getValue().getStorageKey());
        assertEquals("RESTORE", inserted.getValue().getChangeType());
        assertEquals(2, inserted.getValue().getParentVersion());
    }

    @Test
    void sameFormatCallersInheritCurrentFormat() {
        prepareAppend();

        var result = service.appendVersion(10L, 7L,
                new VersionWriter.VersionContent("edited.jpg", 15, "sha"),
                ChangeType.MANUAL_EDIT, null, null, 2);

        assertEquals("image/jpeg", result.getContentType());
        assertEquals("jpg", result.getExtension());
    }

    @Test
    void conflictDoesNotChangeCurrentContentOrInsertVersion() {
        when(assets.selectById(10L)).thenReturn(currentJpeg());
        when(assets.advanceVersion(10L, 1)).thenReturn(0);
        when(assets.selectLatestVersion(10L)).thenReturn(2);

        var error = assertThrows(BusinessException.class, () -> service.appendVersion(10L, 7L,
                new VersionWriter.VersionContent("converted.png", 3, "sha", "image/png", "png"),
                ChangeType.CONVERT, null, null, 1));

        assertEquals(409, error.getCode());
        verify(assets, never()).updateById(any(FileAsset.class));
        verify(versions, never()).insert(any(DocumentVersion.class));
    }

    private void prepareAppend() {
        when(assets.selectById(10L)).thenReturn(currentJpeg());
        when(assets.advanceVersion(10L, 2)).thenReturn(1);
        when(assets.selectLatestVersion(10L)).thenReturn(3);
        when(assets.updateById(any(FileAsset.class))).thenReturn(1);
    }

    private static FileAsset currentJpeg() {
        FileAsset asset = new FileAsset();
        asset.setId(10L);
        asset.setUserId(7L);
        asset.setDisplayName("photo.jpg");
        asset.setStatus("READY");
        asset.setStorageKey("current.jpg");
        asset.setContentType("image/jpeg");
        asset.setExtension("jpg");
        asset.setLatestVersion(2);
        return asset;
    }

    private static DocumentVersion originalPng() {
        DocumentVersion version = new DocumentVersion();
        version.setFileId(10L);
        version.setVersionNo(1);
        version.setStorageKey("original.png");
        version.setContentType("image/png");
        version.setExtension("png");
        version.setSizeBytes(3L);
        version.setSha256("original-sha");
        return version;
    }
}
