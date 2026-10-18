package com.documenter.enums;

/** PDF 转 DOCX 的首批输出取向。 */
public enum PdfConversionMode {

    /** 按阅读行输出，减少不必要的缩进和空距，方便继续编辑。 */
    EDITABLE,

    /** 尽量保留文字横向位置、字体大小和行间距，内容仍保持可编辑。 */
    LAYOUT
}
