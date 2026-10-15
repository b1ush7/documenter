package com.documenter.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 页面版式预览配置（TODO 3.3「页面预览尚未实现；接入后需要绑定明确版本并执行同等鉴权」）。
 *
 * <p>背景：PDF 与图片可以就地渲染，但 DOCX / XLSX 必须先转成 PDF 才能得到版式预览，
 * 而这一步依赖外部办公套件（LibreOffice）。JDK 生态没有可用的纯 Java 排版引擎——
 * 自己用 POI 重新排版等于重写一个渲染器，与 TODO 第 9 节
 * 「不能据此假定具备完整办公软件渲染能力」的提醒相悖。
 *
 * <p>因此这里把转换器做成可插拔的：未配置时 DOCX/XLSX 预览会返回明确的可操作错误，
 * 而不是静默失败或返回与真实版式不符的内容。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "res.preview")
public class PreviewProperties {

    /** 预览渲染的默认 DPI。 */
    private int defaultDpi = 110;

    /** 预览渲染允许的最大 DPI。 */
    private int maxDpi = 200;

    /** 单页 PNG 的像素数上限（宽 × 高），防止超大页面耗尽内存。 */
    private long maxPagePixels = 40_000_000L;

    /** 渲染结果缓存目录（相对存储临时目录）。 */
    private String cacheDirectory = "preview-cache";

    /** 是否启用预览缓存。相同内容 + 相同页 + 相同 DPI 只渲染一次。 */
    private boolean cacheEnabled = true;

    /**
     * Office 渲染/转换后端：none / kit。
     *
     * <p>kit 指打包好的 LibreOffice kit（Node CLI）。它同时提供两件本服务需要的能力：
     * 页面渲染与格式转换，因此比让运维单独安装 LibreOffice 更可控 ——
     * 版本固定、无需桌面会话、不依赖 office 是否被激活。
     *
     * <p>PDF 与图片预览不需要它；DOCX/XLSX 的版式预览与 4.1 的兼容性验收需要。
     */
    private String officeBackend = "none";

    /** Office kit 的 Node 可执行文件路径。 */
    private String officeKitNode;

    /** Office kit 的 CLI 入口（cli.js）路径。 */
    private String officeKitCli;

    /** 单次渲染或转换的超时。 */
    private Duration officeTimeout = Duration.ofSeconds(120);

    /** 送入渲染或转换的最大输入字节数。 */
    private long officeMaxInputBytes = 50L * 1024 * 1024;

    /**
     * 字体目录（TODO 4.3「配置中文字体」）。
     *
     * <p><b>实测结论（2026-10，Windows + 内置 LibreOffice native 后端）</b>：
     * 该选项会传给渲染工具，并<b>影响 {@code missingFonts} 诊断结果</b>
     * ——传入不含中文字形的目录后，缺失字体列表会新增 {@code SimSun}；
     * 但<b>不影响实际渲染</b>：传入只含拉丁字体的目录后，
     * 渲染结果与使用系统字体时<b>逐字节相同</b>（PNG SHA-256 一致）。
     * 原因是该后端在渲染阶段直接向操作系统字体库请求字体，
     * 字体目录参数只作用于字体可用性检测。
     *
     * <p>因此：在 Windows 上让中文正确渲染的<b>真正前提是宿主机装了中文字体</b>；
     * 在 Linux 上字体解析通常由 fontconfig 决定，该目录可能真正生效，
     * 但本项目尚未在 Linux 运行时验证过。
     *
     * <p>保留该配置的理由：它是渲染工具的公开参数，接入成本为零，
     * 且在 Linux 部署时是让渲染结果不依赖宿主字体的唯一手段。
     * 多个目录用 {@link java.io.File#pathSeparator} 分隔。
     */
    private String fontDirectory;

    /**
     * 缺字时的后备字体族（逗号分隔）。
     *
     * <p>与 {@link #fontDirectory} 同样受后端限制，见其说明。
     */
    private String fontFallbacks;

    /**
     * 初始字体族，影响未显式指定字体的内容。
     *
     * <p>与 {@link #fontDirectory} 同样受后端限制，见其说明。
     */
    private String initialFontFamily;

    /** 兼容性验收时最多检查的页数，避免大文档把验收流程拖垮。 */
    private int compatibilityMaxPages = 20;

    /** 兼容性验收的渲染 DPI。 */
    private int compatibilityDpi = 110;

    /**
     * 页面边缘留白检测阈值（像素）。
     *
     * <p>渲染结果若在距页面边缘该范围内出现非白像素，判定为「可能被裁切」。
     * 设为 0 表示关闭该检测。
     */
    private int clippingMarginPixels = 2;
}
