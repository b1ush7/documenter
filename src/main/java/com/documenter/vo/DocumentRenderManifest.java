package com.documenter.vo;

import java.util.List;

/**
 * Office 文档渲染结果（TODO 4.1 的兼容性验收依赖它）。
 *
 * <p>直接对应 kit CLI 输出的 manifest：{@code pageCount} 是渲染后端实际排出的页数，
 * 这是「应用级打开」最直接的证据 —— 结构校验通过但页数为 0，
 * 说明文档能解析却排不出内容。
 *
 * @param backend      渲染后端名（如 libreoffice / pdfium）
 * @param pageCount    实际页数
 * @param pages        每页图片与其像素尺寸
 * @param missingFonts 渲染时缺失而被替换的字体。<b>非空即意味着版式可能与本机预期不同</b>，
 *                     是「版式检查」最有价值的信号之一
 */
public record DocumentRenderManifest(
        String backend,
        int pageCount,
        List<Page> pages,
        List<String> missingFonts) {

    public DocumentRenderManifest {
        pages = pages == null ? List.of() : List.copyOf(pages);
        missingFonts = missingFonts == null ? List.of() : List.copyOf(missingFonts);
    }

    /**
     * 单页渲染结果。
     *
     * @param page      页码，从 1 开始（与接口的页码语义一致）
     * @param path      渲染出的图片绝对路径
     * @param width     像素宽
     * @param height    像素高
     * @param byteLength 文件字节数
     */
    public record Page(int page, String path, int width, int height, long byteLength) {
    }
}
