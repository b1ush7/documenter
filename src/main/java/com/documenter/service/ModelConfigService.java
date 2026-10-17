package com.documenter.service;

import com.documenter.dto.ModelConfigQueryDTO;
import com.documenter.dto.SaveModelConfigDTO;
import com.documenter.vo.ModelConfigVO;
import com.documenter.vo.PageResult;

/**
 * 模型配置管理（TODO 3.2「管理模型配置；模型密钥不返回前端」）。
 *
 * <p>所有出参统一为 {@link ModelConfigVO}，它不含密文与提示位字段。
 * 需要明文密钥时只能通过 {@link #requirePlainApiKey(String)} 取用，
 * 该方法的返回值<b>不得</b>写入日志、响应体或异常消息。
 */
public interface ModelConfigService {

    PageResult<ModelConfigVO> list(ModelConfigQueryDTO query);

    ModelConfigVO detail(Long id);

    ModelConfigVO create(SaveModelConfigDTO dto);

    /** 更新；dto.apiKey 留空表示保持原密钥不变。 */
    ModelConfigVO update(Long id, SaveModelConfigDTO dto);

    ModelConfigVO updateStatus(Long id, String status);

    /**
     * 删除配置。
     *
     * <p>默认配置不允许直接删除：调用方应先指定另一个默认配置，
     * 否则该用途会处于「无默认配置」状态，后续任务无法自动选取模型。
     */
    void delete(Long id);

    /**
     * 取明文 API Key，供后续接入模型调用时使用。
     *
     * @param code 配置标识
     * @return 明文密钥；未配置或未启用时返回 null
     */
    String requirePlainApiKey(String code);

    /**
     * 读取某用途当前启用的默认配置，供服务端运行时调用。
     * 返回对象只允许在服务端内部使用，序列化到接口前必须丢弃 apiKey。
     */
    ModelRuntimeConfig requireDefaultRuntimeConfig(String scope);

    record ModelRuntimeConfig(String code, String provider, String baseUrl,
                              String modelName, String apiKey, int timeoutSeconds,
                              Integer maxOutputTokens, java.math.BigDecimal temperature) {
    }
}
