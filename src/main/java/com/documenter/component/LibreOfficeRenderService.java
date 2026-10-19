package com.documenter.component;

import com.documenter.configuration.PreviewProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.OfficeRenderService;
import com.documenter.vo.DocumentRenderManifest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 标准 LibreOffice 独立进程转换 + PDFBox 页面渲染，每个请求隔离用户配置。 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(name = "res.preview.office-backend", havingValue = "libreoffice")
public class LibreOfficeRenderService implements OfficeRenderService {
    private static final Set<String> EXTENSIONS = Set.of("docx", "xlsx");
    private final PreviewProperties properties;
    private final Path executable;
    private final Path python;

    public LibreOfficeRenderService(PreviewProperties properties) {
        this.properties = properties;
        this.executable = resolveExecutable(properties.getOfficeExecutable());
        String configuredPython = properties.getOfficePython();
        Path candidate = configuredPython != null && !configuredPython.isBlank() ? Path.of(configuredPython)
                : executable == null ? null : executable.resolveSibling(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "python.exe" : "python3");
        this.python = candidate != null && Files.isRegularFile(candidate) ? candidate.toAbsolutePath().normalize() : null;
    }

    @Override public String backendName() { return "libreoffice"; }
    @Override public boolean available() { return executable != null && python != null; }
    @Override public String configurationHint() {
        return "请设置 res.preview.office-backend=libreoffice、res.preview.office-executable=soffice.com/soffice，及包含 UNO 的 res.preview.office-python";
    }

