package com.muzhou.report.export;

import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.PageConfigDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfObject;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PdfExporter 单元测试（纯 POJO，不启动 Spring）。
 *
 * <p>链路是 渲染结果 -> ExcelExporter -> xlsx -> PdfExporter -> pdf，所以这里连着 ExcelExporter
 * 一起测：盯的是「PDF 出纸和导出的 Excel 是同一回事」—— 纸张、方向、打印区域、按纸张高度分页。
 */
class PdfExporterTest {

    private final ExcelExporter excel = new ExcelExporter(new MzProperties());

    private final PdfExporter pdf = new PdfExporter(new MzProperties());

    @Test
    @DisplayName("小表导出成 1 页 A4 纵向 PDF，中文内容能读回来")
    void singlePageKeepsContent() throws Exception {
        byte[] bytes = pdf.convert(excel.export(List.of(sheet(3, 3)), new PageConfigDTO()));

        assertTrue(bytes.length > 0);
        assertEquals("%PDF", new String(bytes, 0, 4));

        PdfReader reader = new PdfReader(bytes);
        assertEquals(1, reader.getNumberOfPages());

        Rectangle page = reader.getPageSize(1);
        assertTrue(page.getWidth() < page.getHeight(), "默认应是纵向 A4");
        assertEquals(595, Math.round(page.getWidth()));

        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertTrue(text.contains("销售额"), "表头应出现在 PDF 里，实际: " + text);
        assertTrue(text.contains("北京"), "数据应出现在 PDF 里，实际: " + text);
        reader.close();
    }

    @Test
    @DisplayName("行数超过一页高度时按纸张分页，不是把内容压成一页")
    void longSheetPaginates() throws Exception {
        // A4 纵向去掉上下 10mm 页边距后约 785pt，每行 19px≈14.25pt，200 行必然不止一页
        byte[] bytes = pdf.convert(excel.export(List.of(sheet(200, 3)), new PageConfigDTO()));

        PdfReader reader = new PdfReader(bytes);
        assertTrue(reader.getNumberOfPages() > 1, "200 行应该分成多页，实际 " + reader.getNumberOfPages() + " 页");
        reader.close();
    }

    @Test
    @DisplayName("横向纸张导出后页面宽高互换")
    void landscapeSwapsPageSize() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setOrientation("landscape");

