package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地文件存储测试（TODO 3.3「通过内部文件 ID 定位资源，防止任意路径读取」）。
 *
 * <p>用 JUnit 的 {@link TempDir} 作为存储根目录，不触碰项目目录，也不需要数据库。
 * 重点是路径穿越防线：即使 storageKey 被污染，也不能读到根目录之外的文件。
 */
class LocalFileStorageTest {

    @TempDir
    Path storageRoot;

    @TempDir
    Path tempRoot;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.getLocal().setRoot(storageRoot.toString());
        properties.getLocal().setTemp(tempRoot.toString());
        storage = new LocalFileStorage(properties);
    }

    @Test
    @DisplayName("保存后能原样读回内容")
    void storeAndReadBack() throws IOException {
        byte[] content = "文档内容".getBytes(StandardCharsets.UTF_8);

        String key = storage.store(content, "docx");

        assertTrue(storage.exists(key));
        try (InputStream input = storage.read(key)) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    @Test
    @DisplayName("从输入流保存")
    void storeFromInputStream() throws IOException {
        byte[] content = "stream-content".getBytes(StandardCharsets.UTF_8);

        String key = storage.store(new ByteArrayInputStream(content), "pdf");

        try (InputStream input = storage.read(key)) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    @Test
    @DisplayName("存储标识按 日期目录/UUID.扩展名 生成，不使用任何客户端文件名")
    void storageKeyFormat() {
        String key = storage.store(new byte[]{1, 2, 3}, "png");

        // 形如 2026/10/09/<uuid>.png
        assertTrue(key.matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-fA-F\\-]{36}\\.png"),
                "存储标识格式不符: " + key);
    }

    @Test
    @DisplayName("两次保存同一内容得到不同标识，不会互相覆盖")
    void storeNeverCollides() {
        String first = storage.store(new byte[]{1}, "txt");
        String second = storage.store(new byte[]{1}, "txt");

        assertNotEquals(first, second);
        assertTrue(storage.exists(first));
        assertTrue(storage.exists(second));
    }

    @Test
    @DisplayName("非法扩展名回退为 bin，不会拼出异常路径")
    void illegalExtensionFallsBackToBin() {
        Set<String> keys = new HashSet<>();
        keys.add(storage.store(new byte[]{1}, ".."));
        keys.add(storage.store(new byte[]{1}, "../../evil"));
        keys.add(storage.store(new byte[]{1}, "a/b"));
        keys.add(storage.store(new byte[]{1}, "verylongextensionname"));
        keys.add(storage.store(new byte[]{1}, null));

        for (String key : keys) {
            assertTrue(key.endsWith(".bin"), "应回退为 bin: " + key);
        }
    }

    @Test
    @DisplayName("路径穿越被拒绝：.. 逃不出根目录")
    void pathTraversalRejected() {
        // 根目录外放一个真实文件，确认读不到它
        Path outside = storageRoot.getParent().resolve("outside-secret.txt");
        try {
            Files.writeString(outside, "secret", StandardCharsets.UTF_8);

            assertThrows(BusinessException.class, () -> storage.resolvePath("../outside-secret.txt"));
            assertThrows(BusinessException.class,
                    () -> storage.resolvePath("../../" + storageRoot.getFileName() + "/../outside-secret.txt"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("绝对路径形式的标识被拒绝")
    void absolutePathRejected() {
        String absolute = storageRoot.getParent().resolve("outside-secret.txt").toString();

        // resolve 绝对路径会直接替换根，因此必须被越界校验拦住
        assertThrows(BusinessException.class, () -> storage.resolvePath(absolute));
    }

    @Test
    @DisplayName("空标识被拒绝")
    void blankKeyRejected() {
        assertThrows(BusinessException.class, () -> storage.resolvePath(null));
        assertThrows(BusinessException.class, () -> storage.resolvePath(""));
        assertThrows(BusinessException.class, () -> storage.resolvePath("   "));
    }

    @Test
    @DisplayName("exists 对越界标识返回 false 而不是抛异常，便于调用方安全探测")
    void existsIsSafeForBadKeys() {
        assertFalse(storage.exists("../outside.txt"));
        assertFalse(storage.exists(""));
        assertFalse(storage.exists(null));
    }

    @Test
    @DisplayName("读取不存在的文件抛 404 业务异常")
    void readMissingFileFails() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> storage.read("2026/01/01/not-exist.bin"));

        assertEquals(404, error.getCode());
    }

    @Test
    @DisplayName("删除文件；重复删除返回 false 而不报错")
    void deleteIsIdempotent() {
        String key = storage.store(new byte[]{1, 2, 3}, "bin");

        assertTrue(storage.delete(key));
        assertFalse(storage.delete(key));
        assertFalse(storage.exists(key));
    }

    @Test
    @DisplayName("根目录与临时目录会被自动创建，且两者相互隔离")
    void directoriesCreatedAndSeparated() {
        assertTrue(Files.isDirectory(storageRoot));
        assertTrue(Files.isDirectory(tempRoot));

        // 正式文件不落在临时目录里
        String key = storage.store(new byte[]{1}, "bin");
        Path resolved = storage.resolvePath(key);
        assertTrue(resolved.startsWith(storageRoot));
        assertFalse(resolved.startsWith(tempRoot));
    }

    @Test
    @DisplayName("解析出的路径始终位于根目录之内")
    void resolvedPathStaysInsideRoot() {
        String key = storage.store(new byte[]{1}, "docx");

        Path resolved = storage.resolvePath(key);

        assertTrue(resolved.startsWith(storageRoot.toAbsolutePath().normalize()));
        // 按日期分目录，避免单目录文件数无限增长
        String year = String.valueOf(java.time.LocalDate.now().getYear());
        assertTrue(resolved.toString().contains(year),
                "应按日期分目录，当前年份=" + year + " 路径=" + resolved);
    }
}
