package com.muzhou.report.export;

import com.muzhou.report.dto.HeaderFooterDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页头页尾的占位符编解码单元测试（纯 POJO）。
 *
 * <p>这一层是 PDF / Word 两条导出路的共同前提：报表里存 {@code ${page}}，xlsx 里存 {@code &P}，
 * 转换器再从 xlsx 读回来解成文字。编、拆、解三步任何一步错了，页头就会缺字或多出一串代码。
 */
class HeaderFooterTextTest {

    private static final HeaderFooterText.Ctx CTX =
            new HeaderFooterText.Ctx(3, 10, "销售明细", LocalDateTime.of(2026, 7, 30, 14, 5));

    @Test
    @DisplayName("三段各自带上字号，占位符编成 Excel 页眉代码")
    void encodesSectionsAndPlaceholders() {
        HeaderFooterDTO hf = new HeaderFooterDTO();
        hf.setLeft("月度报表");
        hf.setCenter("第 ${page} 页 / 共 ${pages} 页");
        hf.setRight("${date}");
        hf.setFontSize(10);

        assertEquals("&L&10月度报表&C&10第 &P 页 / 共 &N 页&R&10&D", HeaderFooterText.toExcelCode(hf));
    }

    @Test
    @DisplayName("空段不写进页眉串")
    void skipsBlankSections() {
        HeaderFooterDTO hf = new HeaderFooterDTO();
        hf.setCenter("只有中间");

        // 字号恒写两位，见 append：一位的话紧跟的数字文字会被卷进字号里
        assertEquals("&C&09只有中间", HeaderFooterText.toExcelCode(hf));
    }

    @Test
    @DisplayName("三段都空 = 没设置，编出空串")
    void blankHeaderEncodesToEmpty() {
        assertEquals("", HeaderFooterText.toExcelCode(new HeaderFooterDTO()));
    }

    @Test
    @DisplayName("编 -> 拆 -> 解 一圈回来，文字和字号都对得上")
    void roundTripsThroughExcelCode() {
        HeaderFooterDTO hf = new HeaderFooterDTO();
        hf.setLeft("销售部");
        hf.setCenter("第 ${page}/${pages} 页");
        hf.setRight("${sheet} ${date} ${time}");
        hf.setFontSize(12);

        String[] parts = HeaderFooterText.split(HeaderFooterText.toExcelCode(hf));

        assertEquals("销售部", HeaderFooterText.parse(parts[HeaderFooterText.LEFT], CTX).text());
        assertEquals("第 3/10 页", HeaderFooterText.parse(parts[HeaderFooterText.CENTER], CTX).text());
        assertEquals("销售明细 2026-07-30 14:05",
                HeaderFooterText.parse(parts[HeaderFooterText.RIGHT], CTX).text());
        assertEquals(12, HeaderFooterText.parse(parts[HeaderFooterText.LEFT], CTX).fontSize());
    }

    @Test
    @DisplayName("用户文字里的 & 要转义，否则「A&C公司」会被当成「居中段从这里开始」")
    void escapesAmpersandSoItIsNotReadAsSectionMarker() {
        HeaderFooterDTO hf = new HeaderFooterDTO();
        hf.setLeft("A&C公司");

        String[] parts = HeaderFooterText.split(HeaderFooterText.toExcelCode(hf));

        assertEquals("A&C公司", HeaderFooterText.parse(parts[HeaderFooterText.LEFT], CTX).text());
        assertEquals("", HeaderFooterText.parse(parts[HeaderFooterText.CENTER], CTX).text(),
                "转义没生效的话「C公司」会跑到中间段去");
    }

    @Test
    @DisplayName("文字以数字开头时不会被卷进字号里（「2026 年度」不是 92026 磅）")
    void textStartingWithDigitsIsNotEatenByFontSize() {
        HeaderFooterDTO hf = new HeaderFooterDTO();
        hf.setCenter("2026 年度经营分析");
        hf.setFontSize(9);

        String[] parts = HeaderFooterText.split(HeaderFooterText.toExcelCode(hf));
        HeaderFooterText.Section section = HeaderFooterText.parse(parts[HeaderFooterText.CENTER], CTX);

        assertEquals("2026 年度经营分析", section.text(), "开头的数字被当成字号吃掉了");
        assertEquals(9, section.fontSize(), "字号该是 9 磅，不是 92026");
    }

