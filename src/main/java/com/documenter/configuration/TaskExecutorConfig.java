package com.documenter.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池（TODO 3.4「使用受限线程池异步执行 OCR、转换、生成等耗时任务」）。
 *
 * <p>调度与处理使用独立有界线程池，避免调度线程占满处理器的执行资源。
 * 队列满或关闭时抛出拒绝异常，由引擎把已持久化任务标记为失败，供用户重试。
 */
@Slf4j
@Configuration
public class TaskExecutorConfig {

    public static final String TASK_EXECUTOR = "documentTaskExecutor";
    public static final String TASK_COORDINATOR = "documentTaskCoordinator";

    @Bean(name = TASK_EXECUTOR)
    public ThreadPoolTaskExecutor documentTaskExecutor(TaskExecutorProperties properties) {
        return createExecutor(properties, "doc-task-");
    }

    @Bean(name = TASK_COORDINATOR)
    public ThreadPoolTaskExecutor documentTaskCoordinator(TaskExecutorProperties properties) {
        return createExecutor(properties, "doc-coordinator-");
    }

    private ThreadPoolTaskExecutor createExecutor(TaskExecutorProperties properties, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds((int) properties.getKeepAlive().toSeconds());
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) properties.getShutdownAwait().toSeconds());
        executor.initialize();
        log.info("任务线程池已初始化, prefix={}, core={}, max={}, queue={}, 默认超时={}s",
                prefix, properties.getCorePoolSize(), properties.getMaxPoolSize(),
                properties.getQueueCapacity(), properties.getDefaultTimeout().toSeconds());
        return executor;
    }
}
