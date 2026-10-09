package com.documenter.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 基于文件内容识别真实类型（TODO 3.3「校验实际文件类型及大小」）。
 *
 * <p>为什么不能信任客户端声明的 Content-Type 或扩展名：两者都由客户端完全控制，
 * 把 .exe 改名成 .docx 再声明成 Office 文档即可绕过。因此这里只看字节内容。
 *
 * <p>首批支持 DOCX、XLSX、PDF、PNG、JPEG（TODO 第 2 节建议的首批格式）。
 */
public final class FileTypeDetector {

    /** 读取的最大字节数：ZIP 的中央目录结尾记录位于文件末尾，取 1MB 足够覆盖。 */
    public static final int PEEK_SIZE = 1024 * 1024;

    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] ZIP_EMPTY_MAGIC = {0x50, 0x4B, 0x05, 0x06};
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46}; // %PDF
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF_MAGIC = {0x47, 0x49, 0x46, 0x38};
    private static final byte[] BMP_MAGIC = {0x42, 0x4D};

    private FileTypeDetector() {
    }

    /**
     * 识别结果。
     *
     * @param contentType MIME 类型
     * @param extension   规范扩展名（小写，不带点）
     * @param supported   是否在首批支持格式内
     * @param message     不支持时的可读原因
     */
    public record DetectedType(String contentType, String extension, boolean supported, String message) {
    }

    public static DetectedType detect(byte[] head) {
        if (head == null || head.length < 4) {
            return unsupported("文件内容为空或过短，无法识别类型");
        }
        if (startsWith(head, PDF_MAGIC)) {
            return supported("application/pdf", "pdf");
        }
        if (startsWith(head, PNG_MAGIC)) {
            return supported("image/png", "png");
        }
        if (startsWith(head, JPEG_MAGIC)) {
            return supported("image/jpeg", "jpg");
        }
        if (startsWith(head, GIF_MAGIC)) {
            return supported("image/gif", "gif");
        }
        if (startsWith(head, BMP_MAGIC)) {
            return supported("image/bmp", "bmp");
        }
        if (startsWith(head, OLE2_MAGIC)) {
            // 旧版 DOC/XLS/PPT 是 OLE2 复合文档，与基于 ZIP 的 OOXML 完全不同
            return unsupported("检测到旧版 Office 二进制格式（.doc/.xls/.ppt），当前仅支持 .docx/.xlsx，请先另存为新格式");
        }
        if (startsWith(head, ZIP_MAGIC) || startsWith(head, ZIP_EMPTY_MAGIC)) {
            return detectOoxml(head);
        }
        return unsupported("无法识别的文件类型，仅支持 DOCX、XLSX、PDF、PNG、JPEG");
    }

    /** 从输入流读取头部并识别。调用方负责关闭流。 */
    public static DetectedType detect(InputStream inputStream) throws IOException {
        return detect(inputStream.readNBytes(PEEK_SIZE));
    }

    /**
     * 区分 DOCX 与 XLSX。
     *
     * <p>两者都是 ZIP 容器，差别在内部条目：DOCX 有 {@code word/document.xml}，
     * XLSX 有 {@code xl/workbook.xml}。因此必须扫描条目名，只看魔数无法区分。
     */
    private static DetectedType detectOoxml(byte[] content) {
        boolean hasWord = false;
        boolean hasExcel = false;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.equals("word/document.xml")) {
                    hasWord = true;
                } else if (name.equals("xl/workbook.xml")) {
                    hasExcel = true;
                }
                // 两个都找到就没必要继续扫了
                if (hasWord && hasExcel) {
                    break;
                }
            }
        } catch (IOException e) {
            return unsupported("压缩包结构损坏，无法识别为有效的 Office 文档");
        }
        if (hasWord) {
            return supported("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        }
        if (hasExcel) {
            return supported("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        }
        return unsupported("该压缩包不是有效的 DOCX 或 XLSX 文档");
    }

    private static DetectedType supported(String contentType, String extension) {
        return new DetectedType(contentType, extension, true, null);
    }

    private static DetectedType unsupported(String message) {
        return new DetectedType("application/octet-stream", "bin", false, message);
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** 供测试与调试使用的十六进制预览。 */
    public static String preview(byte[] head, int length) {
        int end = Math.min(head.length, length);
        StringBuilder builder = new StringBuilder(end * 3);
        for (int i = 0; i < end; i++) {
            builder.append(String.format("%02X ", head[i]));
        }
        return builder.toString().trim();
    }

    /** 把任意文件名规范化为「安全的展示名」，去掉路径分隔符。 */
    public static String sanitizeDisplayName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "未命名文件";
        }
        // 去掉客户端可能传入的目录部分，防止展示名里带出服务端路径结构
        String name = originalName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty()) {
            return "未命名文件";
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    /** 供日志使用的内容摘要（不含正文）。 */
    public static String describe(byte[] head) {
        return preview(head, 8) + " (" + new String(head, 0, Math.min(head.length, 4), StandardCharsets.ISO_8859_1)
                .replaceAll("[^\\x20-\\x7E]", ".") + ")";
    }
}
