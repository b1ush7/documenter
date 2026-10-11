package com.documenter.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件类型识别与文件名清洗测试（TODO 3.3「校验实际文件类型及大小」）。
 *
 * <p>这是纯函数，不需要数据库或 Redis，因此可以用真实构造的字节内容离线验证。
 * 重点验证「不信任客户端」这条原则：无论扩展名与 Content-Type 怎么伪装，
 * 判定都只依据字节内容。
 */
class FileTypeDetectorTest {

    @Test
    @DisplayName("识别 PDF")
    void detectPdf() {
        byte[] head = "%PDF-1.7\n%âãÏÓ\n1 0 obj\n".getBytes(StandardCharsets.ISO_8859_1);

        FileTypeDetector.DetectedType type = FileTypeDetector.detect(head);

        assertTrue(type.supported());
        assertEquals("pdf", type.extension());
        assertEquals("application/pdf", type.contentType());
    }

    @Test
    @DisplayName("识别 PNG")
    void detectPng() {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

        FileTypeDetector.DetectedType type = FileTypeDetector.detect(head);

        assertTrue(type.supported());
        assertEquals("png", type.extension());
        assertEquals("image/png", type.contentType());
    }

    @Test
    @DisplayName("识别 JPEG")
    void detectJpeg() {
        byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

        FileTypeDetector.DetectedType type = FileTypeDetector.detect(head);

        assertTrue(type.supported());
        assertEquals("jpg", type.extension());
        assertEquals("image/jpeg", type.contentType());
    }

