package com.documenter.component;

import com.documenter.configuration.PreviewProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentConverter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 基于 LibreOffice 命令行的文档转 PDF（TODO 3.3 页面预览、TODO 第 9 节的建议起点）。
 *
 * <p>为什么走命令行而不是 UNO/COM 桥接：命令行进程是<b>可隔离、可超时、可强杀</b>的。
 * TODO 第 9 节明确要求评估「转换进程隔离」，命令行天然满足；
 * 而进程内的 UNO 连接一旦卡住会拖死整个服务，且无法可靠中断。
 *
 * <p>三个必须处理的工程细节：
 * <ol>
 *   <li><b>独立 user profile</b>：并发调用同一个 profile 会让 LibreOffice 互相抢锁，
 *       表现为「只有一个转换能成功，其余静默失败」。因此每次转换用独立的
 *       {@code -env:UserInstallation} 目录。</li>
 *   <li><b>超时后强杀</b>：超时只返回错误而不 destroy 进程，会留下僵尸 soffice
 *       持续占用内存，多次之后机器就被拖垮。</li>
 *   <li><b>临时目录不主动删除</b>：被强杀的进程可能仍持有文件句柄，
 *       在 Windows 上删除会失败。清理交给既有的过期临时文件清理任务
 *       （{@code TemporaryFileCleaner}）。</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "res.preview.converter", havingValue = "libreoffice")
public class LibreOfficeDocumentConverter implements DocumentConverter {

    private static final String ENGINE = "libreoffice";

    /** Windows / Linux / macOS 上常见的安装位置，按优先级排列。 */
    private static final List<String> CANDIDATE_PATHS = List.of(
            "C:/Program Files/LibreOffice/program/soffice.exe",
            "C:/Program Files (x86)/LibreOffice/program/soffice.exe",
            "/usr/bin/soffice",
            "/usr/local/bin/soffice",
            "/opt/libreoffice/program/soffice",
            "/Applications/LibreOffice.app/Contents/MacOS/soffice");

    private final PreviewProperties properties;

    /** 解析出的可执行文件路径；为 null 表示不可用。 */
    private Path executable;

    public LibreOfficeDocumentConverter(PreviewProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        this.executable = resolveExecutable();
        if (executable == null) {
            log.warn("res.preview.converter=libreoffice 但未找到 soffice 可执行文件，"
                    + "DOCX/XLSX 页面预览将不可用。请检查 res.preview.libreoffice-path 配置。");
        } else {
            log.info("LibreOffice 转换器已就绪: {}", executable);
        }
    }

    @Override
    public String engineName() {
        return ENGINE;
    }

    @Override
    public boolean available() {
        return executable != null;
    }

    @Override
    public String configurationHint() {
        return "请安装 LibreOffice 并设置 res.preview.libreoffice-path "
                + "（或环境变量 PREVIEW_LIBREOFFICE_PATH）指向 soffice 可执行文件";
    }

    @Override
    public byte[] toPdf(byte[] source, String extension, String baseName) {
        if (!available()) {
            throw new BusinessException(503, "LibreOffice 未就绪，" + configurationHint());
        }
        if (source == null || source.length == 0) {
            throw new BusinessException(400, "待转换内容为空");
        }
        if (source.length > properties.getMaxSourceBytes()) {
            throw new BusinessException(413,
                    "文档过大，无法生成页面预览（上限 " + properties.getMaxSourceBytes() / 1024 / 1024 + "MB）");
        }

        Path workDirectory = null;
        try {
            // 独立目录承载源文件、user profile 与产物，互不干扰
            workDirectory = Files.createTempDirectory("doc-preview-");
            Path profileDirectory = Files.createDirectories(workDirectory.resolve("profile"));
            Path outputDirectory = Files.createDirectories(workDirectory.resolve("out"));

            String safeBaseName = sanitizeBaseName(baseName);
            Path sourceFile = workDirectory.resolve(safeBaseName + "." + extension);
            Files.write(sourceFile, source);

            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.add("-env:UserInstallation=" + profileDirectory.toUri());
            command.add("--headless");
            command.add("--norestore");
            command.add("--nolockcheck");
            command.add("--nodefault");
            command.add("--convert-to");
            command.add("pdf:writer_pdf_Export");
            command.add("--outdir");
            command.add(outputDirectory.toString());
            command.add(sourceFile.toString());

            runProcess(command, workDirectory);

            Path produced = outputDirectory.resolve(safeBaseName + ".pdf");
            if (!Files.isRegularFile(produced)) {
                throw new BusinessException(500, "转换未产生 PDF 产物，可能是文档内容不受支持");
            }
            return Files.readAllBytes(produced);
        } catch (IOException e) {
            log.warn("LibreOffice 转换 IO 失败", e);
            throw new BusinessException(500, "页面预览转换失败，请稍后重试");
        } finally {
            cleanupQuietly(workDirectory);
        }
    }

