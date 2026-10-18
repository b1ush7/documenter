package com.documenter.vo;

import java.util.List;

/** sourceVersion/outputVersion 分别用于确认前后既有预览、比较和下载入口。 */
public record AiReplyVO(AiMessageVO message, String runStatus, String planStatus,
                        Integer sourceVersion, Integer outputVersion,
                        AgentRunResult result, List<PlannedChange> changes) {
    public record PlannedChange(String type, String target, String before, String after, String reason,
                                 DocumentEditPlan.ImageReference image, DocumentEditPlan.ParagraphFormat format,
                                 ParagraphStyle beforeFormat) {
        public PlannedChange(String type, String target, String before, String after, String reason) {
            this(type, target, before, after, reason, null, null, null);
        }
        public PlannedChange(String type, String target, String before, String after, String reason,
                              DocumentEditPlan.ImageReference image) {
            this(type, target, before, after, reason, image, null, null);
        }
    }
    public record ParagraphStyle(String styleId, Integer headingLevel, String numberingFormat, Boolean pageBreakBefore) {
        public ParagraphStyle(String styleId, Integer headingLevel, String numberingFormat) {
            this(styleId, headingLevel, numberingFormat, null);
        }
    }
}
