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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「第一张模板是清单列表、第二张是每条数据的详情」：拆分不再是整份报表一刀切，
 * 而是**按模板**决定跟不跟着拆（{@code content.sheetSplits}）。
 *
 * <pre>
 * 模板0「清单」 once   ：一行一条数据的行带（纵向扩展），主接口给它**全量**
 * 模板1「详情」 perRow ：一条数据一份
 * 主接口 orders 三行：SO-1 / SO-2 / SO-3
 * </pre>
 *
 * <p>这里锁住的几件事，每一件漏了都是实打实的报障：
 * <ul>
 *   <li>清单页只出一份、且拿得到全部三行 —— 老的 {@code perRow} 会把它复制 3 遍、每份一行；</li>
 *   <li>输出顺序是「清单、详情1、详情2、详情3」；</li>
 *   <li>{@code perRowPage} 下<b>段与段之间必定断开</b>，哪怕两段的打印设置一模一样
 *       —— 清单被摞进单据那张里，Excel 端就没法单独筛选排序了；</li>
 *   <li>清单自成一份单据（{@code mzDocBreaks}），页码不跟后面的单据连着数；</li>
 *   <li>没配 {@code sheetSplits} 的老报表一个字都不变。</li>
 * </ul>
 *
 * <p>纯 POJO：引擎手工 new，取数函数手写，不启动 Spring。
 */
class RenderSplitSegmentTest {

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

    private CellConfigDTO ordersCfg(String expandType) {
        CellConfigDTO cfg = new CellConfigDTO();
        cfg.setType("data");
        cfg.setDatasetCode("orders");
        cfg.setField("no");
        cfg.setExpandType(expandType);
        cfg.setGroupType("list");
        cfg.setAggregate("none");
        cfg.setFormatType("text");
        return cfg;
    }

