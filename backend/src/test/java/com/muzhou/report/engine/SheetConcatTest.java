package com.muzhou.report.engine;

import com.muzhou.report.engine.model.SheetTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SheetConcat} 的纯 POJO 测试：直接手工构造 {@code renderPerRow} 的产物
 * （sheet map 的列表）喂进去，不启动 Spring、也不经过渲染引擎。
 *
 * <p>用的假数据是「一份三行的单据」：r0 标题（跨 2 列合并）、r1 数据、r2 合计（带原生公式）。
 */
class SheetConcatTest {

    /** 各模板打印设置都相同（最常见的情形）：全都能拼进同一张 sheet */
    private static final IntFunction<Object> SAME = t -> "A4-portrait";

    /** 构造一份 3 行的单据：合并、行高、边框、原生公式各一处，用来验证四类坐标都跟着偏移。 */
    private Map<String, Object> onePart(String title, int index) {
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", title);
        sheet.put("id", "tpl1_" + index);
        sheet.put("order", index);
        sheet.put("status", index == 0 ? 1 : 0);
        sheet.put("frozen", Map.of("type", "row"));

        List<Map<String, Object>> celldata = new ArrayList<>();
        celldata.add(cell(0, 0, mapOf("v", title, "m", title,
                "mc", mapOf("r", 0, "c", 0, "rs", 1, "cs", 2))));
        celldata.add(cell(0, 1, mapOf("mc", mapOf("r", 0, "c", 0))));
        celldata.add(cell(1, 0, mapOf("v", "商品", "m", "商品")));
        celldata.add(cell(1, 1, mapOf("v", 100, "m", "100")));
        celldata.add(cell(2, 1, mapOf("v", 100, "m", "100", "f", "=SUM(B2:B2)")));
        sheet.put("celldata", celldata);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("merge", mapOf("0_0", mapOf("r", 0, "c", 0, "rs", 1, "cs", 2)));
        config.put("rowlen", mapOf("0", 40));
        config.put("columnlen", mapOf("0", 120, "1", 80));
        config.put("borderInfo", List.of(mapOf(
                "rangeType", "range",
                "borderType", "border-all",
                "range", List.of(mapOf("row", List.of(0, 2), "column", List.of(0, 1))))));
        sheet.put("config", config);
        sheet.put("row", 60);
        sheet.put("column", 20);
        return sheet;
    }

    private Map<String, Object> cell(int r, int c, Map<String, Object> v) {
        return mapOf("r", r, "c", c, "v", v);
    }

