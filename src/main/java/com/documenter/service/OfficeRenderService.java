package com.documenter.service;

import com.documenter.vo.DocumentRenderManifest;

import java.nio.file.Path;

/**
 * Office 文档的渲染与格式转换（TODO 4.1）。
 *
 * <p>为什么需要它：TODO 4.1 要求「使用实际 Office 或 LibreOffice 对固定样本执行
 * 兼容性验收，补充应用级打开与版式检查」。仅靠 POI 重新打开只能验证 ZIP 结构与
 * XML 可解析，无法发现「办公软件打开时提示需要修复」「页数与预期不符」
 * 「表格超宽被裁切」这类真实问题。
 *
 * <p>本接口把渲染后端抽象出来，原因：
 * <ul>
 *   <li>渲染依赖外部办公套件，部署环境不一定有；未接入时应给出明确提示，
 *       而不是让调用方拿到一个难以理解的运行时异常；</li>
 *   <li>将来换用其他后端（系统 LibreOffice、云端转换）只需新增实现。</li>
 * </ul>
 */
public interface OfficeRenderService {

    /** 后端名称，用于报告中标注实际使用的引擎。 */
    String backendName();

    /** 是否可用。false 时渲染类调用应返回可操作的错误说明。 */
    boolean available();

    /** 如何启用，用于错误提示。 */
    String configurationHint();

    /**
     * 渲染为逐页图片。
     *
     * @param source     源文件
     * @param extension  源扩展名（小写、不带点），决定 kit 走哪个转换链
     * @param outputDir  输出目录。<b>必须是不存在的路径</b>：kit 会自行创建，
     *                   事先创建会导致 EEXIST 失败。其父目录必须可写
     * @param pages      要渲染的页码（从 1 开始）；传 null 渲染全部页
     * @param dpi        渲染 DPI
     */
    DocumentRenderManifest render(Path source, String extension, Path outputDir,
                                  java.util.List<Integer> pages, int dpi);

    /**
     * 转换为 PDF。
     *
     * <p>注意与 {@link #render} 的差异：本方法的 {@code outputDir}
     * 会被实现自行创建（转换工具要求父目录已存在），调用方无需预先建立，
     * 已存在也不会报错。
     *
     * @param source    源文件
     * @param extension 源扩展名
     * @param outputDir 输出目录
     * @return 生成的 PDF 路径
     */
    Path toPdf(Path source, String extension, Path outputDir);
}
