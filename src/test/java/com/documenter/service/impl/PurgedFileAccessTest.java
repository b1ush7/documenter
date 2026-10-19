package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.entity.FileAsset;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.FileAssetMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PurgedFileAccessTest {
    @Test
    void purgedFileIsUnavailableBeforeAnyContentReadButUnpurgedDeletedMetadataIsRetained() {
        var mapper = mock(FileAssetMapper.class);
        var storage = mock(com.documenter.service.FileStorage.class);
        var service = new FileServiceImpl(mapper, storage, new StorageProperties(), null, null, null);
        var asset = new FileAsset();
        asset.setId(1L); asset.setUserId(7L); asset.setStatus("DELETED");
        when(mapper.selectById(1L)).thenReturn(asset);
        assertSame(asset, service.requireOwned(7L, 1L));
        asset.setStoragePurgedTime(LocalDateTime.now());
        assertThrows(BusinessException.class, () -> service.requireOwned(7L, 1L));
        assertThrows(BusinessException.class, () -> service.download(7L, 1L));
        verifyNoInteractions(storage);
    }
}
