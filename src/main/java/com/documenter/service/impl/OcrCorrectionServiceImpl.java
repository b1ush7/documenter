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
                        page.getPageNo(), page.getText(), toTables(page.getTables()))).toList(), now);
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
        int tableCells = 0;
        for (SaveOcrCorrectionsDTO.PageCorrection page : request.getPages()) {
            if (page == null || page.getPageNo() == null || page.getPageNo() < 1
                    || page.getText() == null) {
                throw new BusinessException(400, "OCR 校正页码和文本不完整");
            }
            if (!pages.add(page.getPageNo())) {
                throw new BusinessException(400, "OCR 校正页码不能重复");
            }
            characters += page.getText().codePointCount(0, page.getText().length());
            Set<Integer> tableIndexes = new HashSet<>();
            if (page.getTables() != null) {
                for (SaveOcrCorrectionsDTO.TableCorrection table : page.getTables()) {
                    if (table == null || table.getTableIndex() == null
                            || table.getTableIndex() < 0 || table.getRows() == null
                            || table.getColumns() == null || table.getCells() == null
                            || table.getCells().isEmpty()) {
                        throw new BusinessException(400, "OCR 校正表格参数不完整");
                    }
                    if (!tableIndexes.add(table.getTableIndex())) {
                        throw new BusinessException(400, "同一页 OCR 校正表格序号不能重复");
                    }
                    if (table.getRows() < 1
                            || table.getRows() > properties.getMaxTableRows()
                            || table.getColumns() < 1
                            || table.getColumns() > properties.getMaxTableColumns()) {
                        throw new BusinessException(400, "OCR 校正表格行列数超出限制");
                    }
                    tableCells += table.getCells().size();
                    if (tableCells > properties.getMaxTableCells()) {
                        throw new BusinessException(413, "OCR 校正表格单元格总数超过限制");
                    }
                    validateTable(table);
                    for (SaveOcrCorrectionsDTO.CellCorrection cell : table.getCells()) {
                        characters += cell.getText().codePointCount(0, cell.getText().length());
                    }
                }
            }
            if (characters > properties.getMaxOutputChars()) {
                throw new BusinessException(413, "OCR 校正文本总量超过限制");
            }
        }
    }

    private static void validateTable(SaveOcrCorrectionsDTO.TableCorrection table) {
        boolean[][] covered = new boolean[table.getRows()][table.getColumns()];
        for (SaveOcrCorrectionsDTO.CellCorrection cell : table.getCells()) {
            if (cell == null || cell.getRow() == null || cell.getColumn() == null
                    || cell.getRowSpan() == null || cell.getColumnSpan() == null
                    || cell.getText() == null) {
                throw new BusinessException(400, "OCR 校正单元格参数不完整");
            }
            int row = cell.getRow();
            int column = cell.getColumn();
            int rowSpan = cell.getRowSpan();
            int columnSpan = cell.getColumnSpan();
            if (row < 0 || column < 0 || rowSpan < 1 || columnSpan < 1
                    || row + rowSpan > table.getRows()
                    || column + columnSpan > table.getColumns()) {
                throw new BusinessException(400, "OCR 校正单元格跨度超出表格范围");
            }
            for (int rowIndex = row; rowIndex < row + rowSpan; rowIndex++) {
                for (int columnIndex = column;
                     columnIndex < column + columnSpan; columnIndex++) {
                    if (covered[rowIndex][columnIndex]) {
                        throw new BusinessException(400, "OCR 校正表格单元格区域重叠");
                    }
                    covered[rowIndex][columnIndex] = true;
                }
            }
        }
        for (boolean[] row : covered) {
            for (boolean value : row) {
                if (!value) {
                    throw new BusinessException(400, "OCR 校正表格存在未覆盖单元格");
                }
            }
        }
    }

    private String toJson(List<SaveOcrCorrectionsDTO.PageCorrection> pages) {
        try {
            return objectMapper.writeValueAsString(pages.stream()
                    .map(page -> new OcrCorrectionVO.Page(
                            page.getPageNo(), page.getText(),
                            toTables(page.getTables()))).toList());
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "OCR 校正文本序列化失败");
        }
    }

    private static List<OcrCorrectionVO.Table> toTables(
            List<SaveOcrCorrectionsDTO.TableCorrection> tables) {
        if (tables == null) return List.of();
        return tables.stream().map(table -> new OcrCorrectionVO.Table(
                table.getTableIndex(), table.getRows(), table.getColumns(),
                table.getCells().stream().map(cell -> new OcrCorrectionVO.Cell(
                        cell.getRow(), cell.getColumn(), cell.getRowSpan(),
                        cell.getColumnSpan(), cell.getText())).toList())).toList();
    }
}
