package com.documenter.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTypeDetectorTest {

    @Test
    void detectsDocxWhenDocumentEntryAppearsAfterFirstMegabyte() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/media/large.bin"));
            byte[] randomBytes = new byte[FileTypeDetector.PEEK_SIZE + 64 * 1024];
            new Random(7).nextBytes(randomBytes);
            zip.write(randomBytes);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        FileTypeDetector.DetectedType result = FileTypeDetector.detect(
                new ByteArrayInputStream(output.toByteArray()));

        assertTrue(result.supported());
        assertEquals("docx", result.extension());
    }

    @Test
    void sanitizesClientPathFromDisplayName() {
        assertEquals("report.docx", FileTypeDetector.sanitizeDisplayName("../folder/report.docx"));
        assertEquals("report.docx", FileTypeDetector.sanitizeDisplayName("C:\\temp\\report.docx"));
    }
}