    @Override public Path toPdf(Path source, String extension, Path outputDir) {
        if (!available()) throw new BusinessException(503, "LibreOffice 不可用，" + configurationHint());
        String type = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(type)) throw new BusinessException(422, "Office 转换不支持该文档格式");
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) throw new BusinessException(422, "Office 源文件不可读取");
        Path profile = null;
        try {
            if (Files.size(source) > properties.getOfficeMaxInputBytes()) throw new BusinessException(413, "Office 文档超过转换大小限制");
            try (var archive = new java.util.zip.ZipFile(source.toFile())) {
                if (archive.getEntry("[Content_Types].xml") == null || archive.getEntry(type.equals("docx") ? "word/document.xml" : "xl/workbook.xml") == null) {
                    throw new BusinessException(422, "Office 文档结构无效");
                }
            } catch (java.util.zip.ZipException invalid) { throw new BusinessException(422, "Office 文档结构无效"); }
            if (properties.getOfficeTimeout() == null || properties.getOfficeTimeout().isNegative() || properties.getOfficeTimeout().isZero()) {
                throw new BusinessException(500, "Office 超时配置无效");
            }
            Files.createDirectories(outputDir);
            Path job = Files.createTempDirectory(outputDir.toAbsolutePath(), "office-");
            profile = Files.createTempDirectory(outputDir.toAbsolutePath(), "profile-");
            writeProfile(profile);
            Path helper = job.resolve("office_convert.py");
            try (var script = getClass().getResourceAsStream("/office/office_convert.py")) {
                if (script == null) throw new IOException("Office conversion helper missing");
                Files.copy(script, helper);
            }
            List<String> command = List.of(python.toString(), helper.toString(), "--office", executable.toString(),
                    "--source", source.toAbsolutePath().toString(), "--target", job.resolve("document.pdf").toString(),
                    "--profile", profile.toString(), "--timeout", Long.toString(Math.max(1, properties.getOfficeTimeout().toSeconds() - 5)));
            run(command, job);
            // 不信任进程退出码：LibreOffice 可能在未产出文件时仍返回 0。
            List<Path> pdfs;
            try (var files = Files.list(job)) {
                pdfs = files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).toList();
            }
            if (pdfs.size() != 1 || Files.size(pdfs.get(0)) == 0) throw new BusinessException(422, "Office 文档未能转换为 PDF，可能已损坏或受密码保护");
            return pdfs.get(0);
        } catch (IOException failure) {
            log.warn("LibreOffice 转换文件操作失败", failure);
            throw new BusinessException(500, "Office 转换文件操作失败");
        } finally { removeProfile(profile); }
    }

    @Override public DocumentRenderManifest render(Path source, String extension, Path outputDir, List<Integer> pages, int dpi) {
        if (dpi < 1 || dpi > properties.getMaxDpi()) throw new BusinessException(400, "Office 渲染 DPI 超过限制");
        int pageLimit = properties.getCompatibilityMaxPages();
        if (pageLimit < 1) throw new BusinessException(500, "Office 渲染页数配置无效");
        if (pages != null && (pages.size() > pageLimit || pages.stream().anyMatch(page -> page == null || page < 1))) {
            throw new BusinessException(400, "Office 渲染页码或页数超过限制");
        }
        Path pdf = toPdf(source, extension, outputDir);
        try (var document = Loader.loadPDF(pdf.toFile())) {
            var selected = new LinkedHashSet<Integer>();
            if (pages == null || pages.isEmpty()) for (int page = 1; page <= Math.min(document.getNumberOfPages(), pageLimit); page++) selected.add(page);
            else selected.addAll(pages);
            var renderer = new PDFRenderer(document);
            var images = new ArrayList<DocumentRenderManifest.Page>();
            for (int number : selected) {
                if (number > document.getNumberOfPages()) continue;
                var page = document.getPage(number - 1);
                double width = Math.ceil(page.getCropBox().getWidth() * dpi / 72.0);
                double height = Math.ceil(page.getCropBox().getHeight() * dpi / 72.0);
                if (!Double.isFinite(width * height) || width < 1 || height < 1 || width * height > properties.getMaxPagePixels()) {
                    throw new BusinessException(413, "Office 页面预览像素超过限制");
                }
                var image = renderer.renderImageWithDPI(number - 1, dpi, ImageType.RGB);
                try {
                    Path target = outputDir.resolve("page-" + number + ".png").toAbsolutePath();
                    if (!ImageIO.write(image, "png", target.toFile())) throw new IOException("PNG encoder unavailable");
                    images.add(new DocumentRenderManifest.Page(number, target.toString(), image.getWidth(), image.getHeight(), Files.size(target)));
                } finally { image.flush(); }
            }
            return new DocumentRenderManifest(backendName(), document.getNumberOfPages(), images, OfficeFontDiagnostics.missingFonts(source));
        } catch (IOException failure) {
            log.warn("LibreOffice PDF 页面渲染失败", failure);
            throw new BusinessException(422, "转换后的 Office 文档无法渲染");
        }
    }

    private void run(List<String> command, Path directory) {
        Process process;
        try { process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start(); }
        catch (IOException failure) { throw new BusinessException(503, "无法启动 LibreOffice，" + configurationHint()); }
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var output = readers.submit(() -> drain(process.getInputStream()));
            if (!process.waitFor(properties.getOfficeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                killOwnedProcess(process);
                throw new BusinessException(504, "Office 文档转换超时");
            }
            String diagnostic = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                log.warn("LibreOffice 退出异常, exit={}, diagnostic={}", process.exitValue(), diagnostic);
                throw new BusinessException(process.exitValue() == 3 ? 503 : process.exitValue() == 4 ? 504 : 422,
                        process.exitValue() == 3 ? "LibreOffice UNO 运行环境不可用" : "Office 文档转换失败");
            }
        } catch (InterruptedException interrupted) {
            killOwnedProcess(process); Thread.currentThread().interrupt();
            throw new BusinessException(500, "Office 文档转换已中断");
        } catch (ExecutionException | TimeoutException failure) {
            killOwnedProcess(process); throw new BusinessException(500, "Office 渲染进程输出异常");
        } finally { readers.shutdownNow(); }
    }

    private static String drain(InputStream input) throws IOException {
        try (input; var captured = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) if (captured.size() < 65536) captured.write(buffer, 0, Math.min(count, 65536 - captured.size()));
            return captured.toString(StandardCharsets.UTF_8);
        }
    }
    private static void killOwnedProcess(Process process) {
        var children = process.descendants().toList();
        for (int i = children.size() - 1; i >= 0; i--) if (children.get(i).isAlive()) children.get(i).destroyForcibly();
        if (process.isAlive()) process.destroyForcibly();
        try { process.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private static void writeProfile(Path profile) throws IOException {
        Path user = Files.createDirectories(profile.resolve("user"));
        Files.writeString(user.resolve("registrymodifications.xcu"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <oor:items xmlns:oor="http://openoffice.org/2001/registry">
                  <item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item>
                  <item oor:path="/org.openoffice.Office.Writer/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>2</value></prop></item>
                  <item oor:path="/org.openoffice.Office.Calc/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>0</value></prop></item>
                </oor:items>
                """);
    }
    private static void removeProfile(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failure) { log.warn("Office 临时配置稍后由临时目录清理回收, path={}", root); }
    }
    private static Path resolveExecutable(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize(); return Files.isRegularFile(path) ? path : null;
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("data/tools/libreoffice/runtime/program/soffice.com"));
        for (String directory : List.of("C:/Program Files/LibreOffice/program", "C:/Program Files (x86)/LibreOffice/program", "/usr/bin", "/usr/local/bin")) {
            candidates.add(Path.of(directory, "soffice.com")); candidates.add(Path.of(directory, "soffice"));
        }
        String path = System.getenv("PATH");
        if (path != null) for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            candidates.add(Path.of(directory, "soffice.com")); candidates.add(Path.of(directory, "soffice"));
        }
        return candidates.stream().map(candidate -> candidate.toAbsolutePath().normalize()).filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
