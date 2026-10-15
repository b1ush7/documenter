package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.exception.BusinessException;
import com.documenter.service.AiTool;
import com.documenter.service.PagePreviewService;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.PagePreviewInfoVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 生成预览（TODO 4.2「封装受控工具：生成预览」）。
 *
 * <p>只返回预览的<b>元信息</b>（页数、是否可预览），不返回图片字节。
 * 原因：图片是二进制的大对象，塞进模型上下文既无意义（模型读不懂像素）
 * 又极其浪费 —— 一页 PNG 就能把上下文预算耗尽。
 *
 * <p>模型需要「确认版式」时，正确的做法是让用户去看生成的预览，
 * 而不是让模型去「看」它。因此这里返回页数与提示，
 * 由前端据此展示预览图。
 */
@Component
public class PreviewDocumentTool extends AbstractAiTool {

    public static final String NAME = "preview_document";

    private final PagePreviewService pagePreviewService;

    public PreviewDocumentTool(PagePreviewService pagePreviewService) {
        this.pagePreviewService = pagePreviewService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询指定版本的页面预览信息（页数、能否渲染、是否需要转换）。"
                + "需要用户查看实际版面时调用它获取页数，再让前端按页取图。"
                + "本工具只读，不产生新版本。";
    }

    @Override
    public List<ToolParameter> parameters() {
        // 版本由上下文决定，模型不能自行指定 —— 否则它可以去看历史版本并
        // 基于旧内容生成编辑计划，造成「照着旧版改新版」
        return List.of();
    }

    @Override
    public boolean mutating() {
        return false;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        if (context.fileId() == null) {
            throw new BusinessException(422, "当前对话没有关联文档，无法生成预览");
        }
        PagePreviewInfoVO info = pagePreviewService.describe(
                context.userId(), context.fileId(), context.versionNo());

        StringBuilder builder = new StringBuilder();
        builder.append("版本 v").append(context.versionNo()).append(" 的预览信息：");
        if (info.previewable()) {
            if (info.pageCount() != null) {
                builder.append("共 ").append(info.pageCount()).append(" 页");
            } else {
                builder.append("页数将在首次渲染时确定");
            }
            builder.append("，渲染引擎 ").append(info.engine());
            if (info.message() != null && !info.message().isBlank()) {
                builder.append("。").append(info.message());
            }
        } else {
            // 不可预览时把原因原样带出：可能是渲染后端未接入，
            // 这是部署问题而不是文档问题，模型需要据此调整说法
            builder.append("暂时无法预览。原因：")
                    .append(info.message() == null ? "未知" : info.message());
        }
        return ToolResult.of(builder.toString());
    }
}