        PdfReader reader = new PdfReader(pdf.convert(excel.export(List.of(sheet(3, 3)), cfg)));
        Rectangle page = reader.getPageSize(1);
        assertTrue(page.getWidth() > page.getHeight(), "横向时页面应更宽");
        assertEquals(842, Math.round(page.getWidth()));
        reader.close();
    }

    @Test
    @DisplayName("A3 纸张按 A3 出纸")
    void paperSizeIsHonored() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setPaperSize("A3");

        PdfReader reader = new PdfReader(pdf.convert(excel.export(List.of(sheet(3, 3)), cfg)));
        assertEquals(842, Math.round(reader.getPageSize(1).getWidth()));
        reader.close();
    }

    @Test
    @DisplayName("设了打印区域就只导出区域内的行，区域外的数据不出现在 PDF 里")
    void printAreaLimitsContent() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        // 只要前两行（表头 + 第一条数据），第 3 行起不该出现
        cfg.setPrintArea("A1:C2");

        PdfReader reader = new PdfReader(pdf.convert(excel.export(List.of(sheet(5, 3)), cfg)));
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertTrue(text.contains("北京"), "区域内的第 1 行数据应保留，实际: " + text);
        assertTrue(!text.contains("城市3"), "区域外的数据不该出现，实际: " + text);
        reader.close();
    }

    @Test
    @DisplayName("打印设置按 sheet 单独生效时，PDF 里每一页各按各的纸张出纸")
    void perSheetPageSetup() throws Exception {
        PageConfigDTO wide = new PageConfigDTO();
        wide.setPaperSize("A3");
        wide.setOrientation("landscape");

        byte[] xlsx = excel.export(List.of(sheet("宽表", 3, 3), sheet("窄表", 3, 3)),
                i -> i == 0 ? wide : new PageConfigDTO());

        PdfReader reader = new PdfReader(pdf.convert(xlsx));
        assertEquals(2, reader.getNumberOfPages());
        // 第 1 张 A3 横向 = 1191×842，第 2 张 A4 纵向 = 595×842
        assertEquals(1191, Math.round(reader.getPageSize(1).getWidth()));
        assertEquals(595, Math.round(reader.getPageSize(2).getWidth()));
        reader.close();
    }

    @Test
    @DisplayName("数据不从 A 列开始时也按 A1 起算：内容宽度含左边的空列，与 Excel 一样占位")
    void contentRangeStartsAtA1() throws Exception {
        // 数据落在 E..N（10 列 × 100px = 265mm）。从 A 算是 14 列 = 370mm，A4 横向可打印宽 277mm
        // 装不下 —— 该像 Excel 一样切成两页宽；从第一个有值的列算就只有 265mm，会缩成一页。
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setOrientation("landscape");
        // 关掉限宽兜底，否则 fitToWidth 会把宽表压成一页宽，页数就不再是判别信号
        cfg.setLimitWidth(false);

        byte[] bytes = pdf.convert(excel.export(List.of(offsetSheet(3, 4, 13, 100)), cfg));

        PdfReader reader = new PdfReader(bytes);
        assertEquals(2, reader.getNumberOfPages(),
                "左边 4 个空列该照样占位，内容宽度按 A 列起算 —— 仍是 1 页说明把表整体挪到左页边距了");
        reader.close();
    }

    @Test
    @DisplayName("自动换行的格子按格宽折行；没开换行的仍然整段压到相邻空格上")
    void wrapTextIsBrokenIntoLines() throws Exception {
        String longText = "这是一段很长的说明文字用来验证换行一二三四五六七八九十";
        byte[] bytes = pdf.convert(excel.export(List.of(wrapSheet(longText)), new PageConfigDTO()));

        PdfReader reader = new PdfReader(bytes);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();

        assertEquals(1, text.lines().filter(l -> l.trim().equals(longText)).count(),
                "整段一行的只该有「没开自动换行」那一格，实际:\n" + text);
        assertTrue(text.lines().anyMatch(l -> {
            String s = l.trim();
            return !s.isEmpty() && s.length() < longText.length() && longText.startsWith(s);
        }), "开了自动换行的那一格该被折成若干短行，实际:\n" + text);
    }

    @Test
    @DisplayName("xlsx 的行高按估算偏矮时，PDF 按真字体把行撑高，不把折出来的行切掉")
    void wrapRowsGrowToFitTheRealFont() throws Exception {
        // 破折号在 ExcelExporter 的估算里算半个字号（不在它的 CJK 区间表里），中文字体里却是全角，
        // 于是实际折出的行数正好是估算的两倍 —— 不撑高就会把上下两头的字切掉。
        // 估算下 10 行合计约 760pt 塞得进一页（A4 去掉 10mm 上下边距约 785pt），
        // 按真字体量则要 1300pt 上下，必然溢出到第二页。
        byte[] xlsx = excel.export(List.of(dashWrapSheet(10, 60)), new PageConfigDTO());

        assertTrue(pageCount(xlsx) > 1,
                "行按真字体撑高后该放不进一页，仍是一页说明 PDF 用的还是估出来的矮行高");
    }

    @Test
    @DisplayName("顶端标题行：第 2 页起也带表头，而且表头只画一遍、不占用两次高度")
    void titleRowsRepeatOnEveryPage() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setTitleRows("1:1");

        PdfReader reader = new PdfReader(pdf.convert(excel.export(List.of(sheet(200, 3)), cfg)));
        int pages = reader.getNumberOfPages();
        assertTrue(pages > 1, "200 行该分成多页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= pages; p++) {
            String text = extractor.getTextFromPage(p);
            assertTrue(text.contains("销售额"), "第 " + p + " 页该重复表头，实际:\n" + text);
        }
        // 表头每页占掉一行，所以页数只会比不配标题行时多一点点；差太多说明行流没把标题行摘干净
        int plain = pageCount(excel.export(List.of(sheet(200, 3)), new PageConfigDTO()));
        assertTrue(pages >= plain && pages <= plain + 1,
                "重复表头只该多占每页一行，实际 " + pages + " 页 / 不重复时 " + plain + " 页");
        reader.close();
    }

    @Test
    @DisplayName("标题行不在内容最上面（或盖住了全部内容）时忽略，不影响出纸")
    void titleRowsElsewhereAreIgnored() throws Exception {
        PageConfigDTO middle = new PageConfigDTO();
        middle.setTitleRows("3:4");
        PageConfigDTO all = new PageConfigDTO();
        all.setTitleRows("1:200");

        int plain = pageCount(excel.export(List.of(sheet(200, 3)), new PageConfigDTO()));
        assertEquals(plain, pageCount(excel.export(List.of(sheet(200, 3)), middle)));
        assertEquals(plain, pageCount(excel.export(List.of(sheet(200, 3)), all)));
    }

    @Test
    @DisplayName("标题行与按单据取名叠加：首行被标题带占掉后，第一份单据的 ${sheet} 不丢名")
    void titleRowsKeepFirstDocName() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setTitleRows("1:1");
        cfg.getFooter().setRight("单据：${sheet}");

        // perRowPage 拼出来的那张：3 份单据各 3 行，第 1 份的起点(0)落在标题带上
        Map<String, Object> concat = sheet(9, 3);
        concat.put("mzRowBreaks", List.of(3, 6));

        PdfReader reader = new PdfReader(pdf.convert(excel.export(List.of(concat), cfg), i -> cfg,
                i -> List.of(0, 3, 6), i -> List.of("单A", "单B", "单C")));
        assertEquals(3, reader.getNumberOfPages());

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        assertTrue(extractor.getTextFromPage(1).contains("单据：单A"),
                "第 1 份单据的名字该钉到正文首行上，而不是丢在标题带里退回工作表名");
        assertTrue(extractor.getTextFromPage(2).contains("单据：单B"));
        assertTrue(extractor.getTextFromPage(3).contains("单据：单C"));
        reader.close();
    }

    @Test
    @DisplayName("标题行占掉半页以上时不生效 —— 否则正文被挤得没地方放")
    void hugeTitleRowsAreIgnored() throws Exception {
        // 第 1 行拖到 700px：xlsx 里夹到 409.5pt，仍超过 A4 正文（约 785pt）的一半
        Map<String, Object> tall = sheet(200, 3);
        tall.put("config", Map.of("columnlen", Map.of("0", 110, "1", 110, "2", 110),
                "rowlen", Map.of("0", 700)));
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setTitleRows("1:1");

        assertEquals(pageCount(excel.export(List.of(tall), new PageConfigDTO())),
                pageCount(excel.export(List.of(tall), cfg)),
                "占掉半页以上的标题行该按没配处理，页数不变");
    }

    @Test
    @DisplayName("比一页还高的行横着劈开跨页印：表头那页不会只剩表头，超出的文字也不丢")
    void oversizedRowIsSlicedAcrossPages() throws Exception {
        // 备注格里一段长文塞进 100px 宽的列，折出来比一页正文（A4 纵向约 785pt）高好几倍
        String note = "起" + "一二三四五六七八九十".repeat(60) + "止";
        byte[] xlsx = excel.export(List.of(noteSheet(note)), new PageConfigDTO());

        PdfReader reader = new PdfReader(pdf.convert(xlsx));
        int pages = reader.getNumberOfPages();
        assertTrue(pages > 1, "这一格该占好几页，实际 " + pages + " 页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        String first = extractor.getTextFromPage(1);
        assertTrue(first.contains("城市"), "第 1 页该有表头，实际:\n" + first);
        // 整行挪到第 2 页的老做法在这里就露馅了：第 1 页只剩表头，大片空白
        assertTrue(first.contains("一二三四"), "备注该从第 1 页表头下面就开始印，实际:\n" + first);
        assertTrue(first.contains("起"), "备注的开头在第 1 页");
        assertTrue(!first.contains("止"), "备注的结尾该在后面的页上，第 1 页就有说明整段被重复画了一遍");
        assertTrue(extractor.getTextFromPage(pages).contains("止"),
                "最后一页该印到备注的结尾，印不出来说明超高行的文字被裁掉了");
        reader.close();
    }

    @Test
    @DisplayName("劈开超高行时切口落在两行文字之间，同一行字不会两页各露半个")
    void sliceCutsBetweenTextLines() throws Exception {
        // 每个字都不一样：重复的填充字会让「上下两页各一行」看着像同一行，断言就失灵了
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            note.append((char) ('一' + i));
        }
        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(List.of(noteSheet(note.toString())), new PageConfigDTO())));
        int pages = reader.getNumberOfPages();
        assertTrue(pages > 1, "前提：这一格该占好几页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p < pages; p++) {
            String last = lastLine(extractor.getTextFromPage(p));
            String first = firstLine(extractor.getTextFromPage(p + 1));
            assertTrue(!last.isEmpty() && !last.equals(first),
                    "第 " + p + " 页末行[" + last + "]和第 " + (p + 1) + " 页首行[" + first
                            + "]是同一行字 —— 切口切在了这行字的中间");
        }
        reader.close();
    }

    private String firstLine(String text) {
        return text.lines().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse("");
    }

    private String lastLine(String text) {
        return text.lines().map(String::trim).filter(s -> !s.isEmpty()).reduce("", (a, b) -> b);
    }

    @Test
    @DisplayName("超高行只劈自己：装得进一页的行照旧整行挪到下一页")
    void rowsThatFitAPageAreNeverSliced() {
        // 一页 100pt：前两行各 40pt，第三行 40pt 放不下 -> 整行挪走，不劈
        assertEquals(List.of("0-1@0", "2-2@0"),
                blocks(PdfExporter.Geom.splitRows(new float[]{40, 40, 40}, 100, Set.of())));

        // 第二行 250pt 比一页还高：从第一行下面剩的 60pt 开始劈，一页页接着印，
        // 最后剩的 90pt 那页还能接着放下第三行
        assertEquals(List.of("0-1@0", "1-1@60", "1-2@160"),
                blocks(PdfExporter.Geom.splitRows(new float[]{40, 250, 10}, 100, Set.of())));

        // 手动分页符照旧无条件断开
        assertEquals(List.of("0-0@0", "1-1@0"),
                blocks(PdfExporter.Geom.splitRows(new float[]{40, 40}, 100, Set.of(1))));
    }

    /* ------------------------------ 超高行的续行 ------------------------------ */

    @Test
    @DisplayName("接着往下印的那一页不再重复前面印过的文字（不然 PDF 里每页都带着一整段）")
    void continuationPageDoesNotRepeatThePrintedHead() throws Exception {
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            note.append((char) ('一' + i));
        }
        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(List.of(noteSheet(note.toString())), new PageConfigDTO())));
        int pages = reader.getNumberOfPages();
        assertTrue(pages > 1, "前提：这一格该占好几页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        // 第 1 页的开头是备注的第一行；它不该再出现在后面任何一页上
        String head = firstLine(extractor.getTextFromPage(1).lines()
                .filter(s -> s.contains("一")).findFirst().orElse("一"));
        for (int p = 2; p <= pages; p++) {
            assertTrue(!extractor.getTextFromPage(p).contains(head),
                    "第 " + p + " 页里还带着第 1 页印过的[" + head + "] —— 已经印过的文字仍写在内容流里，"
                            + "画在了纸外面和页边距上");
        }
        reader.close();
    }

    @Test
    @DisplayName("超高行跨页时，没有整行文字画在正文区外面（画出去的会露在页边距里、还让 PDF 每页都带一整段）")
    void noTextIsDrawnOutsideTheBodyArea() throws Exception {
        // 字面框按出纸用的那一款字体量，不按「0.85 个字号」这类常数估：中文字体的字面框
        // 各家差着一截，估出来的判定会在零点几磅上翻车（CI 上是文泉驿微米黑，开发机多半是
        // 微软雅黑，同一份 PDF 一边绿一边红）。全局字面框比任何一个字都大，往「放过」那边偏
        BaseFont font = pdf.font();
        float inkTop = font.getFontDescriptor(BaseFont.BBOXURY, 11);
        float inkBottom = font.getFontDescriptor(BaseFont.BBOXLLY, 11);

        // 一行里好几列长短不一的长文字 —— 真实报表（询价单那种宽表）就是这个形状
        for (String mode : List.of("slice", "split")) {
            PageConfigDTO cfg = new PageConfigDTO();
            cfg.setRowOverflow(mode);
            byte[] bytes = pdf.convert(excel.export(List.of(multiNoteSheet()), cfg), i -> cfg);

            PdfReader reader = new PdfReader(bytes);
            assertTrue(reader.getNumberOfPages() > 2, "前提：该占好几页");
            // A4 纵向、上下页边距各 10mm(28.35pt)；页尾没配，正文就是这一段
            float top = 842 - 28.35f;
            for (int p = 1; p <= reader.getNumberOfPages(); p++) {
                for (float y : textBaselines(reader, p)) {
                    // 横切（slice）时切口不躲文字行，一行字被劈成上下两半、两页各画半个（见
                    // drawText），所以判的是「字面框与正文区还有交集」而不是「整个装在里面」。
                    // 整行落在外面的才是 bug —— clipTop 换回 top 时每页多出几十行，最远画到 y=1221
                    assertTrue(y + inkTop > 28.35f && y + inkBottom < top,
                            mode + " 模式第 " + p + " 页有整行文字画在正文区(" + 28.35f + ".." + top
                                    + ")外面：基线 y=" + y);
                }
            }
            reader.close();
        }
    }

    /** 一页上所有文字的基线 y（`x y Tm` 的第二个数），页头页尾那两行除外 */
    private List<Float> textBaselines(PdfReader reader, int page) throws Exception {
        String content = new String(reader.getPageContent(page), java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "[-\\d.]+ [-\\d.]+ [-\\d.]+ [-\\d.]+ ([-\\d.]+) ([-\\d.]+) Tm")
                .matcher(content);
        List<Float> ys = new ArrayList<>();
        while (m.find()) {
            ys.add(Float.parseFloat(m.group(2)));
        }
        return ys;
    }

    /** 一行里 4 列都是长文字（长短不一），列宽 60px —— 折出来比一页高好几倍 */
    private Map<String, Object> multiNoteSheet() {
        List<Map<String, Object>> celldata = new ArrayList<>();
        Map<String, Object> columnlen = new LinkedHashMap<>();
        int[] lengths = {900, 500, 260, 120};
        for (int c = 0; c < lengths.length; c++) {
            columnlen.put(String.valueOf(c), 60);
            celldata.add(cell(0, c, "表头" + c, true));
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < lengths[c]; i++) {
                // 每列用不同的汉字段，各行文字互不相同
                text.append((char) ('一' + c * 1000 + i));
            }
            Map<String, Object> wrapped = cell(1, c, text.toString(), false);
            ((Map<String, Object>) wrapped.get("v")).put("tb", "2");
            celldata.add(wrapped);
        }

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "多列长备注");
        sheet.put("celldata", celldata);
        sheet.put("config", Map.of("columnlen", columnlen));
        return sheet;
    }

    @Test
    @DisplayName("续行：切口两边各是一个边框闭合的完整格子，页底多出一条封口的横线")
    void rowSplitClosesTheCellBox() throws Exception {
        String note = "起" + "一二三四五六七八九十".repeat(60) + "止";

        byte[] sliced = borderedNote(note, "slice");
        byte[] split = borderedNote(note, "split");

        // 横切时格子的下边框画在整行的底边上 —— 那在纸外面好几百磅，这一页上是个敞口的格子
        assertTrue(horizontalLines(sliced, 1).stream().noneMatch(y -> y > 0 && y < 60),
                "前提：横切时第 1 页底部没有封口的横线，实际 " + horizontalLines(sliced, 1));
        assertTrue(horizontalLines(split, 1).stream().anyMatch(y -> y > 0 && y < 60),
                "续行时第 1 页底部该有一条把格子封口的横线，实际 " + horizontalLines(split, 1));

        // 下一页同理：接着印的那一段顶边要封口，而横切时它在纸上边好几百磅的地方
        assertTrue(horizontalLines(split, 2).stream().anyMatch(y -> y > 780 && y < 820),
                "续行时第 2 页顶部该有一条封口的横线，实际 " + horizontalLines(split, 2));
        assertTrue(horizontalLines(sliced, 2).stream().noneMatch(y -> y > 780 && y < 820),
                "前提：横切时第 2 页顶部没有封口的横线，实际 " + horizontalLines(sliced, 2));
    }

    @Test
    @DisplayName("续行时超高行按顶对齐：同一行里那个短短的格子留在第一页，不会跑到第二页中间去")
    void rowSplitKeepsShortCellsOnTheFirstPage() throws Exception {
        // 这一行要有好几页高，「整行垂直居中」才明显落在后面的页上
        String note = "起" + "一二三四五六七八九十".repeat(200) + "止";
        Map<String, Object> sheet = noteSheet(note);
        // 同一行第 2 列放一个短短的序号：垂直居中的话它会落在这一行（好几页高）的正中间
        List<Map<String, Object>> celldata = new ArrayList<>((List<Map<String, Object>>) sheet.get("celldata"));
        celldata.add(cell(1, 1, "序号A1", false));
        sheet.put("celldata", celldata);

        PageConfigDTO split = new PageConfigDTO();
        split.setRowOverflow("split");
        PdfReader reader = new PdfReader(pdf.convert(excel.export(List.of(sheet), split), i -> split));
        assertTrue(new PdfTextExtractor(reader).getTextFromPage(1).contains("序号A1"),
                "序号该跟着这条数据的开头留在第 1 页");
        reader.close();

        // 对照：横切时它按整行垂直居中，落在中间那一页
        PdfReader old = new PdfReader(pdf.convert(excel.export(List.of(sheet), new PageConfigDTO())));
        assertTrue(!new PdfTextExtractor(old).getTextFromPage(1).contains("序号A1"),
                "前提：不开续行时它不在第 1 页（否则这个用例证明不了什么）");
        old.close();
    }

    @Test
    @DisplayName("续行不吃字：各页拼起来仍是完整的一段")
    void rowSplitKeepsEveryCharacter() throws Exception {
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            note.append((char) ('一' + i));
        }
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setRowOverflow("split");

        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(List.of(noteSheet(note.toString())), cfg), i -> cfg));
        StringBuilder all = new StringBuilder();
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            extractor.getTextFromPage(p).lines().map(String::trim).forEach(all::append);
        }
        for (int i = 0; i < 400; i++) {
            assertTrue(all.indexOf(String.valueOf((char) ('一' + i))) >= 0,
                    "第 " + i + " 个字丢了");
        }
        reader.close();
    }

    /** 一份带四周边框的长备注表，按给定的 rowOverflow 出 PDF */
    private byte[] borderedNote(String note, String rowOverflow) {
        Map<String, Object> sheet = noteSheet(note);
        Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) sheet.get("config"));
        config.put("borderInfo", List.of(Map.of(
                "rangeType", "range", "borderType", "border-all", "style", "1", "color", "#000000",
                "range", List.of(Map.of("row", List.of(0, 1), "column", List.of(0, 1))))));
        sheet.put("config", config);

        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setRowOverflow(rowOverflow);
        return pdf.convert(excel.export(List.of(sheet), cfg), i -> cfg);
    }

    /**
     * 一页上所有横线的 y 坐标（去重后从高到低）。
     *
     * <p>OpenPDF 把每条边框写成 {@code x1 y1 m  x2 y2 l  S}，这里就照这个形状把内容流扫一遍 ——
     * 「切口两边的格子封没封口」只有在画出来的线上才看得出来，文字提取看不到边框。
     */
    private List<Float> horizontalLines(byte[] pdfBytes, int page) throws Exception {
        PdfReader reader = new PdfReader(pdfBytes);
        String content = new String(reader.getPageContent(page), java.nio.charset.StandardCharsets.ISO_8859_1);
        reader.close();

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(-?[\\d.]+) (-?[\\d.]+) m\\s+(-?[\\d.]+) (-?[\\d.]+) l")
                .matcher(content);
        java.util.TreeSet<Float> ys = new java.util.TreeSet<>();
        while (m.find()) {
            float y1 = Float.parseFloat(m.group(2));
            float y2 = Float.parseFloat(m.group(4));
            if (Math.abs(y1 - y2) < 0.01f) {
                // 同一条线可能被相邻两格各画一次，四舍五入到 0.1pt 去重
                ys.add(Math.round(y1 * 10) / 10f);
            }
        }
        return new ArrayList<>(ys);
    }

    /** 把切页结果压成 "起行-止行@首行已印高度" 的串，好写断言 */
    private List<String> blocks(List<PdfExporter.RowBlock> blocks) {
        return blocks.stream()
                .map(b -> b.start() + "-" + b.end() + "@" + Math.round(b.skip()))
                .toList();
    }

    @Test
    @DisplayName("手动行分页符：两份短单据装得下同一页，也必须各占一页")
    void manualRowBreakForcesNewPage() throws Exception {
        // 6 行 A4 纵向一页绰绰有余，没有分页符就是 1 页
        assertEquals(1, pageCount(excel.export(List.of(sheet(6, 3)), new PageConfigDTO())),
                "前提：6 行本来只有 1 页");

        // 「每条数据一页」拼出来的 sheet 就长这样：第 2 份从第 3 行起
        Map<String, Object> concat = sheet(6, 3);
        concat.put("mzRowBreaks", List.of(3));

        assertEquals(2, pageCount(excel.export(List.of(concat), new PageConfigDTO())),
                "分页符没生效，两条数据被印在同一张纸上了");
    }

    @Test
    @DisplayName("分页符落在打印区域之外时忽略，不会多出空页")
    void rowBreakOutsidePrintAreaIsIgnored() throws Exception {
        Map<String, Object> s = sheet(6, 3);
        // 分页符落在打印区域（前 3 行）下面
        s.put("mzRowBreaks", List.of(5));
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.setPrintArea("A1:C3");

        assertEquals(1, pageCount(excel.export(List.of(s), cfg)));
    }

    @Test
    @DisplayName("每条数据一页：页码按单据重编，每份都是「第 1 页 / 共 1 页」")
    void pageNumbersRestartPerDocumentInOneSheet() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getFooter().setRight("第 ${page} 页 / 共 ${pages} 页");

        // perRowPage 拼出来的那张：3 份单据首尾相接，每份 3 行、各自一页
        Map<String, Object> concat = sheet(9, 3);
        concat.put("mzRowBreaks", List.of(3, 6));
        concat.put("mzDocBreaks", List.of(0, 3, 6));

        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(List.of(concat), cfg), i -> cfg, i -> List.of(0, 3, 6)));
        assertEquals(3, reader.getNumberOfPages(), "前提：3 份单据各占一页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= 3; p++) {
            assertTrue(extractor.getTextFromPage(p).contains("第 1 页 / 共 1 页"),
                    "第 " + p + " 页该按本单据编号，实际:\n" + extractor.getTextFromPage(p));
        }
        reader.close();
    }

    @Test
    @DisplayName("一份单据里的第 2 张模板接着数，下一条数据才从 1 重来")
    void pageNumbersContinueWithinOneDocument() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getFooter().setRight("第 ${page} 页 / 共 ${pages} 页");

        // 2 条数据 × 2 张模板：每份 3 行一页，单据的起点只有第 0 行和第 6 行
        Map<String, Object> concat = sheet(12, 3);
        concat.put("mzRowBreaks", List.of(3, 6, 9));
        List<Integer> docs = List.of(0, 6);
        concat.put("mzDocBreaks", docs);

        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(List.of(concat), cfg), i -> cfg, i -> docs));
        assertEquals(4, reader.getNumberOfPages(), "前提：4 份各占一页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        String[] want = {"第 1 页 / 共 2 页", "第 2 页 / 共 2 页", "第 1 页 / 共 2 页", "第 2 页 / 共 2 页"};
        for (int p = 1; p <= 4; p++) {
            assertTrue(extractor.getTextFromPage(p).contains(want[p - 1]),
                    "第 " + p + " 页该是「" + want[p - 1] + "」，实际:\n" + extractor.getTextFromPage(p));
        }
        reader.close();
    }

    @Test
    @DisplayName("每条数据一个 sheet：单据跨 sheet 时页码接着数，新单据那张才重来")
    void pageNumbersRestartAcrossSheets() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getFooter().setRight("第 ${page} 页 / 共 ${pages} 页");

        // perRow + 两张模板：结果 sheet 是「数据1的表1/表2、数据2的表1/表2」
        List<Map<String, Object>> sheets = List.of(
                sheet("S1", 3, 3), sheet("S2", 3, 3), sheet("S3", 3, 3), sheet("S4", 3, 3));
        // 一条数据的第 2 张模板不重编页码（空列表 = 延续上一张）
        List<List<Integer>> docBreaks = List.of(List.of(0), List.of(), List.of(0), List.of());

        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(sheets, cfg), i -> cfg, docBreaks::get));
        assertEquals(4, reader.getNumberOfPages());

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        String[] want = {"第 1 页 / 共 2 页", "第 2 页 / 共 2 页", "第 1 页 / 共 2 页", "第 2 页 / 共 2 页"};
        for (int p = 1; p <= 4; p++) {
            assertTrue(extractor.getTextFromPage(p).contains(want[p - 1]),
                    "第 " + p + " 页该是「" + want[p - 1] + "」，实际:\n" + extractor.getTextFromPage(p));
        }
        reader.close();
    }

    @Test
    @DisplayName("每条数据一页：页头里的 ${sheet} 逐份换成该单据的名字")
    void headerSheetNameFollowsEachDocument() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getHeader().setCenter("单号：${sheet}");

        // 3 份单据拼在同一张 sheet 里 —— 工作表名对三份都一样，只能靠 mzDocNames 分辨
        Map<String, Object> concat = sheet("汇总", 9, 3);
        concat.put("mzRowBreaks", List.of(3, 6));
        List<Integer> docs = List.of(0, 3, 6);
        List<String> names = List.of("SO-001", "SO-002", "SO-003");
        concat.put("mzDocBreaks", docs);
        concat.put("mzDocNames", names);

        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(List.of(concat), cfg), i -> cfg, i -> docs, i -> names));
        assertEquals(3, reader.getNumberOfPages(), "前提：3 份单据各占一页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= 3; p++) {
            String text = extractor.getTextFromPage(p);
            assertTrue(text.contains("单号：" + names.get(p - 1)),
                    "第 " + p + " 页该印本单据的名字，实际:\n" + text);
        }
        reader.close();
    }

    @Test
    @DisplayName("单据跨了两页时后一页跟着同一个名字，没配名字的仍印工作表名")
    void headerSheetNameSpansPagesAndFallsBackToSheetName() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getHeader().setCenter("单号：${sheet}");

        // 第 1 份 120 行（会自己跨页），第 2 份从第 120 行起且没配名字
        Map<String, Object> concat = sheet("汇总", 130, 3);
        concat.put("mzRowBreaks", List.of(120));
        List<Integer> docs = List.of(0, 120);
        List<String> names = List.of("SO-001", "");
        concat.put("mzDocBreaks", docs);
        concat.put("mzDocNames", names);

        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(List.of(concat), cfg), i -> cfg, i -> docs, i -> names));
        int pages = reader.getNumberOfPages();
        assertTrue(pages > 2, "前提：第 1 份该跨页，实际共 " + pages + " 页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p < pages; p++) {
            assertTrue(extractor.getTextFromPage(p).contains("单号：SO-001"),
                    "第 " + p + " 页仍是同一份单据，实际:\n" + extractor.getTextFromPage(p));
        }
        assertTrue(extractor.getTextFromPage(pages).contains("单号：汇总"),
                "没配名字的那一份退回工作表名，实际:\n" + extractor.getTextFromPage(pages));
        reader.close();
    }

    @Test
    @DisplayName("不印页码的 sheet（封面/总结）不占页数，正文从第 1 页数起")
    void unnumberedSheetsAreSkippedInNumbering() throws Exception {
        // 封面、总结都没设页头页尾；正文那张才写页码
        PageConfigDTO plain = new PageConfigDTO();
        PageConfigDTO numbered = new PageConfigDTO();
        numbered.getFooter().setRight("第 ${page} 页 / 共 ${pages} 页");

        // 封面 1 页、正文 2 页（150 行放不进一页）、总结 1 页
        List<Map<String, Object>> sheets = List.of(
                sheet("封面", 3, 3), sheet("数据", 150, 3), sheet("总结", 3, 3));
        List<PageConfigDTO> configs = List.of(plain, numbered, plain);

        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(sheets, configs::get), configs::get, null));
        int pages = reader.getNumberOfPages();
        assertTrue(pages > 3, "前提：正文该跨页，实际共 " + pages + " 页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        // 第 1 页是封面：没有页尾，什么都不该印
        assertTrue(!extractor.getTextFromPage(1).contains("页 / 共"), "封面不该有页码");
        // 正文从第 2 页开始，页码却要从 1 数起；共几页只数正文自己那几页
        int bodyPages = pages - 2;
        for (int p = 2; p < pages; p++) {
            String want = "第 " + (p - 1) + " 页 / 共 " + bodyPages + " 页";
            assertTrue(extractor.getTextFromPage(p).contains(want),
                    "第 " + p + " 页该是「" + want + "」，实际:\n" + extractor.getTextFromPage(p));
        }
        assertTrue(!extractor.getTextFromPage(pages).contains("页 / 共"), "总结不该有页码");
        reader.close();
    }

    @Test
    @DisplayName("一条数据出封面/正文/总结三张时，「共几页」只数这一条的正文页")
    void pageCountStaysPerDocumentWhenCoverIsSkipped() throws Exception {
        PageConfigDTO plain = new PageConfigDTO();
        PageConfigDTO numbered = new PageConfigDTO();
        numbered.getFooter().setRight("第 ${page} 页 / 共 ${pages} 页");

        // 2 条数据 × 3 张模板（封面 / 正文 / 总结），正文 150 行会跨成 2 页
        List<Map<String, Object>> sheets = List.of(
                sheet("封面1", 3, 3), sheet("正文1", 150, 3), sheet("总结1", 3, 3),
                sheet("封面2", 3, 3), sheet("正文2", 150, 3), sheet("总结2", 3, 3));
        List<PageConfigDTO> configs = List.of(plain, numbered, plain, plain, numbered, plain);
        // 单据的起点落在**封面**那张上（perRow 的产物就长这样）
        List<List<Integer>> docBreaks = List.of(
                List.of(0), List.of(), List.of(), List.of(0), List.of(), List.of());

        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(sheets, configs::get), configs::get, docBreaks::get));
        int pages = reader.getNumberOfPages();
        // 封面 1 + 正文 n + 总结 1，两条数据
        int bodyPages = (pages - 4) / 2;
        assertTrue(bodyPages > 1, "前提：正文该跨页，实际每条 " + bodyPages + " 页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int doc = 0; doc < 2; doc++) {
            // 每条数据占 1(封面) + bodyPages + 1(总结) 页，正文从第 2 页起
            int first = doc * (bodyPages + 2) + 2;
            for (int k = 0; k < bodyPages; k++) {
                String want = "第 " + (k + 1) + " 页 / 共 " + bodyPages + " 页";
                assertTrue(extractor.getTextFromPage(first + k).contains(want),
                        "第 " + (doc + 1) + " 条数据的正文第 " + (k + 1) + " 页该是「" + want
                                + "」，实际:\n" + extractor.getTextFromPage(first + k));
            }
        }
        reader.close();
    }

    @Test
    @DisplayName("只写了标题、没写页码的页头不让那张 sheet 占页数")
    void headerWithoutPageTokenDoesNotCount() throws Exception {
        PageConfigDTO cover = new PageConfigDTO();
        cover.getHeader().setCenter("2026 年度经营分析");
        PageConfigDTO body = new PageConfigDTO();
        body.getFooter().setRight("第 ${page} 页 / 共 ${pages} 页");

        List<PageConfigDTO> configs = List.of(cover, body);
        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(List.of(sheet("封面", 3, 3), sheet("数据", 3, 3)), configs::get),
                configs::get, null));

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        assertTrue(extractor.getTextFromPage(1).contains("2026 年度经营分析"), "封面的页头照旧要画");
        assertTrue(extractor.getTextFromPage(2).contains("第 1 页 / 共 1 页"),
                "封面印的是标题不是页码，不该占页数，实际:\n" + extractor.getTextFromPage(2));
        reader.close();
    }

    @Test
    @DisplayName("每张都写了页码时仍是整份连续编号（老行为不变）")
    void allNumberedSheetsStayContinuous() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getFooter().setRight("第 ${page} 页 / 共 ${pages} 页");

        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(List.of(sheet("S1", 3, 3), sheet("S2", 3, 3)), cfg), i -> cfg, null));

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        assertTrue(extractor.getTextFromPage(1).contains("第 1 页 / 共 2 页"));
        assertTrue(extractor.getTextFromPage(2).contains("第 2 页 / 共 2 页"));
        reader.close();
    }

    @Test
    @DisplayName("普通报表（没有 mzDocBreaks）仍是整份连续编号")
    void pageNumbersStayContinuousWithoutDocBreaks() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getFooter().setRight("第 ${page} 页 / 共 ${pages} 页");

        List<Map<String, Object>> sheets = List.of(sheet("S1", 3, 3), sheet("S2", 3, 3));
        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(sheets, cfg), i -> cfg, i -> List.of()));
        assertEquals(2, reader.getNumberOfPages());

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        assertTrue(extractor.getTextFromPage(1).contains("第 1 页 / 共 2 页"));
        assertTrue(extractor.getTextFromPage(2).contains("第 2 页 / 共 2 页"), "多 sheet 报表是一份连续的 PDF");
        reader.close();
    }

    @Test
    @DisplayName("待转换内容为空时直接报错")
    void emptyInputFails() {
        assertThrows(BizException.class, () -> pdf.convert(null));
        assertThrows(BizException.class, () -> pdf.convert(new byte[0]));
    }

    @Test
    @DisplayName("页头页尾每页都画，页码/总页数按实际页数展开")
    void headerFooterIsDrawnOnEveryPageWithPageNumbers() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getHeader().setCenter("月度经营分析");
        cfg.getFooter().setRight("第 ${page} 页 / 共 ${pages} 页");

        PdfReader reader = new PdfReader(pdf.convert(excel.export(List.of(sheet(200, 3)), cfg)));
        int pages = reader.getNumberOfPages();
        assertTrue(pages > 1, "200 行该分成多页");

        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= pages; p++) {
            String text = extractor.getTextFromPage(p);
            assertTrue(text.contains("月度经营分析"), "第 " + p + " 页该有页头，实际:\n" + text);
            assertTrue(text.contains("第 " + p + " 页 / 共 " + pages + " 页"),
                    "第 " + p + " 页的页码不对，实际:\n" + text);
        }
        reader.close();
    }

    @Test
    @DisplayName("上页边距放不下页头时把正文推下去，不让页头压在第一行上")
    void headerPushesContentDownWhenMarginIsTooSmall() throws Exception {
        // 上页边距只有 2mm，装不下 5mm 页头边距 + 60pt 的字，正文必须让出 33mm 左右
        PageConfigDTO tight = new PageConfigDTO();
        tight.setMarginTop(2);
        tight.getHeader().setCenter("页头");
        tight.getHeader().setFontSize(60);
        tight.getHeader().setMargin(5);

        PageConfigDTO plain = new PageConfigDTO();
        plain.setMarginTop(2);

        // 让出来的高度会挤掉十几行，于是页数比没有页头时多
        int withHeader = pageCount(excel.export(List.of(sheet(110, 3)), tight));
        int without = pageCount(excel.export(List.of(sheet(110, 3)), plain));
        assertTrue(withHeader > without,
                "页头占掉的高度该反映在分页上：有页头 " + withHeader + " 页 / 没有 " + without + " 页");
    }

    @Test
    @DisplayName("水印每页都画，文字压在内容之上")
    void watermarkIsDrawnOnEveryPage() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getWatermark().setText("内部资料");

        PdfReader reader = new PdfReader(pdf.convert(
                excel.export(List.of(sheet(200, 3)), cfg), i -> cfg));
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            assertTrue(extractor.getTextFromPage(p).contains("内部资料"),
                    "第 " + p + " 页该有水印");
        }
        reader.close();
    }

    @Test
    @DisplayName("没传打印设置时不画水印（老调用方的单参数重载）")
    void noWatermarkWithoutPageConfig() throws Exception {
        PageConfigDTO cfg = new PageConfigDTO();
        cfg.getWatermark().setText("内部资料");

        PdfReader reader = new PdfReader(pdf.convert(excel.export(List.of(sheet(3, 3)), cfg)));
        assertTrue(!new PdfTextExtractor(reader).getTextFromPage(1).contains("内部资料"));
        reader.close();
    }

    @Test
    @DisplayName("图片单元格画进 PDF：图片按它锚定的那块格子铺满")
    void cellImageIsDrawnAtItsCell() throws Exception {
        // 图片放在第 2 行第 1 列，列宽 110px = 82.5pt，行高默认 19px = 14.25pt
        byte[] xlsx = excel.export(List.of(imageSheet(2, 1, TestImages.PNG_DATA_URI)), new PageConfigDTO());

        PdfReader reader = new PdfReader(pdf.convert(xlsx));
        PdfDictionary resources = reader.getPageN(1).getAsDict(PdfName.RESOURCES);
        PdfDictionary xobjects = resources.getAsDict(PdfName.XOBJECT);
        assertTrue(xobjects != null && !xobjects.getKeys().isEmpty(), "PDF 里应有一个图片 XObject");

        PdfObject first = PdfReader.getPdfObject(xobjects.get(xobjects.getKeys().iterator().next()));
        assertEquals(PdfName.IMAGE, ((PdfDictionary) first).getAsName(PdfName.SUBTYPE));
        reader.close();
    }

    /** 一张只有一个图片单元格的表（图片单元格的形态见 GridCell#image） */
    private Map<String, Object> imageSheet(int r, int c, String src) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("v", "");
        v.put("m", "");
        v.put("mzImg", Map.of("src", src));
        Map<String, Object> cd = new LinkedHashMap<>();
        cd.put("r", r);
        cd.put("c", c);
        cd.put("v", v);

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "图片");
        sheet.put("celldata", List.of(cell(0, 0, "标题", true), cd));
        sheet.put("config", Map.of("columnlen", Map.of("0", 110, "1", 110)));
        return sheet;
    }

    private int pageCount(byte[] xlsx) throws Exception {
        PdfReader reader = new PdfReader(pdf.convert(xlsx));
        int pages = reader.getNumberOfPages();
        reader.close();
        return pages;
    }

    /* ------------------------------ 构造渲染结果 ------------------------------ */

    /**
     * 造一张 rows x cols 的表：第 0 行是中文表头，其余是「城市n + 数字」。
     * 结构与 RenderResultDTO.sheets 里的元素一致（celldata + config）。
     */
    private Map<String, Object> sheet(int rows, int cols) {
        return sheet("Sheet1", rows, cols);
    }

    private Map<String, Object> sheet(String name, int rows, int cols) {
        List<Map<String, Object>> celldata = new ArrayList<>();
        String[] header = {"城市", "销售额", "占比"};
        for (int c = 0; c < cols; c++) {
            celldata.add(cell(0, c, c < header.length ? header[c] : "列" + c, true));
        }
        for (int r = 1; r < rows; r++) {
            celldata.add(cell(r, 0, r == 1 ? "北京" : "城市" + r, false));
            for (int c = 1; c < cols; c++) {
                celldata.add(cell(r, c, String.valueOf(r * 100 + c), false));
            }
        }

        Map<String, Object> columnlen = new LinkedHashMap<>();
        for (int c = 0; c < cols; c++) {
            columnlen.put(String.valueOf(c), 110);
        }

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", name);
        sheet.put("celldata", celldata);
        sheet.put("config", Map.of("columnlen", columnlen));
        return sheet;
    }

    /** 同一段长文字放两格：第 0 行开自动换行，第 1 行不开，列宽 100px 都放不下 */
    private Map<String, Object> wrapSheet(String longText) {
        Map<String, Object> wrapped = cell(0, 0, longText, false);
        ((Map<String, Object>) wrapped.get("v")).put("tb", "2");

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "换行");
        sheet.put("celldata", List.of(wrapped, cell(1, 0, longText, false)));
        sheet.put("config", Map.of("columnlen", Map.of("0", 100, "1", 100)));
        return sheet;
    }

    /** rows 行的表，但数据只落在 firstCol..lastCol 上（左边留出空列），每列 colPx 宽 */
    private Map<String, Object> offsetSheet(int rows, int firstCol, int lastCol, int colPx) {
        List<Map<String, Object>> celldata = new ArrayList<>();
        Map<String, Object> columnlen = new LinkedHashMap<>();
        for (int c = 0; c <= lastCol; c++) {
            columnlen.put(String.valueOf(c), colPx);
        }
        for (int r = 0; r < rows; r++) {
            for (int c = firstCol; c <= lastCol; c++) {
                celldata.add(cell(r, c, "R" + r + "C" + c, false));
            }
        }

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "偏右的表");
        sheet.put("celldata", celldata);
        sheet.put("config", Map.of("columnlen", columnlen));
        return sheet;
    }

    /** 第 0 行是表头，第 1 行是一格开了自动换行的长备注（列宽 100px，折出来比一页还高） */
    private Map<String, Object> noteSheet(String note) {
        Map<String, Object> wrapped = cell(1, 0, note, false);
        ((Map<String, Object>) wrapped.get("v")).put("tb", "2");

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "长备注");
        sheet.put("celldata", List.of(cell(0, 0, "城市", true), cell(0, 1, "销售额", true), wrapped));
        sheet.put("config", Map.of("columnlen", Map.of("0", 100, "1", 100)));
        return sheet;
    }

    /** rows 行、每行一格 len 个破折号并开自动换行，列宽 100px 放不下 */
    private Map<String, Object> dashWrapSheet(int rows, int len) {
        List<Map<String, Object>> celldata = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            Map<String, Object> cd = cell(r, 0, "—".repeat(len), false);
            ((Map<String, Object>) cd.get("v")).put("tb", "2");
            celldata.add(cd);
        }

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "换行行高");
        sheet.put("celldata", celldata);
        sheet.put("config", Map.of("columnlen", Map.of("0", 100)));
        return sheet;
    }

    private Map<String, Object> cell(int r, int c, String value, boolean bold) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("v", value);
        v.put("m", value);
        if (bold) {
            v.put("bl", 1);
        }
        Map<String, Object> cd = new LinkedHashMap<>();
        cd.put("r", r);
        cd.put("c", c);
        cd.put("v", v);
        return cd;
    }
}
