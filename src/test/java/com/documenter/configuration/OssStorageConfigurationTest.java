package com.documenter.configuration;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.documenter.service.FileStorage;
import com.documenter.service.impl.LocalFileStorage;
import com.documenter.service.impl.OssFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OssStorageConfigurationTest {
    @TempDir Path workspace;

    private ApplicationContextRunner context(String type) {
        return new ApplicationContextRunner()
                .withUserConfiguration(OssStorageConfiguration.class, LocalFileStorage.class, OssFileStorage.class)
                .withBean(StorageProperties.class, () -> {
                    var properties = new StorageProperties();
                    properties.setType(type);
                    properties.getLocal().setRoot(workspace.resolve("formal").toString());
                    properties.getLocal().setTemp(workspace.resolve("temp").toString());
                    properties.getOss().setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
                    properties.getOss().setRegion("cn-hangzhou");
                    properties.getOss().setBucket("private-documents");
                    if (type.equals("oss")) {
                        properties.getOss().setAccessKeyId("test-access-key");
                        properties.getOss().setAccessKeySecret("test-secret-key");
                    }
                    return properties;
                })
                .withPropertyValues("res.storage.type=" + type);
    }

    @Test
    void ossModeBuildsRealSdkClientAndDoesNotCreateFormalLocalDirectory() {
        context("oss").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FileStorage.class).hasSingleBean(OSSClient.class);
            assertThat(context.getBean(FileStorage.class)).isInstanceOf(OssFileStorage.class);
            assertThat(context).doesNotHaveBean(LocalFileStorage.class);
            assertThat(Files.exists(workspace.resolve("formal"))).isFalse();
            assertThat(Files.isDirectory(workspace.resolve("temp"))).isTrue();
        });
    }

    @Test
    void localModeDoesNotRequireOssCredentialsOrCreateClient() {
        context("local").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FileStorage.class).doesNotHaveBean(OSSClient.class);
            assertThat(context.getBean(FileStorage.class)).isInstanceOf(LocalFileStorage.class);
        });
    }

    @Test
    void missingOssSettingsFailWithActionableMessage() {
        new ApplicationContextRunner().withUserConfiguration(OssStorageConfiguration.class)
                .withBean(StorageProperties.class)
                .withPropertyValues("res.storage.type=oss").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("OSS 存储缺少配置: OSS_ENDPOINT");
                });
    }
}
