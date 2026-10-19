package com.documenter.configuration;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

/** OSS 客户端只在 OSS 模式创建；启动时验证配置，不打印访问凭据。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "res.storage", name = "type", havingValue = "oss")
public class OssStorageConfiguration {
    @Bean(destroyMethod = "close")
    public OSSClient ossClient(StorageProperties properties) {
        var oss = properties.getOss();
        require(oss.getEndpoint(), "OSS_ENDPOINT");
        require(oss.getRegion(), "OSS_REGION");
        require(oss.getBucket(), "OSS_BUCKET");
        require(oss.getAccessKeyId(), "OSS_ACCESS_KEY_ID");
        require(oss.getAccessKeySecret(), "OSS_ACCESS_KEY_SECRET");
        URI endpoint;
        try {
            endpoint = URI.create(oss.getEndpoint());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("OSS_ENDPOINT 必须是 HTTPS Endpoint");
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))) {
            throw new IllegalStateException("OSS_ENDPOINT 必须是 HTTPS Endpoint，不包含 Bucket、路径或凭据");
        }
        return OSSClient.newBuilder()
                .endpoint(oss.getEndpoint())
                .region(oss.getRegion())
                .connectTimeout(Duration.ofSeconds(10))
                .readWriteTimeout(Duration.ofSeconds(60))
                .retryMaxAttempts(3)
                .credentialsProvider(new StaticCredentialsProvider(oss.getAccessKeyId(), oss.getAccessKeySecret(),
                        oss.getSecurityToken()))
                .build();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException("OSS 存储缺少配置: " + name);
    }
}
