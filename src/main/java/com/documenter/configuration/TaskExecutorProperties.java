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
 * 而且用户看到的等待时间完全不可预期。调度与处理各使用一个有界线程池，
 * 下列线程数及队列容量分别应用于两个池。队列满时由引擎记录 QUEUE_FULL 失败。
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
     * 默认开启，仅适用于未启用 cluster 的单实例。cluster 开启后跳过本启动恢复，改为扫描过期租约。
     */
    private boolean recoverOnStartup = true;

    /**
     * 是否在启动时重新入队 PENDING 任务。
     *
     * <p>属性类默认关闭；部署 YAML 对单实例默认开启。该开关仅在 recoverOnStartup 开启时生效。
     */
    private boolean requeuePendingOnStartup = false;

    private Cluster cluster = new Cluster();

    @Getter
    @Setter
    public static class Cluster {
        /** 所有共享数据库的实例必须采用一致模式；切换前停止旧执行者。 */
        private boolean enabled;
        private Duration heartbeat = Duration.ofSeconds(10);
        private Duration leaseDuration = Duration.ofSeconds(60);
        private int batchSize = 100;
        private boolean requeuePending = true;
    }
}
