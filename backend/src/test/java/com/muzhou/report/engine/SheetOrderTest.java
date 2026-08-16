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

/**
 * 工作表的**出纸顺序按 `order` 排**，不是按 `content.sheets` 的数组顺序。
 *
 * <p>设计器里拖动标签重排时，FortuneSheet 只改各 sheet 的 `order`、不动数组顺序
 * （标签栏也是照 `order` 显示的），所以照数组顺序出纸的话就是
 * 「拖完保存了，导出的 Excel/PDF/Word 还是原来的顺序」—— 这个用例锁的就是它。
 *
 * <p>同时锁住另一半：**`sheetIndex` 不跟着排**。`cellConfigs` 与 `pageConfigs` 都按数组下标
 * 寻址（CONTRACT §4），跟着排就等于把所有绑定和打印设置挪到别人身上。
 *
 * <p>纯 POJO：引擎手工 new，取数函数手写，不启动 Spring。
 */
class SheetOrderTest {

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

    /** 数组下标 index、拖动之后排在第 order 位的一张 sheet。 */
    private Map<String, Object> sheet(String name, int index, int order, List<Map<String, Object>> celldata) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("id", "sheet" + index);
        s.put("order", order);
        // 拖动重排不改 status（它记的是「打开时显示哪一张」），这里刻意留在数组第一张上
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

    /**
     * 三张 sheet，数组顺序 A / B / C，用户把它们拖成了 B / C / A。
     * 每张各配一份打印设置（纸张互不相同，用来验证配置跟对了 sheet）。
     */
    private ReportContentDTO content() {
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheet("A", 0, 2, List.of(cd(0, 0, "A"), cd(1, 0, "#{orders.no}"))));
        sheets.add(sheet("B", 1, 0, List.of(cd(0, 0, "B"))));
        sheets.add(sheet("C", 2, 1, List.of(cd(0, 0, "C"))));

        Map<String, CellConfigDTO> cellConfigs = new LinkedHashMap<>();
        // key 里的 0 是**数组下标**（A 那张），不是它排在第几位
        cellConfigs.put("0_1_0", ordersCfg("down"));

        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(sheets);
        content.setCellConfigs(cellConfigs);
        content.setPageConfig(page("A4"));
        content.getPageConfigs().put("0", page("A3"));
        content.getPageConfigs().put("1", page("A5"));
        return content;
    }

    private BiFunction<String, Map<String, Object>, List<Map<String, Object>>> fetcher() {
        return (code, params) -> "orders".equals(code)
                ? List.of(Map.of("no", "SO-1"), Map.of("no", "SO-2"))
                : List.of();
    }

    private List<String> names(RenderResultDTO result) {
        return result.getSheets().stream().map(s -> String.valueOf(s.get("name"))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> texts(Map<String, Object> sheet) {
        List<Map<String, Object>> celldata = (List<Map<String, Object>>) sheet.get("celldata");
        return celldata.stream()
                .map(cd -> String.valueOf(((Map<String, Object>) cd.get("v")).get("m")))
                .toList();
    }

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("出纸顺序按 order 排：拖过标签的报表，导出的顺序与设计器一致")
    void sheetsAreRenderedInDraggedOrder() {
        RenderResultDTO result = engine.render(content(), Map.of(), fetcher());

        assertEquals(List.of("B", "C", "A"), names(result));
    }

    @Test
    @DisplayName("结果 sheet 的 order/status 按输出位置重编，第一张才是打开时显示的那张")
    void resultSheetsAreRenumberedByOutputPosition() {
        RenderResultDTO result = engine.render(content(), Map.of(), fetcher());

        assertEquals(List.of(0, 1, 2), result.getSheets().stream().map(s -> s.get("order")).toList());
        // 模板里 status=1 挂在数组第一张（A）上，但排在最前面的是 B
        assertEquals(List.of(1, 0, 0), result.getSheets().stream().map(s -> s.get("status")).toList());
    }

    @Test
    @DisplayName("cellConfigs 仍按数组下标寻址：绑定跟着它自己那张 sheet 走")
    void cellConfigsStillAddressedByArrayIndex() {
        RenderResultDTO result = engine.render(content(), Map.of(), fetcher());

        // 行带在 A 那张（数组下标 0）上，它排在最后一位
        assertEquals(List.of("A", "SO-1", "SO-2"), texts(result.getSheets().get(2)));
        assertEquals(List.of("B"), texts(result.getSheets().get(0)));
        assertEquals(List.of("C"), texts(result.getSheets().get(1)));
    }

    @Test
    @DisplayName("pageConfigs 同理：A3 是 A 那张的，跟着 A 排到最后一位")
    void pageConfigsFollowTheirSheet() {
        RenderResultDTO result = engine.render(content(), Map.of(), fetcher());

        // sheetPageConfigs 与结果 sheets 一一对应（导出与预览一律认它）
        assertEquals(List.of("A5", "A4", "A3"),
                result.getSheetPageConfigs().stream().map(PageConfigDTO::getPaperSize).toList());
        // 兜底那条路认的 mzTemplateIndex 仍是**数组下标**
        assertEquals(List.of(1, 2, 0),
                result.getSheets().stream().map(s -> s.get("mzTemplateIndex")).toList());
    }

    @Test
    @DisplayName("与按条拆分叠加：段按排好的顺序切，sheetSplits 照旧按数组下标认")
    void splitSegmentsFollowTheOrderedTemplates() {
        // 数组顺序「详情、清单」，用户把清单拖到了前面
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheet("详情", 0, 1, List.of(cd(0, 0, "#{orders.no}"))));
        sheets.add(sheet("清单", 1, 0, List.of(cd(0, 0, "订单清单"), cd(1, 0, "#{orders.no}"))));

        Map<String, CellConfigDTO> cellConfigs = new LinkedHashMap<>();
        cellConfigs.put("0_0_0", ordersCfg("none"));
        cellConfigs.put("1_1_0", ordersCfg("down"));

        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(sheets);
        content.setCellConfigs(cellConfigs);
        content.setPrimaryDataset("orders");
        content.setSplitMode("perRow");
        content.setSheetNameField("no");
        content.setPageConfig(page("A4"));
        // 「不跟着拆」标在**数组下标 1**（清单）上
        content.getSheetSplits().put("1", "once");

        RenderResultDTO result = engine.render(content, Map.of(), fetcher());

        // 清单排在前面，所以先出清单（一份、拿全量），再出两份详情
        assertEquals(List.of("清单", "SO-1", "SO-2"), names(result));
        assertEquals(List.of("订单清单", "SO-1", "SO-2"), texts(result.getSheets().get(0)));
    }

    @Test
    @DisplayName("order 恒等于数组下标的老报表一个字不变")
    void legacyReportsUnchanged() {
        List<Map<String, Object>> sheets = new ArrayList<>();
        sheets.add(sheet("A", 0, 0, List.of(cd(0, 0, "A"))));
        sheets.add(sheet("B", 1, 1, List.of(cd(0, 0, "B"))));
        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(sheets);
        content.setPageConfig(page("A4"));

        RenderResultDTO result = engine.render(content, Map.of(), fetcher());

        assertEquals(List.of("A", "B"), names(result));
        assertEquals(List.of(0, 1), result.getSheets().stream().map(s -> s.get("mzTemplateIndex")).toList());
    }
}
