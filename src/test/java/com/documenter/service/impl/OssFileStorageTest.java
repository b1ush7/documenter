package com.documenter.service.impl;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.models.*;
import com.documenter.component.TemporaryFileCleaner;
import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.StorageRecycleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OssFileStorageTest {
    private static final String KEY = "2026/10/18/12345678-1234-1234-1234-123456789abc.docx";
    @TempDir Path workspace;
    private final OSSClient client = mock(OSSClient.class);
    private final StorageProperties properties = new StorageProperties();
    private OssFileStorage storage;

    @BeforeEach
    void setup() {
        properties.setType("oss");
        properties.getOss().setBucket("private-documents");
        properties.getOss().setPrefix("/app/files/");
        properties.getLocal().setRoot(workspace.resolve("unused-formal-storage").toString());
        properties.getLocal().setTemp(workspace.resolve("temp").toString());
        storage = new OssFileStorage(properties, client);
    }

    @Test
    void uploadStagesExactBytesWithoutDoubleHashingAndCleansCopy() throws Exception {
        byte[] bytes = "document content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(bytes) {
            @Override public void close() { closed.set(true); }
        };
        var digest = MessageDigest.getInstance("SHA-256");
        doAnswer(call -> {
            PutObjectRequest request = call.getArgument(0);
            Path path = call.getArgument(1);
            assertEquals("private-documents", request.bucket());
            assertTrue(request.key().startsWith("app/files/"));
            assertTrue(request.forbidOverwrite());
            assertArrayEquals(bytes, Files.readAllBytes(path));
            // 模拟 SDK 多次读取上传副本，不应再次读取/累计业务摘要流。
            assertArrayEquals(bytes, Files.readAllBytes(path));
            return PutObjectResult.newBuilder().build();
        }).when(client).putObjectFromFile(any(PutObjectRequest.class), any(Path.class));
        String key = storage.store(new DigestInputStream(input, digest), ".DOCX");
        assertTrue(key.matches("\\d{4}/\\d{2}/\\d{2}/[a-f0-9-]{36}\\.docx"));
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), digest.digest());
        assertFalse(closed.get(), "调用方拥有输入流");
        assertFalse(Files.exists(Path.of(properties.getLocal().getRoot())));
        assertTempEmpty();
    }

    @Test
    void sdkAndInputFailuresCleanStagedFilesAndHideProviderDetails() throws Exception {
        when(client.putObjectFromFile(any(PutObjectRequest.class), any(Path.class)))
                .thenThrow(new IllegalStateException("secret-token-must-not-escape"));
        var error = assertThrows(BusinessException.class, () -> storage.store(new byte[]{1}, "pdf"));
        assertEquals(503, error.getCode());
        assertFalse(error.getMessage().contains("secret-token"));
        assertTempEmpty();
        reset(client);
        assertThrows(BusinessException.class, () -> storage.store(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("broken input"); }
        }, "pdf"));
        verifyNoInteractions(client);
        assertTempEmpty();
    }

    @Test
    void readPreservesStreamOwnershipAndUsesConfiguredPrefix() throws Exception {
        var closed = new AtomicBoolean();
        var body = new ByteArrayInputStream(new byte[]{1, 2}) {
            @Override public void close() { closed.set(true); }
        };
        when(client.getObject(any(GetObjectRequest.class)))
                .thenReturn(GetObjectResult.newBuilder().innerBody(body).build());
        try (var input = storage.read(KEY)) {
            assertArrayEquals(new byte[]{1, 2}, input.readAllBytes());
        }
        assertTrue(closed.get());
        verify(client).getObject(argThat((GetObjectRequest request) ->
                request.bucket().equals("private-documents") && request.key().equals("app/files/" + KEY)));
    }

    @Test
    void missingObjectIs404ButAuthenticationAndNetworkErrorsAre503() {
        var missing = ServiceException.newBuilder().statusCode(404)
                .errorFields(Map.of("Code", "NoSuchKey")).build();
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(missing);
        assertEquals(404, assertThrows(BusinessException.class, () -> storage.read(KEY)).getCode());
        var denied = ServiceException.newBuilder().statusCode(403)
                .errorFields(Map.of("Code", "AccessDenied")).build();
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(denied);
        assertEquals(503, assertThrows(BusinessException.class, () -> storage.read(KEY)).getCode());
        when(client.doesObjectExist(anyString(), anyString())).thenThrow(denied);
        assertEquals(503, assertThrows(BusinessException.class, () -> storage.exists(KEY)).getCode());
    }

    @Test
    void deleteOnlyTargetsRequestedObjectAndFailureReturnsFalse() {
        assertFalse(storage.delete(KEY));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
        when(client.doesObjectExist("private-documents", "app/files/" + KEY)).thenReturn(true);
        assertTrue(storage.exists(KEY));
        assertTrue(storage.delete(KEY));
        verify(client).deleteObject(argThat((DeleteObjectRequest request) ->
                request.bucket().equals("private-documents") && request.key().equals("app/files/" + KEY)));
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(new IllegalStateException("offline"));
        assertFalse(storage.delete(KEY));
    }

    @Test
    void malformedKeysCannotReadOrDeleteOtherPrefixes() {
        for (String key : new String[]{"../other/file", "/" + KEY, "app/files/" + KEY,
                "https://example.com/file", KEY + "/..", ""}) {
            assertFalse(storage.exists(key));
            assertEquals(400, assertThrows(BusinessException.class, () -> storage.read(key)).getCode());
            assertEquals(400, assertThrows(BusinessException.class, () -> storage.delete(key)).getCode());
        }
        verifyNoInteractions(client);
        assertThrows(UnsupportedOperationException.class, () -> storage.resolvePath(KEY));
        properties.getOss().setPrefix("../other");
        assertThrows(IllegalStateException.class, () -> new OssFileStorage(properties, client));
    }

    @Test
    void ossModeRejectsLocalPermanentRecyclingButStillCleansTemporaryFiles() throws Exception {
        var mapper = mock(StorageRecycleMapper.class);
        var recycle = new StorageRecycleService(storage, properties, mapper);
        properties.getRecycle().setEnabled(true);
        properties.getRecycle().setDryRun(false);
        recycle.scheduledRecycle();
        assertEquals(409, assertThrows(BusinessException.class, () -> recycle.recycle(true)).getCode());
        assertEquals(409, assertThrows(BusinessException.class, () -> recycle.recycle(false)).getCode());
        verifyNoInteractions(mapper, client);
        Path old = storage.tempDirectory().resolve("expired.tmp");
        Files.write(old, new byte[]{1});
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        new TemporaryFileCleaner(storage, properties).cleanExpiredFiles();
        assertFalse(Files.exists(old));
    }

    private void assertTempEmpty() throws IOException {
        try (var paths = Files.list(storage.tempDirectory())) {
            assertEquals(0, paths.count());
        }
    }
}
