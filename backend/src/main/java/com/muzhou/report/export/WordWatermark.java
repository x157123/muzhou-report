package com.muzhou.report.export;

import com.muzhou.report.dto.WatermarkDTO;

import java.util.Locale;

/**
 * Word 水印的 XML 片段。{@link WordExporter} 建页眉时直接塞一个 run。
 *
 * <p>Word 的「水印」没有专门的元素，就是一段绝对定位的 VML 艺术字（{@code v:textpath}）
 * 放在页眉里 —— 放页眉是因为只有页眉才每页都出现，绝对定位则保证它不占页眉的行高。
 */
final class WordWatermark {

    private static final String NAMESPACES =
            "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                    + " xmlns:v=\"urn:schemas-microsoft-com:vml\""
                    + " xmlns:o=\"urn:schemas-microsoft-com:office:office\"";

    private WordWatermark() {
    }

    /** 水印的 {@code <w:r>}，可以单独解析（命名空间声明在自己身上）。 */
    static String runXml(WatermarkDTO wm) {
        return "<w:r " + NAMESPACES + ">" + pict(wm) + "</w:r>";
    }

    private static String pict(WatermarkDTO wm) {
        int size = wm.getFontSize() == null || wm.getFontSize() <= 0 ? 60 : wm.getFontSize();
        // 艺术字的字号由图形的宽高决定，不是 font-size。按「一个中文字约一个字号宽」估个框，
        // 字会撑满这个框，所以框的宽高比对了，字看着就是对的
        long width = Math.round(Math.max(wm.getText().length(), 1) * size * 0.9);
        long height = Math.round(size * 1.6);
        // VML 里正角度是顺时针，本项目的 rotation 是逆时针（与 PDF / CSS 一致），所以取反
        int rotation = -clamp(wm.getRotation() == null ? 45 : wm.getRotation(), -90, 90);
        double opacity = clamp(wm.getOpacity() == null ? 30 : wm.getOpacity(), 0, 100) / 100.0;
        String color = wm.getColor() == null || !wm.getColor().startsWith("#") ? "#c0c0c0" : wm.getColor();

        return ("<w:pict>"
                + "<v:shapetype id=\"_x0000_t136\" coordsize=\"21600,21600\" o:spt=\"136\" adj=\"10800\""
                + " path=\"m@7,l@8,m@5,21600l@11,21600e\"/>"
                + "<v:shape id=\"MzWatermark\" type=\"#_x0000_t136\" fillcolor=\"%s\" stroked=\"f\""
                + " style=\"position:absolute;margin-left:0;margin-top:0;width:%dpt;height:%dpt;"
                + "rotation:%d;z-index:-251658752;mso-position-horizontal:center;"
                + "mso-position-horizontal-relative:margin;mso-position-vertical:center;"
                + "mso-position-vertical-relative:margin\">"
                + "<v:fill opacity=\"%s\"/>"
                + "<v:textpath on=\"t\" style=\"font-size:1pt\" string=\"%s\"/>"
                + "</v:shape>"
                + "</w:pict>").formatted(color, width, height, rotation,
                String.format(Locale.ROOT, "%.2f", opacity), escape(wm.getText()));
    }

    /** 拼进 XML 属性/文本的用户输入要转义，否则一个引号就能让 docx 打不开。 */
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static int clamp(int v, int min, int max) {
        return Math.min(Math.max(v, min), max);
    }
}
