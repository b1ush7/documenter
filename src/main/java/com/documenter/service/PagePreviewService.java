package com.documenter.service;

import com.documenter.vo.PagePreviewInfoVO;
import com.documenter.vo.PdfPagePreview;

/**
 * 页面版式预览（TODO 3.3「页面预览尚未实现；接入后需要绑定明确版本并执行同等鉴权」）。
 *
 * <p>本接口的两个硬性要求，也是它与直接下载的区别所在：
 * <ol>
 *   <li><b>绑定明确版本</b>：所有方法都要求 {@code versionNo}，不接受「当前最新版本」这种模糊语义。
 *       否则会出现「用户看到的是 v2 的预览、下载拿到的是 v3」的不一致。</li>
 *   <li><b>同等鉴权</b>：归属校验与下载走<b>同一条路径</b>
 *       （{@code DocumentVersionService.downloadVersion}），
 *       因此不存在「预览接口漏了鉴权」这种独立缺陷面。</li>
 * </ol>
 *
 * <p>覆盖范围：PDF 与图片可就地渲染；DOCX / XLSX 需要外部 Office 渲染后端，
 * 未配置时返回可操作的错误说明（见 {@link OfficeRenderService}）。
 */
public interface PagePreviewService {

    /**
     * 读取某一版本的预览元信息。
     *
     * @param userId    当前登录用户
     * @param fileId    文件 ID
     * @param versionNo 明确的版本号
     * @return 页数与可预览性；不可预览时不抛异常，而是在结果中给出原因
     */
    PagePreviewInfoVO describe(Long userId, Long fileId, Integer versionNo);

    /**
     * 渲染某一版本的指定页为 PNG。
     *
     * @param dpi 可选；留空取配置默认值，超出上限会被拒绝
     * @throws com.documenter.exception.BusinessException 版本不存在、不属于该用户、页号越界或渲染失败
     */
    PdfPagePreview renderPage(Long userId, Long fileId, Integer versionNo, Integer pageNo, Integer dpi);
}
