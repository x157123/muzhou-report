package com.muzhou.report.export;

import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.PageConfigDTO;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFOddFooter;
import org.apache.poi.xssf.usermodel.XSSFOddHeader;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.spreadsheetDrawing.STEditAs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExcelExporter 的页面几何单元测试（纯 POJO，不启动 Spring）。
 *
 * <p>盯的是同一件事：导出的 xlsx 在 Excel 里的宽度必须和设计器画布上一致，
 * 且不能因为换算误差被挤到第二页 —— 也就是「导出的 Excel 打印不全」那个问题。
 */
class ExcelExporterTest {

    /** 设计器（FortuneSheet）里没被拖过的列的默认宽度 */
    private static final int DEFAULT_COL_PX = 73;

    private final ExcelExporter exporter = new ExcelExporter(new MzProperties());

    @Test
    @DisplayName("列宽按 Excel 的字符宽单位精确换算，误差不超过 1px 且只会偏窄")
    void columnWidthMatchesDesignerPixels() throws Exception {
        int[] designed = {110, 110, 80, 130, 70, 90, 100, 110};

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetWith(designed)), new PageConfigDTO()))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            for (int c = 0; c < designed.length; c++) {
                float px = sheet.getColumnWidthInPixels(c);
                assertTrue(px <= designed[c],
                        "第 " + c + " 列导出后不能比设计的更宽：" + px + " > " + designed[c]);
                assertTrue(designed[c] - px < 1,
                        "第 " + c + " 列导出后偏差过大：设计 " + designed[c] + "，导出 " + px);
            }
        }
    }

    @Test
    @DisplayName("columnlen 里没有的列补成设计器的默认列宽，而不是 Excel 自己的 ~64px")
    void unsetColumnsFallBackToDesignerDefault() throws Exception {
        int[] designed = {110, 90};
        // 第 2 列有内容但没设过宽度
        Map<String, Object> sheetData = sheetWith(designed);
        cells(sheetData).add(cell(0, designed.length));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetData), new PageConfigDTO()))) {
            float px = wb.getSheetAt(0).getColumnWidthInPixels(designed.length);
            assertEquals(DEFAULT_COL_PX, px, 1.0,
                    "没设过宽度的列应按设计器的 " + DEFAULT_COL_PX + "px 导出");
        }
    }

    @Test
    @DisplayName("限宽 + 未改缩放时按「一页宽」出纸，内容不会被切到第二页")
    void limitWidthExportsFitToOnePageWide() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetWith(new int[]{110, 110})), cfg))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertTrue(sheet.getFitToPage(), "限宽报表应按一页宽出纸");
            assertEquals(1, sheet.getPrintSetup().getFitWidth());
            assertEquals(0, sheet.getPrintSetup().getFitHeight(), "行数不限，只压宽度");
        }
    }

    @Test
    @DisplayName("用户显式调过缩放时以缩放为准，不擅自接管成「一页宽」")
    void explicitScaleWins() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setScale(80);

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetWith(new int[]{110, 110})), cfg))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertFalse(sheet.getFitToPage());
            assertEquals(80, sheet.getPrintSetup().getScale());
        }
    }

    @Test
    @DisplayName("纸张、方向、页边距写进页面设置")
    void pageSetupIsWritten() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setPaperSize("A3");
        cfg.setOrientation("landscape");
        cfg.setMarginLeft(20);

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetWith(new int[]{110})), cfg))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            PrintSetup ps = sheet.getPrintSetup();
            assertEquals(PrintSetup.A3_PAPERSIZE, ps.getPaperSize());
            assertTrue(ps.getLandscape());
            assertEquals(20 / 25.4d, sheet.getMargin(PageMargin.LEFT), 0.001);
        }
    }

    @Test
    @DisplayName("顶端标题行写成 xlsx 的 Print_Titles，PDF / Word 从这里读回来")
    void titleRowsAreWritten() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setTitleRows("1:3");

        try (XSSFWorkbook wb = read(exporter.export(List.of(rowsSheet(8)), cfg))) {
            CellRangeAddress rows = wb.getSheetAt(0).getRepeatingRows();
            assertEquals(0, rows.getFirstRow());
            assertEquals(2, rows.getLastRow(), "1:3 是 1 起算的闭区间");
        }
    }

    @Test
    @DisplayName("没配顶端标题行、或配了非法值时不写 Print_Titles，也不让导出失败")
    void badTitleRowsAreIgnored() throws Exception {
        for (String bad : new String[]{"", "  ", "abc", "3:1", "0:2", "1:2:3"}) {
            PageConfigDTO cfg = new PageConfigDTO();
            cfg.setTitleRows(bad);
            try (XSSFWorkbook wb = read(exporter.export(List.of(rowsSheet(8)), cfg))) {
                assertNull(wb.getSheetAt(0).getRepeatingRows(), "[" + bad + "] 不该被当成标题行");
            }
        }
    }

    @Test
    @DisplayName("标题行必须在内容最上面且不盖住全部内容 —— 设计器拦得住，手写 content 得靠这里")
    void titleRowsMustBeTopmostAndPartial() throws Exception {
        // 落在内容中间：Excel 本身写得进去（先印上方内容、从出现那页起重复），
        // 但 PDF 是照行流分页的、只认最上面的 —— 放过去三条路就分叉了
        PageConfigDTO middle = new PageConfigDTO();
        middle.setTitleRows("3:4");
        try (XSSFWorkbook wb = read(exporter.export(List.of(rowsSheet(8)), middle))) {
            assertNull(wb.getSheetAt(0).getRepeatingRows(), "不在最上面的标题行不该写进 xlsx");
        }

        PageConfigDTO all = new PageConfigDTO();
        all.setTitleRows("1:8");
        try (XSSFWorkbook wb = read(exporter.export(List.of(rowsSheet(8)), all))) {
            assertNull(wb.getSheetAt(0).getRepeatingRows(), "盖住全部内容的标题行不该写进 xlsx");
        }

        // 设了打印区域时按区域的第一行起算：A3:A8 的第一行是第 3 行，标题行 3:4 合法
        PageConfigDTO area = new PageConfigDTO();
        area.setPrintArea("A3:A8");
        area.setTitleRows("3:4");
        try (XSSFWorkbook wb = read(exporter.export(List.of(rowsSheet(8)), area))) {
            CellRangeAddress rows = wb.getSheetAt(0).getRepeatingRows();
            assertEquals(2, rows.getFirstRow(), "打印区域内最上面的标题行该写进去");
            assertEquals(3, rows.getLastRow());
        }
    }

    /** n 行 1 列都有内容的表 */
    private Map<String, Object> rowsSheet(int n) {
        List<Map<String, Object>> celldata = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            celldata.add(cell(r, 0));
        }
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "Sheet1");
        sheet.put("celldata", celldata);
        sheet.put("config", Map.of("columnlen", Map.of("0", 110)));
        return sheet;
    }

    @Test
    @DisplayName("默认行高按设计器的 19px（14.25pt）写入")
    void defaultRowHeightMatchesDesigner() throws Exception {
        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetWith(new int[]{110})), new PageConfigDTO()))) {
            assertEquals(14.25f, wb.getSheetAt(0).getDefaultRowHeightInPoints(), 0.01);
        }
    }

    @Test
    @DisplayName("打印设置按 sheet 单独生效：一张横向 A3、一张纵向 A4，互不影响")
    void pageSetupIsPerSheet() throws Exception {
        PageConfigDTO wide = new PageConfigDTO();
        wide.setPaperSize("A3");
        wide.setOrientation("landscape");
        PageConfigDTO narrow = new PageConfigDTO();

        byte[] xlsx = exporter.export(
                List.of(sheetWith("宽表", new int[]{110, 110}), sheetWith("窄表", new int[]{110})),
                i -> i == 0 ? wide : narrow);

        try (XSSFWorkbook wb = read(xlsx)) {
            PrintSetup first = wb.getSheetAt(0).getPrintSetup();
            assertEquals(PrintSetup.A3_PAPERSIZE, first.getPaperSize());
            assertTrue(first.getLandscape(), "第 1 张应是横向");

            PrintSetup second = wb.getSheetAt(1).getPrintSetup();
            assertEquals(PrintSetup.A4_PAPERSIZE, second.getPaperSize());
            assertFalse(second.getLandscape(), "第 2 张应保持纵向");
        }
    }

    @Test
    @DisplayName("同名 sheet 加序号后缀，而不是让整份导出失败")
    void duplicateSheetNamesAreDeduped() throws Exception {
        byte[] xlsx = exporter.export(
                List.of(sheetWith("汇总", new int[]{110}), sheetWith("汇总", new int[]{110})),
                new PageConfigDTO());

        try (XSSFWorkbook wb = read(xlsx)) {
            assertEquals(2, wb.getNumberOfSheets());
            assertEquals("汇总", wb.getSheetAt(0).getSheetName());
            assertEquals("汇总(2)", wb.getSheetAt(1).getSheetName());
        }
    }

    @Test
    @DisplayName("设计器里的自动换行(tb=2)导出后仍是自动换行，tb=0/1 保持不换行")
    void wrapTextIsExported() throws Exception {
        Map<String, Object> sheetData = sheetWith(new int[]{110});
        cells(sheetData).clear();
        cells(sheetData).add(styledCell(0, 0, Map.of("tb", "2")));
        cells(sheetData).add(styledCell(1, 0, Map.of("tb", "1")));
        cells(sheetData).add(styledCell(2, 0, Map.of()));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetData), new PageConfigDTO()))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertTrue(sheet.getRow(0).getCell(0).getCellStyle().getWrapText(), "tb=2 应导出为自动换行");
            assertFalse(sheet.getRow(1).getCell(0).getCellStyle().getWrapText(), "tb=1（溢出）不该换行");
            assertFalse(sheet.getRow(2).getCell(0).getCellStyle().getWrapText(), "没设过的单元格不该换行");
        }
    }

    @Test
    @DisplayName("自动换行折成多行时把行撑高——Excel 打开文件不会自己重算行高")
    void wrapRowGrowsToFitText() throws Exception {
        // 100px 宽的列 ≈ 75pt，11pt 的中文一行放得下 6 个字，24 个字要折 4 行
        Map<String, Object> sheetData = sheetOf(Map.of("0", 100), Map.of(),
                styledCell(0, 0, "一二三四五六七八九十一二三四五六七八九十一二三四", Map.of("tb", "2")));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetData), new PageConfigDTO()))) {
            float height = wb.getSheetAt(0).getRow(0).getHeightInPoints();
            assertTrue(height > 40, "四行文字的行高至少该有 40pt，实际 " + height);
        }
    }

    @Test
    @DisplayName("自动换行但只有一行的格子不撑高，免得平白多出一截空白")
    void shortWrapTextKeepsRowHeight() throws Exception {
        Map<String, Object> sheetData = sheetOf(Map.of("0", 200), Map.of(),
                styledCell(0, 0, "短文本", Map.of("tb", "2")));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetData), new PageConfigDTO()))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertEquals(sheet.getDefaultRowHeightInPoints(), sheet.getRow(0).getHeightInPoints(), 0.01,
                    "不该动这一行的高度");
        }
    }

    @Test
    @DisplayName("行高夹在 Excel 的上限 409.5pt 以内 —— 写超了 Excel 会把文件当损坏")
    void rowHeightIsCappedAtExcelLimit() throws Exception {
        // 一段长文落在窄列里，按公式量出来七百多磅
        Map<String, Object> wrapped = styledCell(0, 0, "说明".repeat(300), Map.of("tb", "2"));
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "长备注");
        sheet.put("celldata", List.of(wrapped));
        // 拖出来的行高越界时同样夹住
        sheet.put("config", Map.of("columnlen", Map.of("0", 100), "rowlen", Map.of("1", 10000)));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            XSSFSheet s = wb.getSheetAt(0);
            assertEquals(409.5f, s.getRow(0).getHeightInPoints(), 0.01,
                    "自动换行撑出来的行高该夹在上限上");
            assertEquals(409.5f, s.getRow(1).getHeightInPoints(), 0.01,
                    "拖出来的行高同样夹在上限上");
        }
    }

    @Test
    @DisplayName("行高只增不减：用户拖出来的高行不会被算出来的值改矮")
    void manualRowHeightIsNotShrunk() throws Exception {
        // 200px = 150pt，比两行文字需要的高度大得多
        Map<String, Object> sheetData = sheetOf(Map.of("0", 100), Map.of("0", 200),
                styledCell(0, 0, "一二三四五六七八九十一二", Map.of("tb", "2")));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetData), new PageConfigDTO()))) {
            assertEquals(150f, wb.getSheetAt(0).getRow(0).getHeightInPoints(), 0.01);
        }
    }

    @Test
    @DisplayName("页头页尾写进 xlsx 的页眉页脚，占位符换成 Excel 代码，页眉边距一并写上")
    void writesHeaderAndFooter() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getHeader().setCenter("月度销售报表");
        cfg.getHeader().setFontSize(12);
        cfg.getHeader().setMargin(8);
        cfg.getFooter().setRight("第 ${page} 页 / 共 ${pages} 页");

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetWith(new int[]{110, 110})), cfg))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            assertEquals("&C&12月度销售报表", ((XSSFOddHeader) sheet.getOddHeader()).getText());
            assertEquals("&R&09第 &P 页 / 共 &N 页", ((XSSFOddFooter) sheet.getOddFooter()).getText());
            assertEquals(8 / 25.4, sheet.getMargin(PageMargin.HEADER), 0.001);
        }
    }

    @Test
    @DisplayName("没设页头页尾时不往 xlsx 里写页眉页脚")
    void skipsHeaderWhenBlank() throws Exception {
        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetWith(new int[]{110})), new PageConfigDTO()))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            String header = ((XSSFOddHeader) sheet.getOddHeader()).getText();
            assertTrue(header == null || header.isEmpty(), "不该有页眉，实际:" + header);
        }
    }

    /* ------------------------- 图片单元格 ------------------------- */

    /** 图片素材见 {@link TestImages} */
    private static final String PNG_DATA_URI = TestImages.PNG_DATA_URI;

    @Test
    @DisplayName("图片锚在它所在的格子里，且是等比例装进去的框——不拉伸")
    void imageIsFittedIntoItsCellWithoutStretching() throws Exception {
        // 1×1 的方图放进 100×150px 的格子：该缩成 100×100 并垂直居中（上下各留 25px）
        Map<String, Object> sheetData = sheetOf(Map.of("0", 100, "1", 100), Map.of("2", 150),
                cell(0, 0), imageCell(2, 1, PNG_DATA_URI));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetData), new PageConfigDTO()))) {
            XSSFSheet sheet = wb.getSheetAt(0);
            List<XSSFShape> shapes = sheet.getDrawingPatriarch().getShapes();
            assertEquals(1, shapes.size(), "应该正好写进一张图片");

            XSSFPicture picture = (XSSFPicture) shapes.get(0);
            ClientAnchor anchor = picture.getClientAnchor();
            assertEquals(2, anchor.getRow1());
            assertEquals(1, anchor.getCol1());
            assertEquals(XSSFWorkbook.PICTURE_TYPE_PNG, picture.getPictureData().getPictureType());
            // 锚定方式只能从 XML 的 editAs 上看：POI 读回来的 XSSFClientAnchor 不带它
            assertEquals(STEditAs.ONE_CELL, sheet.getDrawingPatriarch().getCTDrawing()
                            .getTwoCellAnchorArray(0).getEditAs(),
                    "跟着格子走但不跟着格子缩放，否则列宽一变图片就被横向拉长");

            // 宽度顶满一格（100px），高度也是 100px —— 方图没被拉成 100×150
            assertEquals(sheet.getColumnWidthInPixels(1), pxWidth(sheet, anchor), 2,
                    "宽度该顶满一格");
            assertEquals(pxWidth(sheet, anchor), pxHeight(anchor), 2, "方图导出后仍该是方的");
            assertTrue(px(anchor.getDy1()) > 20, "垂直方向该居中留白，实际上边距 " + px(anchor.getDy1()));

            // 图片格自己不写文字，否则 Excel 里图片下面压着一串 base64
            assertEquals("", sheet.getRow(2).getCell(1).getStringCellValue());
        }
    }

    @Test
    @DisplayName("落在合并区里的图片按整块来装，而不是只按左上角那一格")
    void imageInMergedRegionFitsWholeBlock() throws Exception {
        // 合并 2 行 3 列 = 300×38px，方图该缩成 38×38 并水平居中
        Map<String, Object> sheet = sheetOf(Map.of("0", 100, "1", 100, "2", 100), Map.of(),
                imageCell(1, 0, PNG_DATA_URI));
        sheet.put("config", Map.of("columnlen", Map.of("0", 100, "1", 100, "2", 100), "rowlen", Map.of(),
                "merge", Map.of("1_0", Map.of("r", 1, "c", 0, "rs", 2, "cs", 3)),
                "borderInfo", List.of()));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            XSSFSheet s = wb.getSheetAt(0);
            ClientAnchor anchor = ((XSSFPicture) s.getDrawingPatriarch().getShapes().get(0)).getClientAnchor();
            assertEquals(1, anchor.getRow1());
            // 高度顶满合并的 2 行（2×19px），宽度跟着一样 —— 方图没有被拉成 300 宽
            assertEquals(38, pxHeight(anchor), 2, "高度该顶满合并的两行");
            assertEquals(pxHeight(anchor), pxWidth(s, anchor), 2, "方图仍该是方的");
            // 水平居中：300px 的块里 38px 的图从 131px 处开始，落在第 2 列
            assertEquals(1, anchor.getCol1(), "水平居中后起点落在中间那一列");
        }
    }

    /** 锚点框的宽度（px）：跨列时把中间整列的宽度也算上 */
    private double pxWidth(XSSFSheet sheet, ClientAnchor anchor) {
        double width = px(anchor.getDx2()) - px(anchor.getDx1());
        for (int c = anchor.getCol1(); c < anchor.getCol2(); c++) {
            width += sheet.getColumnWidthInPixels(c);
        }
        return width;
    }

    /** 锚点框的高度（px）：行高都是默认的 19px */
    private double pxHeight(ClientAnchor anchor) {
        return px(anchor.getDy2()) - px(anchor.getDy1())
                + (anchor.getRow2() - anchor.getRow1()) * 19d;
    }

    /** EMU -> px */
    private double px(int emu) {
        return emu / 9525d;
    }

    @Test
    @DisplayName("同一张图出现多次只存一份，坏图跳过且不让整份导出失败")
    void repeatedImagesAreSharedAndBadOnesSkipped() throws Exception {
        Map<String, Object> sheetData = sheetOf(Map.of("0", 100), Map.of(),
                imageCell(0, 0, PNG_DATA_URI),
                imageCell(1, 0, PNG_DATA_URI),
                // 解得开但不是图片：认不出格式，跳过这一张
                imageCell(2, 0, "data:text/plain;base64,aGVsbG8="),
                // 服务端解析不了的相对路径：同样跳过
                imageCell(3, 0, "/files/logo.png"));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetData), new PageConfigDTO()))) {
            assertEquals(1, wb.getAllPictures().size(), "重复的图片只该存一份");
            assertEquals(2, wb.getSheetAt(0).getDrawingPatriarch().getShapes().size(),
                    "两处引用各有一个锚点，坏图不出现");
        }
    }

    @Test
    @DisplayName("金额写成数值 + 带货币符号的数字格式，Excel 里也看得到 ¥")
    void currencyKeepsSymbolInDataFormat() throws Exception {
        Map<String, Object> sheet = sheetOf(Map.of("0", 100), Map.of(),
                ctCell(0, 0, 1234.5, "¥1,234.50", "¥#,##0.00", "n"));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            Cell cell = wb.getSheetAt(0).getRow(0).getCell(0);
            assertEquals(CellType.NUMERIC, cell.getCellType(), "金额仍要是数值，Excel 里才能继续算");
            assertEquals(1234.5, cell.getNumericCellValue(), 0.001);
            assertEquals("¥#,##0.00", cell.getCellStyle().getDataFormatString());
        }
    }

    @Test
    @DisplayName("金额中文大写（ct.t=s）写成文本，不被当数字写回阿拉伯数字")
    void chineseUpperAmountIsWrittenAsText() throws Exception {
        Map<String, Object> sheet = sheetOf(Map.of("0", 200), Map.of(),
                ctCell(0, 0, 1234.56, "壹仟贰佰叁拾肆元伍角陆分", "@", "s"));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            Cell cell = wb.getSheetAt(0).getRow(0).getCell(0);
            assertEquals(CellType.STRING, cell.getCellType());
            assertEquals("壹仟贰佰叁拾肆元伍角陆分", cell.getStringCellValue());
        }
    }

    @Test
    @DisplayName("自定义日期格式转成 Excel 的格式码：MM→mm，中文字面量裸着写")
    void customDateFormatIsConverted() throws Exception {
        Map<String, Object> sheet = sheetOf(Map.of("0", 120), Map.of(),
                ctCell(0, 0, "2024-01-31", "2024年01月31日", "yyyy年MM月dd日", "d"));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            Cell cell = wb.getSheetAt(0).getRow(0).getCell(0);
            assertEquals(CellType.NUMERIC, cell.getCellType(), "日期要写成日期值，不是一串文本");
            assertEquals("yyyy年mm月dd日", cell.getCellStyle().getDataFormatString());
            // PDF / Word 两条路都靠 DataFormatter 按这份格式码取显示文本：
            // 中文加了引号的话，这里出的是 2024"年"01"月"31"日"

            assertEquals("2024年01月31日", new DataFormatter(Locale.CHINA).formatCellValue(cell));
        }
    }

    /* ------------------------- 构造测试数据 ------------------------- */

    /** 带格式描述的单元格：渲染引擎输出的 `v.ct`（{fa,t}），见 CellFormatter */
    private Map<String, Object> ctCell(int r, int c, Object value, String display, String fa, String t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("v", value);
        v.put("m", display);
        v.put("ct", Map.of("fa", fa, "t", t));
        return Map.of("r", r, "c", c, "v", v);
    }

    /** 带图片的单元格：渲染引擎输出的 `v.mzImg.src`，见 GridCell#image */
    private Map<String, Object> imageCell(int r, int c, String src) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("v", "");
        v.put("m", "");
        v.put("mzImg", Map.of("src", src));
        return Map.of("r", r, "c", c, "v", v);
    }

    /** 指定列宽 / 行高和若干单元格的一张表 */
    private Map<String, Object> sheetOf(Map<String, Object> columnlen, Map<String, Object> rowlen,
                                        Map<String, Object>... cells) {
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "Sheet1");
        sheet.put("celldata", new ArrayList<>(List.of(cells)));
        sheet.put("config", Map.of("columnlen", columnlen, "rowlen", rowlen,
                "merge", Map.of(), "borderInfo", List.of()));
        return sheet;
    }

    /** 一张首行有内容、列宽为 designed 的表 */
    @Test
    @DisplayName("mzRowBreaks 写成 Excel 的手动行分页符（起始行 -> 在它上一行下方断页）")
    void rowBreaksBecomeManualPageBreaks() throws Exception {
        Map<String, Object> sheet = sheetWith(new int[]{100, 100});
        // 「每条数据一页」拼出来的 sheet 就长这样：每一份的起始行挂在 mzRowBreaks 上
        sheet.put("mzRowBreaks", List.of(4, 8));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            int[] breaks = wb.getSheetAt(0).getRowBreaks();
            // POI 的语义是「在这一行下方断页」，所以起始行 4/8 落成 3/7
            assertArrayEquals(new int[]{3, 7}, breaks,
                    "分页符位置不对：" + java.util.Arrays.toString(breaks));
        }
    }

    @Test
    @DisplayName("没有 mzRowBreaks 的普通报表不写任何手动分页符")
    void noRowBreaksByDefault() throws Exception {
        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetWith(new int[]{100})), new PageConfigDTO()))) {
            assertEquals(0, wb.getSheetAt(0).getRowBreaks().length);
        }
    }

    @Test
    @DisplayName("mzDocBreaks 非空的 sheet 起始页号钉成 1（一条数据一份单据，页码各数各的）")
    void docBreaksRestartExcelPageNumbering() throws Exception {
        // perRow：第 1 张起一份新单据，第 2 张是同一条数据的第 2 张模板（接着上一张数）
        Map<String, Object> first = sheetWith("单据1", new int[]{100});
        first.put("mzDocBreaks", List.of(0));
        Map<String, Object> second = sheetWith("单据1明细", new int[]{100});
        second.put("mzDocBreaks", List.of());

        try (XSSFWorkbook wb = read(exporter.export(List.of(first, second), new PageConfigDTO()))) {
            PrintSetup one = wb.getSheetAt(0).getPrintSetup();
            assertTrue(one.getUsePage(), "新单据那张该从第 1 页重新数");
            assertEquals(1, one.getPageStart());
            assertFalse(wb.getSheetAt(1).getPrintSetup().getUsePage(), "同一份单据的第 2 张该接着数");
        }
    }

    @Test
    @DisplayName("普通报表（没有 mzDocBreaks）不动起始页号，整本连续编号")
    void noPageStartByDefault() throws Exception {
        try (XSSFWorkbook wb = read(exporter.export(List.of(sheetWith(new int[]{100})), new PageConfigDTO()))) {
            assertFalse(wb.getSheetAt(0).getPrintSetup().getUsePage());
        }
    }

    @Test
    @DisplayName("封面不印页码时，正文那张的起始页号钉成 1（Excel 的 &P 是跨 sheet 连续的）")
    void firstNumberedSheetRestartsExcelPageNumbering() throws Exception {
        PageConfigDTO plain = new PageConfigDTO();
        PageConfigDTO numbered = new PageConfigDTO();
        numbered.getFooter().setCenter("第 ${page} 页");
        List<PageConfigDTO> configs = List.of(plain, numbered, plain);

        List<Map<String, Object>> sheets = List.of(
                sheetWith("封面", new int[]{100}), sheetWith("数据", new int[]{100}),
                sheetWith("总结", new int[]{100}));
        try (XSSFWorkbook wb = read(exporter.export(sheets, configs::get))) {
            assertFalse(wb.getSheetAt(0).getPrintSetup().getUsePage(), "封面本来就不印页码");
            PrintSetup body = wb.getSheetAt(1).getPrintSetup();
            assertTrue(body.getUsePage(), "正文该从第 1 页重新数，否则印出来是「第 2 页」");
            assertEquals(1, body.getPageStart());
            assertFalse(wb.getSheetAt(2).getPrintSetup().getUsePage(), "总结不印页码，不必动");
        }
    }

    @Test
    @DisplayName("单据里有封面时，起始页号钉在这份单据第一张印页码的 sheet 上")
    void pageStartPinsFirstNumberedSheetOfEachDocument() throws Exception {
        PageConfigDTO plain = new PageConfigDTO();
        PageConfigDTO numbered = new PageConfigDTO();
        numbered.getFooter().setCenter("第 ${page} 页");
        List<PageConfigDTO> configs = List.of(plain, numbered, plain, numbered);

        // 2 条数据 × 2 张模板（封面 + 正文），单据的起点落在封面上
        Map<String, Object> cover1 = sheetWith("封面1", new int[]{100});
        cover1.put("mzDocBreaks", List.of(0));
        Map<String, Object> body1 = sheetWith("正文1", new int[]{100});
        body1.put("mzDocBreaks", List.of());
        Map<String, Object> cover2 = sheetWith("封面2", new int[]{100});
        cover2.put("mzDocBreaks", List.of(0));
        Map<String, Object> body2 = sheetWith("正文2", new int[]{100});
        body2.put("mzDocBreaks", List.of());

        try (XSSFWorkbook wb = read(exporter.export(List.of(cover1, body1, cover2, body2), configs::get))) {
            assertFalse(wb.getSheetAt(0).getPrintSetup().getUsePage(), "封面不印页码，钉在它身上没意义");
            assertTrue(wb.getSheetAt(1).getPrintSetup().getUsePage(), "正文该从第 1 页数起");
            assertFalse(wb.getSheetAt(2).getPrintSetup().getUsePage());
            assertTrue(wb.getSheetAt(3).getPrintSetup().getUsePage(), "下一条数据的正文重新从 1 数");
        }
    }

    @Test
    @DisplayName("第一张就印页码时不动起始页号 —— Excel 本来就从 1 数")
    void noPinWhenFirstSheetIsNumbered() throws Exception {
        PageConfigDTO numbered = new PageConfigDTO();
        numbered.getFooter().setCenter("第 ${page} 页");

        try (XSSFWorkbook wb = read(exporter.export(
                List.of(sheetWith("数据", new int[]{100}), sheetWith("附录", new int[]{100})), numbered))) {
            assertFalse(wb.getSheetAt(0).getPrintSetup().getUsePage());
            assertFalse(wb.getSheetAt(1).getPrintSetup().getUsePage());
        }
    }

    private Map<String, Object> sheetWith(int[] designed) {
        return sheetWith("Sheet1", designed);
    }

    private Map<String, Object> sheetWith(String name, int[] designed) {
        Map<String, Object> columnlen = new LinkedHashMap<>();
        List<Map<String, Object>> celldata = new ArrayList<>();
        for (int c = 0; c < designed.length; c++) {
            columnlen.put(String.valueOf(c), designed[c]);
            celldata.add(cell(0, c));
        }
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", name);
        sheet.put("celldata", celldata);
        sheet.put("config", Map.of("columnlen", columnlen, "rowlen", Map.of(),
                "merge", Map.of(), "borderInfo", List.of()));
        return sheet;
    }

    private Map<String, Object> cell(int r, int c) {
        String text = "c" + c;
        return Map.of("r", r, "c", c, "v", Map.of("v", text, "m", text));
    }

    private Map<String, Object> styledCell(int r, int c, String text, Map<String, Object> style) {
        Map<String, Object> v = new LinkedHashMap<>(style);
        v.put("v", text);
        v.put("m", text);
        return Map.of("r", r, "c", c, "v", v);
    }

    /** 带样式属性的单元格（style 里放 FortuneSheet 的 tb/ht/bg 之类） */
    private Map<String, Object> styledCell(int r, int c, Map<String, Object> style) {
        String text = "这是一段需要换行的很长的文本";
        Map<String, Object> v = new LinkedHashMap<>(style);
        v.put("v", text);
        v.put("m", text);
        return Map.of("r", r, "c", c, "v", v);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cells(Map<String, Object> sheetData) {
        return (List<Map<String, Object>>) sheetData.get("celldata");
    }

    private XSSFWorkbook read(byte[] xlsx) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(xlsx));
    }

    /* ---------------------------------- 边框 ---------------------------------- */

    @Test
    @DisplayName("边框颜色照搬 borderInfo：设成白色导出就是白色，不能落成 auto（下游按黑色画）")
    void borderColorIsPreserved() throws Exception {
        Map<String, Object> sheet = sheetWith(new int[]{100, 100});
        borderInfo(sheet, borderRange("border-all", "1", "#FFFFFF", 0, 0, 0, 1));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            XSSFCellStyle style = wb.getSheetAt(0).getRow(0).getCell(0).getCellStyle();
            assertEquals(BorderStyle.THIN, style.getBorderTop());
            // 颜色写不进去的话 PdfExporter#line / WordExporter#border 都会退回黑色
            assertEquals("FFFFFF", rgb(style.getTopBorderXSSFColor()), "上边框该是白色");
            assertEquals("FFFFFF", rgb(style.getBottomBorderXSSFColor()), "下边框该是白色");
            assertEquals("FFFFFF", rgb(style.getLeftBorderXSSFColor()), "左边框该是白色");
            assertEquals("FFFFFF", rgb(style.getRightBorderXSSFColor()), "右边框该是白色");
        }
    }

    @Test
    @DisplayName("后画的边框覆盖先画的：黑框上再刷一层白，出来的是白色而不是黑色")
    void laterBorderWins() throws Exception {
        Map<String, Object> sheet = sheetWith(new int[]{100, 100});
        borderInfo(sheet,
                borderRange("border-all", "1", "#000000", 0, 0, 0, 1),
                borderRange("border-all", "1", "#FFFFFF", 0, 0, 0, 1));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            XSSFCellStyle style = wb.getSheetAt(0).getRow(0).getCell(0).getCellStyle();
            assertEquals("FFFFFF", rgb(style.getTopBorderXSSFColor()), "后刷的白色该覆盖先画的黑色");
        }
    }

    @Test
    @DisplayName("把某一格刷白时，上一格朝向它的那条黑边也跟着变白（Excel 里两格共用一条线）")
    void whiteBorderAlsoClearsTheNeighbourEdge() throws Exception {
        Map<String, Object> sheet = sheetWith(new int[]{100});
        cells(sheet).add(cell(1, 0));
        borderInfo(sheet,
                borderRange("border-all", "1", "#000000", 0, 1, 0, 0),
                // 只把第 2 行刷白：第 1 行的下边框还是黑的，不一起改的话那条线照旧印出来
                borderRange("border-all", "1", "#FFFFFF", 1, 1, 0, 0));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            XSSFSheet s = wb.getSheetAt(0);
            assertEquals("FFFFFF", rgb(s.getRow(1).getCell(0).getCellStyle().getTopBorderXSSFColor()));
            assertEquals("FFFFFF", rgb(s.getRow(0).getCell(0).getCellStyle().getBottomBorderXSSFColor()),
                    "上一格朝下的那条边没跟着改，打印出来还是一条黑线");
            // 没挨着的那几条边不受影响
            assertEquals("000000", rgb(s.getRow(0).getCell(0).getCellStyle().getTopBorderXSSFColor()));
        }
    }

    @Test
    @DisplayName("border-none 是「没有边框」，不能反过来画出一圈线")
    void borderNoneDrawsNothing() throws Exception {
        Map<String, Object> sheet = sheetWith(new int[]{100, 100});
        borderInfo(sheet,
                borderRange("border-all", "1", "#000000", 0, 0, 0, 1),
                borderRange("border-none", "1", "#000000", 0, 0, 0, 0));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            XSSFCellStyle style = wb.getSheetAt(0).getRow(0).getCell(0).getCellStyle();
            assertEquals(BorderStyle.NONE, style.getBorderTop(), "被设成「无边框」的格子不该有边框");
            assertEquals(BorderStyle.NONE, style.getBorderBottom());
            assertEquals(BorderStyle.NONE, style.getBorderLeft());
            assertEquals(BorderStyle.NONE, style.getBorderRight());
            // 右边那格没被清，仍该留着
            assertEquals(BorderStyle.THIN, wb.getSheetAt(0).getRow(0).getCell(1).getCellStyle().getBorderTop());
        }
    }

    @Test
    @DisplayName("只画一条边就只出一条边，线型按 FortuneSheet 的码值翻成 Excel 的线型")
    void singleEdgeAndLineStyle() throws Exception {
        Map<String, Object> sheet = sheetWith(new int[]{100, 100});
        // style=13 是粗实线
        borderInfo(sheet, borderRange("border-bottom", "13", "#FF0000", 0, 0, 0, 1));

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            XSSFCellStyle style = wb.getSheetAt(0).getRow(0).getCell(0).getCellStyle();
            assertEquals(BorderStyle.THICK, style.getBorderBottom());
            assertEquals("FF0000", rgb(style.getBottomBorderXSSFColor()));
            assertEquals(BorderStyle.NONE, style.getBorderTop(), "只画了下边框，上边不该有线");
            assertEquals(BorderStyle.NONE, style.getBorderLeft());
        }
    }

    @Test
    @DisplayName("rangeType=cell 的逐格边框（复制粘贴带过来的那种）也照样认")
    void cellRangeTypeIsHonoured() throws Exception {
        Map<String, Object> sheet = sheetWith(new int[]{100});
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("rangeType", "cell");
        entry.put("value", Map.of("row_index", 0, "col_index", 0,
                "t", Map.of("style", "1", "color", "#FFFFFF"),
                "b", Map.of("style", "8", "color", "#0000FF")));
        borderInfo(sheet, entry);

        try (XSSFWorkbook wb = read(exporter.export(List.of(sheet), new PageConfigDTO()))) {
            XSSFCellStyle style = wb.getSheetAt(0).getRow(0).getCell(0).getCellStyle();
            assertEquals(BorderStyle.THIN, style.getBorderTop());
            assertEquals("FFFFFF", rgb(style.getTopBorderXSSFColor()));
            assertEquals(BorderStyle.MEDIUM, style.getBorderBottom());
            assertEquals("0000FF", rgb(style.getBottomBorderXSSFColor()));
            assertEquals(BorderStyle.NONE, style.getBorderLeft(), "value 里没给 l，这条边就不该有");
        }
    }

    /** 把 borderInfo 挂到一张表上（sheetWith 建出来的 config 是不可变的 Map.of） */
    private void borderInfo(Map<String, Object> sheet, Map<String, Object>... entries) {
        Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) sheet.get("config"));
        config.put("borderInfo", List.of(entries));
        sheet.put("config", config);
    }

    /** FortuneSheet 工具栏画边框时写进 config.borderInfo 的那种条目 */
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

    /** XSSFColor -> "RRGGBB"，取不到返回 null（= 写进 xlsx 的是 auto） */
    private String rgb(XSSFColor color) {
        byte[] bytes = color == null ? null : color.getRGB();
        if (bytes == null || bytes.length < 3) {
            return null;
        }
        return String.format("%02X%02X%02X", bytes[0] & 0xff, bytes[1] & 0xff, bytes[2] & 0xff);
    }
}
