package com.documenter.component;

import com.documenter.configuration.PreviewProperties;
import com.documenter.exception.BusinessException;
import com.documenter.service.OfficeRenderService;
import com.documenter.vo.DocumentRenderManifest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 通过打包的 LibreOffice kit（Node CLI）渲染与转换 Office 文档（TODO 4.1）。
 *
 * <p>为什么用打包的 kit 而不是让运维装 LibreOffice：
 * <ul>
 *   <li>版本固定，渲染结果可复现 —— 兼容性验收最怕的就是「换台机器结果不一样」；</li>
 *   <li>不需要桌面会话与 Office 激活，容器里也能跑；</li>
 *   <li>同时提供渲染与转换，DOCX/XLSX 页面预览与兼容性验收共用同一条链路。</li>
 * </ul>
 *
 * <p>为什么不走 LibreOffice 的 UNO 桥接：外部进程是<b>可隔离、可超时、可强杀</b>的。
 * 进程内的 UNO 连接一旦卡住会拖死整个服务，且无法可靠中断。
 *
 * <p>两个必须处理的工程细节：
 * <ol>
 *   <li><b>并发读取输出流</b>：只读 stdout 不读 stderr 时，子进程写满 stderr 管道缓冲区
 *       就会阻塞，表现为「渲染永远不返回」。这里用两个虚拟线程分别读取。</li>
 *   <li><b>超时后强杀</b>：只报超时而不销毁进程会留下僵尸 node/LibreOffice，
 *       单个实例占用内存可观，多次之后机器被拖垮。</li>
 * </ol>
 */
@Slf4j
@Component
public class LibreOfficeKitRenderService implements OfficeRenderService {

    private static final String BACKEND_KIT = "kit";
    private static final String ENGINE_NAME = "libreoffice-kit";

    /** 打包运行时的默认位置，允许通过配置覆盖。 */
    private static final String DEFAULT_NODE =
            "D:/DS/resources/runtime/primary-runtime/dependencies/node/bin/node.exe";
    private static final String DEFAULT_CLI =
            "D:/DS/resources/app.asar.unpacked/dsh/node_modules/@deepseek-ai/libreoffice-kit/lib/cli.js";

    private final PreviewProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Path nodeExecutable;
    private Path cliEntry;

    public LibreOfficeKitRenderService(PreviewProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        if (!BACKEND_KIT.equalsIgnoreCase(properties.getOfficeBackend())) {
            if ("libreoffice".equalsIgnoreCase(properties.getOfficeBackend())) return;
            log.info("未启用 Office kit 渲染后端，DOCX/XLSX 版式渲染与兼容性验收不可用。"
                    + "启用方式：res.preview.office-backend=kit");
            return;
        }
        this.nodeExecutable = resolve(properties.getOfficeKitNode(), DEFAULT_NODE);
        this.cliEntry = resolve(properties.getOfficeKitCli(), DEFAULT_CLI);
        if (available()) {
            log.info("Office kit 渲染后端已就绪, node={}, cli={}", nodeExecutable, cliEntry);
        } else {
            log.warn("res.preview.office-backend=kit 但未找到 node 或 cli 入口，"
                    + "DOCX/XLSX 版式渲染将不可用。");
        }
    }

    @Override
    public String backendName() {
        return ENGINE_NAME;
    }

    @Override
    public boolean available() {
        return nodeExecutable != null && cliEntry != null;
    }

    @Override
    public String configurationHint() {
        return "请设置 res.preview.office-backend=kit 并配置 res.preview.office-kit-node 与 "
                + "res.preview.office-kit-cli 指向打包的 Node 与 libreoffice-kit cli.js";
    }

    // ---------------------------------------------------------------- 渲染

    @Override
    public DocumentRenderManifest render(Path source, String extension, Path outputDir,
                                         List<Integer> pages, int dpi) {
        requireAvailable();
        requireReadableSource(source);

        List<String> command = baseCommand("render");
        command.add("--input");
        command.add(source.toString());
        command.add("--output-dir");
        command.add(outputDir.toString());
        command.add("--dpi");
        command.add(Integer.toString(dpi));
        if (pages != null && !pages.isEmpty()) {
            command.add("--pages");
            command.add(joinPages(pages));
        }
        command.add("--timeout-ms");
        // 让 kit 自己的超时略短于外层超时，优先由它做受控退出
        command.add(Long.toString(Math.max(1000, properties.getOfficeTimeout().toMillis() - 5_000)));
        command.add("--max-input-bytes");
        command.add(Long.toString(properties.getOfficeMaxInputBytes()));
        appendFontOptions(command);

        String output = run(command, workingDirectoryOf(source));
        return parseManifest(output, outputDir);
    }

