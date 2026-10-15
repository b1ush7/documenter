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
import com.documenter.vo.PagePreviewInfoVO;
import com.documenter.vo.PageResult;
import com.documenter.vo.PdfContentVO;
import com.documenter.vo.PdfPagePreview;
import com.documenter.vo.VersionVO;
import com.documenter.vo.XlsxStructureVO;
import com.documenter.vo.XlsxRangeVO;
import com.documenter.vo.XlsxFormulaResultsVO;

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

    /**
     * 页面版式预览元信息（TODO 3.3 的管理员侧入口）。
     *
     * <p>以文件的<b>真实所有者</b>身份调用底层服务，因此管理员不需要绕过归属校验，
     * 而是复用同一套「绑定明确版本 + 归属校验」的逻辑。
     */
    PagePreviewInfoVO adminPreviewInfo(Long fileId, Integer versionNo);

    /**
     * 页面版式预览图片（TODO 3.3）。
     *
     * <p>管理员查看用户文档内容属于敏感操作，会写入审计日志。
     */
    PdfPagePreview adminPreviewPage(Long fileId, Integer versionNo, Integer pageNo, Integer dpi);

    XlsxStructureVO readXlsxStructure(Long fileId, Integer versionNo);

    XlsxRangeVO readXlsxRange(Long fileId, Integer versionNo, String sheetName, String range);

    XlsxFormulaResultsVO calculateXlsxFormulas(Long fileId, Integer versionNo,
                                              String sheetName, String range);

    PageResult<AdminTaskVO> listTasks(Long userId, TaskQueryDTO query);

    AdminTaskDetailVO taskDetail(Long taskId);

    AdminTaskVO cancelTask(Long taskId);

    AdminTaskVO retryTask(Long taskId);
}
