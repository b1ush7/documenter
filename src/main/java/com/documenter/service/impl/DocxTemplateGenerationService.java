package com.documenter.service.impl;

import com.documenter.component.DocxTemplateRenderer;
import com.documenter.configuration.StorageProperties;
import com.documenter.dto.GenerateDocxDTO;
import com.documenter.entity.DocTemplate;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.DocTemplateMapper;
import com.documenter.mapper.DocTemplateVersionMapper;
import com.documenter.service.FileService;
import com.documenter.service.FileStorage;
import com.documenter.vo.FileAssetVO;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Objects;

/** Reads a pinned, enabled DOCX template and creates an independent user-owned file. */
@Service
public class DocxTemplateGenerationService {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final DocTemplateMapper templates;
    private final DocTemplateVersionMapper versions;
    private final FileStorage storage;
    private final FileService files;
    private final DocxTemplateRenderer renderer;
    private final StorageProperties properties;

    public DocxTemplateGenerationService(DocTemplateMapper templates, DocTemplateVersionMapper versions,
            FileStorage storage, FileService files, DocxTemplateRenderer renderer, StorageProperties properties) {
        this.templates = templates;
        this.versions = versions;
        this.storage = storage;
        this.files = files;
        this.renderer = renderer;
        this.properties = properties;
    }

    public FileAssetVO generate(Long userId, GenerateDocxDTO request) {
        if (userId == null || userId < 1 || request.getTemplateId() == null || request.getTemplateId() < 1
                || request.getTemplateVersion() == null || request.getTemplateVersion() < 1) {
            throw new BusinessException(400, "模板生成必须指定模板 ID 和明确的正版本号");
        }
        if (request.getTitle() != null || request.getBlocks() != null) {
            throw new BusinessException(400, "模板生成不能同时提交 title 或 blocks");
        }
        DocTemplate template = requireEnabled(request.getTemplateId());
        if (!request.getTemplateVersion().equals(template.getVersionNo())) throw changed();
        String definitions = template.getVariables();
        var source = versions.findVersion(template.getId(), request.getTemplateVersion());
        if (source == null) throw new BusinessException(404, "模板版本不存在");
        if (!DOCX.equalsIgnoreCase(source.getContentType()) || !"docx".equalsIgnoreCase(source.getExtension())) {
            throw new BusinessException(400, "当前模板不是可生成的 DOCX 文档");
        }
        long maxSize = properties.getUpload().getMaxSizeBytes();
        if (source.getSizeBytes() == null || source.getSizeBytes() < 1 || source.getStorageKey() == null
                || source.getStorageKey().isBlank()) {
            throw new BusinessException(500, "模板文件元数据不完整，请联系管理员");
        }
        if (source.getSizeBytes() > maxSize) throw new BusinessException(413, "模板文件超过大小限制");
        if (!storage.exists(source.getStorageKey())) throw new BusinessException(500, "模板文件已丢失，请联系管理员");
        byte[] bytes;
        try (var input = storage.read(source.getStorageKey())) {
            int limit = (int) Math.min(maxSize, Integer.MAX_VALUE - 1L);
            bytes = input.readNBytes(limit + 1);
            if (bytes.length > maxSize) throw new BusinessException(413, "模板文件超过大小限制");
        } catch (IOException e) {
            throw new BusinessException(500, "模板文件读取失败，请稍后重试");
        }
        byte[] generated = renderer.render(bytes, definitions, request.getVariables());
        if (generated.length > maxSize) throw new BusinessException(413, "生成的模板文档超过大小限制");
        // A second check rejects changes observed during rendering, before any new user file is stored.
        DocTemplate latest = requireEnabled(template.getId());
        if (!request.getTemplateVersion().equals(latest.getVersionNo())
                || !Objects.equals(definitions, latest.getVariables())) throw changed();
        String name = request.getFileName() == null || request.getFileName().isBlank()
                ? template.getName() : request.getFileName();
        if (name == null || name.isBlank()) name = "模板生成.docx";
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".docx")) name += ".docx";
        String instruction = "模板 ID=" + template.getId() + "，内容版本=" + request.getTemplateVersion();
        if (request.getInstruction() != null && !request.getInstruction().isBlank()) instruction += "；" + request.getInstruction();
        return files.createGeneratedDocx(userId, name, generated, instruction);
    }

    private DocTemplate requireEnabled(Long id) {
        var template = templates.selectById(id);
        if (template == null || !Boolean.TRUE.equals(template.getEnabled())) {
            throw new BusinessException(404, "模板不存在或未启用");
        }
        if (!DOCX.equalsIgnoreCase(template.getContentType())) {
            throw new BusinessException(400, "当前模板不是可生成的 DOCX 文档");
        }
        return template;
    }

    private static BusinessException changed() { return new BusinessException(409, "模板内容或变量定义已更新，请刷新后重新生成"); }
}