    @Override
    public Path toPdf(Path source, String extension, Path outputDir) {
        requireAvailable();
        requireReadableSource(source);

        try {
            // 与 render 相反：convert 要求输出文件的父目录已存在，
            // 否则会报 ENOENT。这里统一创建，避免调用方踩这个不一致。
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new BusinessException(500, "无法创建转换输出目录");
        }

        String target = outputDir.resolve(baseName(source) + ".pdf").toString();
        List<String> command = baseCommand("convert");
        command.add("--input");
        command.add(source.toString());
        command.add("--output");
        command.add(target);
        command.add("--timeout-ms");
        command.add(Long.toString(Math.max(1000, properties.getOfficeTimeout().toMillis() - 5_000)));
        command.add("--max-input-bytes");
        command.add(Long.toString(properties.getOfficeMaxInputBytes()));
        appendFontOptions(command);

        run(command, workingDirectoryOf(source));
        Path produced = Path.of(target);
        if (!Files.isRegularFile(produced)) {
            throw new BusinessException(500, "转换未产生 PDF 产物，文档可能不受支持");
        }
        return produced;
    }

    /**
     * 追加字体相关选项（TODO 4.3「配置中文字体」）。
     *
     * <p>中文字形能否渲染取决于渲染环境的字体，而不是文档里写的字体名。
     * 开发机通常装了宋体/黑体，本地看着正常；部署到未装中文字体的精简容器后，
     * 同一份文档会渲染成方块或空白——这类问题在开发阶段几乎发现不了。
     *
     * <p><b>实测限制（Windows + native 后端）</b>：这些参数会传给渲染工具并影响
     * {@code missingFonts} 诊断，但<b>不改变实际渲染结果</b>——该后端渲染时
     * 直接向操作系统字体库取字体。因此本方法在 Windows 上的作用是让缺字问题
     * <b>可见</b>（体现为兼容性报告里的 WARNING），而不是修好它；
     * 真正让中文渲染正确的前提仍是宿主机装有中文字体。
     * Linux 由 fontconfig 决定字体解析，这些参数可能真正生效，但尚未在该运行时验证。
     *
     * <p>只传入实际存在的目录：传不存在的路径会让字体检测把原本可用的系统字体
     * 也判为缺失（实测 {@code missingFonts} 会凭空多出文档实际使用的字体），
     * 产生误导性的告警。
     */
    private void appendFontOptions(List<String> command) {
        String configured = properties.getFontDirectory();
        if (configured != null && !configured.isBlank()) {
            List<String> existing = new ArrayList<>();
            for (String part : configured.split(java.io.File.pathSeparator)) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (Files.isDirectory(Path.of(trimmed))) {
                    existing.add(trimmed);
                } else {
                    log.warn("res.preview.font-directory 中的路径不存在，已忽略: {}", trimmed);
                }
            }
            if (!existing.isEmpty()) {
                // 多个目录需按平台分隔符拼接，与 kit 的约定一致
                command.add("--font-directory");
                command.add(String.join(java.io.File.pathSeparator, existing));
            }
        }

        if (properties.getFontFallbacks() != null && !properties.getFontFallbacks().isBlank()) {
            command.add("--font-fallbacks");
            command.add(properties.getFontFallbacks().trim());
        }

