package com.documenter.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.documenter.configuration.OcrProperties;
import com.documenter.dto.SaveOcrCorrectionsDTO;
import com.documenter.entity.OcrCorrection;
import com.documenter.exception.BusinessException;
import com.documenter.mapper.OcrCorrectionMapper;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.OcrCorrectionService;
import com.documenter.vo.OcrCorrectionVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 仅保存人工校正文本，不覆盖源文件，也不创建文档版本。 */
@Service
public class OcrCorrectionServiceImpl implements OcrCorrectionService {

    private static final Pattern FINGERPRINT = Pattern.compile("^[a-f0-9]{64}$");

    private final OcrCorrectionMapper mapper;
    private final DocumentVersionService versions;
    private final ObjectMapper objectMapper;
    private final OcrProperties properties;

    public OcrCorrectionServiceImpl(OcrCorrectionMapper mapper,
                                    DocumentVersionService versions,
                                    ObjectMapper objectMapper,
                                    OcrProperties properties) {
        this.mapper = mapper;
        this.versions = versions;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OcrCorrectionVO save(Long userId, Long fileId, Integer versionNo,
                                SaveOcrCorrectionsDTO request) {
        versions.downloadVersion(userId, fileId, versionNo);
        validate(request);
        String json = toJson(request.getPages());
        OcrCorrection existing = mapper.selectOne(baseQuery(userId, fileId, versionNo,
                request.getFingerprint()));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            OcrCorrection correction = new OcrCorrection();
            correction.setUserId(userId);
            correction.setFileId(fileId);
            correction.setVersionNo(versionNo);
            correction.setFingerprint(request.getFingerprint());
            correction.setCorrectionsJson(json);
            correction.setCreateTime(now);
            correction.setUpdateTime(now);
            try {
                mapper.insert(correction);
            } catch (DuplicateKeyException concurrent) {
                throw new BusinessException(409, "OCR 校正文本已被其他请求保存，请刷新后重试");
            }
        } else {
            existing.setCorrectionsJson(json);
            existing.setUpdateTime(now);
            mapper.updateById(existing);
        }
        return new OcrCorrectionVO(fileId, versionNo, request.getFingerprint(),
                request.getPages().stream().map(page -> new OcrCorrectionVO.Page(
                        page.getPageNo(), page.getText())).toList(), now);
    }

    @Override
    public OcrCorrectionVO get(Long userId, Long fileId, Integer versionNo,
                               String fingerprint) {
        versions.downloadVersion(userId, fileId, versionNo);
        if (fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new BusinessException(400, "OCR 结果指纹格式错误");
        }
        OcrCorrection correction = mapper.selectOne(
                baseQuery(userId, fileId, versionNo, fingerprint));
        if (correction == null) {
            throw new BusinessException(404, "该 OCR 结果没有保存过人工校正");
        }
        List<OcrCorrectionVO.Page> pages;
        try {
            pages = objectMapper.readValue(correction.getCorrectionsJson(),
                    new TypeReference<>() { });
        } catch (JsonProcessingException invalid) {
            throw new BusinessException(500, "OCR 校正文本数据损坏");
        }
        return new OcrCorrectionVO(fileId, versionNo, fingerprint, pages,
                correction.getUpdateTime());
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OcrCorrection>
    baseQuery(Long userId, Long fileId, Integer versionNo, String fingerprint) {
        return Wrappers.<OcrCorrection>lambdaQuery()
                .eq(OcrCorrection::getUserId, userId)
                .eq(OcrCorrection::getFileId, fileId)
                .eq(OcrCorrection::getVersionNo, versionNo)
                .eq(OcrCorrection::getFingerprint, fingerprint);
    }

    private void validate(SaveOcrCorrectionsDTO request) {
        if (request == null || request.getPages() == null || request.getPages().isEmpty()) {
            throw new BusinessException(400, "至少需要一页 OCR 校正文本");
        }
        if (request.getFingerprint() == null
                || !FINGERPRINT.matcher(request.getFingerprint()).matches()) {
            throw new BusinessException(400, "OCR 结果指纹格式错误");
        }
        Set<Integer> pages = new HashSet<>();
        int characters = 0;
        for (SaveOcrCorrectionsDTO.PageCorrection page : request.getPages()) {
            if (page == null || page.getPageNo() == null || page.getPageNo() < 1
                    || page.getText() == null) {
                throw new BusinessException(400, "OCR 校正页码和文本不完整");
            }
            if (!pages.add(page.getPageNo())) {
                throw new BusinessException(400, "OCR 校正页码不能重复");
            }
            characters += page.getText().codePointCount(0, page.getText().length());
            if (characters > properties.getMaxOutputChars()) {
                throw new BusinessException(413, "OCR 校正文本总量超过限制");
            }
        }
    }

    private String toJson(List<SaveOcrCorrectionsDTO.PageCorrection> pages) {
        try {
            return objectMapper.writeValueAsString(pages.stream()
                    .map(page -> new OcrCorrectionVO.Page(
                            page.getPageNo(), page.getText())).toList());
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "OCR 校正文本序列化失败");
        }
    }
}
