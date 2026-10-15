package com.documenter.service;

import com.documenter.vo.FileDownload;

/**
 * 与源文件对应的导出产物（TODO 4.3「分别下载原文件、修改版 DOCX 和导出的 PDF」）。
 *
 * <p>与预览、比较一致的两个要求：<b>绑定明确版本</b>、<b>同等鉴权</b>。
 * 导出必须也能指定版本，否则「导出 PDF」只会得到最新版，
 * 与用户正在查看的版本不一致 —— 这正是 TODO 第 3 条要避免的问题。
 */
public interface DocumentExportService {

    /**
     * 导出版本为 PDF。
     *
     * <p>若该版本本身就是 PDF，直接返回其内容（不做无意义的再转换）；
     * 否则经 Office 渲染后端转换。
     *
     * @param userId    当前登录用户
     * @param fileId    文件 ID
     * @param versionNo 明确的版本号
     * @return 可供下载的 PDF
     * @throws com.documenter.exception.BusinessException 版本不存在、不属于该用户、
     *         格式不支持转换，或渲染后端未接入（503）
     */
    FileDownload exportPdf(Long userId, Long fileId, Integer versionNo);
}
