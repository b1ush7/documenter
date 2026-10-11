package com.documenter.component;

import com.documenter.enums.TaskType;
import com.documenter.service.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 任务处理器注册表（TODO 3.4）。
 *
 * <p>由 Spring 注入所有 {@link TaskHandler} 实现，按声明的 {@link TaskHandler#supports}
 * 匹配任务类型。P0 阶段只有一个占位实现；P1/P2 新增真实处理器后无需改动本类，
 * 但必须注意：如果多个处理器同时声明支持同一类型，这里会取<b>第一个</b>匹配项，
 * 那是隐式且易错的，因此启动时会检测冲突并直接失败。
 */
@Slf4j
@Component
public class TaskHandlerRegistry {

    private final List<TaskHandler> handlers;

    public TaskHandlerRegistry(List<TaskHandler> handlers) {
        this.handlers = handlers;
        detectConflicts(handlers);
        log.info("已注册任务处理器 {} 个: {}", handlers.size(),
                handlers.stream().map(TaskHandler::name).toList());
    }

    /** 查找能处理某类型的处理器。 */
    public Optional<TaskHandler> find(TaskType type) {
        return handlers.stream().filter(handler -> handler.supports(type)).findFirst();
    }

    /**
     * 启动时检测「多个处理器声明支持同一类型」。
     *
     * <p>这种情况如果静默通过，运行时选到哪个取决于 Bean 顺序，属于不可预期行为，
     * 因此直接让应用启动失败，强迫配置者明确取舍。
     */
    private static void detectConflicts(List<TaskHandler> handlers) {
        for (TaskType type : TaskType.values()) {
            List<String> claiming = handlers.stream()
                    .filter(handler -> handler.supports(type))
                    .map(TaskHandler::name)
                    .toList();
            if (claiming.size() > 1) {
                throw new IllegalStateException(
                        "任务类型 " + type + " 被多个处理器声明支持: " + claiming
                                + "。请让实现类通过 @ConditionalOnProperty 或 @Primary 明确互斥。");
            }
        }
    }
}