    @Test
    @DisplayName("两位字号照旧原样读回")
    void twoDigitFontSizeRoundTrips() {
        HeaderFooterDTO hf = new HeaderFooterDTO();
        hf.setCenter("12 月汇总");
        hf.setFontSize(16);

        String[] parts = HeaderFooterText.split(HeaderFooterText.toExcelCode(hf));
        HeaderFooterText.Section section = HeaderFooterText.parse(parts[HeaderFooterText.CENTER], CTX);

        assertEquals("12 月汇总", section.text());
        assertEquals(16, section.fontSize());
    }

    @Test
    @DisplayName("hasPageNumber 认的是「印不印页码」，不是「有没有页头页尾」")
    void detectsPageNumberPlaceholders() {
        HeaderFooterDTO withPage = new HeaderFooterDTO();
        withPage.setCenter("第 ${page} 页");
        HeaderFooterDTO withPages = new HeaderFooterDTO();
        withPages.setRight("共 ${pages} 页");
        HeaderFooterDTO titleOnly = new HeaderFooterDTO();
        titleOnly.setCenter("2026 年度经营分析 ${date}");

        assertTrue(HeaderFooterText.hasPageNumber(
                HeaderFooterText.split(HeaderFooterText.toExcelCode(withPage))));
        assertTrue(HeaderFooterText.hasPageNumber(
                HeaderFooterText.split(HeaderFooterText.toExcelCode(withPages))));
        assertFalse(HeaderFooterText.hasPageNumber(
                HeaderFooterText.split(HeaderFooterText.toExcelCode(titleOnly))),
                "只写了标题的封面不该占页数");
        assertFalse(HeaderFooterText.hasPageNumber(HeaderFooterText.toExcelCode(new HeaderFooterDTO())),
                "没设页头页尾自然也不印页码");
        assertFalse(HeaderFooterText.hasPageNumber((String[]) null));
    }

    @Test
    @DisplayName("用户文字里写的「&P」是文字不是页码，转义后不该被当成页码")
    void escapedAmpersandIsNotAPageNumber() {
        HeaderFooterDTO hf = new HeaderFooterDTO();
        hf.setCenter("A&P超市");

        assertFalse(HeaderFooterText.hasPageNumber(
                HeaderFooterText.split(HeaderFooterText.toExcelCode(hf))));
    }

    @Test
    @DisplayName("不认识的格式码丢掉，不要原样留在页头上")
    void dropsUnknownCodes() {
        // 别处（Excel 自己）设的页眉可能带粗体、字体名、颜色码
        String[] parts = HeaderFooterText.split("&L&B&\"宋体,Bold\"&KFF0000标题&R&P");

        assertEquals("标题", HeaderFooterText.parse(parts[HeaderFooterText.LEFT], CTX).text());
        assertEquals("3", HeaderFooterText.parse(parts[HeaderFooterText.RIGHT], CTX).text());
    }

    @Test
    @DisplayName("没写分段标记时整串算居中段（Excel 的规矩）")
    void textWithoutMarkerGoesToCenter() {
        String[] parts = HeaderFooterText.split("裸文字");

        assertEquals("", parts[HeaderFooterText.LEFT]);
        assertEquals("裸文字", parts[HeaderFooterText.CENTER]);
    }

    @Test
    @DisplayName("页码单独成片段，Word 那条路要靠它写成 PAGE 域")
    void keepsPageNumbersAsSeparateParts() {
        HeaderFooterText.Parsed parsed = HeaderFooterText.parseParts("第 &P 页 / 共 &N 页", CTX);

        List<HeaderFooterText.Part> parts = parsed.parts();
        assertEquals(5, parts.size(), "文字/页码/文字/总页数/文字 共 5 段，实际:" + parts);
        assertEquals(HeaderFooterText.Kind.PAGE, parts.get(1).kind());
        assertEquals(HeaderFooterText.Kind.PAGES, parts.get(3).kind());
        assertEquals("第 ", parts.get(0).text());
        assertTrue(parts.get(1).text().isEmpty(), "页码片段本身不带文字");
    }
}