    /** 启动外部进程并等待，超时则强杀。 */
    private void runProcess(List<String> command, Path workDirectory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDirectory.toFile());
        // 合并错误流，避免 LibreOffice 大量警告写满管道缓冲区导致进程阻塞
        builder.redirectErrorStream(true);

        Process process = builder.start();
        boolean finished;
        try {
            finished = process.waitFor(properties.getConversionTimeout().toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "页面预览转换被中断");
        }

        if (!finished) {
            // 超时必须强杀：只返回错误而不销毁进程会留下僵尸 soffice
            process.destroyForcibly();
            log.warn("LibreOffice 转换超时（{}s），已强制终止进程",
                    properties.getConversionTimeout().toSeconds());
            throw new BusinessException(504,
                    "页面预览转换超时（超过 " + properties.getConversionTimeout().toSeconds() + " 秒）");
        }
        if (process.exitValue() != 0) {
            // 只读取有限长度的输出，避免异常时日志被灌爆
            String output = readLimited(process);
            log.warn("LibreOffice 转换失败, exitCode={}, output={}", process.exitValue(), output);
            throw new BusinessException(500, "页面预览转换失败，文档可能已损坏或格式不受支持");
        }
    }

    /** 读取进程输出，长度受限，仅用于日志。 */
    private static String readLimited(Process process) {
        try (var in = process.getInputStream()) {
            byte[] buffer = in.readNBytes(2048);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(无法读取转换进程输出)";
        }
    }

    /**
     * 清理临时目录。
     *
     * <p>失败只记录日志：被强杀的进程可能仍持有句柄，
     * 在 Windows 上删除会失败，这属于预期情况。
     */
    private static void cleanupQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 交给过期临时文件清理任务回收
                }
            });
        } catch (IOException e) {
            log.debug("预览临时目录清理失败（将由清理任务回收）: {}", directory, e);
        }
    }

    /** 解析可执行文件：优先显式配置，其次探测常见路径。 */
    private Path resolveExecutable() {
        String configured = properties.getLibreofficePath();
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            if (Files.isRegularFile(path)) {
                return path;
            }
            log.warn("res.preview.libreoffice-path 指向的文件不存在: {}", configured);
            return null;
        }
        Optional<Path> detected = CANDIDATE_PATHS.stream()
                .map(Path::of)
                .filter(Files::isRegularFile)
                .findFirst();
        return detected.orElse(null);
    }

    /**
     * 规范化基础文件名。
     *
     * <p>文件名会作为外部进程的参数，因此必须剥掉路径分隔符与空白，防止参数注入或越界写入。
     */
    private static String sanitizeBaseName(String baseName) {
        String value = baseName == null ? "" : baseName.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        if (value.isEmpty()) {
            value = "document";
        }
        return value.length() > 100 ? value.substring(0, 100) : value;
    }

    /** 供诊断使用。 */
    public String resolvedExecutable() {
        return executable == null ? null : executable.toString().toLowerCase(Locale.ROOT);
    }
}
