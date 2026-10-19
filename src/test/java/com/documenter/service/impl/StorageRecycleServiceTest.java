package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.StorageRecycleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageRecycleServiceTest {
    @TempDir Path workspace;
    private final StorageProperties properties = new StorageProperties();
    private final StorageRecycleMapper mapper = mock(StorageRecycleMapper.class);
    private LocalFileStorage storage;
    private StorageRecycleService service;

    @BeforeEach
    void setup() {
        properties.getLocal().setRoot(workspace.resolve("storage").toString());
        properties.getLocal().setTemp(workspace.resolve("temp").toString());
        storage = new LocalFileStorage(properties);
        service = new StorageRecycleService(storage, properties, mapper);
        when(mapper.eligibleFiles(any(), anyInt())).thenReturn(List.of());
        when(mapper.protectedReference(anyString(), anyList())).thenReturn(0);
    }

    @Test
    void defaultPreviewLeavesBytesAndMetadataUntouchedButIncludesExpiredReferences() throws Exception {
        String key = oldBlob("candidate");
        when(mapper.eligibleFiles(any(), anyInt())).thenReturn(List.of(7L));
        when(mapper.protectedReference(key, List.of(7L))).thenReturn(0);
        var report = service.recycle(true);
        assertTrue(report.dryRun());
        assertEquals(1, report.expiredFiles());
        assertEquals(0, report.deletedBlobs());
        assertEquals("WOULD_DELETE", report.entries().getFirst().outcome());
        assertTrue(storage.exists(key));
        verify(mapper, never()).markPurged(anyList(), any(), any());
    }

    @Test
    void requiresBothSwitchesBeforeMutatingAnything() {
        assertThrows(BusinessException.class, () -> service.recycle(false));
        properties.getRecycle().setEnabled(true);
        assertThrows(BusinessException.class, () -> service.recycle(false));
        verifyNoInteractions(mapper);
    }

    @Test
    void removesOnlyOldUnreferencedManagedBlobsAndRechecksBeforeDeletion() throws Exception {
        enable();
        String orphan = oldBlob("orphan");
        String live = oldBlob("shared / current / version / template");
        String recent = storage.store("recent".getBytes(), "docx");
        Path unknown = storage.resolvePath("manual.txt");
        Files.writeString(unknown, "keep unknown layout");
        when(mapper.protectedReference(live, List.of())).thenReturn(1);
        var report = service.recycle(false);
        assertEquals(1, report.deletedBlobs());
        assertEquals(6, report.deletedBytes());
        assertFalse(storage.exists(orphan));
        assertTrue(storage.exists(live));
        assertTrue(storage.exists(recent));
        assertTrue(Files.exists(unknown));
        verify(mapper, times(2)).protectedReference(orphan, List.of());
    }

    @Test
    void newReferenceAtFinalCheckProtectsBlob() throws Exception {
        enable();
        String key = oldBlob("newly referenced");
        when(mapper.protectedReference(key, List.of())).thenReturn(0, 1);
        assertEquals(0, service.recycle(false).deletedBlobs());
        assertTrue(storage.exists(key));
    }

    @Test
    void databaseFailureOrNullReferenceResultNeverMeansUnreferenced() throws Exception {
        enable();
        String key = oldBlob("keep");
        when(mapper.protectedReference(key, List.of())).thenThrow(new IllegalStateException("db down"));
        assertThrows(IllegalStateException.class, () -> service.recycle(false));
        assertTrue(storage.exists(key));
        doReturn(null).when(mapper).protectedReference(key, List.of());
        assertThrows(IllegalStateException.class, () -> service.recycle(false));
        assertTrue(storage.exists(key));
    }

    @Test
    void writesTombstoneBeforeBlobScanAndRetriesAfterPhysicalRemoval() throws Exception {
        enable();
        String key = oldBlob("deleted owner");
        when(mapper.eligibleFiles(any(), anyInt())).thenReturn(List.of(7L));
        when(mapper.markPurged(eq(List.of(7L)), any(), any())).thenReturn(1);
        assertEquals(1, service.recycle(false).expiredFiles());
        var ordered = inOrder(mapper);
        ordered.verify(mapper).eligibleFiles(any(), anyInt());
        ordered.verify(mapper).markPurged(eq(List.of(7L)), any(), any());
        ordered.verify(mapper, times(2)).protectedReference(key, List.of());
        assertEquals(0, service.recycle(false).deletedBlobs());
    }

    @Test
    void batchBoundsPreviewAndActualDeletion() throws Exception {
        enable();
        properties.getRecycle().setBatchSize(1);
        oldBlob("one");
        oldBlob("two");
        assertEquals(1, service.recycle(true).entries().size());
        var report = service.recycle(false);
        assertTrue(report.truncated());
        assertEquals(1, report.deletedBlobs());
        assertEquals(1, service.recycle(false).deletedBlobs());
    }

    @Test
    void rejectsConcurrentRecycleAndOverlappingStorageDirectories() throws Exception {
        try (var channel = FileChannel.open(storage.resolvePath(".recycle.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(BusinessException.class, () -> service.recycle(true));
        }
        properties.getLocal().setRoot(storage.tempDirectory().toString());
        assertThrows(BusinessException.class, () -> service.recycle(true));
        verifyNoInteractions(mapper);
    }

    @Test
    void refusesUnsafeRetentionAndDoesNotRunDisabledScheduler() {
        service.scheduledRecycle();
        verifyNoInteractions(mapper);
        properties.getRecycle().setOrphanGrace(Duration.ofSeconds(10));
        assertThrows(BusinessException.class, () -> service.recycle(true));
        properties.getRecycle().setOrphanGrace(Duration.ofHours(24));
        properties.getRecycle().setDeletedRetention(Duration.ZERO);
        assertThrows(BusinessException.class, () -> service.recycle(true));
    }

    @Test
    void crashedWriteIsReclaimedButActiveWriterIsProtectedEvenWhenOld() throws Exception {
        enable();
        String pending = "2026/01/01/" + java.util.UUID.randomUUID() + ".docx";
        Path path = storage.resolvePath(pending);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "unfinished");
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        try (var writer = FileChannel.open(path, StandardOpenOption.WRITE); var lock = writer.lock()) {
            assertEquals(0, service.recycle(false).deletedBlobs());
            assertTrue(Files.exists(path));
        }
        assertEquals(1, service.recycle(false).deletedBlobs());
        assertFalse(Files.exists(path));
    }

    @Test
    void failedStorageWritePublishesNoPartialBlob() throws Exception {
        var broken = new java.io.InputStream() {
            private int reads;
            @Override public int read() throws java.io.IOException {
                if (++reads > 12) throw new java.io.IOException("source disconnected");
                return 1;
            }
        };
        assertThrows(BusinessException.class, () -> storage.store(broken, "docx"));
        try (var paths = Files.walk(storage.resolvePath("."))) {
            assertEquals(0, paths.filter(Files::isRegularFile).count());
        }
    }

    private void enable() {
        properties.getRecycle().setEnabled(true);
        properties.getRecycle().setDryRun(false);
    }

    private String oldBlob(String content) throws Exception {
        String key = storage.store(content.getBytes(java.nio.charset.StandardCharsets.UTF_8), "docx");
        Files.setLastModifiedTime(storage.resolvePath(key), FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        return key;
    }
}
