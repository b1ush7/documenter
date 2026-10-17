package com.documenter.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

/**
 * 文本模型提供者（TODO 4.2「优先沿用已有 LangChain4j 依赖，接入一个选定的文本模型」）。
 *
 * <p>为什么要有这层抽象：模型来源可能是部署配置（yaml/环境变量）、
 * 数据库中的模型配置表，或将来按用途路由到不同模型。
 * 智能体循环只依赖 {@link ChatModel}，不关心它从哪来 ——
 * 换来源时只改实现，循环与工具代码不动。
 *
 * <p>{@link #available()} 与 {@link #chatModel()} 分开是刻意的：
 * 未配置模型时应用仍应能启动并正常提供文件、任务等能力，
 * 只在真正发起对话时给出可操作提示。若让它在启动期抛异常，
 * 部署者在接入模型之前就无法验证任何其他功能。
 */
public interface AiModelProvider {

    /** 提供者名称，用于日志与前端展示。 */
    String providerName();

    /** 是否具备真实调用条件。 */
    boolean available();

    /** 未就绪时的可操作说明。 */
    String configurationHint();

    /**
     * 取得对话模型。
     *
     * @throws com.documenter.exception.BusinessException 未就绪时抛出，附 {@link #configurationHint()}
     */
    ChatModel chatModel();

    /**
     * 取得流式对话模型；返回 null 表示提供者只能同步调用。
     */
    default StreamingChatModel streamingChatModel() {
        return null;
    }
}
