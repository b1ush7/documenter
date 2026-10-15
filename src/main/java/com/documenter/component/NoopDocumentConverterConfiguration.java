package com.documenter.component;

import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 未配置外部转换器时的占位实现（TODO 3.3 页面预览）。
 *
 * <p>为什么不直接让 DOCX/XLSX 预览抛「未实现」：
 * 那样调用方无法区分「功能没做」和「部署时忘了装 LibreOffice」，
 * 排查成本很高。这里返回的是<b>可操作</b>的说明——告诉运维具体装什么、配哪个参数。
 *
 * <p>为什么不假装支持（例如把 DOCX 当一个空页面渲染）：
 * 那会产出版式完全错误的预览，比明确报错更糟——用户会以为文档坏了。
 *
 * <p>用 {@link ConditionalOnMissingBean} 保证容器里<b>始终恰好有一个</b>
 * {@link DocumentConverter}：配置了 libreoffice 就用真实实现，没配置就用本占位实现。
 * 这样业务层可以直接注入单个 Bean，不必处理「可能没有实现」的分支。
 */
@Slf4j
@Configuration
public class NoopDocumentConverterConfiguration {

    @Bean
    @ConditionalOnMissingBean(DocumentConverter.class)
    public DocumentConverter noopDocumentConverter() {
        log.info("未启用外部文档转换器，DOCX/XLSX 页面预览不可用；"
                + "PDF 与图片预览不受影响。启用方式：res.preview.converter=libreoffice");
        return new NoopDocumentConverter();
    }

    /** 占位转换器：任何转换请求都返回可操作的错误说明。 */
    static class NoopDocumentConverter implements DocumentConverter {

        @Override
        public String engineName() {
            return "none";
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String configurationHint() {
            return "请设置 res.preview.converter=libreoffice 并安装 LibreOffice"
                    + "（可用 res.preview.libreoffice-path 指定 soffice 路径），"
                    + "或使用环境变量 PREVIEW_CONVERTER";
        }

        @Override
        public byte[] toPdf(byte[] source, String extension, String baseName) {
            // 用 503 而不是 500/501：这是「部署未配置」而非「代码未实现」或「服务端崩溃」，
            // 运维看到后应立即知道要去配转换器，而不是去查代码。
            throw new BusinessException(503,
                    "当前部署未接入文档转换器，无法生成 " + extension.toUpperCase() + " 的版式预览；"
                            + configurationHint());
        }
    }
}
