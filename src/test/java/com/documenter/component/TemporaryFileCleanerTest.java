package com.documenter.component;

import com.documenter.configuration.StorageProperties;
import com.documenter.service.impl.LocalFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporaryFileCleanerTest {

    @TempDir
    Path workspace;

    @Test
    void deletesOnlyExpiredRegularFiles() throws Exception {
        StorageProperties properties = new StorageProperties();
        properties.getLocal().setRoot(workspace.resolve("storage").toString());
        properties.getLocal().setTemp(workspace.resolve("temp").toString());
        properties.getLocal().setTempRetention(Duration.ofHours(1));

        LocalFileStorage storage = new LocalFileStorage(properties);
        Path expired = storage.tempDirectory().resolve("old/work.bin");
        Path fresh = storage.tempDirectory().resolve("new/work.bin");
        Files.createDirectories(expired.getParent());
        Files.createDirectories(fresh.getParent());
        Files.writeString(expired, "old");
        Files.writeString(fresh, "fresh");
        Files.setLastModifiedTime(expired, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        new TemporaryFileCleaner(storage, properties).cleanExpiredFiles();

        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(fresh));
    }
}