    @Test
    @DisplayName("识别 DOCX：ZIP 容器内含 word/document.xml")
    void detectDocx() throws IOException {
        byte[] content = zipOf("word/document.xml", "<w:document/>");

        FileTypeDetector.DetectedType type = FileTypeDetector.detect(content);

        assertTrue(type.supported());
        assertEquals("docx", type.extension());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                type.contentType());
    }

    @Test
    @DisplayName("识别 XLSX：ZIP 容器内含 xl/workbook.xml")
    void detectXlsx() throws IOException {
        byte[] content = zipOf("xl/workbook.xml", "<workbook/>");

        FileTypeDetector.DetectedType type = FileTypeDetector.detect(content);

        assertTrue(type.supported());
        assertEquals("xlsx", type.extension());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                type.contentType());
    }

    @Test
    @DisplayName("DOCX 与 XLSX 只能靠内部条目区分，不能只看 ZIP 魔数")
    void docxAndXlsxShareZipMagic() throws IOException {
        byte[] docx = zipOf("word/document.xml", "<w:document/>");
        byte[] xlsx = zipOf("xl/workbook.xml", "<workbook/>");

        // 前四字节相同，说明仅凭魔数无法区分
        assertEquals(docx[0], xlsx[0]);
        assertEquals(docx[1], xlsx[1]);
        // 但识别结果必须不同
        assertNotEquals(FileTypeDetector.detect(docx).extension(), FileTypeDetector.detect(xlsx).extension());
    }

    @Test
    @DisplayName("旧版 Office 二进制格式被明确拒绝，并给出可操作提示")
    void rejectLegacyOle2Formats() {
        // OLE2 复合文档头：.doc/.xls/.ppt 都是这个签名
        byte[] head = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1,
                0x1A, (byte) 0xE1, 0, 0, 0, 0};

        FileTypeDetector.DetectedType type = FileTypeDetector.detect(head);

        assertFalse(type.supported());
        // 提示必须能指导用户下一步怎么做，而不是含糊的「无法识别」
        assertTrue(type.message().contains(".doc") || type.message().contains("另存"),
                "提示应指出是旧格式并给出处理建议，实际为: " + type.message());
    }

    @Test
    @DisplayName("普通 ZIP 不被误判为 Office 文档")
    void rejectPlainZip() throws IOException {
        byte[] content = zipOf("readme.txt", "hello");

        FileTypeDetector.DetectedType type = FileTypeDetector.detect(content);

        assertFalse(type.supported());
        assertTrue(type.message().contains("DOCX") || type.message().contains("XLSX"));
    }

    @Test
    @DisplayName("可执行文件伪装成 docx 也会被拒绝")
    void rejectExecutableDisguisedAsDocx() {
        // Windows PE 文件头 MZ
        byte[] head = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00};

        FileTypeDetector.DetectedType type = FileTypeDetector.detect(head);

        // 判定只看内容，因此与文件名无关，直接拒绝
        assertFalse(type.supported());
        assertFalse(type.extension().equals("docx"));
    }

    @Test
    @DisplayName("内容为空或过短时拒绝")
    void rejectEmptyOrTooShort() {
        assertFalse(FileTypeDetector.detect(new byte[0]).supported());
        assertFalse(FileTypeDetector.detect(new byte[]{1, 2}).supported());
        // 显式转型：detect 有 byte[] 与 InputStream 两个重载，传 null 会产生歧义
        assertFalse(FileTypeDetector.detect((byte[]) null).supported());
    }

    @Test
    @DisplayName("损坏的 ZIP 结构不会抛异常，而是返回不支持")
    void corruptZipIsRejectedGracefully() {
        byte[] head = {'P', 'K', 0x03, 0x04, 'g', 'a', 'r', 'b', 'a', 'g', 'e', 0x00};

        FileTypeDetector.DetectedType type = FileTypeDetector.detect(head);

        assertFalse(type.supported());
    }

    @Test
    @DisplayName("从输入流识别，供上传流程直接使用")
    void detectFromInputStream() throws IOException {
        byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1);

        FileTypeDetector.DetectedType type =
                FileTypeDetector.detect(new ByteArrayInputStream(head));

        assertTrue(type.supported());
        assertEquals("pdf", type.extension());
    }

    @Test
    @DisplayName("展示名清洗：剥离客户端可能传入的目录结构")
    void sanitizeDisplayNameStripsPaths() {
        assertEquals("a.docx", FileTypeDetector.sanitizeDisplayName("a.docx"));
        assertEquals("a.docx", FileTypeDetector.sanitizeDisplayName("C:\\Users\\x\\a.docx"));
        assertEquals("a.docx", FileTypeDetector.sanitizeDisplayName("../../etc/a.docx"));
        assertEquals("a.docx", FileTypeDetector.sanitizeDisplayName("/var/tmp/a.docx"));
        assertEquals("未命名文件", FileTypeDetector.sanitizeDisplayName(null));
        assertEquals("未命名文件", FileTypeDetector.sanitizeDisplayName("   "));
        assertEquals("未命名文件", FileTypeDetector.sanitizeDisplayName("///"));
    }

    @Test
    @DisplayName("超长文件名被截断，避免超出数据库列宽")
    void sanitizeDisplayNameTruncates() {
        String longName = "x".repeat(500) + ".docx";

        String sanitized = FileTypeDetector.sanitizeDisplayName(longName);

        assertEquals(200, sanitized.length());
    }

    /** 构造一个最小 ZIP 容器，用于模拟 OOXML 文件的内部结构。 */
    private static byte[] zipOf(String entryName, String content) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    @Test
    @DisplayName("识别结果与文件落盘无关，可对超长内容只读头部")
    void detectWorksOnPartialContent() throws IOException {
        // 模拟大文件：只取前 1MB 用于识别
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] full = buffer.toByteArray();
        byte[] headOnly = new byte[Math.min(full.length, FileTypeDetector.PEEK_SIZE)];
        System.arraycopy(full, 0, headOnly, 0, headOnly.length);

        FileTypeDetector.DetectedType type = FileTypeDetector.detect(headOnly);

        assertTrue(type.supported());
        assertEquals("docx", type.extension());
    }

    @Test
    @DisplayName("PEEK_SIZE 足够大以容纳 ZIP 中央目录（依赖该值识别 Office 文档）")
    void peekSizeIsLargeEnough(@TempDir Path tempDir) throws IOException {
        // ZIP 的中央目录结尾记录位于文件末尾，PEEK_SIZE 必须显著大于 64KB
        assertTrue(FileTypeDetector.PEEK_SIZE >= 128 * 1024,
                "PEEK_SIZE 必须足够大，否则大体积 Office 文档会被误判");

        // 顺带确认临时目录可用（本测试不写项目目录）
        Path file = tempDir.resolve("probe.bin");
        Files.write(file, new byte[]{'P', 'K', 0x03, 0x04});
        assertTrue(Files.exists(file));
    }
}
