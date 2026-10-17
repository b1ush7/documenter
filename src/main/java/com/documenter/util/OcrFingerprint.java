package com.documenter.util;

import com.documenter.vo.OcrResultVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 对 OCR 结果的关键文字、坐标和表格结构计算稳定指纹。 */
public final class OcrFingerprint {

    private OcrFingerprint() {
    }

    public static String of(OcrResultVO result) {
        MessageDigest digest = Digests.newDigest();
        update(digest, result.engine());
        update(digest, result.language());
        for (OcrResultVO.Page page : result.pages()) {
            update(digest, Integer.toString(page.pageNo()));
            update(digest, page.source());
            update(digest, page.text());
            if (page.region() != null) {
                update(digest, page.region().x() + ":" + page.region().y() + ":"
                        + page.region().width() + ":" + page.region().height());
            }
            for (OcrResultVO.Line line : page.lines()) {
                update(digest, line.text());
                update(digest, line.left() + ":" + line.top() + ":"
                        + line.width() + ":" + line.height() + ":"
                        + line.confidence());
            }
            for (OcrResultVO.Table table : page.tables()) {
                update(digest, table.rows() + ":" + table.columns());
                for (OcrResultVO.Cell cell : table.cells()) {
                    update(digest, cell.row() + ":" + cell.column() + ":"
                            + cell.rowSpan() + ":" + cell.columnSpan());
                    update(digest, cell.text());
                    update(digest, cell.left() + ":" + cell.top() + ":"
                            + cell.width() + ":" + cell.height() + ":"
                            + cell.confidence());
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        if (value != null) digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