    private Map<String, Object> sheet(String name, int index, List<Map<String, Object>> celldata) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("id", "sheet" + index);
        s.put("order", index);
        s.put("status", index == 0 ? 1 : 0);
        s.put("row", 20);
        s.put("column", 5);
        s.put("celldata", celldata);
        s.put("config", new LinkedHashMap<>());
        return s;
    }

    private PageConfigDTO page(String paper) {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setPaperSize(paper);
        cfg.setOrientation("portrait");
        return cfg;
    }

    /**
     * 清单（模板0，行带纵向扩展）+ 详情（模板1，一条数据一份）。
     *
     * @param listOnce 清单那张标不标 {@code once}（false = 老行为，两张都跟着拆）
     */
    private ReportContentDTO content(String splitMode, boolean listOnce) {
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheet("清单", 0, List.of(cd(0, 0, "订单清单"), cd(1, 0, "#{orders.no}"))));
        sheets.add(sheet("详情", 1, List.of(cd(0, 0, "#{orders.no}"))));

        Map<String, CellConfigDTO> cellConfigs = new LinkedHashMap<>();
        cellConfigs.put("0_1_0", ordersCfg("down"));
        cellConfigs.put("1_0_0", ordersCfg("none"));

        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(sheets);
        content.setCellConfigs(cellConfigs);
        content.setPrimaryDataset("orders");
        content.setSplitMode(splitMode);
        content.setSheetNameField("no");
        content.setPageConfig(page("A4"));
        if (listOnce) {
            content.getSheetSplits().put("0", "once");
        }
        return content;
    }

    private BiFunction<String, Map<String, Object>, List<Map<String, Object>>> fetcher() {
        return (code, params) -> {
            if ("orders".equals(code)) {
                return List.of(Map.of("no", "SO-1"), Map.of("no", "SO-2"), Map.of("no", "SO-3"));
            }
            return List.of();
        };
    }

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("perRow：清单只出一份且拿到全量，后面才是一条数据一张详情")
    void listSheetRendersOnceWithAllRows() {
        RenderResultDTO result = engine.render(content("perRow", true), Map.of(), fetcher());

        // 1 张清单 + 3 张详情，不是老的 2×3
        assertEquals(4, result.getSheets().size());
        assertEquals(List.of("清单", "SO-1", "SO-2", "SO-3"), names(result));

        // 清单那张：标题 + 行带展开出来的 3 行，全量都在
        assertEquals(List.of("订单清单", "SO-1", "SO-2", "SO-3"), texts(result.getSheets().get(0)));
        // 详情那张：只有自己那一行
        assertEquals(List.of("SO-2"), texts(result.getSheets().get(2)));
    }

    @Test
    @DisplayName("perRow：清单自成一份单据，页码不跟后面的单据连着数")
    void listSheetIsItsOwnDocument() {
        RenderResultDTO result = engine.render(content("perRow", true), Map.of(), fetcher());

        // 每张都是一份新单据的开头（清单一份、三张详情各一份）
        for (Map<String, Object> sheet : result.getSheets()) {
            assertEquals(List.of(0), sheet.get("mzDocBreaks"), "每张都该重编页码: " + sheet.get("name"));
        }
    }

    @Test
    @DisplayName("perRowPage：三份详情拼成一张，清单**不**被拼进去")
    void listSheetIsNeverConcatenatedIntoDocuments() {
        ReportContentDTO content = content("perRowPage", true);
        content.getPageConfigs().put("1", page("A3"));

        RenderResultDTO result = engine.render(content, Map.of(), fetcher());

        assertEquals(2, result.getSheets().size());
        assertEquals(List.of("A4", "A3"),
                result.getSheetPageConfigs().stream().map(PageConfigDTO::getPaperSize).toList());

        // 清单那张不该有行分页符（它就一份）
        assertEquals(List.of(), result.getSheets().get(0).get("mzRowBreaks"));
        // 详情那张：三份首尾相接，两个行分页符 + 三个单据起点 + 三个单据名
        Map<String, Object> docs = result.getSheets().get(1);
        assertTrue(docs.get("mzRowBreaks") instanceof List<?> l && l.size() == 2,
                "三份拼一张该有两个行分页符: " + docs.get("mzRowBreaks"));
        assertTrue(docs.get("mzDocBreaks") instanceof List<?> d && d.size() == 3,
                "三份单据该有三个起点: " + docs.get("mzDocBreaks"));
        assertEquals(List.of("SO-1", "SO-2", "SO-3"), docs.get("mzDocNames"));
    }

    @Test
    @DisplayName("perRowPage：打印设置一模一样时段与段之间照样断开")
    void segmentsNeverMergeEvenWithIdenticalPageSetup() {
        // 两段都用报表级那一份设置（equals 相同）—— 只按设置比对的话会被拼成一张
        RenderResultDTO result = engine.render(content("perRowPage", true), Map.of(), fetcher());

        assertEquals(2, result.getSheets().size());
        assertEquals(List.of("订单清单", "SO-1", "SO-2", "SO-3"), texts(result.getSheets().get(0)));
    }

    @Test
    @DisplayName("整份都标 once 时退回普通渲染：每张模板一份，主接口全量")
    void allOnceFallsBackToPlainRender() {
        ReportContentDTO content = content("perRow", true);
        content.getSheetSplits().put("1", "once");

        RenderResultDTO result = engine.render(content, Map.of(), fetcher());

        assertEquals(2, result.getSheets().size());
        assertEquals(List.of("清单", "详情"), names(result));
        // 拆分是恒等变换，不该再给下游挂「一份单据」的标记
        assertNull(result.getSheets().get(0).get("mzDocBreaks"));
    }

    @Test
    @DisplayName("没配 sheetSplits 的老报表一个字不变：模板张数 × 数据条数")
    void legacyReportsUnchanged() {
        RenderResultDTO result = engine.render(content("perRow", false), Map.of(), fetcher());

        // 行优先：一条数据的两张模板挨着
        assertEquals(6, result.getSheets().size());
        assertEquals(List.of("SO-1-清单", "SO-1-详情", "SO-2-清单", "SO-2-详情", "SO-3-清单", "SO-3-详情"),
                names(result));
    }

    /* ------------------------------ 断言辅助 ------------------------------ */

    private List<String> names(RenderResultDTO result) {
        return result.getSheets().stream().map(s -> String.valueOf(s.get("name"))).toList();
    }

    /** 一张 sheet 上的文字，按 celldata 顺序。 */
    @SuppressWarnings("unchecked")
    private List<String> texts(Map<String, Object> sheet) {
        List<Map<String, Object>> celldata = (List<Map<String, Object>>) sheet.get("celldata");
        return celldata.stream()
                .map(cd -> String.valueOf(((Map<String, Object>) cd.get("v")).get("m")))
                .toList();
    }
}
