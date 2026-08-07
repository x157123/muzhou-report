package com.muzhou.report.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A1RefUtils 单元测试（纯 POJO，不启动 Spring）。
 */
class A1RefUtilsTest {

    @Test
    @DisplayName("列号与列字母互转：A / Z / AA / AZ / BA")
    void colLetterRoundTrip() {
        assertEquals("A", A1RefUtils.colToLetter(0));
        assertEquals("Z", A1RefUtils.colToLetter(25));
        assertEquals("AA", A1RefUtils.colToLetter(26));
        assertEquals("AZ", A1RefUtils.colToLetter(51));
        assertEquals("BA", A1RefUtils.colToLetter(52));

        assertEquals(0, A1RefUtils.letterToCol("A"));
        assertEquals(25, A1RefUtils.letterToCol("Z"));
        assertEquals(26, A1RefUtils.letterToCol("AA"));
        assertEquals(51, A1RefUtils.letterToCol("AZ"));
        assertEquals(52, A1RefUtils.letterToCol("BA"));
    }

    @Test
    @DisplayName("toA1 / parseA1 互为逆运算")
    void toA1AndParse() {
        assertEquals("A1", A1RefUtils.toA1(0, 0));
        assertEquals("C5", A1RefUtils.toA1(4, 2));

        assertArrayEquals(new int[]{4, 2}, A1RefUtils.parseA1("C5"));
        assertArrayEquals(new int[]{0, 0}, A1RefUtils.parseA1("A1"));
        assertNull(A1RefUtils.parseA1("not-a-ref"));
    }

    @Test
    @DisplayName("parseRange 解析区间，端点顺序颠倒也能正确归一")
    void parseRange() {
        // A2:B10 -> 行 1..9，列 0..1（0 基）
        assertArrayEquals(new int[]{1, 0, 9, 1}, A1RefUtils.parseRange("A2:B10"));
        // 颠倒写法 B10:A2 结果应一致
        assertArrayEquals(new int[]{1, 0, 9, 1}, A1RefUtils.parseRange("B10:A2"));
        // 单个单元格视为 1x1 区间
        assertArrayEquals(new int[]{0, 0, 0, 0}, A1RefUtils.parseRange("A1"));
        assertNull(A1RefUtils.parseRange(""));
        assertNull(A1RefUtils.parseRange(null));
    }

    @Test
    @DisplayName("shiftFormula 按行/列映射重写公式里的相对引用")
    void shiftRelativeRefs() {
        IntUnaryOperator rowMapper = r -> r + 3; // 整体下移 3 行
        IntUnaryOperator colMapper = c -> c;     // 列不变

        String shifted = A1RefUtils.shiftFormula("=SUM(B2:B5)", rowMapper, colMapper);
        // B2(0基row1)->row4->B5；B5(0基row4)->row7->B8
        assertEquals("=SUM(B5:B8)", shifted);
    }

    @Test
    @DisplayName("shiftFormula 不偏移带 $ 的绝对引用")
    void shiftKeepsAbsoluteRefs() {
        IntUnaryOperator rowMapper = r -> r + 3;
        IntUnaryOperator colMapper = c -> c + 2;

        // A$1：行绝对，不随 rowMapper 偏移；列相对，随 colMapper 偏移 A->C
        // $B2：列绝对，不随 colMapper 偏移；行相对，随 rowMapper 偏移 2(0基1)->4(0基)->行5
        String shifted = A1RefUtils.shiftFormula("=A$1+$B2", rowMapper, colMapper);
        assertEquals("=C$1+$B5", shifted);
    }

    @Test
    @DisplayName("shiftFormula 跳过字符串字面量内的引用样式文本")
    void shiftSkipsStringLiterals() {
        IntUnaryOperator rowMapper = r -> r + 3;
        IntUnaryOperator colMapper = c -> c;

        String shifted = A1RefUtils.shiftFormula("=CONCAT(\"A1\", B2)", rowMapper, colMapper);
        // 双引号里的 "A1" 原样保留，双引号外的 B2 正常偏移成 B5
        assertEquals("=CONCAT(\"A1\", B5)", shifted);
    }
}
