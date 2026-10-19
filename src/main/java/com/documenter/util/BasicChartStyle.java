package com.documenter.util;

import org.apache.poi.xddf.usermodel.*;
import org.apache.poi.xddf.usermodel.chart.*;

/** 显式保存系列颜色，避免 Office 实现对缺省主题样式的不同解释导致图形不可见。 */
public final class BasicChartStyle {
    private static final byte[][] COLORS = {{25,95,(byte)145}, {46,(byte)139,87}, {(byte)224,124,40}, {(byte)130,90,(byte)170}};
    private BasicChartStyle() {}
    public static void apply(XDDFChartData data, XDDFChartData.Series series, String type) {
        data.setVaryColors("PIE".equals(type));
        var line = new XDDFLineProperties(); line.setWidth(1.5);
        line.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(COLORS[0])));
        series.setLineProperties(line);
        series.setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(COLORS[0])));
        if ("PIE".equals(type)) for (int i = 0; i < series.getCategoryData().getPointCount(); i++) {
            series.getDataPoint(i).setFillProperties(new XDDFSolidFillProperties(XDDFColor.from(COLORS[i % COLORS.length])));
        }
        if (series instanceof XDDFLineChartData.Series lineSeries) {
            lineSeries.setMarkerStyle(MarkerStyle.CIRCLE);
        }
    }
}
