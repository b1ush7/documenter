package com.documenter.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** AI SSE 使用虚拟线程，避免长连接占用有限的任务处理线程。 */
@Configuration
public class AiStreamExecutionConfiguration {

    public static final String AI_STREAM_EXECUTOR = "aiStreamExecutor";

    @Bean(name = AI_STREAM_EXECUTOR, destroyMethod = "close")
    public ExecutorService aiStreamExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ai-stream-", 0).factory());
    }
}
