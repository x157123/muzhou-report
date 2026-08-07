package com.muzhou.report.engine;

import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.dto.DatasetLinkDTO;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 父子关联接到渲染引擎上之后的整体行为，见 docs/CONTRACT.md §7。
 *
 * <p>验的是「装饰器套在哪一层」这件事本身：普通渲染下子表是所有主表行的明细拼起来的一条行带，
 * {@code perRow} 下每张 sheet 只有当前这一行的明细（子表不能进那个跨行复用的缓存）。
 *
 * <p>纯 POJO：引擎、模板解析、扩展都手工 new，取数函数手写，不启动 Spring。
 *
 * <pre>
 * 模板 r0: 单号 | 物料            （表头）
 *      r1: #{items.order_no} | #{items.name}   （子表行带）
 * 主表 orders: (1,SO-001) (2,SO-002)
 * 子表 items : 1 -> 螺丝/螺母，2 -> 垫片
 * </pre>
 */
class RenderLinkFlowTest {

    private ReportRenderEngine engine;

    /** 每次取数记一笔 `code:orderId`，用来看子表被打了几次、每次带的什么参数。 */
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        FormulaEvaluator evaluator = new FormulaEvaluator();
        evaluator.init(); // 无 Spring 容器时手动触发 @PostConstruct
        engine = new ReportRenderEngine(new TemplateParser(),
                new ExpandProcessor(new CellFormatter(), evaluator, new MzProperties()),
                new MzProperties());
        calls.clear();
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private BiFunction<String, Map<String, Object>, List<Map<String, Object>>> fetcher() {
        return (code, params) -> {
            calls.add(code + ":" + params.get("orderId"));
            if ("orders".equals(code)) {
                return List.of(row("id", "1", "order_no", "SO-001"), row("id", "2", "order_no", "SO-002"));
            }
            if ("items".equals(code)) {
                return "1".equals(params.get("orderId"))
                        ? List.of(row("name", "螺丝"), row("name", "螺母"))
                        : List.of(row("name", "垫片"));
            }
            return List.of();
        };
    }

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

    private CellConfigDTO itemsCfg(String field) {
        CellConfigDTO cfg = new CellConfigDTO();
        cfg.setType("data");
        cfg.setDatasetCode("items");
        cfg.setField(field);
        cfg.setExpandType("down");
        cfg.setGroupType("list");
        cfg.setAggregate("none");
        cfg.setFormatType("text");
        return cfg;
    }

    private ReportContentDTO content() {
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "Sheet1");
        sheet.put("id", "sheet_1");
        sheet.put("order", 0);
        sheet.put("status", 1);
        sheet.put("row", 10);
        sheet.put("column", 5);
        sheet.put("celldata", List.of(
                cd(0, 0, "单号"), cd(0, 1, "物料"),
                cd(1, 0, ""), cd(1, 1, "")));
        sheet.put("config", new LinkedHashMap<>());

        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(List.of(sheet));
        Map<String, CellConfigDTO> cellConfigs = new LinkedHashMap<>();
        // 主表字段也从**子表**上取 —— 关联取数会把主表这一行合进它的每条子行
        cellConfigs.put("0_1_0", itemsCfg("order_no"));
        cellConfigs.put("0_1_1", itemsCfg("name"));
        content.setCellConfigs(cellConfigs);

