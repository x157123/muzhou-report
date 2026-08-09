package com.muzhou.report.export;

import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.HeaderFooterDTO;
import com.muzhou.report.dto.PageConfigDTO;
import com.muzhou.report.dto.ReportContentDTO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExcelImporter 的单元测试（纯 POJO，不启动 Spring）。
 *
 * <p>主力是**往返测试**：报表版式 -> {@link ExcelExporter} 出 xlsx -> {@link ExcelImporter}
 * 读回来，逐项比对。导入器是导出器的逆映射，只有这么锁着，改了一边忘了另一边才会当场红。
 *
 * <p>两处已知的不对称在下面各有一个用例说明，别当成 bug 去「修」：
 * ① {@code fitToWidth}（正向有个 limitWidth 兜底，反读分不清）；
 * ② 数值/日期一律落成文字（这是导入的既定取舍，见 ExcelImporter 类注释）。
 */
class ExcelImporterTest {

    private final ExcelExporter exporter = new ExcelExporter(new MzProperties());

    /* ------------------------------ 往返：版式 ------------------------------ */

    @Test
    @DisplayName("往返：文字、字体、底色、对齐、换行、合并、行高列宽一项不丢")
    void roundTripKeepsLayout() {
        Map<String, Object> title = cell(0, 0, "订单明细",
                Map.of("bl", 1, "fs", 16, "ht", 0, "bg", "#FFFF00", "fc", "#FF0000"));
        Map<String, Object> merge = new LinkedHashMap<>();
        merge.put("0_0", Map.of("r", 0, "c", 0, "rs", 1, "cs", 3));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("merge", merge);
        config.put("rowlen", Map.of("0", 40));
        config.put("columnlen", Map.of("0", 110, "1", 80));

        byte[] xlsx = exporter.export(List.of(sheet("订单", config, List.of(
                title,
                cell(1, 0, "编号", Map.of("ht", 1)),
                cell(1, 1, "名称", Map.of("tb", "2")),
                cell(2, 0, "A-001", Map.of())))), pageConfig());

        ReportContentDTO content = ExcelImporter.parse(xlsx).content();
        assertEquals(1, content.getSheets().size());
        assertEquals("订单", content.getSheets().get(0).get("name"));

        Map<String, Object> v = valueAt(content, 0, 0, 0);
        assertEquals("订单明细", v.get("v"));
        assertEquals(1, v.get("bl"));
        assertEquals(16, v.get("fs"));
        assertEquals(0, v.get("ht"));
        assertEquals("#FFFF00", v.get("bg"));
        assertEquals("#FF0000", v.get("fc"));
        assertEquals("2", valueAt(content, 0, 1, 1).get("tb"), "自动换行要带回来");
        assertEquals(1, valueAt(content, 0, 1, 0).get("ht"), "左对齐要带回来");

        Map<String, Object> back = mapOf(config(content, 0), "merge");
        Map<String, Object> region = mapOf(back, "0_0");
        assertNotNull(region, "合并区没带回来");
        assertEquals(1, region.get("rs"));
        assertEquals(3, region.get("cs"));

        assertEquals(40, (int) num(mapOf(config(content, 0), "rowlen").get("0")), 1,
                "行高按 px 原样带回来");
        Map<String, Object> columnlen = mapOf(config(content, 0), "columnlen");
        assertEquals(110, num(columnlen.get("0")), 1, "列宽换算误差不超过 1px");
        assertEquals(80, num(columnlen.get("1")), 1);
    }

    @Test
    @DisplayName("合并区里被盖住的格子只留指回首格的 mc，文字要清掉")
    void coveredCellsKeepOnlyMergeMarker() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("merge", Map.of("0_0", Map.of("r", 0, "c", 0, "rs", 2, "cs", 2)));

        byte[] xlsx = exporter.export(
                List.of(sheet("合并", config, List.of(cell(0, 0, "标题", Map.of())))), pageConfig());
        ReportContentDTO content = ExcelImporter.parse(xlsx).content();

