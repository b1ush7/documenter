package com.documenter.service;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * 文件存储抽象（TODO 3.3「完成 OssUtil 或独立存储组件，实现上传、读取、下载、删除」）。
 *
 * <p>为什么不让业务层直接碰 {@link Path}：存储标识是业务层唯一需要知道的东西，
 * 由实现负责映射为真实路径。这样以后换成对象存储时，业务代码与本接口都不用改。
 *
 * <p>存储标识（storageKey）的约定：<b>由本组件生成，绝不采用客户端传入的值</b>。
 * 本地实现使用 UUID 作为文件名，从根上排除路径穿越。
 */
public interface FileStorage {

    /**
     * 保存字节内容，返回存储标识。
     *
     * @return 新生成的存储标识，形如 {@code 2026/10/09/uuid.docx}
     */
    String store(byte[] content, String extension);

    /**
     * 保存输入流内容。
     *
     * <p>调用方负责关闭流。实现内部不应把整份内容长期驻留内存。
     */
    String store(InputStream inputStream, String extension);

    /** 读取为输入流，调用方负责关闭。 */
    InputStream read(String storageKey);

    /** 判断存储标识是否存在，用于下载前校验文件是否被人工删除。 */
    boolean exists(String storageKey);

    /** 删除文件；不存在时返回 false，不抛异常。 */
    boolean delete(String storageKey);

    /**
     * 解析为可读路径，仅本地实现提供。
     *
     * @throws UnsupportedOperationException 非本地存储实现不支持
     */
    Path resolvePath(String storageKey);
}
