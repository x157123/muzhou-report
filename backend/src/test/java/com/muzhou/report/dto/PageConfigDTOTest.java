package com.muzhou.report.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 页头页尾占位高度的单元测试。
 *
 * <p>这个换算前端 {@code utils/print.js} 里有一份一样的（分页线要按它画），
 * 后端 {@code PdfExporter} / {@code WordExporter} 也各按它让出正文空间。改了这里要同步过去。
 */
class PageConfigDTOTest {

    @Test
    @DisplayName("没设页头页尾时不占高度，正文页边距原样")
    void blankHeaderReservesNothing() {
        PageConfigDTO cfg = new PageConfigDTO();

        assertEquals(0, cfg.headerReserveMm(), 0.001);
        assertEquals(0, cfg.footerReserveMm(), 0.001);
        assertEquals(10, cfg.effectiveMarginTopMm(), 0.001);
    }

    @Test
    @DisplayName("页头占的高度 = 页头边距 + 一行字高")
    void headerReserveIsMarginPlusLineHeight() {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getHeader().setCenter("标题");
        cfg.getHeader().setFontSize(9);
        cfg.getHeader().setMargin(5);

        // 9pt * 1.35 = 12.15pt ≈ 4.29mm
        assertEquals(5 + 9 * 1.35 / 72 * 25.4, cfg.headerReserveMm(), 0.001);
        // 默认上页边距 10mm 装得下，正文不用往下推
        assertEquals(10, cfg.effectiveMarginTopMm(), 0.001);
    }

    @Test
    @DisplayName("页边距装不下页头时正文往下推到页头下面（和 Excel 一样）")
    void contentIsPushedDownWhenMarginTooSmall() {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setMarginTop(5);
        cfg.getHeader().setCenter("很大的页头");
        cfg.getHeader().setFontSize(24);
        cfg.getHeader().setMargin(5);

        assertEquals(cfg.headerReserveMm(), cfg.effectiveMarginTopMm(), 0.001);
    }

    @Test
    @DisplayName("页尾同理，撑的是下页边距")
    void footerPushesBottomMargin() {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setMarginBottom(5);
        cfg.getFooter().setRight("第 ${page} 页");
        cfg.getFooter().setFontSize(20);
        cfg.getFooter().setMargin(6);

        assertEquals(6 + 20 * 1.35 / 72 * 25.4, cfg.effectiveMarginBottomMm(), 0.001);
        assertEquals(10, cfg.effectiveMarginTopMm(), 0.001, "上页边距不受页尾影响，还是默认的 10mm");
    }
}
