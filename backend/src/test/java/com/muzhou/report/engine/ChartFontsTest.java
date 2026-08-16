package com.muzhou.report.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图表字体的挑选规则：{@link ChartFonts}。
 *
 * <p>这里能锁住的是**规则本身**（配置优先、字号/粗体照给、坏路径不抛异常），
 * 至于最终落到哪一款字体，跟跑测试的这台机器装了什么有关，断言不了。
 */
class ChartFontsTest {

    @AfterEach
    void reset() {
        // 静态状态，别把配置留给下一个测试（ExpandProcessor 起 Spring 时也会 configure 一次）
        ChartFonts.configure(null);
    }

    @Test
    @DisplayName("字号与粗体照给，返回的字体不为 null")
    void deriveSizeAndWeight() {
        Font plain = ChartFonts.of(24f, false);
        Font bold = ChartFonts.of(24f, true);
        assertNotNull(plain);
        assertEquals(24f, plain.getSize2D(), 0.01);
        assertEquals(24f, bold.getSize2D(), 0.01);
        assertTrue(bold.isBold(), "标题要的是粗体");
    }

    @Test
    @DisplayName("配了不存在的路径不抛异常，退回下一档")
    void badPathFallsBack() {
        ChartFonts.configure("Z:/没有这个文件.ttf");
        assertNotNull(ChartFonts.of(12f, false));
    }

    @Test
    @DisplayName("配了 .ttc 也不抛异常 —— AWT 读不了字体集，退回下一档")
    void ttcFallsBack() {
        ChartFonts.configure("C:/Windows/Fonts/msyh.ttc,0");
        assertNotNull(ChartFonts.of(12f, false));
    }

    @Test
    @DisplayName("装了中文字体的机器上，图表字体显示得出中文")
    void showsChineseWhenAvailable() {
        Font font = ChartFonts.of(12f, false);
        boolean systemHasChinese = new Font(Font.SANS_SERIF, Font.PLAIN, 12).canDisplay('中');
        if (systemHasChinese) {
            assertTrue(font.canDisplay('中'), "系统有中文字体时，挑出来的这款必须显示得出中文");
        }
    }

    @Test
    @DisplayName("数字与标点不该按等宽中文字体的格子排 —— 逗号明显窄于数字")
    void punctuationIsNotFullWidth() {
        // 中文字体几乎都是等宽的：逗号也占满半个汉字的格子，数值标签会排成「1, 234. 5」。
        // 这条断言锁的就是「优先用逻辑字体」那一档（见 ChartFonts 类注释）。
        // 系统没有中文字体时挑出来的必然是逻辑字体，这条同样成立。
        Font font = ChartFonts.of(100f, false);
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            FontRenderContext frc = g.getFontRenderContext();
            double comma = font.getStringBounds(",", frc).getWidth();
            double digit = font.getStringBounds("8", frc).getWidth();
            assertTrue(comma < digit * 0.8,
                    "逗号(" + comma + ")该明显窄于数字(" + digit + ")，否则数字串两边全是空档");
        } finally {
            g.dispose();
        }
    }
}
