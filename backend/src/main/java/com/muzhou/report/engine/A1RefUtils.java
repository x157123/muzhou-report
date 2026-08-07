package com.muzhou.report.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Excel A1 引用工具：列字母互转、区间解析、公式引用偏移。
 *
 * <p>报表模板里写的公式（如 {@code =SUM(B2:B3)}）用的是<b>模板行号</b>，
 * 但纵向扩展后同一模板行可能变成很多行，因此必须把公式里的引用重写到<b>输出行号</b>。
 */
public final class A1RefUtils {

    /** 匹配 A1 引用：$?列字母 $?行号。 */
    private static final Pattern REF = Pattern.compile("(\\$?)([A-Za-z]{1,3})(\\$?)(\\d{1,7})");

    private A1RefUtils() {
    }

    /** 列号 -> 字母（0 -> A, 25 -> Z, 26 -> AA）。 */
    public static String colToLetter(int c) {
        StringBuilder sb = new StringBuilder();
        int n = c;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        }
        return sb.toString();
    }

    /** 字母 -> 列号（A -> 0, AA -> 26）。 */
    public static int letterToCol(String letters) {
        int n = 0;
        for (char ch : letters.toUpperCase().toCharArray()) {
            n = n * 26 + (ch - 'A' + 1);
        }
        return n - 1;
    }

    /** (r,c) -> "A1"（r/c 均从 0 开始）。 */
    public static String toA1(int r, int c) {
        return colToLetter(c) + (r + 1);
    }

    /** "B3" -> {2, 1}（{行, 列}，均从 0 开始）；解析失败返回 null。 */
    public static int[] parseA1(String a1) {
        if (a1 == null) {
            return null;
        }
        Matcher m = REF.matcher(a1.trim());
        if (!m.matches()) {
            return null;
        }
        return new int[]{Integer.parseInt(m.group(4)) - 1, letterToCol(m.group(2))};
    }

    /**
     * 解析区间 "A2:B10" -> {startRow, startCol, endRow, endCol}（含端点，从 0 开始）。
     * 单个单元格 "A2" 视为 1x1 区间。解析失败返回 null。
     */
    public static int[] parseRange(String range) {
        if (range == null || range.isBlank()) {
            return null;
        }
        String s = range.trim().replace("$", "");
        int idx = s.indexOf(':');
        if (idx < 0) {
            int[] one = parseA1(s);
            return one == null ? null : new int[]{one[0], one[1], one[0], one[1]};
        }
        int[] a = parseA1(s.substring(0, idx));
        int[] b = parseA1(s.substring(idx + 1));
        if (a == null || b == null) {
            return null;
        }
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                Math.max(a[0], b[0]), Math.max(a[1], b[1])};
    }

    /**
     * 重写公式中的 A1 引用。
     *
     * <p>规则：带 {@code $} 的绝对引用不偏移；字符串字面量（成对双引号内）里的内容跳过。
     *
     * @param rowMapper 模板行号 -> 输出行号（均从 0 开始）
     * @param colMapper 模板列号 -> 输出列号
     */
    public static String shiftFormula(String formula, IntUnaryOperator rowMapper, IntUnaryOperator colMapper) {
        if (formula == null || formula.isEmpty()) {
            return formula;
        }
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        int i = 0;
        while (i < formula.length()) {
            char ch = formula.charAt(i);
            if (ch == '"') {
                inString = !inString;
                out.append(ch);
                i++;
                continue;
            }
            if (inString) {
                out.append(ch);
                i++;
                continue;
            }
            Matcher m = REF.matcher(formula);
            if (m.find(i) && m.start() == i) {
                String absCol = m.group(1);
                String colLetters = m.group(2);
                String absRow = m.group(3);
                int row = Integer.parseInt(m.group(4)) - 1;
                int col = letterToCol(colLetters);

                int newRow = absRow.isEmpty() ? rowMapper.applyAsInt(row) : row;
                int newCol = absCol.isEmpty() ? colMapper.applyAsInt(col) : col;

                out.append(absCol).append(colToLetter(newCol))
                        .append(absRow).append(newRow + 1);
                i = m.end();
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    /**
     * 区间感知的公式偏移。
     *
     * <p>与 {@link #shiftFormula} 的区别：对 {@code A2:B2} 这样的<b>区间</b>，
     * 起点用 {@code firstRow}（模板行的第一个输出行）、终点用 {@code lastRow}（最后一个输出行）。
     * 这样模板里的 {@code =SUM(B2:B2)} 在第 2 行扩展成 10 行后会正确变成 {@code =SUM(B2:B11)}，
     * 而不是塌缩成单个单元格。
     */
    public static String shiftFormulaSmart(String formula, IntUnaryOperator firstRow,
                                           IntUnaryOperator lastRow, IntUnaryOperator colMapper) {
        if (formula == null || formula.isEmpty()) {
            return formula;
        }
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        int i = 0;
        while (i < formula.length()) {
            char ch = formula.charAt(i);
            if (ch == '"') {
                inString = !inString;
                out.append(ch);
                i++;
                continue;
            }
            if (inString) {
                out.append(ch);
                i++;
                continue;
            }
            Matcher rm = RANGE.matcher(formula);
            if (rm.find(i) && rm.start() == i) {
                out.append(rewriteRef(rm.group(1), rm.group(2), rm.group(3), rm.group(4), firstRow, colMapper));
                out.append(':');
                out.append(rewriteRef(rm.group(5), rm.group(6), rm.group(7), rm.group(8), lastRow, colMapper));
                i = rm.end();
                continue;
            }
            Matcher m = REF.matcher(formula);
            if (m.find(i) && m.start() == i) {
                out.append(rewriteRef(m.group(1), m.group(2), m.group(3), m.group(4), firstRow, colMapper));
                i = m.end();
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    /** 匹配 A1:B2 形式的区间。 */
    private static final Pattern RANGE = Pattern.compile(
            "(\\$?)([A-Za-z]{1,3})(\\$?)(\\d{1,7}):(\\$?)([A-Za-z]{1,3})(\\$?)(\\d{1,7})");

    private static String rewriteRef(String absCol, String colLetters, String absRow, String rowDigits,
                                     IntUnaryOperator rowMapper, IntUnaryOperator colMapper) {
        int row = Integer.parseInt(rowDigits) - 1;
        int col = letterToCol(colLetters);
        int newRow = absRow.isEmpty() ? rowMapper.applyAsInt(row) : row;
        int newCol = absCol.isEmpty() ? colMapper.applyAsInt(col) : col;
        return absCol + colToLetter(newCol) + absRow + (newRow + 1);
    }

    /** 列出区间内的所有坐标（用于聚合函数遍历）。 */
    public static List<int[]> cellsInRange(int[] range) {
        List<int[]> list = new ArrayList<>();
        if (range == null) {
            return list;
        }
        for (int r = range[0]; r <= range[2]; r++) {
            for (int c = range[1]; c <= range[3]; c++) {
                list.add(new int[]{r, c});
            }
        }
        return list;
    }
}
