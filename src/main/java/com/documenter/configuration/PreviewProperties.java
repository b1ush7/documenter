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
