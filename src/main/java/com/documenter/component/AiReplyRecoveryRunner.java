package com.documenter.component;

import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.mapper.AiMessageMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 单实例兼容恢复；cluster 开启后跳过，由执行租约恢复器处理。 */
@Component
public class AiReplyRecoveryRunner implements ApplicationRunner {
    private ExecutionLeaseService leases;

    @org.springframework.beans.factory.annotation.Autowired
    public void setExecutionLeases(ExecutionLeaseService leases) { this.leases = leases; }
    private final AiMessageMapper messages;
    private final TaskExecutorProperties properties;

    public AiReplyRecoveryRunner(AiMessageMapper messages, TaskExecutorProperties properties) {
        this.messages = messages;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (leases != null && leases.enabled()) return;
        if (properties.isRecoverOnStartup()) messages.failInterruptedReplies();
    }
}