        DatasetLinkDTO link = new DatasetLinkDTO();
        link.setName("订单明细");
        link.setMaster("orders");
        link.setChild("items");
        DatasetLinkDTO.Mapping m = new DatasetLinkDTO.Mapping();
        m.setParam("orderId");
        m.setField("id");
        link.setMappings(List.of(m));
        content.setDatasetLinks(List.of(link));
        return content;
    }

    /** 取输出 sheet 里 (r,c) 的显示文本。 */
    @SuppressWarnings("unchecked")
    private String text(Map<String, Object> sheet, int r, int c) {
        for (Map<String, Object> cell : (List<Map<String, Object>>) sheet.get("celldata")) {
            if ((int) cell.get("r") == r && (int) cell.get("c") == c) {
                Map<String, Object> v = (Map<String, Object>) cell.get("v");
                return String.valueOf(v.get("m"));
            }
        }
        return null;
    }

    @Test
    @DisplayName("普通渲染：子表行带是所有主表行的明细拼起来的，主表字段跟着子行走")
    void childBandFlattensAllMasterRows() {
        RenderResultDTO result = engine.render(content(), Map.of(), fetcher());

        assertEquals(1, result.getSheets().size());
        Map<String, Object> sheet = result.getSheets().get(0);
        assertEquals(List.of("螺丝", "螺母", "垫片"),
                List.of(text(sheet, 1, 1), text(sheet, 2, 1), text(sheet, 3, 1)));
        assertEquals(List.of("SO-001", "SO-001", "SO-002"),
                List.of(text(sheet, 1, 0), text(sheet, 2, 0), text(sheet, 3, 0)));
        // 主表一次、子表按主表行数两次
        assertEquals(List.of("orders:null", "items:1", "items:2"), calls);
    }

    @Test
    @DisplayName("perRow：每张 sheet 只有当前这一行的明细，子表不能跨 sheet 复用")
    void perRowFetchesChildForCurrentRowOnly() {
        ReportContentDTO content = content();
        content.setPrimaryDataset("orders");
        content.setSplitMode("perRow");
        content.setSheetNameField("order_no");

        RenderResultDTO result = engine.render(content, Map.of(), fetcher());

        assertEquals(2, result.getSheets().size());
        Map<String, Object> first = result.getSheets().get(0);
        assertEquals("SO-001", first.get("name"));
        assertEquals("螺丝", text(first, 1, 1));
        assertEquals("螺母", text(first, 2, 1));

        Map<String, Object> second = result.getSheets().get(1);
        assertEquals("SO-002", second.get("name"));
        assertEquals("垫片", text(second, 1, 1));
        // 第二张的明细不是第一张那份 —— 缓存串了的话这里会是「螺丝」
        assertTrue(calls.contains("items:2"));

        // 一条数据一份单据：两张都是新单据的开头，页头页尾的页码各数各的
        assertEquals(List.of(0), first.get("mzDocBreaks"));
        assertEquals(List.of(0), second.get("mzDocBreaks"));
    }

    @Test
    @DisplayName("普通输出（single）不标 mzDocBreaks —— 页码整份连续")
    void singleModeHasNoDocBreaks() {
        RenderResultDTO result = engine.render(content(), Map.of(), fetcher());

        assertTrue(!result.getSheets().get(0).containsKey("mzDocBreaks"));
    }

    @Test
    @DisplayName("perRowPage：拆法与 perRow 一样，只是拼回一张 sheet 并打上分页符")
    void perRowPageConcatsIntoOneSheet() {
        ReportContentDTO content = content();
        content.setPrimaryDataset("orders");
        content.setSplitMode("perRowPage");

        RenderResultDTO result = engine.render(content, Map.of(), fetcher());

        assertEquals(1, result.getSheets().size(), "两条数据拼回一张 sheet");
        Map<String, Object> sheet = result.getSheets().get(0);
        assertEquals("Sheet1", sheet.get("name"), "拼完用的是模板名，不是某一条数据的名字");

        // 第 1 份：表头 + 两条明细（r0~r2）；第 2 份整体下移 3 行
        assertEquals("单号", text(sheet, 0, 0));
        assertEquals(List.of("螺丝", "螺母"), List.of(text(sheet, 1, 1), text(sheet, 2, 1)));
        assertEquals("单号", text(sheet, 3, 0), "第 2 份的表头");
        assertEquals("垫片", text(sheet, 4, 1));
        assertEquals("SO-002", text(sheet, 4, 0), "第 2 份喂的是第 2 行数据");

        // 分页符落在第 2 份的起始行 —— 这是「两条不挤一张纸」唯一的载体
        assertEquals(List.of(3), sheet.get("mzRowBreaks"));
        // 每份单据的起点：模板只有一张，这里与分页符一一对应，再加上第 0 行
        assertEquals(List.of(0, 3), sheet.get("mzDocBreaks"));
        // 子表照样按主表每一行各查一次，拼接不影响取数
        assertTrue(calls.contains("items:1") && calls.contains("items:2"));
        // 没配 sheetNameField 时退回「第 n 条」
        assertEquals(List.of("第1条", "第2条"), sheet.get("mzDocNames"));
    }

    @Test
    @DisplayName("perRowPage：单据名取的是 sheetNameField 那个字段，与 perRow 同一项配置")
    void perRowPageNamesEachDocumentByField() {
        ReportContentDTO content = content();
        content.setPrimaryDataset("orders");
        content.setSplitMode("perRowPage");
        content.setSheetNameField("order_no");

        RenderResultDTO result = engine.render(content, Map.of(), fetcher());

        Map<String, Object> sheet = result.getSheets().get(0);
        assertEquals("Sheet1", sheet.get("name"), "拼完的 sheet 仍是模板名");
        // 页头页尾里的 ${sheet} 认这一项：每张纸印自己那一份的单号
        assertEquals(List.of(0, 3), sheet.get("mzDocBreaks"));
        assertEquals(List.of("SO-001", "SO-002"), sheet.get("mzDocNames"));
    }
}
