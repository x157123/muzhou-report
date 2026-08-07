package com.muzhou.report.engine;

import com.muzhou.report.dto.CellConfigDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 单元格格式化的单元测试（纯 POJO，不启动 Spring）。
 *
 * <p>盯着两件事：①货币符号必须同时进显示文本 {@code m} 与 {@code ct.fa}
 * ——只进 m 的话导出的 Excel 按 fa 重新格式化，符号就没了；
 * ②金额中文大写出的是文字，{@code ct} 必须标成文本，标成数字会被 Excel 重新算回阿拉伯数字。
 */
class CellFormatterTest {

    private final CellFormatter formatter = new CellFormatter();

    private CellConfigDTO cfg(String type, String pattern) {
        CellConfigDTO c = new CellConfigDTO();
        c.setFormatType(type);
        c.setFormatPattern(pattern);
        return c;
    }

    @Test
    @DisplayName("金额的货币符号写在模板里，显示文本与 ct.fa 都带着它")
    void currencySymbolComesFromPattern() {
        CellFormatter.Formatted f = formatter.format(new BigDecimal("1234.5"), cfg("currency", "¥#,##0.00"));
        assertEquals("¥1,234.50", f.display());
        assertEquals("¥#,##0.00", f.ct().get("fa"), "符号要留在 fa 里，导出才带得走");
        assertEquals("n", f.ct().get("t"));
    }

    @Test
    @DisplayName("换成美元符号不会再被拼上一个 ¥")
    void currencyPrefixIsNotDoubled() {
        assertEquals("$1,234.50", formatter.format(new BigDecimal("1234.5"), cfg("currency", "$#,##0.00")).display());
        assertEquals("1,234.50", formatter.format(new BigDecimal("1234.5"), cfg("currency", "#,##0.00")).display());
    }

    @Test
    @DisplayName("金额模板为空时按默认的 ¥#,##0.00 出")
    void currencyDefaultsToRmb() {
        assertEquals("¥1,234.50", formatter.format(new BigDecimal("1234.5"), cfg("currency", "")).display());
    }

    @Test
    @DisplayName("中文大写：元角分、整、零的读法")
    void chineseAmount() {
        assertEquals("壹仟贰佰叁拾肆元伍角陆分", cn("1234.56"));
        assertEquals("壹仟贰佰叁拾肆元整", cn("1234"));
        assertEquals("壹佰元零伍分", cn("100.05"));
        assertEquals("壹佰元伍角", cn("100.5"));
        assertEquals("零元整", cn("0"));
        assertEquals("零元伍角陆分", cn("0.56"));
        assertEquals("负壹佰贰拾叁元肆角伍分", cn("-123.45"));
        // 分以下四舍五入
        assertEquals("壹元贰角叁分", cn("1.2349"));
        assertEquals("壹元贰角肆分", cn("1.2350"));
    }

    @Test
    @DisplayName("中文大写：节与节之间的零只读一个，整节为零也只读一个")
    void chineseAmountZeroSections() {
        assertEquals("壹万零壹元整", cn("10001"));
        assertEquals("壹万壹仟元整", cn("11000"));
        assertEquals("壹亿零壹元整", cn("100000001"));
        assertEquals("壹仟零壹拾元整", cn("1010"));
        assertEquals("壹拾亿贰仟万零叁佰元整", cn("1020000300"));
        assertEquals("壹万亿元整", cn("1000000000000"));
    }

    @Test
    @DisplayName("中文大写出的是文字，ct 标成文本（fa=@ / t=s）")
    void chineseAmountIsText() {
        CellFormatter.Formatted f = formatter.format(new BigDecimal("1234.56"),
                cfg("currency", CellFormatter.CN_UPPER));
        assertEquals("@", f.ct().get("fa"));
        assertEquals("s", f.ct().get("t"), "标成 n 的话导出时 Excel 会照 fa 把大写算回数字");
    }

    @Test
    @DisplayName("中文大写遇到非数字/超出万亿：退回普通金额，别把值吞掉")
    void chineseAmountFallsBack() {
        CellFormatter.Formatted text = formatter.format("待定", cfg("currency", CellFormatter.CN_UPPER));
        assertEquals("待定", text.display());

        CellFormatter.Formatted huge = formatter.format(new BigDecimal("10000000000000000"),
                cfg("currency", CellFormatter.CN_UPPER));
        assertEquals("¥10,000,000,000,000,000.00", huge.display());
        assertEquals("n", huge.ct().get("t"));
    }

    @Test
    @DisplayName("日期支持自定义格式，中文字面量原样输出")
    void dateAcceptsCustomPattern() {
        LocalDate day = LocalDate.of(2024, 1, 31);
        assertEquals("2024-01-31", formatter.format(day, cfg("date", "")).display());
        assertEquals("2024年01月31日", formatter.format(day, cfg("date", "yyyy年MM月dd日")).display());
        assertEquals("2024/01/31", formatter.format("2024-01-31", cfg("date", "yyyy/MM/dd")).display());
        assertEquals("yyyy年MM月dd日", formatter.format(day, cfg("date", "yyyy年MM月dd日")).ct().get("fa"));
    }

    @Test
    @DisplayName("认不出的日期原样输出，不吞值")
    void unparsableDateIsKept() {
        assertEquals("待定", formatter.format("待定", cfg("date", "yyyy-MM-dd")).display());
    }

    private String cn(String value) {
        return formatter.format(new BigDecimal(value), cfg("currency", CellFormatter.CN_UPPER)).display();
    }
}
