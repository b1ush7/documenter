package com.documenter.component;

import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.mapper.AiMessageMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 与现有任务恢复相同，默认用于单实例；多实例部署须关闭启动恢复。 */
@Component
public class AiReplyRecoveryRunner implements ApplicationRunner {
    private final AiMessageMapper messages;
    private final TaskExecutorProperties properties;

    public AiReplyRecoveryRunner(AiMessageMapper messages, TaskExecutorProperties properties) {
        this.messages = messages;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.isRecoverOnStartup()) messages.failInterruptedReplies();
    }
}
