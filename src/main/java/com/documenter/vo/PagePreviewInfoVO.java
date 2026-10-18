package com.documenter.vo;

/**
 * 页面预览元信息（TODO 3.3「页面预览…绑定明确版本并执行同等鉴权」）。
 *
 * <p>前端先取本对象，据此决定怎么渲染：
 * 有 {@code pageCount} 就按页请求图片；{@code previewable=false} 时展示 {@code message}。
 */
public record PagePreviewInfoVO(
        Long fileId,
        Integer versionNo,
        String contentType,
        String extension,
        /** 可预览的页数；不可预览时为 null。 */
        Integer pageCount,
        /** 是否需要经过外部转换器（PDF、图片为 false）。 */
        boolean requiresConversion,
        /** 是否具备预览条件。 */
        boolean previewable,
        /** 实际使用的转换引擎名；PDF、图片为 "native"。 */
        String engine,
        /** 使用的渲染 DPI。 */
        int dpi,
        /** 不可预览或需要提示时的可读说明；正常时为 null。 */
        String message) {
}
