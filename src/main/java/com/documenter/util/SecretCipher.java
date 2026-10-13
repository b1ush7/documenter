package com.documenter.util;

import com.documenter.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段加解密（TODO 3.2「模型密钥不得返回前端」）。
 *
 * <p>为什么要在数据库里加密而不是只做「不返回前端」：
 * 不返回前端只挡住了接口层，一旦数据库被导出、备份泄露或运维直连查表，
 * 明文密钥就直接暴露。加密后攻击者还需要环境变量里的主密钥才有意义。
 *
 * <p>算法选择：AES-256-GCM。GCM 是带认证的加密模式，
 * 密文被篡改会在解密时直接失败，而不是解出错误内容后被误用。
 * 每次加密使用随机 12 字节 IV，与密文一起存储（IV 不需要保密）。
 *
 * <p>存储格式：{@code v1:<base64(IV)>:<base64(密文+认证标签)>}。
 * 带版本前缀是为了将来更换算法时能识别旧格式并平滑迁移。
 *
 * <p>主密钥来源：环境变量 {@code MODEL_CONFIG_SECRET}。
 * <b>不要</b>把它写进配置文件或提交进仓库——那等于没有加密。
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String VERSION_PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int AES_KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String configuredSecret;
    private SecretKeySpec key;

    public SecretCipher(@Value("${res.model-config.secret:}") String configuredSecret) {
        this.configuredSecret = configuredSecret;
    }

    /**
     * 初始化主密钥。
     *
     * <p>没有配主密钥时<b>不让应用启动失败</b>，只打印告警：
     * 开发机上不配也能跑通全流程，但一旦真的保存模型密钥就会抛出明确异常，
     * 避免「以为加密了实际没加密」。
     */
    @PostConstruct
    public void init() {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            log.warn("未配置 res.model-config.secret，模型密钥的加密功能不可用。"
                    + "生产环境必须通过环境变量 MODEL_CONFIG_SECRET 配置一个足够长的随机串。");
            return;
        }
        byte[] raw = configuredSecret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < AES_KEY_BYTES) {
            // 直接拒绝弱密钥，而不是补齐——补齐会让人误以为短密钥也安全
            throw new IllegalStateException(
                    "res.model-config.secret 至少需要 " + AES_KEY_BYTES + " 字节（当前 " + raw.length + " 字节）");
        }
        byte[] keyBytes = new byte[AES_KEY_BYTES];
        System.arraycopy(raw, 0, keyBytes, 0, AES_KEY_BYTES);
        this.key = new SecretKeySpec(keyBytes, "AES");
        log.info("模型密钥加密已启用（AES-256-GCM）");
    }

    /** 加密。返回带版本前缀的可存储字符串。 */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            // 不把明文写进日志或异常消息
            throw new BusinessException(500, "密钥加密失败，请检查加密配置");
        }
    }

    /** 解密。格式不合法或密文被篡改都会抛出明确异常。 */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return null;
        }
        requireKey();
        if (!cipherText.startsWith(VERSION_PREFIX)) {
            throw new BusinessException(500, "密钥密文格式不受支持，可能是历史遗留数据");
        }
        String[] parts = cipherText.substring(VERSION_PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            throw new BusinessException(500, "密钥密文格式损坏");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 常见原因是主密钥被更换，此时旧密文无法解开
            log.error("密钥解密失败，请确认 MODEL_CONFIG_SECRET 未被更换");
            throw new BusinessException(500, "密钥解密失败，可能是加密主密钥已变更");
        }
    }

    /** 加密功能是否可用。 */
    public boolean available() {
        return key != null;
    }

    /**
     * 生成用于前端展示的脱敏提示。
     *
     * <p>只保留首尾各 4 位。这个值本身不足以还原密钥，
     * 但对「确认当前生效的是哪把密钥」足够。
     */
    public static String maskHint(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isEmpty()) {
            return null;
        }
        if (plaintextKey.length() <= 8) {
            // 过短的密钥不做部分展示，全部打码
            return "****";
        }
        return plaintextKey.substring(0, 4) + "****" + plaintextKey.substring(plaintextKey.length() - 4);
    }

    private void requireKey() {
        if (key == null) {
            throw new BusinessException(500,
                    "未配置加密主密钥（res.model-config.secret / 环境变量 MODEL_CONFIG_SECRET），无法保存模型密钥");
        }
    }
}
