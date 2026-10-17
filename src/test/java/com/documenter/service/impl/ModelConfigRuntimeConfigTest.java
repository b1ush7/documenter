package com.documenter.service.impl;

import com.documenter.entity.ModelConfig;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.ModelConfigMapper;
import com.documenter.service.AuditService;
import com.documenter.util.SecretCipher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelConfigRuntimeConfigTest {

    @Test
    void exposesDecryptedRuntimeConfigOnlyForEnabledDefault() {
        ModelConfigMapper mapper = mock(ModelConfigMapper.class);
        SecretCipher cipher = mock(SecretCipher.class);
        ModelConfig config = new ModelConfig();
        config.setCode("cloud-ocr");
        config.setProvider("openai");
        config.setBaseUrl("https://cloud.example.test/v1");
        config.setModelName("vision-ocr");
        config.setApiKeyCipher("encrypted");
        config.setTimeoutSeconds(90);
        when(mapper.selectOne(any())).thenReturn(config);
        when(cipher.decrypt("encrypted")).thenReturn("plain-secret");
        var service = new ModelConfigServiceImpl(mapper, cipher, mock(AuditService.class));

        var runtime = service.requireDefaultRuntimeConfig("OCR");

        assertEquals("cloud-ocr", runtime.code());
        assertEquals("https://cloud.example.test/v1", runtime.baseUrl());
        assertEquals("vision-ocr", runtime.modelName());
        assertEquals("plain-secret", runtime.apiKey());
        assertEquals(90, runtime.timeoutSeconds());
    }

    @Test
    void reportsMissingCloudOcrModelClearly() {
        ModelConfigMapper mapper = mock(ModelConfigMapper.class);
        var service = new ModelConfigServiceImpl(
                mapper, mock(SecretCipher.class), mock(AuditService.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.requireDefaultRuntimeConfig("OCR"));

        assertEquals(503, error.getCode());
    }
}
