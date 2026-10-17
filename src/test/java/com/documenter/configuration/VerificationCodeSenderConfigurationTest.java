package com.documenter.configuration;

import com.documenter.service.VerificationCodeSender;
import com.documenter.service.impl.MockVerificationCodeSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationCodeSenderConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockVerificationCodeSender.class, RequiresSender.class);

    @Test
    void missingSwitchPreservesDevelopmentMock() {
        runner.run(context -> assertThat(context).hasSingleBean(VerificationCodeSender.class)
                .hasSingleBean(MockVerificationCodeSender.class));
    }

    @Test
    void explicitTrueEnablesDevelopmentMock() {
        runner.withPropertyValues("res.verification.mock-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(MockVerificationCodeSender.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "invalid", "0"})
    void disabledOrInvalidSwitchNeverFallsBackToLoggingCodes(String value) {
        runner.withPropertyValues("res.verification.mock-enabled=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
        });
    }

    @Test
    void disabledMockAllowsExplicitReplacementChannel() {
        runner.withPropertyValues("res.verification.mock-enabled=false").withUserConfiguration(Replacement.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(VerificationCodeSender.class)
                            .doesNotHaveBean(MockVerificationCodeSender.class);
                    assertThat(context.getBean(VerificationCodeSender.class))
                            .isSameAs(context.getBean("replacementSender"));
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RequiresSender {
        @Bean Object dependentService(VerificationCodeSender sender) { return new Object(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Replacement {
        @Bean VerificationCodeSender replacementSender() { return (identifier, scene, code) -> {}; }
    }
}
