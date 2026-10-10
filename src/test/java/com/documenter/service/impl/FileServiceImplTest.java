package com.documenter.service.impl;

import com.documenter.component.InitialVersionWriter;
import com.documenter.component.UploadRateLimiter;
import com.documenter.configuration.StorageProperties;
import com.documenter.entity.FileAsset;
import com.documenter.enums.ChangeType;
import com.documenter.mapper.FileAssetMapper;
import com.documenter.service.FileStorage;
import com.documenter.util.Digests;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileServiceImplTest {

    @Test
    void savesGeneratedDocxAsOwnedFileAndInitialVersion() {
        FileAssetMapper mapper = mock(FileAssetMapper.class);
        FileStorage storage = mock(FileStorage.class);
        InitialVersionWriter initialVersionWriter = mock(InitialVersionWriter.class);
        byte[] content = {1, 2, 3, 4};
        when(storage.store(content, "docx")).thenReturn("memory/generated.docx");
        doAnswer(invocation -> {
            invocation.<FileAsset>getArgument(0).setId(23L);
            return 1;
        }).when(mapper).insert(any(FileAsset.class));

        var result = service(mapper, storage, initialVersionWriter)
                .createGeneratedDocx(7L, "folder/report", content, "按大纲生成");

        assertEquals(23L, result.getId());
        assertEquals("report.docx", result.getDisplayName());
        assertEquals("GENERATED", result.getSource());
        assertEquals(1, result.getLatestVersion());
        ArgumentCaptor<FileAsset> assetCaptor = ArgumentCaptor.forClass(FileAsset.class);
        verify(mapper).insert(assetCaptor.capture());
        FileAsset asset = assetCaptor.getValue();
        assertEquals(7L, asset.getUserId());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                asset.getContentType());
        assertEquals(Digests.sha256Hex(content), asset.getSha256());
        verify(initialVersionWriter).createInitialVersion(asset, 7L, ChangeType.MANUAL_EDIT,
                "按大纲生成", "本地结构化生成 DOCX");
    }

    @Test
    void removesStoredContentWhenGeneratedFileMetadataFails() {
        FileAssetMapper mapper = mock(FileAssetMapper.class);
        FileStorage storage = mock(FileStorage.class);
        InitialVersionWriter initialVersionWriter = mock(InitialVersionWriter.class);
        byte[] content = {1, 2, 3};
        when(storage.store(content, "docx")).thenReturn("memory/orphan.docx");
        when(storage.delete("memory/orphan.docx")).thenReturn(true);
        doThrow(new IllegalStateException("database unavailable"))
                .when(mapper).insert(any(FileAsset.class));

        assertThrows(IllegalStateException.class, () -> service(mapper, storage, initialVersionWriter)
                .createGeneratedDocx(7L, "report.docx", content, null));

        verify(storage).delete("memory/orphan.docx");
    }

    private static FileServiceImpl service(FileAssetMapper mapper, FileStorage storage,
                                           InitialVersionWriter initialVersionWriter) {
        StorageProperties properties = new StorageProperties();
        properties.getUpload().setMaxSizeBytes(1024 * 1024);
        return new FileServiceImpl(mapper, storage, properties,
                mock(UploadRateLimiter.class), initialVersionWriter);
    }
}
