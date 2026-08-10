package com.muzhou.report.export;

import java.util.ArrayList;
import java.util.List;

/**
 * 超高行的「续行」计算：一行比一页还高时，切口该落在哪儿、切完的每一段印哪几行文字。
 *
 * <p>纯算法、不依赖 POI，定位同 {@link ImageFit} —— <b>一处算，PDF / Word 两条路共用</b>。
 * 两边的用法不一样，共用的是同一条规则：<b>切口只落在两行文字之间，绝不从一行字中间切过去</b>。
 *
 * <ul>
 *   <li><b>PDF</b>（{@code PdfExporter.Geom#splitRows}）：切口是一个磅数，
 *       {@link #snapCut} 把它往上挪到不切字的位置，文字照旧整段画、由裁剪决定这一页看得见哪几行。</li>
 *   <li><b>Word</b>（{@code WordExporter#plan}）：Word 是转结构不是渲染，切完要**真的多出一行**，
 *       所以还要知道切口上面有几行文字（{@link #linesAbove}），据此把那一格的文字拆成两截。</li>
 * </ul>
 *
 * <p>为什么不各写一份：两条路都要「不切断文字行」这条规则，写两份必然对不齐 —— 同一份报表
 * PDF 里切在第 12 行下面、Word 里切在第 11 行下面，对着看就是两份不一样的东西。
 */
public final class RowSpill {

    private RowSpill() {
    }

    /**
     * 一格文字在行内的排布：首行的**字面顶边**距行顶 {@code top} 磅，往下每 {@code lineHeight}
     * 一行，共 {@code count} 行，每行字面高 {@code face}。
     *
     * <p>{@code face} 与 {@code lineHeight} 是两个数：行距里除了字面还有行间的空隙，切口落在
     * 空隙里就不算切断文字。PDF 那条路 {@code face = ascent - descent} 明显小于行距；
     * Word 那条路量不到字面（POI 不加载字体），两者取同一个数 = 「行与行之间没有空隙」，
     * 于是切口一律对到整行上，这是更保守的一侧。
     */
    public record Lines(float top, float lineHeight, float face, int count) {

        /** 第 {@code i} 行字面顶边距行顶多少磅。 */
        public float lineTop(int i) {
            return top + i * lineHeight;
        }
    }

    /**
     * 把「距行顶 {@code at} 磅」这一刀挪到不切断任何一行文字的位置。
     *
     * <p><b>只往上挪</b>：往下挪会让这一页装不下，分页就错了。挪不动（这一刀本来就落在空隙里、
     * 或者落在所有文字的上面/下面）时原样返回。
     *
     * <p>一行里有好几格文字时<b>取最小的那个</b> —— 每一格都不能被切断，谁最先要求往上挪就听谁的。
     * 代价是最多白费一格的字面高（切口本来就落在那一行字里，往上挪也就挪那么多）。
     *
     * @param cells 这一行里各格的文字行网格；空表示这一行没有可对齐的文字，原样返回
     */
    public static float snapCut(float at, List<Lines> cells) {
        if (cells == null || cells.isEmpty()) {
            return at;
        }
        float out = at;
        for (Lines cell : cells) {
            out = Math.min(out, snapCut(at, cell));
        }
        return out;
    }

    private static float snapCut(float at, Lines cell) {
        if (cell == null || cell.count() <= 0 || cell.lineHeight() <= 0) {
            return at;
        }
        int i = (int) Math.floor((at - cell.top()) / cell.lineHeight());
        if (i < 0 || i >= cell.count()) {
            // 切在第一行上面 / 最后一行下面，没有字被切到
            return at;
        }
        float lineTop = cell.lineTop(i);
        // 落在这一行的行间空隙里就不用动，否则挪到这行字的上面去
        return at >= lineTop + cell.face() ? at : lineTop;
    }

    /**
     * 「距行顶 {@code at} 磅」这一刀上面完整地放着几行文字。
     *
     * <p>给 Word 那条路把文字拆成两截用：上面这几行留在本段，剩下的进下一段。
     * 露出半行的不算 —— 切口是 {@link #snapCut} 挪过的，正常不会出现半行。
     */
    public static int linesAbove(Lines cell, float at) {
        if (cell == null || cell.count() <= 0 || cell.lineHeight() <= 0) {
            return 0;
        }
        int n = (int) Math.floor((at - cell.top() - cell.face()) / cell.lineHeight()) + 1;
        return Math.max(0, Math.min(cell.count(), n));
    }

    /** 折出来的一行文字。{@code head} = 这一行是某个自然段（换行符隔开的那种）的开头。 */
    public record Line(String text, boolean head) {
    }

    /**
     * 按可用宽度把文字折成若干行 —— <b>字宽靠估，不加载字体</b>。
     *
     * <p>「全角 1 个字号、半角半个字号」，服务器上未必装了报表用的那款字体（Linux 容器常常连中文
     * 字体都没有），拿 AWT 量出来的反而可能离谱。中文报表里这个估法误差在几个百分点。
     *
     * <p>{@code ExcelExporter} 的行高、{@code WordExporter} 的续行都用它 —— 两处算出来的行数必须
     * 一样，否则「Excel 里 12 行、Word 里切在第 11 行」。PDF 那条路不用它：那边能量到真正内嵌的
     * 那款字体（{@code PdfExporter#wrapLines}），比估算准。
     *
     * <p><b>一个字符都不会丢</b>：折行只是插断点，把各行接回去就是原文（Word 那条路靠这条把
     * 切开的两截拼成完整的文字）。
     */
    public static List<Line> wrapEstimate(String text, float fontSize, float maxWidth) {
        List<Line> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String paragraph : text.split("\r\n|\r|\n", -1)) {
            if (paragraph.isEmpty()) {
                out.add(new Line("", true));
                continue;
            }
            StringBuilder cur = new StringBuilder();
            boolean head = true;
            float used = 0;
            for (int i = 0; i < paragraph.length(); i++) {
                char ch = paragraph.charAt(i);
                float w = charWidth(ch) * fontSize;
                if (!cur.isEmpty() && used + w > maxWidth) {
                    out.add(new Line(cur.toString(), head));
                    cur.setLength(0);
                    head = false;
                    used = 0;
                }
                cur.append(ch);
                used += w;
            }
            out.add(new Line(cur.toString(), head));
        }
        return out;
    }

    /** 字符占几个「字号」宽：CJK / 全角算 1，其余算 0.5。 */
    static float charWidth(char ch) {
        if (ch >= 0x1100 && (ch <= 0x115F                       // 韩文字母
                || (ch >= 0x2E80 && ch <= 0xA4CF)               // CJK 部首 / 汉字 / 假名
                || (ch >= 0xAC00 && ch <= 0xD7A3)               // 韩文音节
                || (ch >= 0xF900 && ch <= 0xFAFF)               // CJK 兼容汉字
                || (ch >= 0xFE30 && ch <= 0xFE6F)               // CJK 兼容标点
                || (ch >= 0xFF00 && ch <= 0xFF60)               // 全角字符
                || (ch >= 0xFFE0 && ch <= 0xFFE6))) {
            return 1f;
        }
        return 0.5f;
    }
}
