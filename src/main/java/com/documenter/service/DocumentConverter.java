package com.documenter.service;

/**
 * 文档转 PDF 的抽象（TODO 3.3 页面预览的依赖）。
 *
 * <p>为什么不直接依赖某个具体实现：DOCX/XLSX 的版式预览必须借助外部办公套件，
 * 而部署环境不一定有（开发机、CI、精简容器都可能没有）。
 * 把这一步抽象出来后：
 * <ul>
 *   <li>未接入转换器时，接口能给出<b>明确原因</b>而不是 500；</li>
 *   <li>将来换用其他引擎（如云转换服务、OnlyOffice）只需新增一个实现类；</li>
 *   <li>PDF 与图片的预览完全不经过这里，不会因为转换器缺失而受影响。</li>
 * </ul>
 */
public interface DocumentConverter {

    /** 引擎名称，用于在错误信息与日志中指明当前用的是哪个转换器。 */
    String engineName();

    /**
     * 当前引擎是否可用。
     *
     * <p>{@code false} 时调用方应拒绝 DOCX/XLSX 预览并说明如何配置，
     * 而不是抛出难以理解的运行时异常。
     */
    boolean available();

    /**
     * 将文档转换为 PDF。
     *
     * @param source 源文件内容
     * @param extension 源文件扩展名（小写、不带点），用于决定转换参数
     * @param baseName 源文件基础名（不含扩展名），用作转换产物名
     * @return PDF 字节内容
     * @throws com.documenter.exception.BusinessException 转换失败或超时
     */
    byte[] toPdf(byte[] source, String extension, String baseName);

    /** 供错误提示使用的「如何启用」说明。 */
    String configurationHint();
}
