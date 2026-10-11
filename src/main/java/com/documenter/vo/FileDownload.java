package com.documenter.vo;

import com.documenter.entity.FileAsset;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;

/**
 * 下载载荷：出参元数据 + 可读内容流。
 *
 * <p>把两者放在一个对象里，保证「响应头里的文件名/类型」与「实际字节内容」来自同一条记录，
 * 避免先查元数据再查文件时被并发删除导致的不一致。
 */
public record FileDownload(FileAsset asset, Resource resource, long contentLength, String contentType,
                           String downloadFileName) {

    public static FileDownload of(FileAsset asset, java.io.InputStream inputStream, long contentLength) {
        return new FileDownload(asset, new InputStreamResource(inputStream), contentLength,
                asset.getContentType(), asset.getDisplayName());
    }

    /**
     * 指定下载文件名，用于按版本下载时在文件名里带上版本号。
     *
     * <p>内容类型仍取文件自身的类型，因为同一文件的不同版本格式一致。
     */
    public static FileDownload of(FileAsset asset, java.io.InputStream inputStream, long contentLength,
                                  String downloadFileName) {
        return new FileDownload(asset, new InputStreamResource(inputStream), contentLength,
                asset.getContentType(), downloadFileName);
    }
}