        if (properties.getInitialFontFamily() != null
                && !properties.getInitialFontFamily().isBlank()) {
            command.add("--initial-font-family");
            command.add(properties.getInitialFontFamily().trim());
        }
    }

    /**
     * 选择子进程的工作目录。
     *
     * <p>不能用输出目录：kit 要求输出目录<b>不存在</b>（它自行创建），
     * 而 {@code ProcessBuilder.directory()} 必须是已存在的目录，直接用会报
     * 「目录名称无效」。源文件所在目录一定存在，因此作为工作目录。
     */
    private static Path workingDirectoryOf(Path source) {
        Path parent = source.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new BusinessException(500, "无法确定渲染进程的工作目录");
        }
        return parent;
    }

    // ---------------------------------------------------------------- 内部

    private List<String> baseCommand(String subCommand) {
        List<String> command = new ArrayList<>();
        command.add(nodeExecutable.toString());
        command.add(cliEntry.toString());
        command.add(subCommand);
        return command;
    }

    /**
     * 执行 CLI 并返回 stdout。
     *
     * <p>stdout 与 stderr 由两个虚拟线程并发读取：只读其中一个时，
     * 另一个的管道缓冲区被写满会让子进程阻塞，表现为「永远不返回」。
     */
    private String run(List<String> command, Path workingDirectory) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            log.warn("无法启动 Office kit 进程", e);
            throw new BusinessException(503, "无法启动文档渲染进程，" + configurationHint());
        }

        Duration timeout = properties.getOfficeTimeout();
        ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<String> stdout = readers.submit(() -> readAll(process.getInputStream()));
            Future<String> stderr = readers.submit(() -> readAll(process.getErrorStream()));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                // 超时必须强杀，否则会留下僵尸 node / LibreOffice 进程
                process.destroyForcibly();
                log.warn("Office kit 执行超时（{}），已强制终止", timeout);
                throw new BusinessException(504,
                        "文档渲染超时（超过 " + timeout.toSeconds() + " 秒）");
            }

            String out = await(stdout);
            String err = await(stderr);
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                // kit 在失败时把结构化错误写到 stderr，尽量原样透出便于定位
                String detail = err == null || err.isBlank() ? out : err;
                log.warn("Office kit 执行失败, exitCode={}, detail={}", exitCode, abbreviate(detail));
                throw new BusinessException(mapExitToStatus(detail), describeFailure(detail));
            }
            return out == null ? "" : out;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "文档渲染被中断");
        } finally {
            readers.shutdownNow();
        }
    }

    /**
     * 把 kit 的结构化错误映射为业务状态码。
     *
     * <p>刻意区分「文档有问题」（422，用户换文件）与「服务端没配好」（503，运维处理）：
     * 都返回 500 会让排查方向完全错掉。
     */
    private static int mapExitToStatus(String detail) {
        String text = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (text.contains("invalid-document") || text.contains("not a supported")) {
            return 422;
        }
        if (text.contains("timeout")) {
            return 504;
        }
        if (text.contains("too large") || text.contains("max-input-bytes")
                || text.contains("exceeds")) {
            return 413;
        }
        return 500;
    }

    private static String describeFailure(String detail) {
        String text = detail == null ? "" : detail;
        if (text.toLowerCase(Locale.ROOT).contains("invalid-document")
                || text.toLowerCase(Locale.ROOT).contains("not a supported")) {
            return "文档结构无效或不受支持，无法渲染；文件可能已损坏或并非真正的 Office 文档";
        }
        if (text.toLowerCase(Locale.ROOT).contains("timeout")) {
            return "文档渲染超时，可能包含过于复杂的排版";
        }
        return "文档渲染失败" + (text.isBlank() ? "" : "：" + abbreviate(text));
    }

    /** 解析 render 子命令输出的 manifest。 */
    private DocumentRenderManifest parseManifest(String output, Path outputDir) {
        String json = extractJson(output);
        if (json == null) {
            throw new BusinessException(500, "渲染进程未返回可解析的清单");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String backend = root.path("backend").asString("unknown");
            int pageCount = root.path("pageCount").asInt(0);

            List<DocumentRenderManifest.Page> pages = new ArrayList<>();
            for (JsonNode image : root.path("images")) {
                Path path = Path.of(image.path("path").asString(""));
                // 产物必须落在调用方指定的目录内，避免被外部输入引导写出目录
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(outputDir.toAbsolutePath().normalize())) {
                    log.warn("渲染产物路径越出输出目录，已忽略: {}", normalized);
                    continue;
                }
                pages.add(new DocumentRenderManifest.Page(
                        image.path("page").asInt(0),
                        normalized.toString(),
                        image.path("width").asInt(0),
                        image.path("height").asInt(0),
                        image.path("byteLength").asLong(0)));
            }

            List<String> missingFonts = new ArrayList<>();
            for (JsonNode font : root.path("missingFonts")) {
                missingFonts.add(font.asString(""));
            }
            return new DocumentRenderManifest(backend, pageCount, pages, missingFonts);
        } catch (RuntimeException e) {
            log.warn("解析渲染清单失败: {}", abbreviate(json), e);
            throw new BusinessException(500, "渲染清单解析失败");
        }
    }

    /**
     * 从输出中取出 JSON 主体。
     *
     * <p>不能假设 stdout 只有一行 JSON：LibreOffice 的警告可能与 JSON 混在一起，
     * 因此取第一个 { 到最后一个 } 之间的内容。
     */
    private static String extractJson(String output) {
        if (output == null) {
            return null;
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return output.substring(start, end + 1);
    }

    private static String joinPages(List<Integer> pages) {
        StringBuilder builder = new StringBuilder();
        for (Integer page : pages) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(page);
        }
        return builder.toString();
    }

    /** 读取流内容，长度受限，避免异常输出把内存打满。 */
    private static String readAll(java.io.InputStream stream) throws IOException {
        try (stream) {
            byte[] bytes = stream.readNBytes(1024 * 1024);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String await(Future<String> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            return "";
        }
    }

    private void requireAvailable() {
        if (!available()) {
            throw new BusinessException(503,
                    "当前部署未接入 Office 渲染后端，无法执行该操作；" + configurationHint());
        }
    }

    private static void requireReadableSource(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            throw new BusinessException(400, "待渲染文件不存在");
        }
    }

    private static String baseName(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed;
    }

    private static Path resolve(String configured, String fallback) {
        String candidate = configured == null || configured.isBlank() ? fallback : configured.trim();
        Path path = Path.of(candidate);
        return Files.isRegularFile(path) ? path : null;
    }
}