        Map<String, Object> head = valueAt(content, 0, 0, 0);
        assertEquals("标题", head.get("v"));
        assertNotNull(head.get("mc"), "首格要带 mc");

        for (int[] rc : new int[][]{{0, 1}, {1, 0}, {1, 1}}) {
            Map<String, Object> covered = valueAt(content, 0, rc[0], rc[1]);
            Map<String, Object> mc = mapOf(covered, "mc");
            assertNotNull(mc, "被盖住的格子要有 mc");
            assertEquals(0, mc.get("r"));
            assertEquals(0, mc.get("c"));
            assertNull(covered.get("v"), "被盖住的格子不该留着文字，否则渲染时会再输出一遍");
        }
    }

    /* ------------------------------ 往返：边框 ------------------------------ */

    @Test
    @DisplayName("往返：边框的线型和颜色都照搬——白色边框不能读回成黑色")
    void roundTripKeepsBorderStyleAndColor() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("columnlen", Map.of("0", 100, "1", 100));
        config.put("borderInfo", List.of(
                borderRange("border-all", "8", "#FFFFFF", 0, 0, 0, 0),
                borderRange("border-all", "3", "#FF0000", 1, 1, 0, 0)));

        byte[] xlsx = exporter.export(List.of(sheet("边框", config, List.of(
                cell(0, 0, "白边", Map.of()),
                cell(1, 0, "红点线", Map.of())))), pageConfig());

        ReportContentDTO content = ExcelImporter.parse(xlsx).content();
        Map<String, Object> white = side(content, 0, 0, 0, "t");
        assertEquals(8, white.get("style"), "中粗线的线型码是 8");
        assertEquals("#FFFFFF", white.get("color"), "白色边框读回来还得是白色");

        Map<String, Object> red = side(content, 0, 1, 0, "b");
        assertEquals(3, red.get("style"), "点线的线型码是 3");
        assertEquals("#FF0000", red.get("color"));
    }

    @Test
    @DisplayName("没画边框的格子不生成 borderInfo 条目")
    void plainCellsHaveNoBorderEntry() {
        byte[] xlsx = exporter.export(
                List.of(sheet("素表", new LinkedHashMap<>(), List.of(cell(0, 0, "甲", Map.of())))),
                pageConfig());

        ReportContentDTO content = ExcelImporter.parse(xlsx).content();
        assertTrue(borderInfo(content, 0).isEmpty(), "没边框就不该有条目，白占 content 体积");
    }

    /* ------------------------------ 往返：页面设置 ------------------------------ */

    @Test
    @DisplayName("往返：纸张、方向、页边距、缩放、打印区域、顶端标题行、页头页尾")
    void roundTripKeepsPageSetup() {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setPaperSize("A3");
        cfg.setOrientation("landscape");
        cfg.setMarginTop(20);
        cfg.setMarginBottom(15);
        cfg.setMarginLeft(12);
        cfg.setMarginRight(12);
        cfg.setScale(80);
        cfg.setLimitWidth(false);       // 否则正向会按「一页宽」兜底，见 fitToWidth 那个用例
        cfg.setPrintArea("A1:C10");
        cfg.setTitleRows("1:2");
        HeaderFooterDTO header = new HeaderFooterDTO();
        // & 必须转义成 && 才不会被当成分段标记，${page} 必须原样还回来而不是展开成数字
        header.setCenter("A&C公司 第${page}页/共${pages}页");
        header.setFontSize(12);
        header.setMargin(6);
        cfg.setHeader(header);

        List<Map<String, Object>> cells = new ArrayList<>();
        for (int r = 0; r < 6; r++) {
            cells.add(cell(r, 0, "第" + (r + 1) + "行", Map.of()));
        }
        byte[] xlsx = exporter.export(List.of(sheet("报表", new LinkedHashMap<>(), cells)), cfg);

        PageConfigDTO back = ExcelImporter.parse(xlsx).content().getPageConfig();
        assertEquals("A3", back.getPaperSize());
        assertEquals("landscape", back.getOrientation());
        assertEquals(20, back.getMarginTop());
        assertEquals(15, back.getMarginBottom());
        assertEquals(12, back.getMarginLeft());
        assertEquals(80, back.getScale());
        assertFalse(back.getFitToWidth());
        assertEquals("A1:C10", back.getPrintArea());
        assertEquals("1:2", back.getTitleRows());
        assertEquals("A&C公司 第${page}页/共${pages}页", back.getHeader().getCenter(),
                "&& 要还原成 &，占位符不能被展开成当时的页码");
        assertEquals(12, back.getHeader().getFontSize());
        assertEquals(6, back.getHeader().getMargin());
    }

    @Test
    @DisplayName("没显式写过纸张的 xlsx 按 A4 导入，不能读成 OOXML 缺省的 Letter")
    void paperDefaultsToA4WhenAbsent() {
        byte[] xlsx = build(sheet -> sheet.createRow(0).createCell(0).setCellValue("甲"));

        assertEquals("A4", ExcelImporter.parse(xlsx).content().getPageConfig().getPaperSize());
    }

    @Test
    @DisplayName("fitToWidth 是不可逆的一项：正向的 limitWidth 兜底会被读回成「适应一页宽」")
    void fitToWidthIsNotReversible() {
        // 默认的 PageConfigDTO 是 limitWidth=true + scale=100，ExcelExporter 会写成 fitToPage
        byte[] xlsx = exporter.export(
                List.of(sheet("默认", new LinkedHashMap<>(), List.of(cell(0, 0, "甲", Map.of())))),
                new PageConfigDTO());

        PageConfigDTO back = ExcelImporter.parse(xlsx).content().getPageConfig();
        assertTrue(back.getFitToWidth(), "xlsx 里只剩 fitToPage 这一个事实，分不清是谁写的");
        assertEquals(100, back.getScale());
    }

    @Test
    @DisplayName("打印设置按 sheet 存：与第一张相同的不写 pageConfigs，不同的才单独记一份")
    void perSheetPageConfigOnlyWhenDifferent() {
        PageConfigDTO portrait = new PageConfigDTO();
        portrait.setLimitWidth(false);
        PageConfigDTO landscape = new PageConfigDTO();
        landscape.setLimitWidth(false);
        landscape.setPaperSize("A3");
        landscape.setOrientation("landscape");

        List<Map<String, Object>> sheets = List.of(
                sheet("一", new LinkedHashMap<>(), List.of(cell(0, 0, "甲", Map.of()))),
                sheet("二", new LinkedHashMap<>(), List.of(cell(0, 0, "乙", Map.of()))),
                sheet("三", new LinkedHashMap<>(), List.of(cell(0, 0, "丙", Map.of()))));

        ReportContentDTO same = ExcelImporter.parse(
                exporter.export(sheets, i -> portrait)).content();
        assertTrue(same.getPageConfigs().isEmpty(), "三张设置一样就该只留报表级那一份");

        ReportContentDTO mixed = ExcelImporter.parse(
                exporter.export(sheets, i -> i == 1 ? landscape : portrait)).content();
        assertEquals(1, mixed.getPageConfigs().size());
        assertEquals("A3", mixed.getPageConfigs().get("1").getPaperSize());
        assertEquals("portrait", mixed.getPageConfig().getOrientation(), "报表级取第一张");
    }

    /* ------------------------------ 值：数值变文本 ------------------------------ */

    @Test
    @DisplayName("数值和日期落成「Excel 里显示的那串文字」，并标成文本格")
    void numbersAndDatesBecomeDisplayText() {
        byte[] xlsx = build(sheet -> {
            XSSFWorkbook wb = sheet.getWorkbook();
            XSSFCellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            XSSFCellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));

            Cell amount = sheet.createRow(0).createCell(0);
            amount.setCellValue(1234.5);
            amount.setCellStyle(money);
            Cell day = sheet.getRow(0).createCell(1);
            day.setCellValue(LocalDate.of(2026, 8, 9));
            day.setCellStyle(date);
        });

        ReportContentDTO content = ExcelImporter.parse(xlsx).content();
        Map<String, Object> amount = valueAt(content, 0, 0, 0);
        // 存原始值 1234.5 的话，渲染时 CellFormatter 会把 ct 换成 General、出纸变成「1234.5」
        assertEquals("1,234.50", amount.get("v"));
        assertEquals("1,234.50", amount.get("m"));
        assertEquals("@", mapOf(amount, "ct").get("fa"), "标成文本格，免得又被识别回数字");
        assertEquals("s", mapOf(amount, "ct").get("t"));
        assertEquals("2026-08-09", valueAt(content, 0, 0, 1).get("v"),
                "日期不能落成序列号——CellFormatter#toDateTime 不认它");
    }

    @Test
    @DisplayName("原生公式原样带走，值那一格留着它算出来的数")
    void formulaIsKept() {
        byte[] xlsx = build(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue(2d);
            sheet.getRow(0).createCell(1).setCellFormula("A1*3");
            sheet.getRow(0).getCell(1).setCellValue(6d);
        });

        Map<String, Object> v = valueAt(ExcelImporter.parse(xlsx).content(), 0, 0, 1);
        assertEquals("=A1*3", v.get("f"));
        assertEquals("6", v.get("v"), "公式格走缓存值，不重算");
    }

    /* ------------------------------ 范围与失败 ------------------------------ */

    @Test
    @DisplayName("只做静态版式：一条数据绑定都不生成，绑定由用户在设计器里手工配")
    void bindingIsNeverGuessed() {
        byte[] xlsx = build(sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("#{orders.amount}");
            sheet.getRow(0).createCell(1).setCellValue("金额");
        });

        ReportContentDTO content = ExcelImporter.parse(xlsx).content();
        assertTrue(content.getCellConfigs().isEmpty(), "导入器不猜绑定，猜错了让人逐格去拆");
        assertEquals("#{orders.amount}", valueAt(content, 0, 0, 0).get("v"),
                "文字照搬——用户自己写的占位符仍然认，那是 TemplateParser 的事");
        assertNull(content.getPrimaryDataset());
    }

    @Test
    @DisplayName("隐藏的工作表跳过并报一条 warning")
    void hiddenSheetIsSkipped() {
        byte[] xlsx = build(wb -> {
            wb.createSheet("正文").createRow(0).createCell(0).setCellValue("甲");
            wb.createSheet("备用").createRow(0).createCell(0).setCellValue("乙");
            wb.setSheetHidden(1, true);
        }, true);

        ExcelImporter.Result result = ExcelImporter.parse(xlsx);
        assertEquals(1, result.content().getSheets().size());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("备用")),
                "跳过了什么必须说出来，否则就是「导进来少了一张表」的报障");
    }

    @Test
    @DisplayName("文字以 = 开头会被当成公式，导入时要提醒")
    void leadingEqualsIsWarned() {
        byte[] xlsx = build(sheet -> sheet.createRow(0).createCell(0).setCellValue("=等于号开头"));

        assertTrue(ExcelImporter.parse(xlsx).warnings().stream().anyMatch(w -> w.contains("=")),
                "TemplateParser 会把它当原生公式，且没有转义手段");
    }

    @Test
    @DisplayName("不是 xlsx 的文件给一句人话，不要把 POI 的异常抛给用户")
    void nonXlsxIsRejected() {
        BizException e = assertThrows(BizException.class,
                () -> ExcelImporter.parse("这不是一个 Excel".getBytes()));
        assertTrue(e.getMessage().contains("xlsx"));

        assertThrows(BizException.class, () -> ExcelImporter.parse(new byte[0]));
    }

    @Test
    @DisplayName("超过行数上限直接报错，不许截断——截出来的版式是错的")
    void oversizedSheetIsRejected() {
        byte[] xlsx = build(sheet -> {
            for (int r = 0; r < 12; r++) {
                sheet.createRow(r).createCell(0).setCellValue(r);
            }
        });

        BizException e = assertThrows(BizException.class,
                () -> ExcelImporter.parse(xlsx, new ExcelImporter.Options(10, 500000, true)));
        assertTrue(e.getMessage().contains("行"));
    }

    /* ------------------------------ 工具 ------------------------------ */

    /** 导出时统一用这一份：关掉限宽兜底，免得每个用例都被 fitToWidth 那条规则干扰。 */
    private PageConfigDTO pageConfig() {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setLimitWidth(false);
        return cfg;
    }

    private Map<String, Object> sheet(String name, Map<String, Object> config,
                                      List<Map<String, Object>> celldata) {
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", name);
        sheet.put("celldata", celldata);
        sheet.put("config", config);
        return sheet;
    }

    private Map<String, Object> cell(int r, int c, String text, Map<String, Object> style) {
        Map<String, Object> v = new LinkedHashMap<>(style);
        v.put("v", text);
        v.put("m", text);
        return Map.of("r", r, "c", c, "v", v);
    }

    private Map<String, Object> borderRange(String type, String style, String color,
                                            int r1, int r2, int c1, int c2) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("rangeType", "range");
        entry.put("borderType", type);
        entry.put("style", style);
        entry.put("color", color);
        entry.put("range", List.of(Map.of("row", List.of(r1, r2), "column", List.of(c1, c2))));
        return entry;
    }

    /** 手工造一份 xlsx（不经 ExcelExporter），用来测那些正向写不出来的输入。 */
    private byte[] build(java.util.function.Consumer<XSSFSheet> build) {
        return build(wb -> build.accept(wb.createSheet("Sheet1")), true);
    }

    private byte[] build(java.util.function.Consumer<XSSFWorkbook> build, boolean workbookLevel) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            build.accept(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(ReportContentDTO content, int sheetIndex) {
        return (Map<String, Object>) content.getSheets().get(sheetIndex).get("config");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> celldata(ReportContentDTO content, int sheetIndex) {
        return (List<Map<String, Object>>) content.getSheets().get(sheetIndex).get("celldata");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> borderInfo(ReportContentDTO content, int sheetIndex) {
        return (List<Map<String, Object>>) config(content, sheetIndex).get("borderInfo");
    }

    /** 某一格的 v 对象。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> valueAt(ReportContentDTO content, int sheetIndex, int r, int c) {
        for (Map<String, Object> entry : celldata(content, sheetIndex)) {
            if ((int) entry.get("r") == r && (int) entry.get("c") == c) {
                return (Map<String, Object>) entry.get("v");
            }
        }
        throw new AssertionError("第 " + r + " 行第 " + c + " 列没有单元格");
    }

    /** 某一格某一条边的 {style,color}。 */
    private Map<String, Object> side(ReportContentDTO content, int sheetIndex, int r, int c, String key) {
        for (Map<String, Object> entry : borderInfo(content, sheetIndex)) {
            Map<String, Object> value = mapOf(entry, "value");
            if (value != null && (int) value.get("row_index") == r && (int) value.get("col_index") == c) {
                Map<String, Object> side = mapOf(value, key);
                assertNotNull(side, "第 " + r + " 行第 " + c + " 列的 " + key + " 边没有");
                return side;
            }
        }
        throw new AssertionError("第 " + r + " 行第 " + c + " 列没有边框");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Map<String, Object> holder, String key) {
        Object v = holder == null ? null : holder.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private int num(Object o) {
        return ((Number) o).intValue();
    }
}
