package com.documenter.component;

import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.util.FileTypeDetector;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadContentInspectorTest {

    private final StorageProperties properties = new StorageProperties();
    private final UploadContentInspector inspector = new UploadContentInspector(properties);

    @Test
    void rejectsPdfOverConfiguredPageLimit() throws Exception {
        properties.getUpload().setMaxPdfPages(1);
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "two-pages.pdf",
                "application/pdf", pdf);

        BusinessException error = assertThrows(BusinessException.class,
                () -> inspector.inspect(file, supported("application/pdf", "pdf")));

        assertEquals(413, error.getCode());
    }

    @Test
    void rejectsImageOverConfiguredPixelLimit() throws Exception {
        properties.getUpload().setMaxImagePixels(99);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", output);
        MockMultipartFile file = new MockMultipartFile("file", "image.png",
                "image/png", output.toByteArray());

        BusinessException error = assertThrows(BusinessException.class,
                () -> inspector.inspect(file, supported("image/png", "png")));

        assertEquals(413, error.getCode());
    }

    @Test
    void rejectsOfficeArchiveOverConfiguredEntryLimit() throws Exception {
        properties.getUpload().setMaxArchiveEntries(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("types".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("document".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile("file", "document.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                output.toByteArray());

        BusinessException error = assertThrows(BusinessException.class,
                () -> inspector.inspect(file, supported(file.getContentType(), "docx")));

        assertEquals(413, error.getCode());
    }

    private static FileTypeDetector.DetectedType supported(String contentType, String extension) {
        return new FileTypeDetector.DetectedType(contentType, extension, true, null);
    }
}
