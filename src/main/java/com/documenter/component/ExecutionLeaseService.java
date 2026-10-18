package com.documenter.component;

import com.documenter.configuration.TaskExecutorProperties;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.ExecutionLeaseMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;

/** 心跳只续期本实例仍在执行的工作；丢失租约的执行者只能失败，不会重新取得原租约。 */
@Slf4j
@Component
public class ExecutionLeaseService implements ApplicationListener<ApplicationReadyEvent> {
    public interface Registration extends AutoCloseable { @Override void close(); }
    public static final Registration NOOP = () -> { };
    private final String owner = UUID.randomUUID().toString();
    private final Set<Long> activeTasks = ConcurrentHashMap.newKeySet();
    private final Set<Long> activeReplies = ConcurrentHashMap.newKeySet();
    private final ExecutionLeaseMapper mapper;
    private final TaskExecutorProperties.Cluster policy;
    private final TransactionTemplate transactions;
    private final ObjectProvider<TaskExecutionEngine> engines;
    private ScheduledExecutorService scheduler;

    public ExecutionLeaseService(ExecutionLeaseMapper mapper, TaskExecutorProperties properties,
                                 PlatformTransactionManager manager, ObjectProvider<TaskExecutionEngine> engines) {
        this.mapper = mapper;
        this.policy = properties.getCluster();
        this.transactions = new TransactionTemplate(manager);
        this.engines = engines;
        if (enabled() && (policy.getHeartbeat() == null || policy.getHeartbeat().toMillis() < 100
                || policy.getLeaseDuration() == null || policy.getLeaseDuration().compareTo(policy.getHeartbeat().multipliedBy(3)) < 0
                || policy.getLeaseDuration().compareTo(java.time.Duration.ofHours(1)) > 0
                || policy.getBatchSize() < 1 || policy.getBatchSize() > 1000)) {
            throw new IllegalArgumentException("执行租约要求 heartbeat>=100ms、lease-duration>=3*heartbeat 且<=1h、batch-size=1..1000");
        }
    }

    public boolean enabled() { return policy.isEnabled(); }
    public String ownerId() { return owner; }
    private long micros() { return policy.getLeaseDuration().toNanos() / 1000; }

    public int claimTask(long id) { return mapper.claimTask(id, owner, micros()); }
    public void claimReply(long id) {
        if (enabled() && mapper.claimReply(id, owner, micros()) != 1) throw lost();
    }
    public Registration trackTask(long id) { return track(activeTasks, id); }
    public Registration trackReply(long id) { return track(activeReplies, id); }
    private Registration track(Set<Long> active, long id) {
        if (!enabled()) return NOOP;
        if (!active.add(id)) throw new IllegalStateException("同一执行租约被重复追踪");
        return () -> active.remove(id);
    }

    /** 必须在调用方事务内锁定任务，锁一直保留到版本提交；提交前再次验证数据库时间。 */
    public void fenceVersion(long task, long user, long file) {
        if (!enabled()) return;
        requireTransaction();
        Runnable check = () -> { if (mapper.lockTaskVersion(task, owner, user, file) == null) throw lost(); };
        check.run();
        beforeCommit(check);
    }

    /** 回复预留和完成均在短事务内执行；结果返回之前拒绝旧执行者。 */
    public void fenceReply(long reply) {
        if (!enabled()) return;
        requireTransaction();
        Runnable check = () -> { if (mapper.lockReply(reply, owner) == null) throw lost(); };
        check.run();
        beforeCommit(() -> { if (mapper.lockReplyOwnership(reply, owner) == null) throw lost(); });
    }

    public boolean taskActive(long id) { return !enabled() || mapper.activeTask(id, owner) == 1; }

    /** 步骤、进度、输出版本和终态更新与恢复使用同一任务行锁。 */
    public void mutateTask(long id, Runnable mutation) {
        if (!enabled()) { mutation.run(); return; }
        transactions.executeWithoutResult(status -> {
            if (mapper.lockTask(id, owner) == null) throw lost();
            beforeCommit(() -> { if (mapper.lockTaskOwnership(id, owner) == null) throw lost(); });
            mutation.run();
        });
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("执行租约写入隔离必须处于数据库事务内");
        }
    }
    private static void beforeCommit(Runnable check) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { check.run(); }
        });
    }
    private static BusinessException lost() { return new BusinessException(409, "执行租约已失效，旧执行者不能继续写入，请重试"); }

    public void heartbeat() {
        if (!enabled()) return;
        renew(new ArrayList<>(activeTasks), false);
        renew(new ArrayList<>(activeReplies), true);
    }
    private void renew(List<Long> ids, boolean replies) {
        for (int offset = 0; offset < ids.size(); offset += policy.getBatchSize()) {
            var batch = ids.subList(offset, Math.min(ids.size(), offset + policy.getBatchSize()));
            transactions.executeWithoutResult(status -> {
                // NOW() 在语句开始时取值。先跳过持锁行，再用新语句检查有效期，
                // 防止等待行锁的旧心跳在实际过期后把租约续回。
                var locked = replies ? mapper.lockRenewableReplies(batch, owner) : mapper.lockRenewableTasks(batch, owner);
                if (locked.isEmpty()) return;
                if (replies) mapper.renewReplies(locked, owner, micros());
                else mapper.renewTasks(locked, owner, micros());
            });
        }
    }

    /** 行锁 + SKIP LOCKED 允许多个恢复器并行，绝不回收有效租约或无归属的历史记录。 */
    public void recoverExpired() {
        if (!enabled()) return;
        transactions.executeWithoutResult(status -> {
            var ids = mapper.lockExpiredTasks(policy.getBatchSize());
            if (!ids.isEmpty()) { mapper.failSteps(ids); mapper.failTasks(ids); }
        });
        transactions.executeWithoutResult(status -> {
            var ids = mapper.lockExpiredReplies(policy.getBatchSize());
            if (!ids.isEmpty()) mapper.failReplies(ids);
        });
    }

    @Override public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!enabled() || scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "execution-lease-heartbeat"); thread.setDaemon(true); return thread;
        });
        scheduler.scheduleWithFixedDelay(this::tick, 0, policy.getHeartbeat().toMillis(), TimeUnit.MILLISECONDS);
    }
    private void tick() {
        try {
            heartbeat();
            recoverExpired();
            if (policy.isRequeuePending()) for (long id : mapper.pendingTasks(policy.getBatchSize())) {
                if (!engines.getObject().submitRecovered(id)) break;
            }
        } catch (RuntimeException failure) {
            // 数据库不可用时不续租、不变更状态，旧执行者的持久化仍须经过数据库租约检查。
            log.warn("执行租约维护失败，将在下次心跳重试", failure);
        }
    }
    @PreDestroy public void close() { if (scheduler != null) scheduler.shutdownNow(); }
}
