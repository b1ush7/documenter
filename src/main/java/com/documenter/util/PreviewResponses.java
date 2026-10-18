package com.documenter.util;

import com.documenter.vo.PdfPagePreview;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/**
 * 图片预览的统一响应封装。
 *
 * <p>抽出来的原因：{@code FileController} 与 {@code AdminController} 各有一份完全相同的
 * {@code toPreview}。重复实现的问题不是「多写了十几行」，而是<b>两份会各自演进</b>——
 * 例如某天给响应加了缓存头，只改了一处，另一个入口的行为就与文档不一致。
 */
public final class PreviewResponses {

    /** 响应头：图片像素宽度，供前端预留布局高度、避免内容跳动。 */
    public static final String HEADER_WIDTH = "X-Image-Width";
    /** 响应头：图片像素高度。 */
    public static final String HEADER_HEIGHT = "X-Image-Height";

    private PreviewResponses() {
    }

    /** 组装 PNG 预览响应。 */
    public static ResponseEntity<byte[]> png(PdfPagePreview preview) {
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(preview.fileName(), StandardCharsets.UTF_8)
                .build();
        byte[] content = preview.content();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HEADER_WIDTH, String.valueOf(preview.widthPixels()))
                .header(HEADER_HEIGHT, String.valueOf(preview.heightPixels()))
                // 预览内容按内容摘要缓存，因此允许浏览器长时间缓存
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(content.length)
                .body(content);
    }

    /** 组装原图响应，用于图片版本直接回传（保留原始格式与透明度）。 */
    public static ResponseEntity<byte[]> originalImage(PdfPagePreview preview, MediaType mediaType) {
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(preview.fileName(), StandardCharsets.UTF_8)
                .build();
        byte[] content = preview.content();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HEADER_WIDTH, String.valueOf(preview.widthPixels()))
                .header(HEADER_HEIGHT, String.valueOf(preview.heightPixels()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(mediaType)
                .contentLength(content.length)
                .body(content);
    }
}
