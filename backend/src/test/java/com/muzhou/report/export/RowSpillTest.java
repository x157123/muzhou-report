package com.muzhou.report.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RowSpill 单元测试（纯 POJO）。
 *
 * <p>盯的是「切口绝不从一行字中间切过去」这一条 —— PDF 与 Word 两条路共用它，
 * 这里错了两边一起错。
 */
class RowSpillTest {

    /** 首行顶边距行顶 2pt，行距 12pt，字面高 10pt，共 5 行（也就是 2..62pt 这一段有字） */
    private final RowSpill.Lines cell = new RowSpill.Lines(2, 12, 10, 5);

    @Test
    @DisplayName("切口落在两行文字之间就不动，切在字上就往上挪到这行字的顶边")
    void cutSnapsUpOffTheText() {
        // 第 2 行（下标 1）占 14..24pt：切在 20pt 是从字中间切过去 -> 挪到 14pt
        assertEquals(14, RowSpill.snapCut(20, List.of(cell)), 0.01);
        // 24..26pt 是行间空隙，切在这里不碰字
        assertEquals(25, RowSpill.snapCut(25, List.of(cell)), 0.01);
        // 正好切在一行的顶边上也不用动
        assertEquals(26, RowSpill.snapCut(26, List.of(cell)), 0.01);
    }

    @Test
    @DisplayName("文字上面 / 下面的空白里随便切")
    void cutOutsideTheTextIsKept() {
        assertEquals(1, RowSpill.snapCut(1, List.of(cell)), 0.01, "第一行文字上面");
        assertEquals(80, RowSpill.snapCut(80, List.of(cell)), 0.01, "最后一行文字下面");
    }

    @Test
    @DisplayName("一行里有好几格时取最靠上的那一刀 —— 每一格都不能被切断")
    void cutTakesTheHighestOfAllCells() {
        // 另一格：首行 0pt 起、行距 30pt、字面 28pt，第 1 行占 0..28pt
        RowSpill.Lines other = new RowSpill.Lines(0, 30, 28, 2);
        // 20pt 这一刀：cell 要求挪到 14，other 要求挪到 0 -> 听 other 的
        assertEquals(0, RowSpill.snapCut(20, List.of(cell, other)), 0.01);
        // 29pt 落在 other 的行间空隙里，只有 cell 说话 -> 挪到第 3 行顶边 26
        assertEquals(26, RowSpill.snapCut(29, List.of(cell, other)), 0.01);
    }

    @Test
    @DisplayName("没有文字可对齐时原样返回 —— 空行照旧硬切")
    void withoutGridTheCutIsKept() {
        assertEquals(37, RowSpill.snapCut(37, List.of()), 0.01);
        assertEquals(37, RowSpill.snapCut(37, null), 0.01);
        assertEquals(37, RowSpill.snapCut(37, List.of(new RowSpill.Lines(0, 12, 10, 0))), 0.01);
    }

    @Test
    @DisplayName("切口上面完整放着几行 —— Word 靠它把文字拆成两截")
    void linesAboveCountsWholeLinesOnly() {
        assertEquals(0, RowSpill.linesAbove(cell, 5), "第一行还没印完");
        assertEquals(1, RowSpill.linesAbove(cell, 14), "第一行 2..12pt 印完了");
        assertEquals(2, RowSpill.linesAbove(cell, 26));
        assertEquals(5, RowSpill.linesAbove(cell, 999), "行高远大于文字时全都在上面");
        assertEquals(0, RowSpill.linesAbove(cell, -5));
    }

    @Test
    @DisplayName("折行一个字符都不丢：各行接回去就是原文")
    void wrapKeepsEveryCharacter() {
        String text = "第一段很长很长很长很长\n第二段";
        List<RowSpill.Line> lines = RowSpill.wrapEstimate(text, 10, 35);

        assertTrue(lines.size() > 2, "该折成好几行，实际 " + lines.size());
        StringBuilder sb = new StringBuilder();
        for (RowSpill.Line line : lines) {
            if (line.head() && !sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line.text());
        }
        assertEquals(text, sb.toString());
    }

    @Test
    @DisplayName("换行符隔开的自然段各自从新行起，空段也占一行")
    void paragraphsStartNewLines() {
        List<RowSpill.Line> lines = RowSpill.wrapEstimate("甲\n\n乙", 10, 100);
        assertEquals(List.of("甲", "", "乙"), lines.stream().map(RowSpill.Line::text).toList());
        assertTrue(lines.stream().allMatch(RowSpill.Line::head), "三段都是段首");
    }

    @Test
    @DisplayName("全角算一个字号、半角算半个")
    void charWidthFollowsFullWidth() {
        // 宽 30pt、字号 10pt = 放得下 3 个汉字 / 6 个字母
        assertEquals(2, RowSpill.wrapEstimate("汉字汉字", 10, 30).size());
        assertEquals(1, RowSpill.wrapEstimate("abcdef", 10, 30).size());
        assertEquals(2, RowSpill.wrapEstimate("abcdefg", 10, 30).size());
    }
}
