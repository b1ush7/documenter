package com.documenter.service;

import com.documenter.dto.EditXlsxCellsDTO;
import com.documenter.dto.EditXlsxStructureDTO;
import com.documenter.vo.VersionVO;
import com.documenter.vo.XlsxRangeVO;
import com.documenter.vo.XlsxStructureVO;

/** XLSX 版本的结构读取与受控单元格编辑能力。 */
public interface XlsxDocumentService {

    XlsxStructureVO readStructure(Long userId, Long fileId, Integer versionNo);

    XlsxRangeVO readRange(Long userId, Long fileId, Integer versionNo,
                          String sheetName, String range);

    VersionVO editCells(Long userId, Long fileId, Integer sourceVersion, EditXlsxCellsDTO request);

    VersionVO editStructure(Long userId, Long fileId, Integer sourceVersion,
                            EditXlsxStructureDTO request);
}
