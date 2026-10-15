package com.documenter.service;

import com.documenter.vo.VersionComparisonVO;

/**
 * 文档版本的修改前后比较（TODO 4.3「支持修改前后内容比较」）。
 *
 * <p>与预览、下载保持一致的两个要求：
 * <ol>
 *   <li><b>两个版本都必须明确指定</b>，没有「和当前版本比」这种模糊语义 ——
 *       否则用户看到的差异会随着别人编辑而改变；</li>
 *   <li><b>同等鉴权</b>：两个版本都经下载路径解析归属，因此不可能通过
 *       「拿自己的旧版本和别人的新版本比」来探测他人内容。</li>
 * </ol>
 */
public interface DocumentComparisonService {

    /**
     * 比较同一文件的两个版本。
     *
     * @param userId      当前登录用户
     * @param fileId      文件 ID
     * @param fromVersion 基准版本（旧）
     * @param toVersion   目标版本（新）
     * @return 差异清单；两个版本相同时返回空清单而不是报错
     * @throws com.documenter.exception.BusinessException 版本不存在、不属于该用户，
     *         或两个版本格式不同因而无法逐块比较
     */
    VersionComparisonVO compare(Long userId, Long fileId, Integer fromVersion, Integer toVersion);
}
