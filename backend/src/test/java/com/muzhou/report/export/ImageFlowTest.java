package com.muzhou.report.export;

import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.dto.PageConfigDTO;
import com.muzhou.report.dto.RenderResultDTO;
import com.muzhou.report.dto.ReportContentDTO;
import com.muzhou.report.engine.CellFormatter;
import com.muzhou.report.engine.ExpandProcessor;
import com.muzhou.report.engine.FormulaEvaluator;
import com.muzhou.report.engine.ReportRenderEngine;
import com.muzhou.report.engine.TemplateParser;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片单元格的整条链路：渲染引擎 -> celldata 的 {@code v.mzImg} -> xlsx 的锚定图片 -> PDF / Word。
 *
 * <p>单独测这一条是因为**两半是在 `v.mzImg` 这个键上对接的**：引擎那半只管把地址挂上去，
 * 导出那半只认这个键。任何一边改了名字或形态，各自的单元测试都还是绿的，只有这条会红。
 */
class ImageFlowTest {

    private final ExcelExporter excel = new ExcelExporter(new MzProperties());
    private final PdfExporter pdf = new PdfExporter(new MzProperties());
    private final WordExporter word = new WordExporter();

    /** 三行数据，每行一张图（base64 裸串，由引擎补成 data URI）。 */
    private RenderResultDTO render() {
        TemplateParser parser = new TemplateParser();
        CellFormatter formatter = new CellFormatter();
        FormulaEvaluator evaluator = new FormulaEvaluator();
        evaluator.init();
        ReportRenderEngine engine = new ReportRenderEngine(parser,
                new ExpandProcessor(formatter, evaluator, new MzProperties()), new MzProperties());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "产品" + i);
            row.put("pic", TestImages.PNG_1X1);
            rows.add(row);
        }
        return engine.render(content(), Map.of(), (code, params) -> rows);
    }

    /** 模板：第 0 行表头，第 1 行是「名称 + 图片」的数据行带。 */
    private ReportContentDTO content() {
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "产品图册");
        sheet.put("id", "sheet_1");
        sheet.put("celldata", List.of(
                cell(0, 0, "名称"), cell(0, 1, "图片"),
                cell(1, 0, "#{goods.name}"), cell(1, 1, "#{goods.pic}")));
        sheet.put("config", Map.of("columnlen", Map.of("0", 100, "1", 120),
                "rowlen", Map.of("1", 80)));

        CellConfigDTO name = new CellConfigDTO();
        name.setType("data");
        name.setDatasetCode("goods");
        name.setField("name");
        name.setExpandType("down");

        CellConfigDTO pic = new CellConfigDTO();
        pic.setType("base64");
        pic.setDatasetCode("goods");
        pic.setField("pic");
        pic.setExpandType("down");

        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(List.of(sheet));
        content.setCellConfigs(Map.of("0_1_0", name, "0_1_1", pic));
        return content;
    }

    private Map<String, Object> cell(int r, int c, String text) {
        return Map.of("r", r, "c", c, "v", Map.of("v", text, "m", text));
    }

    @Test
    @DisplayName("图片行带扩展成 3 行，导出的 xlsx 里就是 3 张锚在各自格子上的图片")
    void imagesExpandWithTheBandAndReachXlsx() throws Exception {
        byte[] xlsx = excel.export(render().getSheets(), new PageConfigDTO());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            List<XSSFShape> shapes = wb.getSheetAt(0).getDrawingPatriarch().getShapes();
            assertEquals(3, shapes.size(), "3 行数据该出 3 张图");
            // 同一张图片只存一份二进制，3 个锚点共用
            assertEquals(1, wb.getAllPictures().size());

            for (int i = 0; i < 3; i++) {
                ClientAnchor anchor = ((XSSFPicture) shapes.get(i)).getClientAnchor();
                assertEquals(1, anchor.getCol1(), "图片列是第 2 列");
                assertEquals(1 + i, anchor.getRow1(), "第 " + (i + 1) + " 张该锚在第 " + (2 + i) + " 行");
                // 行高 80px、列宽 120px，方图等比例装进去 = 80×80，整个落在这一行里
                assertEquals(1 + i, anchor.getRow2(), "图片不该伸到下一行去");
                double height = (anchor.getDy2() - anchor.getDy1()) / 9525d;
                assertEquals(80, height, 2, "高度该顶满 80px 的行");
            }
        }
    }

    @Test
    @DisplayName("同一份 xlsx 转 PDF 与 Word 都带上图片")
    void imagesSurviveIntoPdfAndWord() throws Exception {
        byte[] xlsx = excel.export(render().getSheets(), new PageConfigDTO());

        PdfReader reader = new PdfReader(pdf.convert(xlsx));
        assertTrue(reader.getNumberOfPages() >= 1);
        reader.close();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(word.convert(xlsx)))) {
            // 三处引用的是同一张图，POI 按内容去重只存一份图片部件，所以数的是「有几处引用」
            assertEquals(1, doc.getAllPictures().size(), "同一张图只该存一份");
            long refs = doc.getTables().get(0).getRows().stream()
                    .flatMap(row -> row.getTableCells().stream())
                    .flatMap(cell -> cell.getParagraphs().stream())
                    .flatMap(p -> p.getRuns().stream())
                    .mapToLong(run -> run.getEmbeddedPictures().size())
                    .sum();
            assertEquals(3, refs, "3 行数据该在 Word 的表格里出 3 处图片");
        }
    }
}
