package com.muzhou.report.export;

import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.PageConfigDTO;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WordExporter（POI 那条 Word 路）单元测试。
 *
 * <p>链路是 渲染结果 -> ExcelExporter -> xlsx -> WordExporter -> docx，所以连着 ExcelExporter 一起测。
 * 盯的是这条路特有的两件事：**横向按打印区域截断、纵向不截**，以及页面/表宽跟着报表的打印设置走。
 */
class WordExporterTest {

    private final ExcelExporter excel = new ExcelExporter(new MzProperties());

    private final WordExporter word = new WordExporter();

    @Test
    @DisplayName("设了打印区域时横向只取到区域内的列，右边的列不进 Word")
    void printAreaClipsColumns() throws Exception {
        byte[] docx = word.convert(excel.export(List.of(sheet()), pageConfig("A1:C6")));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            // 表头行：区域内 3 列
            assertEquals(3, table.getRow(1).getTableCells().size());
            String text = table.getText();
            assertTrue(text.contains("金额"), "区域内的列该在，实际:\n" + text);
            assertTrue(!text.contains("区域外备注"), "区域外的列不该进 Word，实际:\n" + text);
        }
    }

    @Test
    @DisplayName("没设打印区域时整表都进 Word")
    void withoutPrintAreaKeepsAllColumns() throws Exception {
        byte[] docx = word.convert(excel.export(List.of(sheet()), pageConfig(null)));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            assertEquals(5, table.getRow(1).getTableCells().size());
            assertTrue(table.getText().contains("区域外备注"));
        }
    }

    @Test
    @DisplayName("纵向不按打印区域截断：扩展出来的数据行会长到区域下面去，截了就把数据切没了")
    void printAreaDoesNotClipRows() throws Exception {
        // 打印区域只框了前两行，但表里有 6 行
        byte[] docx = word.convert(excel.export(List.of(sheet()), pageConfig("A1:C2")));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            assertEquals(6, table.getRows().size(), "行不该被打印区域截断");
            assertTrue(table.getText().contains("合计"), "最后一行的数据该在");
        }
    }

    @Test
    @DisplayName("页面用报表的纸张与页边距，表格铺满正文宽度")
    void pageSetupAndTableWidthFollowReport() throws Exception {
        PageConfigDTO cfg = pageConfig(null);
        cfg.setMarginLeft(20);
        cfg.setMarginRight(20);
        byte[] docx = word.convert(excel.export(List.of(sheet()), cfg));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            var sect = doc.getDocument().getBody().getSectPr();
            long paper = ((BigInteger) sect.getPgSz().getW()).longValue();
            long left = ((BigInteger) sect.getPgMar().getLeft()).longValue();
            long right = ((BigInteger) sect.getPgMar().getRight()).longValue();
            // A4 纵向 210mm = 11906 twip，20mm 页边距 = 1134 twip
            assertEquals(11906, paper, 2);
            assertEquals(1134, left);
            assertEquals(1134, right);

            long tableWidth = ((BigInteger) doc.getTables().get(0).getCTTbl()
                    .getTblPr().getTblW().getW()).longValue();
            assertEquals(paper - left - right, tableWidth, "表格该铺满正文宽度");
        }
    }

    @Test
    @DisplayName("打印设置按 sheet 单独生效时，每张 sheet 自成一节，各按各的方向出纸")
    void perSheetSectionFollowsItsOwnOrientation() throws Exception {
        PageConfigDTO portrait = pageConfig(null);
        PageConfigDTO landscape = pageConfig(null);
        landscape.setOrientation("landscape");

        byte[] docx = word.convert(excel.export(
                List.of(sheet(), sheet(), sheet()), i -> i == 1 ? landscape : portrait));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            List<CTSectPr> sections = sections(doc);
            assertEquals(3, sections.size(), "三张 sheet 该有三节");
            // A4 纵向 210mm = 11906 twip，横向则宽高互换
            assertEquals(STPageOrientation.PORTRAIT, sections.get(0).getPgSz().getOrient());
            assertEquals(STPageOrientation.LANDSCAPE, sections.get(1).getPgSz().getOrient());
            assertEquals(STPageOrientation.PORTRAIT, sections.get(2).getPgSz().getOrient());
            assertEquals(16838, ((BigInteger) sections.get(1).getPgSz().getW()).longValue(), 2,
                    "第二节该是 A4 横向的宽度");

            // 表格铺的是各自那一节的正文宽度：横向那张要更宽，不能还按第一张的宽度算
            long portraitWidth = tableWidth(doc, 0);
            long landscapeWidth = tableWidth(doc, 1);
            assertEquals(11906 - 567 - 567, portraitWidth, 4, "纵向那张铺满纵向正文宽");
            assertEquals(16838 - 567 - 567, landscapeWidth, 4, "横向那张该铺满横向正文宽");
            assertEquals(portraitWidth, tableWidth(doc, 2), 4);
        }
    }

    @Test
    @DisplayName("多节时页眉引用要抄到每一节上，否则页头只出现在最后一张 sheet 上")
    void everySectionKeepsTheHeader() throws Exception {
        PageConfigDTO cfg = pageConfig(null);
        cfg.getHeader().setCenter("月度报表");
        cfg.getFooter().setCenter("第 ${page} 页");

        byte[] docx = word.convert(excel.export(List.of(sheet(), sheet()), i -> cfg), cfg);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            List<CTSectPr> sections = sections(doc);
            assertEquals(2, sections.size());
            for (int i = 0; i < sections.size(); i++) {
                assertTrue(sections.get(i).sizeOfHeaderReferenceArray() > 0,
                        "第 " + (i + 1) + " 节该有页眉引用");
                assertTrue(sections.get(i).sizeOfFooterReferenceArray() > 0,
                        "第 " + (i + 1) + " 节该有页脚引用");
            }
        }
    }

    @Test
    @DisplayName("合并单元格还原成 gridSpan，公式算出结果而不是留空")
    void mergesAndFormulas() throws Exception {
        byte[] docx = word.convert(excel.export(List.of(sheet()), pageConfig("A1:C6")));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            XWPFTableCell title = table.getRow(0).getTableCells().get(0);
            assertEquals(3, ((BigInteger) title.getCTTc().getTcPr().getGridSpan().getVal()).intValue(),
                    "标题该横跨 3 列");
            // C3:C5 = 2000 + 3000 + 4000
            assertTrue(table.getText().contains("9000"), "公式该算出 9000，实际:\n" + table.getText());
        }
    }

    @Test
    @DisplayName("合并单元格的宽度是被跨掉的那几列之和，不是第一列的宽度")
    void mergedCellWidthIsSumOfSpannedColumns() throws Exception {
        byte[] docx = word.convert(excel.export(List.of(mergeSheet()), pageConfig(null)));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            List<Integer> grid = gridCols(table);
            assertEquals(4, grid.size(), "tblGrid 该有 4 列 —— 固定布局下 Word 是按它定列的");

            // 行0：标题跨满 4 列
            assertEquals(sum(grid, 0, 4), cellWidth(table, 0, 0), "跨 4 列的格子该有 4 列的宽度");
            // 行1：A 跨前 2 列
            assertEquals(sum(grid, 0, 2), cellWidth(table, 1, 0), "跨 2 列的格子该有 2 列的宽度");
            // 只跨行不跨列的格子保持本列宽度
            assertEquals(grid.get(0), cellWidth(table, 2, 0));
        }
    }

    @Test
    @DisplayName("同一行里的两个横向合并都要生效（删格子会让后面的下标前移）")
    void twoMergesInOneRow() throws Exception {
        byte[] docx = word.convert(excel.export(List.of(mergeSheet()), pageConfig(null)));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            List<XWPFTableCell> cells = table.getRow(5).getTableCells();
            assertEquals(2, cells.size(), "该只剩两个格子，每个跨 2 列");
            assertEquals(2, gridSpan(cells.get(0)));
            assertEquals(2, gridSpan(cells.get(1)));
            assertEquals("左", cells.get(0).getText());
            assertEquals("右", cells.get(1).getText());
        }
    }

    @Test
    @DisplayName("跨行合并写成 vMerge：首行 restart，后续行 continue")
    void verticalMerge() throws Exception {
        byte[] docx = word.convert(excel.export(List.of(mergeSheet()), pageConfig(null)));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            assertEquals(STMerge.RESTART, vMerge(table.getRow(2).getTableCells().get(0)));
            assertEquals(STMerge.CONTINUE, vMerge(table.getRow(3).getTableCells().get(0)));
            assertEquals("组", table.getRow(2).getTableCells().get(0).getText());
        }
    }

    @Test
    @DisplayName("边框颜色写死成黑色，不留给 Word 的 auto —— auto 在有底色的格子上会变成白色")
    void borderColorIsExplicitBlack() throws Exception {
        byte[] docx = word.convert(excel.export(List.of(sheet()), pageConfig("A1:C6")));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            // 行1 是有底色(#DDEEFF)的表头，也在 borderInfo 的范围里
            CTTcBorders borders = table.getRow(1).getTableCells().get(0)
                    .getCTTc().getTcPr().getTcBorders();
            for (CTBorder b : List.of(borders.getTop(), borders.getBottom(),
                    borders.getLeft(), borders.getRight())) {
                assertEquals(STBorder.SINGLE, b.getVal());
                assertEquals("000000", hexColor(b), "颜色要显式写黑，交给 auto 会被底色反推成白色");
            }
        }
    }

    @Test
    @DisplayName("Excel 里没画边框的地方 Word 里也不该有线（POI 建表自带的一整套默认边框要抹掉）")
    void cellsWithoutBorderStayBorderless() throws Exception {
        byte[] docx = word.convert(excel.export(List.of(sheet()), pageConfig("A1:C6")));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            CTTblBorders tbl = table.getCTTbl().getTblPr().getTblBorders();
            for (CTBorder b : List.of(tbl.getTop(), tbl.getBottom(), tbl.getLeft(),
                    tbl.getRight(), tbl.getInsideH(), tbl.getInsideV())) {
                assertEquals(STBorder.NIL, b.getVal(), "表格级默认边框该抹成 nil");
            }
            // 行0 是标题行，borderInfo 没框到它
            var pr = table.getRow(0).getTableCells().get(0).getCTTc().getTcPr();
            assertTrue(pr == null || !pr.isSetTcBorders(), "没边框的格子不该写 tcBorders");
        }
    }

    @Test
    @DisplayName("待转换内容为空时直接报错")
    void emptyInputFails() {
        assertThrows(BizException.class, () -> word.convert(null));
        assertThrows(BizException.class, () -> word.convert(new byte[0]));
    }

    @Test
    @DisplayName("页头页尾搬进 docx 的页眉页脚，页码写成 PAGE 域而不是写死的数字")
    void headerFooterBecomeWordHeaderFooter() throws Exception {
        PageConfigDTO cfg = pageConfig(null);
        cfg.getHeader().setLeft("销售部");
        cfg.getHeader().setRight("${date}");
        cfg.getFooter().setCenter("第 ${page} 页 / 共 ${pages} 页");
        byte[] docx = word.convert(excel.export(List.of(sheet()), cfg), cfg);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            String header = doc.getHeaderList().get(0).getText();
            assertTrue(header.contains("销售部"), "页头文字该在，实际:" + header);

            String footerXml = doc.getFooterList().get(0)._getHdrFtr().toString();
            assertTrue(footerXml.contains("PAGE"), "页码该是 PAGE 域，实际:\n" + footerXml);
            assertTrue(footerXml.contains("NUMPAGES"), "总页数该是 NUMPAGES 域，实际:\n" + footerXml);
        }
    }

    @Test
    @DisplayName("每条数据一个 sheet：新单据那一节页码从 1 重编，总页数改数本节")
    void pageNumbersRestartPerDocument() throws Exception {
        PageConfigDTO cfg = pageConfig(null);
        cfg.getFooter().setCenter("第 ${page} 页 / 共 ${pages} 页");
        // 一条数据两张模板：第 1 张起一份新单据，第 2 张接着数
        List<Map<String, Object>> sheets = List.of(sheet(), sheet(), sheet());
        List<List<Integer>> docBreaks = List.of(List.of(0), List.of(), List.of(0));

        byte[] docx = word.convert(excel.export(sheets, cfg), cfg, docBreaks::get);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            List<CTSectPr> sections = sections(doc);
            assertEquals(3, sections.size(), "每张 sheet 一节");
            assertTrue(sections.get(0).isSetPgNumType(), "第 1 节该重编页码");
            assertEquals(1, sections.get(0).getPgNumType().getStart().intValue());
            assertTrue(!sections.get(1).isSetPgNumType(), "同一份单据的第 2 张该接着上一节数");
            assertTrue(sections.get(2).isSetPgNumType(), "第 2 份单据该重编页码");

            String footerXml = doc.getFooterList().get(0)._getHdrFtr().toString();
            assertTrue(footerXml.contains("SECTIONPAGES"),
                    "按单据编号时「共几页」该数本节，实际:\n" + footerXml);
        }
    }

    @Test
    @DisplayName("普通报表不重编页码，总页数仍是整份文档的 NUMPAGES")
    void pageNumbersStayContinuousWithoutDocBreaks() throws Exception {
        PageConfigDTO cfg = pageConfig(null);
        cfg.getFooter().setCenter("第 ${page} 页 / 共 ${pages} 页");
        byte[] docx = word.convert(excel.export(List.of(sheet(), sheet()), cfg), cfg, i -> List.of());

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            for (CTSectPr sect : sections(doc)) {
                assertTrue(!sect.isSetPgNumType(), "普通报表不该重编页码");
            }
            String footerXml = doc.getFooterList().get(0)._getHdrFtr().toString();
            assertTrue(footerXml.contains("NUMPAGES") && !footerXml.contains("SECTIONPAGES"),
                    "实际:\n" + footerXml);
        }
    }

    @Test
    @DisplayName("水印放在页眉里（Word 只有页眉里的图形才每页都出现）")
    void watermarkGoesIntoHeader() throws Exception {
        PageConfigDTO cfg = pageConfig(null);
        cfg.getWatermark().setText("内部资料");
        byte[] docx = word.convert(excel.export(List.of(sheet()), cfg), cfg);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertEquals(1, doc.getHeaderList().size(), "该有一个页眉装水印");
            String xml = doc.getHeaderList().get(0)._getHdrFtr().toString();
            assertTrue(xml.contains("内部资料"), "水印文字该在页眉里，实际:\n" + xml);
            assertTrue(xml.contains("textpath"), "水印该是一段 VML 艺术字，实际:\n" + xml);
        }
    }

    @Test
    @DisplayName("水印文字里的引号要转义，否则 docx 打不开")
    void watermarkTextIsXmlEscaped() throws Exception {
        PageConfigDTO cfg = pageConfig(null);
        cfg.getWatermark().setText("\"绝密\" & <内部>");
        byte[] docx = word.convert(excel.export(List.of(sheet()), cfg), cfg);

        // 能被 POI 打开就说明 XML 是合法的
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            String xml = doc.getHeaderList().get(0)._getHdrFtr().toString();
            assertTrue(xml.contains("绝密"), "水印文字该在，实际:\n" + xml);
        }
    }

    @Test
    @DisplayName("没设页头页尾水印时不建页眉页脚")
    void noHeaderWhenNothingConfigured() throws Exception {
        byte[] docx = word.convert(excel.export(List.of(sheet()), pageConfig(null)), pageConfig(null));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertTrue(doc.getHeaderList().isEmpty(), "不该有页眉");
            assertTrue(doc.getFooterList().isEmpty(), "不该有页脚");
        }
    }

    /* ------------------------- 读 docx 的小工具 ------------------------- */

    /**
     * 文档里的各节，按出现顺序。
     *
     * <p>前面几节的 {@code sectPr} 挂在各自最后一个段落的 {@code pPr} 里（分节符），
     * 最后一节的挂在 body 上 —— docx 就是这么存的。
     */
    private List<CTSectPr> sections(XWPFDocument doc) {
        List<CTSectPr> sections = new ArrayList<>();
        for (XWPFParagraph p : doc.getParagraphs()) {
            if (p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetSectPr()) {
                sections.add(p.getCTP().getPPr().getSectPr());
            }
        }
        sections.add(doc.getDocument().getBody().getSectPr());
        return sections;
    }

    private long tableWidth(XWPFDocument doc, int index) {
        return ((BigInteger) doc.getTables().get(index).getCTTbl().getTblPr().getTblW().getW()).longValue();
    }

    private List<Integer> gridCols(XWPFTable table) {
        List<Integer> cols = new ArrayList<>();
        var grid = table.getCTTbl().getTblGrid();
        for (int i = 0; grid != null && i < grid.sizeOfGridColArray(); i++) {
            cols.add(((BigInteger) grid.getGridColArray(i).getW()).intValue());
        }
        return cols;
    }

    private int sum(List<Integer> values, int from, int toExclusive) {
        int total = 0;
        for (int i = from; i < toExclusive; i++) {
            total += values.get(i);
        }
        return total;
    }

    private int cellWidth(XWPFTable table, int row, int cell) {
        return ((BigInteger) table.getRow(row).getTableCells().get(cell)
                .getCTTc().getTcPr().getTcW().getW()).intValue();
    }

    private int gridSpan(XWPFTableCell cell) {
        return ((BigInteger) cell.getCTTc().getTcPr().getGridSpan().getVal()).intValue();
    }

    /**
     * 一条边的颜色，统一成 "RRGGBB"。
     *
     * <p>{@code w:color} 在 schema 里是个联合类型（"auto" 或六位十六进制），XmlBeans 读回来
     * 会把十六进制那支解析成 {@code byte[]}，不是字符串。
     */
    private String hexColor(CTBorder b) {
        Object color = b.getColor();
        if (color instanceof byte[] rgb) {
            StringBuilder sb = new StringBuilder();
            for (byte v : rgb) {
                sb.append(String.format("%02X", v & 0xFF));
            }
            return sb.toString();
        }
        return String.valueOf(color);
    }

    private STMerge.Enum vMerge(XWPFTableCell cell) {
        return cell.getCTTc().getTcPr().getVMerge().getVal();
    }

    /* ------------------------- 构造测试数据 ------------------------- */

    /**
     * 各种合并都来一份的 4 列表：
     * 行0 标题跨 4 列、行1 跨 2 列、行2-3 跨 2 行、行4-5 二维 2x2、行5 同一行两个横向合并。
     */
    private Map<String, Object> mergeSheet() {
        List<Map<String, Object>> celldata = new ArrayList<>();
        celldata.add(cell(0, 0, "标题", Map.of()));
        celldata.add(cell(1, 0, "A", Map.of()));
        celldata.add(cell(1, 2, "C", Map.of()));
        celldata.add(cell(1, 3, "D", Map.of()));
        celldata.add(cell(2, 0, "组", Map.of()));
        celldata.add(cell(2, 1, "b1", Map.of()));
        celldata.add(cell(3, 1, "b2", Map.of()));
        celldata.add(cell(4, 0, "合计", Map.of()));
        celldata.add(cell(5, 0, "左", Map.of()));
        celldata.add(cell(5, 2, "右", Map.of()));

        Map<String, Object> merge = new LinkedHashMap<>();
        merge.put("0_0", Map.of("r", 0, "c", 0, "rs", 1, "cs", 4));
        merge.put("1_0", Map.of("r", 1, "c", 0, "rs", 1, "cs", 2));
        merge.put("2_0", Map.of("r", 2, "c", 0, "rs", 2, "cs", 1));
        merge.put("4_0", Map.of("r", 4, "c", 0, "rs", 1, "cs", 2));
        merge.put("5_0", Map.of("r", 5, "c", 0, "rs", 1, "cs", 2));
        merge.put("5_2", Map.of("r", 5, "c", 2, "rs", 1, "cs", 2));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("columnlen", Map.of("0", 100, "1", 80, "2", 90, "3", 90));
        config.put("rowlen", Map.of());
        config.put("merge", merge);
        config.put("borderInfo", List.of());

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "合并");
        sheet.put("celldata", celldata);
        sheet.put("config", config);
        return sheet;
    }

    private PageConfigDTO pageConfig(String printArea) {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setPrintArea(printArea);
        return cfg;
    }

    @Test
    @DisplayName("图片放进对应的 Word 单元格里，等比例装进这一格而不是拉满")
    void cellImageGoesIntoItsTableCell() throws Exception {
        PageConfigDTO cfg = pageConfig(null);
        // 行高 100px = 75pt，Word 那格宽约 269pt —— 方图该缩成 75×75，而不是拉成 269×75
        byte[] docx = word.convert(excel.export(List.of(imageSheet()), cfg));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            XWPFTableCell imageCell = table.getRow(1).getCell(1);
            List<XWPFPicture> pictures = imageCell.getParagraphs().get(0).getRuns().stream()
                    .flatMap(run -> run.getEmbeddedPictures().stream())
                    .toList();
            assertEquals(1, pictures.size(), "第 2 行第 2 格里该有一张图片");

            // 别的格子没有图
            assertEquals(0, table.getRow(0).getCell(0).getParagraphs().get(0).getRuns().stream()
                    .mapToLong(run -> run.getEmbeddedPictures().size()).sum());

            long width = pictures.get(0).getCTPicture().getSpPr().getXfrm().getExt().getCx();
            long height = pictures.get(0).getCTPicture().getSpPr().getXfrm().getExt().getCy();
            assertEquals(75 * 12700, height, 12700, "高度该顶满 100px(75pt) 的行");
            assertEquals(height, width, 12700, "方图在 Word 里仍该是方的");
        }
    }

    /** 两列两行：第 1 行是表头，第 2 行第 2 格是图片单元格（行拖高到 100px） */
    private Map<String, Object> imageSheet() {
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("v", "");
        img.put("m", "");
        img.put("mzImg", Map.of("src", TestImages.PNG_DATA_URI));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("columnlen", Map.of("0", 100, "1", 100));
        config.put("rowlen", Map.of("1", 100));

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "带图");
        sheet.put("celldata", List.of(
                cell(0, 0, "名称", Map.of("bl", "1")), cell(0, 1, "图片", Map.of("bl", "1")),
                cell(1, 0, "产品A", Map.of()), Map.of("r", 1, "c", 1, "v", img)));
        sheet.put("config", config);
        return sheet;
    }

    /**
     * 5 列 6 行：标题（横跨 3 列）、表头、3 行数据、合计行（公式）。
     * 第 4、5 列是「打印区域之外」的备注。
     */
    private Map<String, Object> sheet() {
        List<Map<String, Object>> celldata = new ArrayList<>();
        celldata.add(cell(0, 0, "月度经营分析报表", Map.of("bl", "1", "ht", "0")));
        String[] header = {"部门", "销量", "金额"};
        for (int c = 0; c < 3; c++) {
            celldata.add(cell(1, c, header[c], Map.of("bl", "1", "bg", "#DDEEFF")));
        }
        for (int r = 2; r <= 4; r++) {
            celldata.add(cell(r, 0, "部门" + (r - 1), Map.of()));
            celldata.add(numCell(r, 1, r * 10));
            celldata.add(numCell(r, 2, r * 1000));
            celldata.add(cell(r, 3, "区域外备注" + r, Map.of()));
            celldata.add(cell(r, 4, "也不该出现" + r, Map.of()));
        }
        celldata.add(cell(5, 0, "合计", Map.of("bl", "1")));
        celldata.add(Map.of("r", 5, "c", 2, "v", Map.of("f", "=SUM(C3:C5)", "v", "", "m", "")));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("columnlen", Map.of("0", 90, "1", 60, "2", 110, "3", 100, "4", 100));
        config.put("rowlen", Map.of("0", 32));
        config.put("merge", Map.of("0_0", Map.of("r", 0, "c", 0, "rs", 1, "cs", 3)));
        config.put("borderInfo", List.of(Map.of("rangeType", "range", "borderType", "border-all",
                "range", List.of(Map.of("row", List.of(1, 5), "column", List.of(0, 2))))));

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "经营分析");
        sheet.put("celldata", celldata);
        sheet.put("config", config);
        return sheet;
    }

    @Test
    @DisplayName("行分页符落成表格行首格的 pageBreakBefore —— 一张表中间没法插分节符")
    void rowBreakBecomesPageBreakBefore() throws Exception {
        Map<String, Object> s = sheet();
        // 「每条数据一页」拼出来的 sheet：第 2 份从第 3 行起
        s.put("mzRowBreaks", List.of(3));
        byte[] docx = word.convert(excel.export(List.of(s), new PageConfigDTO()));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            assertTrue(breaksBefore(table, 3), "第 3 行该另起一页");
            assertTrue(!breaksBefore(table, 2), "别的行不该被打上分页");
            assertTrue(!breaksBefore(table, 4), "别的行不该被打上分页");
        }
    }

    @Test
    @DisplayName("没有分页符的普通报表，表格里一个 pageBreakBefore 都没有")
    void noPageBreakByDefault() throws Exception {
        byte[] docx = word.convert(excel.export(List.of(sheet()), new PageConfigDTO()));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            for (int r = 0; r < table.getNumberOfRows(); r++) {
                assertTrue(!breaksBefore(table, r), "第 " + r + " 行不该有分页");
            }
        }
    }

    /** 表格第 r 行的首格是否带 pageBreakBefore */
    private boolean breaksBefore(XWPFTable table, int r) {
        XWPFTableCell cell = table.getRow(r).getCell(0);
        for (XWPFParagraph p : cell.getParagraphs()) {
            if (p.isPageBreak()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> cell(int r, int c, String text, Map<String, Object> style) {
        Map<String, Object> v = new LinkedHashMap<>(style);
        v.put("v", text);
        v.put("m", text);
        return Map.of("r", r, "c", c, "v", v);
    }

    private Map<String, Object> numCell(int r, int c, double n) {
        return Map.of("r", r, "c", c, "v", Map.of("v", n, "m", String.valueOf(n),
                "ct", Map.of("t", "n", "fa", "#,##0.00")));
    }
}
