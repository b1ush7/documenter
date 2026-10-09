package com.documenter.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池（TODO 3.4「使用受限线程池异步执行 OCR、转换、生成等耗时任务」）。
 *
 * <p>关键取舍：
 * <ul>
 *   <li><b>有界队列 + CallerRuns</b>：队列无界会让任务无限堆积、内存被吃满，
 *       而且用户等待时间不可预期。队列满时由提交线程自己执行，形成天然背压——
 *       宁可让提交接口慢一点，也不要静默堆积。</li>
 *   <li><b>不丢弃任务</b>：AbortPolicy 会直接抛异常导致任务丢失，
 *       对文档处理这类有状态流程来说不可接受。</li>
 *   <li><b>优雅停机</b>：waitForTasksToCompleteOnShutdown 让正在执行的任务有机会收尾，
 *       超过 awaitTerminationSeconds 才强制中断。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class TaskExecutorConfig {

    public static final String TASK_EXECUTOR = "documentTaskExecutor";

    @Bean(name = TASK_EXECUTOR)
    public ThreadPoolTaskExecutor documentTaskExecutor(TaskExecutorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds((int) properties.getKeepAlive().toSeconds());
        executor.setThreadNamePrefix("doc-task-");
        // 队列满时由调用线程执行：不丢任务，同时把压力反馈给上游
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) properties.getShutdownAwait().toSeconds());
        executor.initialize();
        log.info("任务线程池已初始化, core={}, max={}, queue={}, 默认超时={}s",
                properties.getCorePoolSize(), properties.getMaxPoolSize(),
                properties.getQueueCapacity(), properties.getDefaultTimeout().toSeconds());
        return executor;
    }
}
