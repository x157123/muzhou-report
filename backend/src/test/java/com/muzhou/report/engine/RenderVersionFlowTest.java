package com.muzhou.report.engine;

import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.dto.PageConfigDTO;
import com.muzhou.report.dto.RenderResultDTO;
import com.muzhou.report.dto.ReportContentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐行选版本（VERSION 方案 Phase 2）接到渲染引擎上之后的行为。
 *
 * <p>「打印 3 月到 9 月的一批单据，每张按自己的下单日期用对应版式」—— 引擎并不知道「版本」是什么，
 * 它只认一个 {@code versionPicker}：给它一行数据，还它一份 content。
 *
 * <p>这里锁住的是那个真实的坑：<b>不同版本的模板张数可以不同</b>，于是
 * 「结果 sheet 数 = 模板张数 × N」这个前提崩了，靠 {@code renderedIndex % 模板张数} 推算打印设置
 * 再也算不回去 —— 所以打印设置由引擎随结果一起产出（{@code RenderResult.sheetPageConfigs}）。
 *
 * <pre>
 * v1：1 张模板，A4 纵向
 * v2：2 张模板，A3 横向
 * 主接口 orders 两行：SO-1(2026-03-01) 走 v1、SO-2(2026-09-01) 走 v2
 * </pre>
 *
 * <p>纯 POJO：引擎手工 new，取数函数手写，不启动 Spring。
 */
class RenderVersionFlowTest {

    private ReportRenderEngine engine;

    @BeforeEach
    void setUp() {
        FormulaEvaluator evaluator = new FormulaEvaluator();
        evaluator.init();
        engine = new ReportRenderEngine(new TemplateParser(),
                new ExpandProcessor(new CellFormatter(), evaluator, new MzProperties()),
                new MzProperties());
    }

    /* ------------------------------ 造数据 ------------------------------ */

