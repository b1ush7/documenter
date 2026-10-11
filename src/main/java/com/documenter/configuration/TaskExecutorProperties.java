package com.documenter.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 异步任务执行配置（TODO 3.4「使用受限线程池异步执行耗时任务」）。
 *
 * <p>刻意不使用无界队列：队列无界时，突发流量会让任务无限堆积、内存被吃满，
 * 而且用户看到的等待时间完全不可预期。这里用「有界队列 + CallerRuns 拒绝策略」，
 * 队列满时由提交线程自己执行，形成天然的背压。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "res.task")
public class TaskExecutorProperties {

    /** 核心线程数，默认按 CPU 核数。 */
    private int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());

    /** 最大线程数。 */
    private int maxPoolSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);

    /** 有界队列容量。 */
    private int queueCapacity = 50;

    /** 空闲线程存活时间。 */
    private Duration keepAlive = Duration.ofSeconds(60);

    /** 任务默认超时时间，超过则置为失败（TODO 3.4「配置超时」）。 */
    private Duration defaultTimeout = Duration.ofMinutes(10);

    /** 默认最大重试次数。 */
    private int defaultMaxRetry = 2;

    /** 优雅停机等待时间，必须小于容器的强制终止时间，否则任务会被硬杀。 */
    private Duration shutdownAwait = Duration.ofSeconds(30);

    /**
     * 是否在启动时把中断的 RUNNING 任务标记为失败（TODO 3.4「重启恢复」）。
     * 默认开启；多实例部署时只应有一个实例执行，或改用分布式锁。
     */
    private boolean recoverOnStartup = true;

    /**
     * 是否在启动时重新入队 PENDING 任务。
     *
     * <p>默认关闭：P0 阶段任务处理器还是占位实现，重新入队没有实际意义；
     * 接入真实处理器后开启，否则重启前排队中的任务会永远停在 PENDING。
     */
    private boolean requeuePendingOnStartup = false;
}
