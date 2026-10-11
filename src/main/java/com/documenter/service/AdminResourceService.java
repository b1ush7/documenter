package com.documenter.service;

import com.documenter.dto.FileQueryDTO;
import com.documenter.dto.TaskQueryDTO;
import com.documenter.vo.AdminFileVO;
import com.documenter.vo.AdminTaskDetailVO;
import com.documenter.vo.AdminTaskVO;
import com.documenter.vo.AdminOverviewVO;
import com.documenter.vo.AdminUserUsageVO;
import com.documenter.vo.DocxStructureVO;
import com.documenter.vo.FileDownload;
import com.documenter.vo.PageResult;
import com.documenter.vo.PdfContentVO;
import com.documenter.vo.PdfPagePreview;
import com.documenter.vo.VersionVO;
import com.documenter.vo.XlsxStructureVO;

import java.util.List;

/** 管理员跨用户查看文件、版本和任务的能力。 */
public interface AdminResourceService {

    AdminOverviewVO overview();

    AdminUserUsageVO userUsage(Long userId);

    PageResult<AdminFileVO> listFiles(Long userId, FileQueryDTO query);

    AdminFileVO fileDetail(Long fileId);

    AdminFileVO renameFile(Long fileId, String displayName);

    void deleteFile(Long fileId);

    FileDownload downloadFile(Long fileId);

    List<VersionVO> listVersions(Long fileId);

    VersionVO versionDetail(Long fileId, Integer versionNo);

    FileDownload downloadVersion(Long fileId, Integer versionNo);

    DocxStructureVO readDocxStructure(Long fileId, Integer versionNo);

    PdfContentVO readPdfContent(Long fileId, Integer versionNo);

    PdfPagePreview previewPdfPage(Long fileId, Integer versionNo, Integer pageNo, Integer dpi);

    XlsxStructureVO readXlsxStructure(Long fileId, Integer versionNo);

    PageResult<AdminTaskVO> listTasks(Long userId, TaskQueryDTO query);

    AdminTaskDetailVO taskDetail(Long taskId);

    AdminTaskVO cancelTask(Long taskId);

    AdminTaskVO retryTask(Long taskId);
}