    private Map<String, Object> cd(int r, int c, String text) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("v", text);
        v.put("m", text);
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("r", r);
        cell.put("c", c);
        cell.put("v", v);
        return cell;
    }

    private CellConfigDTO ordersCfg() {
        CellConfigDTO cfg = new CellConfigDTO();
        cfg.setType("data");
        cfg.setDatasetCode("orders");
        cfg.setField("no");
        cfg.setExpandType("none");
        cfg.setGroupType("list");
        cfg.setAggregate("none");
        cfg.setFormatType("text");
        return cfg;
    }

    private PageConfigDTO page(String paper, String orientation) {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setPaperSize(paper);
        cfg.setOrientation(orientation);
        return cfg;
    }

    /** 一份 content：sheetCount 张模板、统一一套打印设置。 */
    private ReportContentDTO content(String idPrefix, int sheetCount, String paper, String orientation,
                                     String splitMode) {
        List<Map<String, Object>> sheets = new ArrayList<>();
        Map<String, CellConfigDTO> cellConfigs = new LinkedHashMap<>();
        for (int i = 0; i < sheetCount; i++) {
            Map<String, Object> sheet = new LinkedHashMap<>();
            sheet.put("name", idPrefix + "-表" + (i + 1));
            sheet.put("id", idPrefix + "_sheet" + i);
            sheet.put("order", i);
            sheet.put("status", i == 0 ? 1 : 0);
            sheet.put("row", 10);
            sheet.put("column", 5);
            sheet.put("celldata", List.of(cd(0, 0, "#{orders.no}")));
            sheet.put("config", new LinkedHashMap<>());
            sheets.add(sheet);
            cellConfigs.put(i + "_0_0", ordersCfg());
        }
        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(sheets);
        content.setCellConfigs(cellConfigs);
        content.setPrimaryDataset("orders");
        content.setSplitMode(splitMode);
        content.setPageConfig(page(paper, orientation));
        return content;
    }

    private BiFunction<String, Map<String, Object>, List<Map<String, Object>>> fetcher() {
        return (code, params) -> {
            if ("orders".equals(code)) {
                return List.of(Map.of("no", "SO-1", "d", "2026-03-01"),
                        Map.of("no", "SO-2", "d", "2026-09-01"));
            }
            return List.of();
        };
    }

    /** 3 月的走 v1，之后的走 v2 —— 真实实现里这一步是 {@code ReportVersionResolver.resolveByValue}。 */
    private Function<Map<String, Object>, ReportContentDTO> picker(ReportContentDTO v1, ReportContentDTO v2) {
        return row -> "2026-03-01".equals(row.get("d")) ? v1 : v2;
    }

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("perRow 逐行换版式：模板张数不同也对得上，每张结果 sheet 的打印设置各是各的")
    void perRowPicksTemplatesAndPageConfigsPerRow() {
        ReportContentDTO v1 = content("v1", 1, "A4", "portrait", "perRow");
        ReportContentDTO v2 = content("v2", 2, "A3", "landscape", "perRow");

        RenderResultDTO result = engine.render(v1, Map.of(), fetcher(), picker(v1, v2));

        // 第 1 条走 v1（1 张），第 2 条走 v2（2 张）—— 不再是「模板张数 × 数据条数」
        assertEquals(3, result.getSheets().size());
        List<PageConfigDTO> configs = result.getSheetPageConfigs();
        assertNotNull(configs, "打印设置必须随结果一起给出");
        assertEquals(3, configs.size());
        assertEquals("A4", configs.get(0).getPaperSize());
        assertEquals("portrait", configs.get(0).getOrientation());
        assertEquals("A3", configs.get(1).getPaperSize());
        assertEquals("landscape", configs.get(1).getOrientation());
        assertEquals("A3", configs.get(2).getPaperSize());

        // 数据也确实是各行自己的
        assertEquals("SO-1", firstCellText(result.getSheets().get(0)));
        assertEquals("SO-2", firstCellText(result.getSheets().get(1)));
        assertEquals("SO-2", firstCellText(result.getSheets().get(2)));
    }

    @Test
    @DisplayName("perRowPage：打印设置不同的相邻份不拼在一起，拼完的设置取该组第一份的")
    void perRowPageBreaksWhenPageSetupDiffers() {
        ReportContentDTO v1 = content("v1", 1, "A4", "portrait", "perRowPage");
        ReportContentDTO v2 = content("v2", 2, "A3", "landscape", "perRowPage");

        RenderResultDTO result = engine.render(v1, Map.of(), fetcher(), picker(v1, v2));

        // 份1(A4) 自成一张，份2+份3(A3) 拼成一张 —— 横向那两份被拼进纵向那张就全变纵向了
        assertEquals(2, result.getSheets().size());
        List<PageConfigDTO> configs = result.getSheetPageConfigs();
        assertEquals(2, configs.size());
        assertEquals("A4", configs.get(0).getPaperSize());
        assertEquals("A3", configs.get(1).getPaperSize());

        // 拼成一张的那份内部要有行分页符（一份一页）
        Object breaks = result.getSheets().get(1).get("mzRowBreaks");
        assertTrue(breaks instanceof List<?> l && l.size() == 1, "两份拼一张该有一个行分页符: " + breaks);
    }

    @Test
    @DisplayName("不传 picker 时行为一字不变（老路径）：模板张数 × 数据条数，设置照模板取")
    void withoutPickerNothingChanges() {
        ReportContentDTO v1 = content("v1", 2, "A4", "portrait", "perRow");
        // 第 2 张模板单独设成横向
        v1.getPageConfigs().put("1", page("A3", "landscape"));

        RenderResultDTO result = engine.render(v1, Map.of(), fetcher());

        assertEquals(4, result.getSheets().size());
        List<PageConfigDTO> configs = result.getSheetPageConfigs();
        assertEquals(List.of("A4", "A3", "A4", "A3"),
                configs.stream().map(PageConfigDTO::getPaperSize).toList());
    }

    @Test
    @DisplayName("普通渲染（不拆分）也带上每张 sheet 的打印设置")
    void plainRenderAlsoReportsPageConfigs() {
        ReportContentDTO content = content("v1", 2, "A4", "portrait", "single");
        content.getPageConfigs().put("1", page("A3", "landscape"));

        RenderResultDTO result = engine.render(content, Map.of(), fetcher());

        assertEquals(2, result.getSheets().size());
        assertEquals(List.of("A4", "A3"),
                result.getSheetPageConfigs().stream().map(PageConfigDTO::getPaperSize).toList());
    }

    @SuppressWarnings("unchecked")
    private String firstCellText(Map<String, Object> sheet) {
        List<Map<String, Object>> celldata = (List<Map<String, Object>>) sheet.get("celldata");
        Map<String, Object> v = (Map<String, Object>) celldata.get(0).get("v");
        return String.valueOf(v.get("m"));
    }
}
