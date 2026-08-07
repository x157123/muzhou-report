package com.muzhou.report.engine;

import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.dto.ReportContentDTO;
import com.muzhou.report.engine.model.RenderGrid;
import com.muzhou.report.engine.model.SheetTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染引擎核心算法测试：{@link TemplateParser} + {@link ExpandProcessor}。
 *
 * <p>纯 POJO 测试，不启动 Spring；取数结果直接手工构造 {@code Map<String, List<Map<String,Object>>>}
 * 传给 {@link ExpandProcessor#process}，绕开对 DatasetService/数据库的依赖。
 *
 * <p>测试用模板（对应 docs/CONTRACT.md §7 的算法）：
 * <pre>
 * r0: 部门 | 姓名 | 金额                      （表头，纯文本）
 * r1: #{emp.dept}(group) | #{emp.name} | #{emp.amount}   （数据行带，纵向扩展）
 * r2: 合计 | (空)         | sum(#{emp.amount})            （合计行，随行带下移）
 * </pre>
 * 数据集 emp 有 3 行：(A,张三,100) (A,李四,200) (B,王五,300)。
 */
class RenderEngineTest {

    private TemplateParser templateParser;
    private ExpandProcessor expandProcessor;

    @BeforeEach
    void setUp() {
        templateParser = new TemplateParser();
        CellFormatter formatter = new CellFormatter();
        FormulaEvaluator evaluator = new FormulaEvaluator();
        evaluator.init(); // 无 Spring 容器时需要手动触发 @PostConstruct 逻辑
        expandProcessor = new ExpandProcessor(formatter, evaluator, new MzProperties());
    }

    /** 构造一个 FortuneSheet celldata 条目 {r,c,v:{v,m}}。 */
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

    private CellConfigDTO dataCfg(String field, String expandType, String groupType,
                                  String aggregate, String formatType, String formatPattern) {
        CellConfigDTO cfg = new CellConfigDTO();
        cfg.setType("data");
        cfg.setDatasetCode("emp");
        cfg.setField(field);
        cfg.setExpandType(expandType);
        cfg.setGroupType(groupType);
        cfg.setAggregate(aggregate);
        cfg.setFormatType(formatType);
        cfg.setFormatPattern(formatPattern);
        return cfg;
    }

    private Map<String, Object> empRow(String dept, String name, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dept", dept);
        row.put("name", name);
        row.put("amount", new BigDecimal(amount));
        return row;
    }

    /** 构造一条 config.merge 记录 {r,c,rs,cs}。 */
    private Map<String, Object> mc(int r, int c, int rs, int cs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("r", r);
        m.put("c", c);
        m.put("rs", rs);
        m.put("cs", cs);
        return m;
    }

    /** 构造上面文档注释里描述的模板。 */
    private ReportContentDTO buildContent() {
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "Sheet1");
        sheet.put("id", "sheet_1");
        sheet.put("order", 0);
        sheet.put("status", 1);
        sheet.put("row", 10);
        sheet.put("column", 5);
        sheet.put("celldata", List.of(
                cd(0, 0, "部门"), cd(0, 1, "姓名"), cd(0, 2, "金额"),
                cd(1, 0, ""), cd(1, 1, ""), cd(1, 2, ""),
                cd(2, 0, "合计"), cd(2, 2, "")
        ));
        sheet.put("config", new LinkedHashMap<>());

        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(List.of(sheet));
        Map<String, CellConfigDTO> cellConfigs = new LinkedHashMap<>();
        cellConfigs.put("0_1_0", dataCfg("dept", "down", "group", "none", "text", ""));
        cellConfigs.put("0_1_1", dataCfg("name", "down", "list", "none", "text", ""));
        cellConfigs.put("0_1_2", dataCfg("amount", "down", "list", "none", "number", "#,##0"));
        cellConfigs.put("0_2_2", dataCfg("amount", "none", "list", "sum", "number", "#,##0"));
        content.setCellConfigs(cellConfigs);
        return content;
    }

    private RenderGrid render(List<Map<String, Object>> empRows) {
        return render(empRows, null);
    }

    /** @param merge 模板的 config.merge（key = "r_c"），不需要时传 null */
    @SuppressWarnings("unchecked")
    private RenderGrid render(List<Map<String, Object>> empRows, Map<String, Object> merge) {
        ReportContentDTO content = buildContent();
        if (merge != null) {
            Map<String, Object> sheet = content.getSheets().get(0);
            ((Map<String, Object>) sheet.get("config")).put("merge", merge);
        }
        List<SheetTemplate> templates = templateParser.parse(content);
        assertEquals(1, templates.size());
        return expandProcessor.process(templates.get(0), Map.of("emp", empRows), Map.of());
    }

    @Test
    @DisplayName("3 行数据纵向扩展：每行数据落在独立输出行，字段值正确填充")
    void verticalExpandThreeRows() {
        List<Map<String, Object>> empRows = List.of(
                empRow("A", "张三", "100"),
                empRow("A", "李四", "200"),
                empRow("B", "王五", "300"));
        RenderGrid grid = render(empRows);

        assertEquals("张三", grid.get(1, 1).getValue());
        assertEquals("李四", grid.get(2, 1).getValue());
        assertEquals("王五", grid.get(3, 1).getValue());

        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) grid.get(1, 2).getValue()));
        assertEquals(0, new BigDecimal("200").compareTo((BigDecimal) grid.get(2, 2).getValue()));
        assertEquals(0, new BigDecimal("300").compareTo((BigDecimal) grid.get(3, 2).getValue()));
        assertEquals("100", grid.get(1, 2).getDisplay());

        // 模板行 1（数据行带）展开成了输出行 1、2、3
        assertEquals(List.of(1, 2, 3), grid.getRowMapping().get(1));
    }

    @Test
    @DisplayName("扩展后下方模板行的输出行号按实际展开行数偏移")
    void rowsBelowBandAreShifted() {
        List<Map<String, Object>> empRows = List.of(
                empRow("A", "张三", "100"),
                empRow("A", "李四", "200"),
                empRow("B", "王五", "300"));
        RenderGrid grid = render(empRows);

        // 表头（模板行 0）不参与扩展，原样落在输出行 0
        assertEquals(List.of(0), grid.getRowMapping().get(0));
        // 合计行模板行号是 2，行带（模板行 1）展开成 3 行后，合计行整体下移到输出行 1+3=4
        assertEquals(List.of(4), grid.getRowMapping().get(2));
        assertEquals("合计", grid.get(4, 0).getDisplay());
    }

    @Test
    @DisplayName("aggregate=sum 的单元格对整个数据集聚合，不随行带展开")
    void sumAggregateComputedOnce() {
        List<Map<String, Object>> empRows = List.of(
                empRow("A", "张三", "100"),
                empRow("A", "李四", "200"),
                empRow("B", "王五", "300"));
        RenderGrid grid = render(empRows);

        // 100 + 200 + 300 = 600，落在合计行（输出行 4）的金额列
        assertEquals(0, new BigDecimal("600").compareTo((BigDecimal) grid.get(4, 2).getValue()));
        assertEquals("600", grid.get(4, 2).getDisplay());
    }

    @Test
    @DisplayName("groupType=group 的列，纵向相邻且值相等的单元格合并成一个 merge")
    void groupColumnsAreMerged() {
        List<Map<String, Object>> empRows = List.of(
                empRow("A", "张三", "100"),
                empRow("A", "李四", "200"),
                empRow("B", "王五", "300"));
        RenderGrid grid = render(empRows);

        // 部门列：张三(A)/李四(A) 相邻同值 -> 合并成 rs=2 的一格；王五(B) 单独一行，不产生 merge
        Map<String, Object> merge = grid.getMerge().get("1_0");
        assertEquals(1, merge.get("r"));
        assertEquals(0, merge.get("c"));
        assertEquals(2, merge.get("rs"));
        assertEquals(1, merge.get("cs"));
        assertFalse(grid.getMerge().containsKey("3_0"));

        // 合并区间内，只有首行保留显示值，其余置空，避免 Excel/前端重复显示文字
        assertEquals("A", grid.get(1, 0).getValue());
        assertNull(grid.get(2, 0).getValue());
        assertEquals("B", grid.get(3, 0).getValue());
    }

    @Test
    @DisplayName("数据集为空时行带退化为 1 行空白，不抛异常")
    void emptyDatasetProducesOneBlankRow() {
        RenderGrid grid = render(List.of());

        assertEquals(List.of(1), grid.getRowMapping().get(1));
        assertTrue(grid.get(1, 1) == null || grid.get(1, 1).getValue() == null);
        // 合计行紧跟在唯一一行空白数据行之后，行号只偏移 1（n=1 时偏移量为 0，但行本身仍占 1 行）
        assertEquals(List.of(2), grid.getRowMapping().get(2));
    }

    @Test
    @DisplayName("行带内的横向合并：展开成 n 行时复制 n 份，而不是纵向拉长成一个大合并")
    void mergeInsideBandIsRepeatedPerRow() {
        List<Map<String, Object>> empRows = List.of(
                empRow("A", "张三", "100"),
                empRow("A", "李四", "200"),
                empRow("B", "王五", "300"));
        // 模板行 1（数据行带）上把姓名列和金额列合成一格
        RenderGrid grid = render(empRows, new LinkedHashMap<>(Map.of("1_1", mc(1, 1, 1, 2))));

        for (int r = 1; r <= 3; r++) {
            Map<String, Object> merge = grid.getMerge().get(r + "_1");
            assertEquals(r, merge.get("r"), "第 " + r + " 行应该有自己的合并");
            assertEquals(1, merge.get("rs"), "行带内的合并不该跨行");
            assertEquals(2, merge.get("cs"));
        }
        // 三条数据各自看得见（拉成一个大合并的话，2、3 行会被第一行盖住）
        assertEquals("张三", grid.get(1, 1).getValue());
        assertEquals("李四", grid.get(2, 1).getValue());
        assertEquals("王五", grid.get(3, 1).getValue());
    }

    @Test
    @DisplayName("跨模板行的合并（行带只是其中一行）仍然按展开后的行数拉长")
    void mergeSpanningBandIsStretched() {
        List<Map<String, Object>> empRows = List.of(
                empRow("A", "张三", "100"),
                empRow("A", "李四", "200"),
                empRow("B", "王五", "300"));
        // 模板行 0（表头）+ 行 1（行带）在部门列上合成一格
        RenderGrid grid = render(empRows, new LinkedHashMap<>(Map.of("0_0", mc(0, 0, 2, 1))));

        Map<String, Object> merge = grid.getMerge().get("0_0");
        assertEquals(0, merge.get("r"));
        // 表头 1 行 + 行带展开的 3 行 = 4 行
        assertEquals(4, merge.get("rs"));
        assertEquals(1, merge.get("cs"));
    }

    @Test
    @DisplayName("单元格的 mc 标记按渲染结果重建：首格带 rs/cs，被盖住的格子指回首格")
    void mergeMarkersRebuiltFromResult() {
        List<Map<String, Object>> empRows = List.of(
                empRow("A", "张三", "100"),
                empRow("A", "李四", "200"),
                empRow("B", "王五", "300"));
        RenderGrid grid = render(empRows, new LinkedHashMap<>(Map.of("1_1", mc(1, 1, 1, 2))));

        // 第二条数据那一行：首格 mc 指向自己（输出行 2），而不是模板行 1
        assertEquals(Map.of("r", 2, "c", 1, "rs", 1, "cs", 2), grid.get(2, 1).getStyle().get("mc"));
        assertEquals(Map.of("r", 2, "c", 1), grid.get(2, 2).getStyle().get("mc"));
    }

    @Test
    @DisplayName("分组合并也会写 mc 标记（模板里没有，前端缺了它画不出合并）")
    void groupMergeAlsoGetsMarkers() {
        List<Map<String, Object>> empRows = List.of(
                empRow("A", "张三", "100"),
                empRow("A", "李四", "200"),
                empRow("B", "王五", "300"));
        RenderGrid grid = render(empRows);

        assertEquals(Map.of("r", 1, "c", 0, "rs", 2, "cs", 1), grid.get(1, 0).getStyle().get("mc"));
        assertEquals(Map.of("r", 1, "c", 0), grid.get(2, 0).getStyle().get("mc"));
    }

    /* ---------------- 数据格的前后缀（`#{emp.amount}元`，见 CONTRACT §5） ---------------- */

    /**
     * 构造一张只有一行的模板，格子文本原样用传进来的那串（含占位符与手写的前后缀）。
     *
     * @param cellConfigs 显式配置，key 是 "0_0_列号"；传 null 则由 TemplateParser 按文本推断
     */
    private RenderGrid renderRow(List<Map<String, Object>> empRows,
                                 Map<String, CellConfigDTO> cellConfigs, String... texts) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (int c = 0; c < texts.length; c++) {
            cells.add(cd(0, c, texts[c]));
        }
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "Sheet1");
        sheet.put("id", "sheet_1");
        sheet.put("celldata", cells);
        sheet.put("config", new LinkedHashMap<>());

        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(List.of(sheet));
        content.setCellConfigs(cellConfigs);
        List<SheetTemplate> templates = templateParser.parse(content);
        return expandProcessor.process(templates.get(0), Map.of("emp", empRows), Map.of("unit", "元"));
    }

    @Test
    @DisplayName("数据格文本里占位符后面手写的字保留下来，值按格式化后填回占位符的位置")
    void suffixAfterDataTokenIsKept() {
        Map<String, CellConfigDTO> cfgs = new LinkedHashMap<>();
        cfgs.put("0_0_0", dataCfg("amount", "down", "list", "none", "number", "#,##0.00"));
        RenderGrid grid = renderRow(List.of(empRow("A", "张三", "1234"), empRow("A", "李四", "20")),
                cfgs, "#{emp.amount}元");

        assertEquals("1,234.00元", grid.get(0, 0).getDisplay());
        assertEquals("1,234.00元", grid.get(0, 0).getValue());
        assertEquals("20.00元", grid.get(1, 0).getDisplay());
        // 整格已经是一段文本，ct 必须退回常规，否则 Excel 那头会照 ct.t=n 去写数值，「元」又没了
        assertEquals("g", grid.get(0, 0).getCt().get("t"));
    }

    @Test
    @DisplayName("整格只有一个占位符时值保持原始类型（Excel 里还要当数值参与计算）")
    void loneDataTokenKeepsRawType() {
        Map<String, CellConfigDTO> cfgs = new LinkedHashMap<>();
        cfgs.put("0_0_0", dataCfg("amount", "down", "list", "none", "number", "#,##0.00"));
        RenderGrid grid = renderRow(List.of(empRow("A", "张三", "1234")), cfgs, "#{emp.amount}");

        assertEquals(0, new BigDecimal("1234").compareTo((BigDecimal) grid.get(0, 0).getValue()));
        assertEquals("1,234.00", grid.get(0, 0).getDisplay());
        assertEquals("n", grid.get(0, 0).getCt().get("t"));
    }

    @Test
    @DisplayName("前缀里的 ${param} 与聚合值一起工作，取不到值时整格留空而不是只剩「元」")
    void prefixAndAggregateAndNullValue() {
        Map<String, CellConfigDTO> cfgs = new LinkedHashMap<>();
        cfgs.put("0_0_0", dataCfg("amount", "none", "list", "sum", "number", "#,##0"));
        RenderGrid grid = renderRow(List.of(empRow("A", "张三", "100"), empRow("A", "李四", "200")),
                cfgs, "合计：#{emp.amount}${unit}");
        assertEquals("合计：300元", grid.get(0, 0).getDisplay());

        // 字段没数据：整格空着
        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("amount", null);
        Map<String, CellConfigDTO> down = new LinkedHashMap<>();
        down.put("0_0_0", dataCfg("amount", "down", "list", "none", "number", "#,##0"));
        RenderGrid empty = renderRow(List.of(blank), down, "#{emp.amount}元");
        assertNull(empty.get(0, 0).getValue());
        assertEquals("", empty.get(0, 0).getDisplay());
    }

    @Test
    @DisplayName("没有显式配置、直接在格子里手写占位符加后缀，同样保留后缀")
    void inferredConfigAlsoKeepsSuffix() {
        RenderGrid grid = renderRow(List.of(empRow("A", "张三", "100"), empRow("B", "王五", "300")),
                null, "#{emp.name}（#{emp.dept}）");

        // 配置里绑的是 name，括号里那个 dept 占位符按普通文本替换
        assertEquals("张三（A）", grid.get(0, 0).getDisplay());
        assertEquals("王五（B）", grid.get(1, 0).getDisplay());
    }

    /* ------------------------- 图片单元格（img / base64） ------------------------- */

    /** 图片格的配置：取数部分与数据格一模一样，只有 type 不同。 */
    private CellConfigDTO imageCfg(String type, String field, String expandType) {
        CellConfigDTO cfg = new CellConfigDTO();
        cfg.setType(type);
        cfg.setDatasetCode("emp");
        cfg.setField(field);
        cfg.setExpandType(expandType);
        return cfg;
    }

    private Map<String, Object> picRow(String name, String pic) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("pic", pic);
        return row;
    }

    @Test
    @DisplayName("type=img：值当图片地址挂到 image 上，格子本身不出文字，并且照常纵向扩展")
    void imageCellExpandsAndCarriesUrl() {
        Map<String, CellConfigDTO> cfgs = new LinkedHashMap<>();
        cfgs.put("0_0_0", imageCfg("img", "pic", "down"));
        RenderGrid grid = renderRow(
                List.of(picRow("张三", "https://x/1.png"), picRow("李四", "https://x/2.png")),
                cfgs, "#{emp.pic}");

        // 图片格自己就构成一条行带：两行数据展开成两行
        assertEquals("https://x/1.png", grid.get(0, 0).getImage());
        assertEquals("https://x/2.png", grid.get(1, 0).getImage());
        // 文字必须是空的，否则导出的 Excel 里会写进这一串地址
        assertEquals("", grid.get(0, 0).getDisplay());
        assertEquals("", grid.get(0, 0).getValue());
    }

    @Test
    @DisplayName("type=base64：裸 base64 补成 data URI，MIME 按文件头判，已经是 data URI 的原样留着")
    void base64CellIsNormalizedToDataUri() {
        Map<String, CellConfigDTO> cfgs = new LinkedHashMap<>();
        cfgs.put("0_0_0", imageCfg("base64", "pic", "none"));

        // PNG 文件头 89 50 4E 47 -> base64 以 iVBORw0KGgo 开头
        RenderGrid png = renderRow(List.of(picRow("张三", "iVBORw0KGgoAAAA")), cfgs, "#{emp.pic}");
        assertEquals("data:image/png;base64,iVBORw0KGgoAAAA", png.get(0, 0).getImage());

        // JPEG 头 FF D8 -> /9j/
        RenderGrid jpg = renderRow(List.of(picRow("张三", "/9j/4AAQSkZJRg")), cfgs, "#{emp.pic}");
        assertEquals("data:image/jpeg;base64,/9j/4AAQSkZJRg", jpg.get(0, 0).getImage());

        // 接口已经给了完整 data URI 时不要再包一层
        RenderGrid uri = renderRow(List.of(picRow("张三", "data:image/gif;base64,R0lGOD")), cfgs, "#{emp.pic}");
        assertEquals("data:image/gif;base64,R0lGOD", uri.get(0, 0).getImage());

        // 值为空的格子不出图片，也不该留下一个空 data URI
        RenderGrid blank = renderRow(List.of(picRow("张三", "")), cfgs, "#{emp.pic}");
        assertNull(blank.get(0, 0).getImage());
    }

    @Test
    @DisplayName("图片地址输出到 celldata 的 v.mzImg.src —— 前端与导出都只认这一个载体")
    void imageIsCarriedByMzImg() {
        Map<String, CellConfigDTO> cfgs = new LinkedHashMap<>();
        cfgs.put("0_0_0", imageCfg("img", "pic", "none"));
        RenderGrid grid = renderRow(List.of(picRow("张三", "https://x/1.png")), cfgs, "#{emp.pic}");

        Object mzImg = grid.get(0, 0).toCellValue().get("mzImg");
        assertEquals("https://x/1.png", ((Map<?, ?>) mzImg).get("src"));
        // 普通文本格不该多出这个键
        RenderGrid text = renderRow(List.of(picRow("张三", "https://x/1.png")), null, "普通文本");
        assertFalse(text.get(0, 0).toCellValue().containsKey("mzImg"));
    }

    @Test
    @DisplayName("边框跟着行带偏移：区域拉长，rangeType=cell 的逐格边框复制成每行一条")
    @SuppressWarnings("unchecked")
    void bordersFollowTheExpandedBand() {
        ReportContentDTO content = buildContent();
        Map<String, Object> config = (Map<String, Object>) content.getSheets().get(0).get("config");
        config.put("borderInfo", List.of(
                // 行带那一行的区域边框：3 行数据之后该从 [1,1] 拉成 [1,3]
                Map.of("rangeType", "range", "borderType", "border-all",
                        "style", "1", "color", "#FFFFFF",
                        "range", List.of(Map.of("row", List.of(1, 1), "column", List.of(0, 2)))),
                // 逐格边框：只挪不复制的话，行带里只有第一行有边框
                Map.of("rangeType", "cell",
                        "value", Map.of("row_index", 1, "col_index", 0,
                                "t", Map.of("style", "1", "color", "#FFFFFF")))));

        List<SheetTemplate> templates = templateParser.parse(content);
        RenderGrid grid = expandProcessor.process(templates.get(0),
                Map.of("emp", List.of(empRow("A", "张三", "100"),
                        empRow("A", "李四", "200"),
                        empRow("B", "王五", "300"))),
                Map.of());

        List<Object> borders = grid.getBorderInfo();
        assertEquals(4, borders.size(), "1 条区域 + 3 行逐格");

        Map<String, Object> range = (Map<String, Object>) borders.get(0);
        List<?> ranges = (List<?>) range.get("range");
        assertEquals(List.of(1, 3), ((Map<?, ?>) ranges.get(0)).get("row"), "行带上的区域该拉到第 3 行");
        assertEquals("#FFFFFF", range.get("color"), "颜色要原样带着，否则导出时退回黑色");

        for (int i = 0; i < 3; i++) {
            Map<?, ?> value = (Map<?, ?>) ((Map<String, Object>) borders.get(1 + i)).get("value");
            assertEquals(1 + i, value.get("row_index"), "第 " + i + " 条数据的逐格边框落错行");
            assertEquals("#FFFFFF", ((Map<?, ?>) value.get("t")).get("color"));
        }
    }
}
