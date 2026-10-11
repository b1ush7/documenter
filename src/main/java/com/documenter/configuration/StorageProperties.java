package com.documenter.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 文件存储与上传策略（TODO 3.3）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "res.storage")
public class StorageProperties {

    /** 存储实现类型，目前只实现 local。 */
    private String type = "local";

    private Local local = new Local();

    /** 上传限制。 */
    private Upload upload = new Upload();

    /** PDF 原生文本提取限制。 */
    private Pdf pdf = new Pdf();

    /** XLSX 结构读取与编辑限制。 */
    private Xlsx xlsx = new Xlsx();

    @Getter
    @Setter
    public static class Local {
        /** 正式文件根目录，必须是应用运行账户可写的路径。 */
        private String root = "./data/storage";

        /** 转换/临时文件根目录，与正式目录隔离（TODO 3.3 要求隔离临时目录）。 */
        private String temp = "./data/tmp";

        /** 是否定期清理转换流程遗留的临时文件。 */
        private boolean tempCleanupEnabled = true;

        /** 临时文件保留时间。 */
        private Duration tempRetention = Duration.ofHours(24);

        /** 临时目录清理任务的执行间隔。 */
        private Duration tempCleanupInterval = Duration.ofHours(1);
    }

    @Getter
    @Setter
    public static class Upload {
        /** 单文件最大字节数，默认 50MB，与 spring.servlet.multipart 的上限保持一致。 */
        private long maxSizeBytes = 50L * 1024 * 1024;

        /** 同一用户单个自然日内的最大上传次数，防止刷存储。 */
        private int hourlyLimit = 60;

        /** 预留的上传限流窗口；当前实现按自然日计数。 */
        private Duration rateLimitWindow = Duration.ofHours(1);

        /** 单个 PDF 允许的最大页数。 */
        private int maxPdfPages = 200;

        /** 单张图片允许的最大总像素数（宽 × 高）。 */
        private long maxImagePixels = 40_000_000L;

        /** DOCX/XLSX ZIP 容器允许的最大条目数。 */
        private int maxArchiveEntries = 10_000;

        /** DOCX/XLSX 解压后的最大总字节数。 */
        private long maxArchiveExpandedSizeBytes = 200L * 1024 * 1024;

        /** DOCX/XLSX 解压总大小与上传文件大小的最大比值。 */
        private int maxArchiveCompressionRatio = 200;
    }

    @Getter
    @Setter
    public static class Pdf {
        /** 单页最多返回的 Unicode 字符数，超出时截断并显式标记。 */
        private int maxExtractedCharsPerPage = 100_000;

        /** 单个 PDF 接口响应最多返回的 Unicode 字符数。 */
        private int maxExtractedCharsTotal = 2_000_000;

        /** PDF 页面预览默认 DPI。 */
        private int previewDpi = 144;

        /** 调用方可请求的最大预览 DPI。 */
        private int maxPreviewDpi = 200;
    }

    @Getter
    @Setter
    public static class Xlsx {
        /** 单次结构读取最多返回的单元格数量，超出部分以 truncated 标记。 */
        private int maxReadCells = 100_000;

        /** 单次允许修改的单元格数量。 */
        private int maxEditOperations = 500;
    }
}
