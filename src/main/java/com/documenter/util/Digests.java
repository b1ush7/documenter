package com.documenter.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要工具（TODO 3.3 文件摘要、TODO 3.1 验证码摘要共用）。
 *
 * <p>集中在一处，避免每个服务各写一遍 MessageDigest 而出现算法或编码不一致。
 */
public final class Digests {

    private Digests() {
    }

    /** 字符串的 SHA-256 十六进制小写摘要。 */
    public static String sha256Hex(String value) {
        if (value == null) {
            throw new IllegalArgumentException("待摘要内容不能为空");
        }
        return HexFormat.of().formatHex(newDigest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 字节数组的 SHA-256 十六进制小写摘要。 */
    public static String sha256Hex(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("待摘要内容不能为空");
        }
        return HexFormat.of().formatHex(newDigest().digest(value));
    }

    /**
     * 读取输入流并计算 SHA-256，同时把内容写入 {@code sink}。
     *
     * <p>{@code sink} 为空时只计算摘要不落盘，用于校验已有文件。
     */
    public static String sha256Hex(InputStream inputStream) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 新建 SHA-256 摘要器，供调用方在遍历大文件时增量更新。 */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，走到这里说明运行环境异常
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }
}
