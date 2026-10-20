package com.documenter.vo;

/**
 * 单页 PNG 预览载荷。
 *
 * <p>{@code totalPages} 是本版本的总页数，供前端显示「第 3 / 12 页」并正确禁用翻页按钮。
 * PDF / 图片可以从元信息接口直接得知页数；DOCX / XLSX 的页数只有真正渲染时才由渲染后端报出，
 * 因此由渲染流程回填到这里，再以响应头 {@code X-Total-Pages} 返回。无法确定时为 {@code null}。
 */
public record PdfPagePreview(byte[] content, int pageNo, int dpi,
                             int widthPixels, int heightPixels, String fileName,
                             Integer totalPages) {

    public PdfPagePreview {
        content = content == null ? new byte[0] : content.clone();
    }

    /** 兼容原有调用：总页数未知时传 null。 */
    public PdfPagePreview(byte[] content, int pageNo, int dpi,
                          int widthPixels, int heightPixels, String fileName) {
        this(content, pageNo, dpi, widthPixels, heightPixels, fileName, null);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
