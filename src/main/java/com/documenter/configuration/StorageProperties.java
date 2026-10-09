package com.documenter.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 文件存储与上传策略（TODO 3.3）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "res.storage")
public class StorageProperties {

    /** 存储实现类型，目前只实现 local。 */
    private String type = "local";

    private Local local = new Local();

    /** 上传限制。 */
    private Upload upload = new Upload();

    @Getter
    @Setter
    public static class Local {
        /** 正式文件根目录，必须是应用运行账户可写的路径。 */
        private String root = "./data/storage";

        /** 转换/临时文件根目录，与正式目录隔离（TODO 3.3 要求隔离临时目录）。 */
        private String temp = "./data/tmp";
    }

    @Getter
    @Setter
    public static class Upload {
        /** 单文件最大字节数，默认 50MB，与 spring.servlet.multipart 的上限保持一致。 */
        private long maxSizeBytes = 50L * 1024 * 1024;

        /** 同一用户 1 小时内的最大上传次数，防止刷存储。 */
        private int hourlyLimit = 60;

        /** 上传限流窗口。 */
        private Duration rateLimitWindow = Duration.ofHours(1);
    }
}
