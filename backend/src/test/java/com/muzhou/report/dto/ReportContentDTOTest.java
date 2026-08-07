package com.muzhou.report.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打印设置的「按 sheet 覆盖 + 退回报表级」取值规则，见 docs/CONTRACT.md §4。
 *
 * <p>这条规则决定了老报表（只有一份 pageConfig）升级后还能不能正常出纸，值得单独锁住。
 */
class ReportContentDTOTest {

    @Test
    @DisplayName("没单独设过的 sheet 退回报表级设置")
    void fallsBackToReportLevel() {
        ReportContentDTO content = new ReportContentDTO();
        content.getPageConfig().setPaperSize("A5");

        assertSame(content.getPageConfig(), content.pageConfigOf(0));
        assertEquals("A5", content.pageConfigOf(3).getPaperSize());
    }

    @Test
    @DisplayName("单独设过的 sheet 用自己的，不影响其它 sheet")
    void perSheetOverrideWins() {
        ReportContentDTO content = new ReportContentDTO();
        content.getPageConfig().setPaperSize("A4");
        PageConfigDTO own = new PageConfigDTO();
        own.setPaperSize("A3");
        own.setOrientation("landscape");
        content.getPageConfigs().put("1", own);

        assertEquals("A4", content.pageConfigOf(0).getPaperSize());
        assertEquals("A3", content.pageConfigOf(1).getPaperSize());
        assertEquals("landscape", content.pageConfigOf(1).getOrientation());
        assertEquals("A4", content.pageConfigOf(2).getPaperSize());
    }

    @Test
    @DisplayName("两种拆分模式都要有主接口，没设主接口一律不拆")
    void splitNeedsPrimaryDataset() {
        ReportContentDTO content = new ReportContentDTO();
        content.setSplitMode("perRow");
        assertFalse(content.splitByRow(), "没设主接口就不知道按谁拆");

        content.setPrimaryDataset("orders");
        assertTrue(content.splitByRow());
        assertFalse(content.concatPerRow(), "perRow 是拆成多张 sheet，不拼");

        content.setSplitMode("perRowPage");
        assertTrue(content.splitByRow(), "perRowPage 走的是同一段拆分代码");
        assertTrue(content.concatPerRow());

        content.setSplitMode("single");
        assertFalse(content.splitByRow());
        assertFalse(content.concatPerRow());
    }

    @Test
    @DisplayName("perRow 的结果下标要取模映射回模板，perRowPage 不取模")
    void pageConfigOfRenderedMapsOnlyWhenSheetsMultiply() {
        ReportContentDTO content = new ReportContentDTO();
        content.setPrimaryDataset("orders");
        content.getSheets().add(java.util.Map.of("name", "明细"));
        content.getSheets().add(java.util.Map.of("name", "汇总"));
        content.getPageConfig().setPaperSize("A4");
        PageConfigDTO landscape = new PageConfigDTO();
        landscape.setOrientation("landscape");
        content.getPageConfigs().put("1", landscape);

        // perRow：2 张模板 × N 条数据，结果下标 3 是第 2 条数据的第 2 张模板
        content.setSplitMode("perRow");
        assertEquals("landscape", content.pageConfigOfRendered(3).getOrientation(),
                "拆出来每一份的第 2 张都该跟着模板第 2 张");

        // perRowPage：所有份摞进同一张 sheet，结果下标恒为 0 —— 整份用模板第 1 张的设置
        content.setSplitMode("perRowPage");
        assertEquals("portrait", content.pageConfigOfRendered(0).getOrientation(),
                "拼接模式整张跟模板第 1 张");
        // 不取模：越界下标退回报表级设置（拼接模式下不会出现，取模反而会把它错映射到模板 1）
        assertEquals("portrait", content.pageConfigOfRendered(3).getOrientation());
    }

    @Test
    @DisplayName("结果 sheet 带 mzTemplateIndex 时一律认它 —— 拼接后下标推算不出来")
    void pageConfigOfSheetPrefersMarker() {
        ReportContentDTO content = new ReportContentDTO();
        content.setPrimaryDataset("orders");
        content.setSplitMode("perRowPage");
        content.getSheets().add(java.util.Map.of("name", "表1"));
        content.getSheets().add(java.util.Map.of("name", "表2"));
        PageConfigDTO landscape = new PageConfigDTO();
        landscape.setOrientation("landscape");
        content.getPageConfigs().put("1", landscape);

        // 拼接断开后，结果里第 2 张出自模板第 2 张（横向）—— 按结果下标 1 也对得上，
        // 但第 4 张（第 2 条数据的表2）就只能靠标记了
        assertEquals("landscape",
                content.pageConfigOfSheet(java.util.Map.of("mzTemplateIndex", 1), 3).getOrientation());
        assertEquals("portrait",
                content.pageConfigOfSheet(java.util.Map.of("mzTemplateIndex", 0), 2).getOrientation());
        // 没有标记（老结果 / 手工构造的）退回按下标推算
        assertEquals("landscape", content.pageConfigOfSheet(java.util.Map.of(), 1).getOrientation());
        assertEquals("portrait", content.pageConfigOfSheet(null, 0).getOrientation());
    }
}
