package com.documenter.component.tool;

import com.documenter.component.AbstractAiTool;
import com.documenter.configuration.AiModelProperties;
import com.documenter.dto.InsertDocxImagesDTO;
import com.documenter.exception.BusinessException;
import com.documenter.service.AiTool;
import com.documenter.service.DocxDocumentService;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.ToolExecutionContext;
import com.documenter.vo.VersionVO;
import com.documenter.vo.DocumentEditPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 插入图片（TODO 4.2「封装受控工具：插入图片」）。
 *
 * <p>默认产出带类型化图片参数的待确认计划，不直接写版本。
 * 仅显式关闭确认的兼容模式保留直接执行。两条路径都受以下约束：
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
    private final AiModelProperties properties;
    private final DocumentVersionService versionService;

    public InsertImageTool(DocxDocumentService docxDocumentService, AiModelProperties properties,
                            DocumentVersionService versionService) {
        this.docxDocumentService = docxDocumentService;
        this.properties = properties;
        this.versionService = versionService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在指定段落位置插入一张图片。图片必须已经上传且属于当前用户，"
                + "需要用户提供图片文件 ID 与版本号。"
                + "默认只生成待确认计划，用户确认后才写入文档。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.required("target", "插入位置的段落块 ID，来自 read_document"),
                ToolParameter.required("imageFileId", "图片文件 ID（必须由用户提供）"),
                ToolParameter.required("imageVersionNo", "图片的版本号"),
                ToolParameter.optional("widthPixels", "显示宽度像素，默认 400"),
                ToolParameter.optional("heightPixels", "显示高度像素，默认 300"),
                ToolParameter.optional("reason", "插入理由，供用户确认"));
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
        if (!target.matches("^body(?:/t\\d+/r\\d+/c\\d+)*/p\\d+$")
                || !context.blockIds().contains(target)) {
            throw new BusinessException(422,
                    "目标「" + target + "」在当前版本中不存在；请先调用 read_document");
        }

        long imageFileId = requirePositiveLong(args, "imageFileId");
        int imageVersionNo = requirePositiveInt(args, "imageVersionNo");
        int width = requireDimension(optionalInt(args, "widthPixels"), 400, "widthPixels");
        int height = requireDimension(optionalInt(args, "heightPixels"), 300, "heightPixels");
        var image = versionService.downloadVersion(context.userId(), imageFileId, imageVersionNo);
        if (!"image/png".equalsIgnoreCase(image.contentType())
                && !"image/jpeg".equalsIgnoreCase(image.contentType())) {
            throw new BusinessException(422, "插入图片仅支持 PNG 或 JPEG 版本");
        }
        if (properties.isRequireConfirmation()) {
            var reference = new DocumentEditPlan.ImageReference(imageFileId, imageVersionNo, width, height);
            var plan = new DocumentEditPlan("在 " + target + " 追加内嵌图片", List.of(
                    new DocumentEditPlan.Operation("INSERT_IMAGE", target, null,
                            optionalString(args, "reason"), reference)), null);
            return ToolResult.withPlan("将在 " + target + " 追加图片 " + imageFileId + " 的 v"
                    + imageVersionNo + "，显示尺寸 " + width + "×" + height + " 像素（待确认）", plan);
        }

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

    private static int requireDimension(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value < 1 || value > MAX_DIMENSION) {
            throw new BusinessException(422, name + " 必须在 1 到 10000 像素之间");
        }
        return value;
    }
}
