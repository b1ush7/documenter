package com.documenter.configuration;

import com.documenter.service.ModelConfigService;
import com.documenter.service.OcrEngine;
import com.documenter.service.impl.AliyunOcrEngine;
import com.documenter.service.impl.CloudVisionOcrEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OcrProviderConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, AliyunOcrEngine.class, CloudVisionOcrEngine.class)
            .withBean(StorageProperties.class, StorageProperties::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ModelConfigService.class, () -> mock(ModelConfigService.class));

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OcrProperties.class)
    static class Properties { }

    @Test
    void preservesExistingDefaultProvider() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(OcrEngine.class)
                .hasSingleBean(CloudVisionOcrEngine.class).doesNotHaveBean(AliyunOcrEngine.class));
    }

    @Test
    void selectsAliyunWithoutRequiringAdminModelOrCredentialsAtStartup() {
        runner.withPropertyValues("res.ocr.provider=aliyun", "res.ocr.aliyun.type=Advanced")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OcrEngine.class)
                            .hasSingleBean(AliyunOcrEngine.class).doesNotHaveBean(CloudVisionOcrEngine.class);
                    assertThat(context.getBean(OcrEngine.class).available()).isFalse();
                    assertThat(context.getBean(OcrProperties.class).getAliyun().getType()).isEqualTo("Advanced");
                });
    }

    @Test
    void unknownProviderDoesNotSilentlyCallAnotherProvider() {
        runner.withPropertyValues("res.ocr.provider=typo")
                .run(context -> assertThat(context).doesNotHaveBean(OcrEngine.class));
    }
}
