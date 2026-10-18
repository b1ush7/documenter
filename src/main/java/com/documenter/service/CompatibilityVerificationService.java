package com.documenter.service;

import com.documenter.vo.DocxCompatibilityReportVO;

/**
 * DOCX 应用级兼容性验收（TODO 4.1）。
 *
 * <p>与「POI 能重新打开」的区别：POI 校验的是 ZIP 结构与 XML 可解析性，
 * 属于「文件没坏」；本服务校验的是「办公软件能打开并排出版面」，
 * 属于「文件可用」。两者都必要，但后者才是用户实际会遇到问题的层面。
 *
 * <p>检查项：
 * <ul>
 *   <li><b>能否渲染</b>：真正让 LibreOffice 打开并排版，失败即验收不通过；</li>
 *   <li><b>页数</b>：页数为 0 说明能解析却排不出内容；</li>
 *   <li><b>缺失字体</b>：字体被替换意味着版式与预期不同，属于高风险但不阻断；</li>
 *   <li><b>边缘裁切</b>：页面渲染结果在边界出现非白像素，提示内容可能超出可打印区域。</li>
 * </ul>
 *
 * <p>与预览接口一样要求<b>绑定明确版本</b>并执行<b>同等鉴权</b>：
 * 验收结论必须对应某个确定的产物，否则「验收通过」没有意义。
 */
public interface CompatibilityVerificationService {

    /**
     * 对指定文件的指定版本执行兼容性验收。
     *
     * @param userId    当前登录用户
     * @param fileId    文件 ID
     * @param versionNo 明确的版本号
     */
    DocxCompatibilityReportVO verify(Long userId, Long fileId, Integer versionNo);
}
