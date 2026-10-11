package com.documenter.component;

import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import com.documenter.util.FileTypeDetector;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 对已识别类型的上传内容执行不会生成产物的安全检查。
 *
 * <p>PDF 只读取页树，图片只读取尺寸元数据，OOXML 以流式方式统计 ZIP 条目和解压字节数；
 * 检查过程不会把压缩包展开到磁盘，也不会使用客户端文件名拼接路径。
 */
@Component
public class UploadContentInspector {

    private static final int BUFFER_SIZE = 8192;

    private final StorageProperties properties;

    public UploadContentInspector(StorageProperties properties) {
        this.properties = properties;
    }

    public void inspect(MultipartFile file, FileTypeDetector.DetectedType type) {
        String extension = type.extension();
        switch (extension) {
            case "pdf" -> inspectPdf(file);
            case "png", "jpg", "gif", "bmp" -> inspectImage(file);
            case "docx", "xlsx" -> inspectArchive(file);
            default -> {
                // 类型白名单由 FileTypeDetector 负责；这里没有额外的结构限制。
            }
        }
    }

    private void inspectPdf(MultipartFile file) {
        int maxPages = properties.getUpload().getMaxPdfPages();
        try (InputStream input = file.getInputStream();
             RandomAccessReadBuffer source = new RandomAccessReadBuffer(input);
             PDDocument document = Loader.loadPDF(source)) {
            int pages = document.getNumberOfPages();
            if (pages < 1) {
                throw new BusinessException(422, "PDF 不包含可读取页面");
            }
            if (maxPages > 0 && pages > maxPages) {
                throw new BusinessException(413,
                        "PDF 页数超过限制（最大 " + maxPages + " 页）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(422, "PDF 文件无法解析或内容已损坏");
        }
    }

    private void inspectImage(MultipartFile file) {
        long maxPixels = properties.getUpload().getMaxImagePixels();
        try (InputStream input = file.getInputStream();
             ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw new BusinessException(422, "图片文件无法解析或内容已损坏");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new BusinessException(422, "图片文件无法解析或内容已损坏");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1) {
                    throw new BusinessException(422, "图片尺寸无效");
                }
                long pixels = (long) width * height;
                if (maxPixels > 0 && pixels > maxPixels) {
                    throw new BusinessException(413,
                            "图片像素超过限制（最大 " + maxPixels + " 像素）");
                }
            } finally {
                reader.dispose();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(422, "图片文件无法解析或内容已损坏");
        }
    }

    private void inspectArchive(MultipartFile file) {
        StorageProperties.Upload limits = properties.getUpload();
        int maxEntries = limits.getMaxArchiveEntries();
        long maxExpanded = limits.getMaxArchiveExpandedSizeBytes();
        int maxRatio = limits.getMaxArchiveCompressionRatio();
        long compressedSize = file.getSize();

        int entries = 0;
        long expanded = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = file.getInputStream();
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (maxEntries > 0 && entries > maxEntries) {
                    throw new BusinessException(413,
                            "Office 文档压缩包条目过多（最大 " + maxEntries + " 个）");
                }
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    if (maxExpanded > 0 && expanded > maxExpanded) {
                        throw new BusinessException(413,
                                "Office 文档解压后超过大小限制");
                    }
                    if (maxRatio > 0 && compressedSize > 0
                            && expanded > compressedSize * (long) maxRatio) {
                        throw new BusinessException(413,
                                "Office 文档压缩比异常，已拒绝上传");
                    }
                }
                zip.closeEntry();
            }
            if (entries == 0) {
                throw new BusinessException(422, "Office 文档压缩包为空或已损坏");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(422, "Office 文档压缩包无法解析或内容已损坏");
        }
    }
}
