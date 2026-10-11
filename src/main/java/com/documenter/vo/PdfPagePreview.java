package com.documenter.vo;

/** PDF 单页 PNG 预览载荷。 */
public record PdfPagePreview(byte[] content, int pageNo, int dpi,
                             int widthPixels, int heightPixels, String fileName) {

    public PdfPagePreview {
        content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