    private Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private SheetTemplate template(int index, String name, String id) {
        SheetTemplate st = new SheetTemplate();
        st.setSheetIndex(index);
        st.setName(name);
        st.setId(id);
        st.setOrder(index);
        return st;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cellAt(Map<String, Object> sheet, int r, int c) {
        for (Object o : (List<Object>) sheet.get("celldata")) {
            Map<String, Object> cd = (Map<String, Object>) o;
            if (((Number) cd.get("r")).intValue() == r && ((Number) cd.get("c")).intValue() == c) {
                return (Map<String, Object>) cd.get("v");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(Map<String, Object> sheet, String key) {
        return (Map<String, Object>) ((Map<String, Object>) sheet.get("config")).get(key);
    }

    /** 在一份的 borderInfo 后面追一条 rangeType=cell 的逐格边框（复制粘贴时 FortuneSheet 写的那种） */
    private Map<String, Object> withCellBorder(Map<String, Object> part) {
        Map<String, Object> cfg = (Map<String, Object>) part.get("config");
        List<Object> borderInfo = new ArrayList<>((List<Object>) cfg.get("borderInfo"));
        borderInfo.add(mapOf("rangeType", "cell",
                "value", mapOf("row_index", 1, "col_index", 0,
                        "t", mapOf("style", "1", "color", "#FFFFFF"))));
        cfg.put("borderInfo", borderInfo);
        return part;
    }

    @Test
    @DisplayName("单模板 3 份：拼成一张，行号按每份高度累加偏移")
    void 单模板拼接() {
        List<Map<String, Object>> parts = List.of(onePart("单据A", 0), onePart("单据B", 1), onePart("单据C", 2));
        List<SheetTemplate> templates = List.of(template(0, "订单", "sheet_1"));

        List<Map<String, Object>> out = SheetConcat.concat(parts, templates, SAME);

        assertEquals(1, out.size(), "3 份拼成 1 张");
        Map<String, Object> sheet = out.get(0);
        // 每份 3 行（r0~r2），第 2 份从 3 行起、第 3 份从 6 行起
        assertEquals("单据A", cellAt(sheet, 0, 0).get("v"));
        assertEquals("单据B", cellAt(sheet, 3, 0).get("v"));
        assertEquals("单据C", cellAt(sheet, 6, 0).get("v"));
        assertEquals(15, ((List<?>) sheet.get("celldata")).size(), "3 份 × 5 个格子");
    }

    @Test
    @DisplayName("name / id / order / status 恢复成模板的那一份")
    void 恢复模板编号() {
        List<Map<String, Object>> out = SheetConcat.concat(
                List.of(onePart("单据A", 0), onePart("单据B", 1)),
                List.of(template(0, "订单", "sheet_1")), SAME);

        Map<String, Object> sheet = out.get(0);
        assertEquals("订单", sheet.get("name"), "拼完不该还叫「单据A」");
        assertEquals("sheet_1", sheet.get("id"));
        assertEquals(0, sheet.get("order"));
        assertEquals(1, sheet.get("status"));
        assertNotNull(sheet.get("frozen"), "raw 上不涉及坐标的属性照抄");
    }

    @Test
    @DisplayName("原生公式的行引用跟着偏移，第 2 份不再指向第 1 份")
    void 公式偏移() {
        List<Map<String, Object>> out = SheetConcat.concat(
                List.of(onePart("单据A", 0), onePart("单据B", 1)),
                List.of(template(0, "订单", "sheet_1")), SAME);

        Map<String, Object> sheet = out.get(0);
        assertEquals("=SUM(B2:B2)", cellAt(sheet, 2, 1).get("f"), "第 1 份原样");
        assertEquals("=SUM(B5:B5)", cellAt(sheet, 5, 1).get("f"), "第 2 份整体下移 3 行");
    }

    @Test
    @DisplayName("合并：config.merge 与格子上的 mc 两份都要偏移，缺一边画不出合并")
    void 合并偏移() {
        List<Map<String, Object>> out = SheetConcat.concat(
                List.of(onePart("单据A", 0), onePart("单据B", 1)),
                List.of(template(0, "订单", "sheet_1")), SAME);

        Map<String, Object> sheet = out.get(0);
        Map<String, Object> merge = config(sheet, "merge");
        assertEquals(2, merge.size());
        assertTrue(merge.containsKey("0_0"));
        assertTrue(merge.containsKey("3_0"), "第 2 份的合并要落在第 3 行");
        assertEquals(3, ((Map<?, ?>) merge.get("3_0")).get("r"));

        assertEquals(3, ((Map<?, ?>) cellAt(sheet, 3, 0).get("mc")).get("r"), "首格 mc");
        assertEquals(3, ((Map<?, ?>) cellAt(sheet, 3, 1).get("mc")).get("r"), "被盖住那格的 mc 指回首格");
    }

    @Test
    @DisplayName("行高 / 边框的行坐标跟着偏移，同一模板各份列宽相同不重复")
    void 行高边框列宽() {
        List<Map<String, Object>> out = SheetConcat.concat(
                List.of(onePart("单据A", 0), onePart("单据B", 1)),
                List.of(template(0, "订单", "sheet_1")), SAME);

        Map<String, Object> sheet = out.get(0);
        Map<String, Object> rowlen = config(sheet, "rowlen");
        assertEquals(2, rowlen.size());
        assertEquals(40, rowlen.get("0"));
        assertEquals(40, rowlen.get("3"), "第 2 份的标题行高落在第 3 行");

        assertEquals(2, config(sheet, "columnlen").size(), "列宽不该被拼成两份");

        List<?> borders = (List<?>) ((Map<?, ?>) sheet.get("config")).get("borderInfo");
        assertEquals(2, borders.size());
        List<?> range = (List<?>) ((Map<?, ?>) borders.get(1)).get("range");
        assertEquals(List.of(3, 5), ((Map<?, ?>) range.get(0)).get("row"), "第 2 份的边框区间整体下移 3 行");
    }

    @Test
    @DisplayName("rangeType=cell 的逐格边框也要偏移，否则各份的边框全堆在第一份上")
    void 逐格边框偏移() {
        List<Map<String, Object>> out = SheetConcat.concat(
                List.of(withCellBorder(onePart("单据A", 0)), withCellBorder(onePart("单据B", 1))),
                List.of(template(0, "订单", "sheet_1")), SAME);

        List<?> borders = (List<?>) ((Map<?, ?>) out.get(0).get("config")).get("borderInfo");
        // 第 1 份原样，第 2 份的那条整体下移 3 行
        Map<?, ?> first = (Map<?, ?>) ((Map<?, ?>) borders.get(1)).get("value");
        Map<?, ?> second = (Map<?, ?>) ((Map<?, ?>) borders.get(3)).get("value");
        assertEquals(1, first.get("row_index"));
        assertEquals(4, second.get("row_index"), "第 2 份的逐格边框该落在第 4 行");
        assertEquals("#FFFFFF", ((Map<?, ?>) second.get("t")).get("color"), "颜色要原样带着");
    }

    @Test
    @DisplayName("每份起始行落一个分页符，第一份前面没有")
    void 分页符() {
        List<Map<String, Object>> out = SheetConcat.concat(
                List.of(onePart("A", 0), onePart("B", 1), onePart("C", 2)),
                List.of(template(0, "订单", "sheet_1")), SAME);

        assertEquals(List.of(3, 6), out.get(0).get("mzRowBreaks"));
    }

    @Test
    @DisplayName("多模板：一条数据的几张模板挨在一起，拼进同一张 sheet")
    void 多模板按数据挨着排() {
        // 2 条数据 × 2 张模板，renderPerRow 给的顺序是 r0t0, r0t1, r1t0, r1t1
        Map<String, Object> r0t0 = onePart("明细A", 0);
        Map<String, Object> r0t1 = onePart("汇总A", 1);
        Map<String, Object> r1t0 = onePart("明细B", 2);
        Map<String, Object> r1t1 = onePart("汇总B", 3);
        List<SheetTemplate> templates = List.of(
                template(0, "明细", "sheet_1"), template(1, "汇总", "sheet_2"));

        List<Map<String, Object>> out = SheetConcat.concat(
                List.of(r0t0, r0t1, r1t0, r1t1), templates, SAME);

        assertEquals(1, out.size(), "跨模板挨着排只能在同一张 sheet 里做");
        Map<String, Object> sheet = out.get(0);
        assertEquals("明细", sheet.get("name"), "名字/编号取模板第 1 张");
        assertEquals("sheet_1", sheet.get("id"));
        assertEquals(1, sheet.get("status"));
        // 每份 3 行：数据1的明细/汇总，然后才是数据2的明细/汇总
        assertEquals("明细A", cellAt(sheet, 0, 0).get("v"));
        assertEquals("汇总A", cellAt(sheet, 3, 0).get("v"), "同一条数据的第 2 张模板紧接在下面");
        assertEquals("明细B", cellAt(sheet, 6, 0).get("v"), "第 2 条数据从这里开始");
        assertEquals("汇总B", cellAt(sheet, 9, 0).get("v"));
        assertEquals(List.of(3, 6, 9), sheet.get("mzRowBreaks"), "每一份各自一页");
    }

    /** 2 条数据 × 3 张模板，行优先：单1的表1/表2/表3、单2的表1/表2/表3 */
    private List<Map<String, Object>> 三模板两条() {
        return List.of(onePart("单1-表1", 0), onePart("单1-表2", 1), onePart("单1-表3", 2),
                onePart("单2-表1", 3), onePart("单2-表2", 4), onePart("单2-表3", 5));
    }

    private List<SheetTemplate> 三模板() {
        return List.of(template(0, "表1", "s1"), template(1, "表2", "s2"), template(2, "表3", "s3"));
    }

    @Test
    @DisplayName("打印设置不同的份不能拼在一起：横向那张必须自成一张 sheet")
    void 打印设置不同不拼() {
        // 第 2 张模板设成横向 —— 拼进纵向那张的话导出就全变纵向了（真出过这个 bug）
        IntFunction<Object> setup = t -> t == 1 ? "A4-landscape" : "A4-portrait";

        List<Map<String, Object>> out = SheetConcat.concat(三模板两条(), 三模板(), setup);

        // 纵-横-纵-纵-横-纵：横向那两份各自成张，中间「单1的表3 + 单2的表1」都是纵向、又挨着，
        // 照旧合成一张（打印出来的页序不变，只是少一个标签页）
        assertEquals(5, out.size());
        assertEquals(List.of(0, 1, 2, 1, 2),
                out.stream().map(s -> s.get("mzTemplateIndex")).toList(),
                "每张都记着自己出自哪张模板，打印设置照这个取");
        assertEquals(List.of("单1-表1", "单1-表2", "单1-表3", "单2-表2", "单2-表3"),
                out.stream().map(s -> s.get("name")).toList(),
                "断开时保留每组第一份自己的名字（renderPerRow 已保证不重名）");
        assertEquals("单1-表3", cellAt(out.get(2), 0, 0).get("v"));
        assertEquals("单2-表1", cellAt(out.get(2), 3, 0).get("v"), "同为纵向且相邻，跨数据也照拼");
        assertEquals(1, out.get(0).get("status"), "只有第一张是选中态");
        assertEquals(0, out.get(1).get("status"));
        assertEquals(List.of(0, 1, 2, 3, 4), out.stream().map(s -> s.get("order")).toList());
    }

    @Test
    @DisplayName("设置相同的相邻份照旧合并：表1+表2 拼一张，横向的表3 另起一张")
    void 设置相同的相邻份合并() {
        IntFunction<Object> setup = t -> t == 2 ? "A4-landscape" : "A4-portrait";

        List<Map<String, Object>> out = SheetConcat.concat(三模板两条(), 三模板(), setup);

        assertEquals(4, out.size(), "每条数据断成「表1+表2」「表3」两张");
        assertEquals("单1-表1", cellAt(out.get(0), 0, 0).get("v"));
        assertEquals("单1-表2", cellAt(out.get(0), 3, 0).get("v"), "同设置的相邻份摞在一起");
        assertEquals(List.of(3), out.get(0).get("mzRowBreaks"), "组内两份之间照样打分页符");
        assertEquals("单1-表3", cellAt(out.get(1), 0, 0).get("v"));
        assertEquals(List.of(0, 2, 0, 2), out.stream().map(s -> s.get("mzTemplateIndex")).toList());
    }

    @Test
    @DisplayName("多模板列宽逐列取最大：宽的那张模板不能被窄的截掉")
    void 多模板列宽取最大() {
        Map<String, Object> narrow = onePart("明细A", 0);
        Map<String, Object> wide = onePart("汇总A", 1);
        ((Map<String, Object>) wide.get("config")).put("columnlen", mapOf("0", 300, "2", 60));

        List<Map<String, Object>> out = SheetConcat.concat(List.of(narrow, wide),
                List.of(template(0, "明细", "sheet_1"), template(1, "汇总", "sheet_2")), SAME);

        Map<String, Object> columnlen = config(out.get(0), "columnlen");
        assertEquals(300, columnlen.get("0"), "两张模板都设了第 0 列，取宽的那个");
        assertEquals(80, columnlen.get("1"), "只有第 1 张设过");
        assertEquals(60, columnlen.get("2"), "只有第 2 张设过");
    }

    @Test
    @DisplayName("只有一份时是恒等变换 —— 主接口没数据那条早返回路径不必写特例")
    void 单份恒等() {
        List<Map<String, Object>> out = SheetConcat.concat(
                List.of(onePart("空模板", 0)), List.of(template(0, "订单", "sheet_1")), SAME);

        assertEquals(1, out.size());
        assertEquals(List.of(), out.get(0).get("mzRowBreaks"), "一份不需要分页符");
        assertEquals("=SUM(B2:B2)", cellAt(out.get(0), 2, 1).get("f"), "公式原样");
    }

    @Test
    @DisplayName("高度取 celldata / merge / rowlen / 边框四处最大：带边框的空尾行不能被吃掉")
    void 空尾行不被吃掉() {
        Map<String, Object> part = onePart("A", 0);
        // 边框画到第 5 行（下面两行有框没值），高度应是 6 而不是 3
        Map<String, Object> config = (Map<String, Object>) part.get("config");
        config.put("borderInfo", List.of(mapOf(
                "rangeType", "range",
                "range", List.of(mapOf("row", List.of(0, 5), "column", List.of(0, 1))))));

        assertEquals(6, SheetConcat.height(part));

        List<Map<String, Object>> out = SheetConcat.concat(
                List.of(part, onePart("B", 1)), List.of(template(0, "订单", "sheet_1")), SAME);
        assertEquals("B", cellAt(out.get(0), 6, 0).get("v"), "第 2 份要从第 6 行起，不能压在空尾行上");
        assertEquals(List.of(6), out.get(0).get("mzRowBreaks"));
    }

    @Test
    @DisplayName("mzDocBreaks 只标新单据的起点，是 mzRowBreaks 的子集")
    void 单据起点是分页符的子集() {
        // 2 条数据 × 2 张模板：4 份各占一页，但只有第 1、3 份是新单据的开头
        SheetConcat.Concated out = SheetConcat.concat(
                List.of(onePart("单1-表1", 0), onePart("单1-表2", 1),
                        onePart("单2-表1", 2), onePart("单2-表2", 3)),
                List.of(template(0, "表1", "s1"), template(1, "表2", "s2"),
                        template(0, "表1", "s1"), template(1, "表2", "s2")),
                List.of("A4", "A4", "A4", "A4"),
                List.of(0, 0, 1, 1));

        Map<String, Object> sheet = out.sheets().get(0);
        assertEquals(List.of(3, 6, 9), sheet.get("mzRowBreaks"), "每一份各自一页");
        assertEquals(List.of(0, 6), sheet.get("mzDocBreaks"), "页码只在第 2 条数据那里重编");
    }

    @Test
    @DisplayName("打印设置断组时，组的第一份不一定是新单据 —— 按全局相邻两份比对")
    void 断组不等于新单据() {
        // 一条数据两张模板，第 2 张设成横向：断成两张 sheet，但仍是同一份单据
        SheetConcat.Concated out = SheetConcat.concat(
                List.of(onePart("表1", 0), onePart("表2", 1)),
                List.of(template(0, "表1", "s1"), template(1, "表2", "s2")),
                List.of("A4-portrait", "A4-landscape"),
                List.of(0, 0));

        assertEquals(2, out.sheets().size(), "打印设置不同不能拼在一起");
        assertEquals(List.of(0), out.sheets().get(0).get("mzDocBreaks"));
        assertEquals(List.of(), out.sheets().get(1).get("mzDocBreaks"), "同一份单据的后半不重编页码");
    }

    @Test
    @DisplayName("不传每份属于第几条数据时不标 mzDocBreaks（老调用方走整份连续编号）")
    void 不传数据下标就不标() {
        List<Map<String, Object>> out = SheetConcat.concat(
                List.of(onePart("A", 0), onePart("B", 1)), List.of(template(0, "订单", "sheet_1")), SAME);

        assertTrue(!out.get(0).containsKey("mzDocBreaks"));
        assertTrue(!out.get(0).containsKey("mzDocNames"));
    }

    @Test
    @DisplayName("mzDocNames 与 mzDocBreaks 一一对应：一条数据的第 2 张模板不另占一个名字")
    void 单据名与单据起点一一对应() {
        // 2 条数据 × 2 张模板，名字是按**数据行**给的，所以同一条数据的两份名字相同
        SheetConcat.Concated out = SheetConcat.concat(
                List.of(onePart("单1-表1", 0), onePart("单1-表2", 1),
                        onePart("单2-表1", 2), onePart("单2-表2", 3)),
                List.of(template(0, "表1", "s1"), template(1, "表2", "s2"),
                        template(0, "表1", "s1"), template(1, "表2", "s2")),
                List.of("A4", "A4", "A4", "A4"),
                List.of(0, 0, 1, 1),
                List.of("SO-001", "SO-001", "SO-002", "SO-002"));

        Map<String, Object> sheet = out.sheets().get(0);
        assertEquals(List.of(0, 6), sheet.get("mzDocBreaks"));
        assertEquals(List.of("SO-001", "SO-002"), sheet.get("mzDocNames"), "名字对着起始行，不是对着每一份");
    }

    @Test
    @DisplayName("断组时名字跟着各自那一组走，取不到名字的落成空串")
    void 断组后名字仍跟着单据() {
        SheetConcat.Concated out = SheetConcat.concat(
                List.of(onePart("表1", 0), onePart("表2", 1), onePart("表1", 2)),
                List.of(template(0, "表1", "s1"), template(1, "表2", "s2"), template(0, "表1", "s1")),
                List.of("A4-portrait", "A4-landscape", "A4-portrait"),
                List.of(0, 0, 1),
                Arrays.asList("SO-001", "SO-001", null));

        assertEquals(3, out.sheets().size());
        assertEquals(List.of("SO-001"), out.sheets().get(0).get("mzDocNames"));
        assertEquals(List.of(), out.sheets().get(1).get("mzDocNames"), "同一份单据的后半不是新单据");
        assertEquals(List.of(""), out.sheets().get(2).get("mzDocNames"), "没配字段的那份退回工作表名");
    }
}
