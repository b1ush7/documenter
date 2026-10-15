package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.dto.InsertDocxImagesDTO;
import com.documenter.exception.BusinessException;
import com.documenter.service.AiTool;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.VersionVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 插入图片（TODO 4.2「封装受控工具：插入图片」）。
 *
 * <p>与段落/表格工具不同，这个工具<b>直接执行</b>而不是产出待确认计划。
 * 原因是它的参数结构（图片文件 ID、版本、宽高）无法用
 * {@code DocumentEditPlan} 的「目标 + 内容」模型表达 ——
 * 硬塞进去会得到一个语义不清的计划。
 *
 * <p>直接执行的代价是用户在提交前看不到差异预览。为了控制这个风险：
 * <ul>
 *   <li>只允许插入<b>用户自己拥有的</b>图片文件（由后端 {@code appendImage} 校验），
 *       因此模型无法引用他人的图片或网络地址；</li>
 *   <li>宽高有上限（后端限制 10000 像素），避免一次插入撑爆文档；</li>
 *   <li>图片文件 ID 必须由用户明确提供 —— 模型无法自行发现文件 ID，
 *       因为它没有任何「列出文件」的工具。</li>
 * </ul>
 */
@Slf4j
@Component
public class InsertImageTool extends AbstractAiTool {

    public static final String NAME = "insert_image";

    private static final int MAX_DIMENSION = 10000;

    private final DocxDocumentService docxDocumentService;

    public InsertImageTool(DocxDocumentService docxDocumentService) {
        this.docxDocumentService = docxDocumentService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在指定段落位置插入一张图片。图片必须已经上传且属于当前用户，"
                + "需要用户提供图片文件 ID 与版本号。"
                + "本工具会立即生成新版本（图片参数无法用「修改计划」表达）。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.required("target", "插入位置的段落块 ID，来自 read_document"),
                ToolParameter.required("imageFileId", "图片文件 ID（必须由用户提供）"),
                ToolParameter.required("imageVersionNo", "图片的版本号"),
                ToolParameter.optional("widthPixels", "显示宽度像素，默认 400"),
                ToolParameter.optional("heightPixels", "显示高度像素，默认 300"));
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        Map<String, String> args = normalize(arguments);

        if (!"DOCX".equalsIgnoreCase(context.format())) {
            throw new BusinessException(422, "只有 DOCX 文档支持插入图片");
        }

        String target = requireString(args, "target", "插入位置的段落块 ID");
        if (!context.blockIds().contains(target)) {
            throw new BusinessException(422,
                    "目标「" + target + "」在当前版本中不存在；请先调用 read_document");
        }

        long imageFileId = requirePositiveLong(args, "imageFileId");
        int imageVersionNo = requirePositiveInt(args, "imageVersionNo");
        int width = clampDimension(optionalInt(args, "widthPixels"), 400, "widthPixels");
        int height = clampDimension(optionalInt(args, "heightPixels"), 300, "heightPixels");

        InsertDocxImagesDTO.Operation operation = new InsertDocxImagesDTO.Operation();
        operation.setBlockId(target);
        operation.setImageFileId(imageFileId);
        operation.setImageVersionNo(imageVersionNo);
        operation.setWidthPixels(width);
        operation.setHeightPixels(height);

        InsertDocxImagesDTO request = new InsertDocxImagesDTO();
        request.setInstruction("在 " + target + " 插入图片");
        request.setOperations(List.of(operation));

        // 版本由上下文决定，不接受模型指定 —— 避免把基于 v1 的操作应用到 v3
        VersionVO version = docxDocumentService.insertImages(
                context.userId(), context.fileId(), context.versionNo(), request);

        log.info("智能体插入图片成功, fileId={}, sourceVersion={}, newVersion={}",
                context.fileId(), context.versionNo(), version.getVersionNo());

        return ToolResult.withArtifact(
                "已在 " + target + " 插入图片，生成新版本 v" + version.getVersionNo(),
                "v" + version.getVersionNo());
    }

    private static long requirePositiveLong(Map<String, String> args, String name) {
        String value = requireString(args, name, name);
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new BusinessException(422, "参数「" + name + "」必须为正数");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BusinessException(422,
                    "参数「" + name + "」应为整数，实际为「" + value + "」");
        }
    }

    private static int requirePositiveInt(Map<String, String> args, String name) {
        Integer value = optionalInt(args, name);
        if (value == null) {
            throw new BusinessException(422, "缺少必填参数「" + name + "」");
        }
        if (value <= 0) {
            throw new BusinessException(422, "参数「" + name + "」必须为正数");
        }
        return value;
    }

    /**
     * 归一化尺寸。
     *
     * <p>越界时直接修正而不是报错：模型给出的像素值通常只是「大概意思」，
     * 因为一个尺寸超限就整轮失败，用户体验不成比例。
     * 但修正后会写进工具结果，让用户知道实际用了多少。
     */
    private static int clampDimension(Integer value, int fallback, String name) {
        if (value == null || value <= 0) {
            return fallback;
        }
        if (value > MAX_DIMENSION) {
            log.info("图片尺寸 {} 超出上限，已收敛为 {}", value, MAX_DIMENSION);
            return MAX_DIMENSION;
        }
        return value;
    }
}
