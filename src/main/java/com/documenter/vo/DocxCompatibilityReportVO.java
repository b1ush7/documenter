package com.documenter.vo;

import java.util.List;

/**
 * DOCX 应用级兼容性验收报告（TODO 4.1）。
 *
 * <p>回答的问题不是「ZIP 能不能解开」——那由 POI 的结构测试覆盖——
 * 而是「办公软件能否正常打开并排出预期的版面」。
 *
 * @param fileId       文件 ID
 * @param versionNo    被验收的明确版本号
 * @param compatible   是否通过。有 ERROR 级问题时为 false
 * @param engine       实际使用的渲染引擎
 * @param pageCount    渲染后端排出的页数
 * @param renderedPages 实际渲染检查的页数
 * @param missingFonts 缺失并被替换的字体；非空说明版式可能与预期不同
 * @param issues       发现的问题清单
 */
public record DocxCompatibilityReportVO(
        Long fileId,
        Integer versionNo,
        boolean compatible,
        String engine,
        int pageCount,
        int renderedPages,
        List<String> missingFonts,
        List<Issue> issues) {

    public DocxCompatibilityReportVO {
        missingFonts = missingFonts == null ? List.of() : List.copyOf(missingFonts);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 是否存在 ERROR 级问题。 */
    public boolean hasError() {
        return issues.stream().anyMatch(issue -> Severity.ERROR == issue.severity());
    }

    /**
     * 单个问题。
     *
     * @param severity ERROR 表示验收不通过；WARNING 表示可用但需人工确认
     * @param code     稳定的问题代码，便于前端做映射与统计
     * @param message  面向人的说明
     */
    public record Issue(Severity severity, String code, String message) {
    }

    /** 问题级别。 */
    public enum Severity {
        /** 验收不通过。 */
        ERROR,
        /** 可用，但存在版式偏差风险，需人工确认。 */
        WARNING
    }
}
