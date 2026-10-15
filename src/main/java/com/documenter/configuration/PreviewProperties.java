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

    /** 转换引擎：none / libreoffice。none 表示不启用外部转换。 */
    private String converter = "none";

    /**
     * LibreOffice 可执行文件路径。
     *
     * <p>留空时按常见安装位置自动探测。若探测不到，
     * DOCX/XLSX 预览会给出「请配置 res.preview.libreoffice-path」的提示。
     */
    private String libreofficePath;

    /** 单次转换的超时（TODO 3.4 要求配置超时）。 */
    private Duration conversionTimeout = Duration.ofSeconds(60);

    /** 允许转换的最大源文件字节数，避免把超大文件送进外部进程。 */
    private long maxSourceBytes = 50L * 1024 * 1024;

    /** 允许转换出的 PDF 最大页数，避免超大文档把内存打满。 */
    private int maxConvertedPages = 500;

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
}
