package com.muzhou.report.export;

import com.muzhou.report.common.BizException;
import com.muzhou.report.dto.PageConfigDTO;
import com.muzhou.report.dto.WatermarkDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFOddFooter;
import org.apache.poi.xssf.usermodel.XSSFOddHeader;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHdrFtrRef;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabStop;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTabs;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblGrid;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STSectionMark;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTabJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STVerticalJc;
import org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * 用 Apache POI 把 {@link ExcelExporter} 产出的 .xlsx 转成 .docx。
 *
 * <p>是**转结构**而不是渲染：xlsx 的单元格逐格搬成 Word 表格的单元格，出来的是一张真表格，
 * 可以在 Word 里继续改。
 *
 * <p><b>横向只取到打印区域为止</b>：Word 的表格不能横向分页，比正文宽的表只会溢出到纸外面。
 * 所以列的范围以打印区域（{@code pageConfig.printArea}）为准，右边多出来的列不搬进来。
 * 行不裁——打印区域是**模板期**设的，扩展出来的数据行本来就会长到它下面去，按它裁会把数据切掉。
 *
 * <p>页面用报表自己的纸张、方向和页边距，表格宽度铺满正文宽度，列宽按 Excel 里的比例分配。
 * 打印设置是按 sheet 生效的，所以**每张 sheet 自成一节**（docx 的页面设置按节存）：第二张设成
 * A4 横向，Word 里第二节就是横向、表格也铺满横向的正文宽度。节与节之间靠「下一页」分节符隔开，
 * 不再另插分页符。
 *
 * <p>页头页尾从 xlsx 的页眉页脚搬成 docx 的页眉页脚，页码写成 {@code PAGE} / {@code NUMPAGES} 域
 * （这条路出的是可编辑文档，页码得跟着内容变）；水印是页眉里的一段 VML 艺术字，见
 * {@link WordWatermark}。
 *
 * <p>已知边界：**页眉页脚与水印整份文档只有一套**，跟第一张 sheet（每节各一套要给每节建一个
 * header 部件，暂不支持）；图表、条件格式不支持（锚在单元格上的图片支持，见
 * {@link #readPictures}）；
 * Word 表格里的文字一律自动换行（Excel 那种「溢出到相邻空格」在 Word 里没有对应表现）；
 * 水印在正文**下面**（页眉里的图形只能在下面），会被有底色的单元格挡住 —— PDF 那条路是压在上面的。
 */
@Slf4j
@Component
public class WordExporter {

    /** 1 英寸 = 1440 twip；96dpi 下 1px = 15 twip。 */
    private static final int TWIPS_PER_INCH = 1440;
    private static final int TWIPS_PER_PIXEL = 15;

    /** 边框粗细，单位是 1/8 磅：4 = 0.5pt，与 PDF 那条路的细边框观感一致。 */
    private static final int BORDER_SIZE = 4;

    /** 没设过字号时 Excel 用默认字体渲染（Calibri 11pt）。 */
    private static final int DEFAULT_FONT_SIZE = 11;

    /** 页眉里没写字号时按这个来，与 {@code HeaderFooterDTO#fontSize} 的缺省一致。 */
    private static final float DEFAULT_HEADER_FONT_SIZE = 9f;

    /**
     * 单元格左右内边距合计（磅）——Word 默认每边 0.19cm ≈ 5.4pt。
     *
     * <p>图片按格子的整宽算就会超出可用宽度，而表格里的图片是行内元素，放不下会被挤到下一行，
     * 看着就是「图片下面多出一大截空白」。
     */
    private static final double CELL_SIDE_PADDING_PT = 11;

    /** 纸张尺寸（mm，纵向摆放），与 ExcelExporter / 前端 utils/print.js 的那份对齐。 */
    private static final Map<Short, int[]> PAPER_SIZES = Map.of(
            PrintSetup.A3_PAPERSIZE, new int[]{297, 420},
            PrintSetup.A4_PAPERSIZE, new int[]{210, 297},
            PrintSetup.A5_PAPERSIZE, new int[]{148, 210},
            PrintSetup.B4_PAPERSIZE, new int[]{250, 353},
            PrintSetup.B5_PAPERSIZE, new int[]{176, 250},
            PrintSetup.LETTER_PAPERSIZE, new int[]{216, 279},
            PrintSetup.LEGAL_PAPERSIZE, new int[]{216, 356});

    /**
     * xlsx -> docx（不画水印）。
     *
     * @param xlsx {@link ExcelExporter#export} 的产物
     */
    public byte[] convert(byte[] xlsx) {
        return convert(xlsx, null);
    }

    /**
     * xlsx -> docx（整份连续编号）。
     *
     * @param xlsx       {@link ExcelExporter#export} 的产物
     * @param pageConfig 第一张 sheet 生效的打印设置（水印整份文档只有一套）；null = 不画水印
     */
    public byte[] convert(byte[] xlsx, PageConfigDTO pageConfig) {
        return convert(xlsx, pageConfig, null);
    }

    /**
     * xlsx -> docx。
     *
     * <p>页头页尾不用从这里传：{@code ExcelExporter} 已经把它们写进 xlsx 的页眉页脚，这里读回来
     * 搬成 docx 的页眉页脚。{@code pageConfig} 只为**水印**而来 —— xlsx 里没有水印这个概念。
     *
     * <p>纸张/方向/页边距同样不用传：它们在 xlsx 里按 sheet 存着，这里逐张读出来写进各自那一节。
     *
     * @param xlsx        {@link ExcelExporter#export} 的产物
     * @param pageConfig  第一张 sheet 生效的打印设置（水印整份文档只有一套）；null = 不画水印
     * @param docBreaksOf 按 sheet 下标取该 sheet 内每份单据的起始行（{@code mzDocBreaks}）：
     *                    非空 = 这张 sheet 起一份新单据，页码从它重编，见 {@link #restartNumbering}。
     *                    传 null = 全篇连续编号（普通报表）
     */
    public byte[] convert(byte[] xlsx, PageConfigDTO pageConfig, IntFunction<List<Integer>> docBreaksOf) {
        if (xlsx == null || xlsx.length == 0) {
            throw new BizException("Word 导出失败: 待转换的 Excel 内容为空");
        }
        long start = System.currentTimeMillis();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
             XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

            int sheets = wb.getNumberOfSheets();
            // 页码按单据重编（一条数据一份单据）时，「共几页」要数的是本节而不是整份文档
            boolean perDoc = anyDocBreaks(sheets, docBreaksOf);
            for (int i = 0; i < sheets; i++) {
                XSSFSheet sheet = wb.getSheetAt(i);
                // 表格铺的是**这张 sheet** 的正文宽度：横向那张就该铺横向那么宽
                int printable = printableWidth(sheet);
                if (i == 0) {
                    // 页眉页脚只有一套，跟第一张 sheet（每节各一套要每节一个 header 部件）
                    applyHeaderFooter(doc, sheet, printable,
                            pageConfig == null ? null : pageConfig.getWatermark(), perDoc);
                }
                writeSheet(doc, sheet, wb.getPrintArea(i), printable, formatter, evaluator);
                // 每张 sheet 自成一节：最后一张的页面设置写在 body 的 sectPr 上，
                // 前面几张写在各自的分节符里
                CTSectPr body = bodySectPr(doc);
                CTSectPr sect = i == sheets - 1 ? body : sectionBreak(doc, body);
                applyPageSetup(sect, sheet);
                if (perDoc && startsDoc(docBreaksOf, i)) {
                    restartNumbering(sect);
                }
            }

            doc.write(out);
            byte[] bytes = out.toByteArray();
            log.debug("xlsx({} bytes) -> docx({} bytes) 耗时 {}ms",
                    xlsx.length, bytes.length, System.currentTimeMillis() - start);
            return bytes;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Word 导出失败", e);
            throw new BizException("Word 导出失败: " + e.getMessage());
        }
    }

    /* ------------------------------ 页面设置 ------------------------------ */

    /**
     * 把 sheet 的纸张 / 方向 / 页边距写进一节的 {@code sectPr}。
     *
     * <p>docx 的页面设置是**按节**存的（一节一套纸张/方向/页边距），所以每张 sheet 各自一节
     * 就能各按自己的方向出纸 —— 与 Excel / PDF 那两条路一致：第二张设成 A4 横向，Word 里
     * 第二节就是横向，表格也跟着铺满横向的正文宽度。
     */
    private void applyPageSetup(CTSectPr sect, XSSFSheet sheet) {
        PrintSetup ps = sheet.getPrintSetup();
        int[] mm = PAPER_SIZES.getOrDefault(ps.getPaperSize(), PAPER_SIZES.get(PrintSetup.A4_PAPERSIZE));
        boolean landscape = ps.getLandscape();
        int width = mmToTwips(landscape ? mm[1] : mm[0]);
        int height = mmToTwips(landscape ? mm[0] : mm[1]);

        int top = inchToTwips(sheet.getMargin(PageMargin.TOP));
        int bottom = inchToTwips(sheet.getMargin(PageMargin.BOTTOM));

        CTPageSz pgSz = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        pgSz.setW(BigInteger.valueOf(width));
        pgSz.setH(BigInteger.valueOf(height));
        pgSz.setOrient(landscape ? STPageOrientation.LANDSCAPE : STPageOrientation.PORTRAIT);

        int headerMargin = inchToTwips(sheet.getMargin(PageMargin.HEADER));
        int footerMargin = inchToTwips(sheet.getMargin(PageMargin.FOOTER));

        CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        mar.setLeft(BigInteger.valueOf(inchToTwips(sheet.getMargin(PageMargin.LEFT))));
        mar.setRight(BigInteger.valueOf(inchToTwips(sheet.getMargin(PageMargin.RIGHT))));
        // 页眉页脚画在页边距里，页边距不够高时把正文推下去（和 Excel / PDF 那条路同一个规则）
        mar.setTop(BigInteger.valueOf(Math.max(top, reserve(sheet, true, headerMargin))));
        mar.setBottom(BigInteger.valueOf(Math.max(bottom, reserve(sheet, false, footerMargin))));
        mar.setHeader(BigInteger.valueOf(headerMargin));
        mar.setFooter(BigInteger.valueOf(footerMargin));
    }

    /**
     * 这一节的页码从 1 重新数（{@code w:pgNumType w:start="1"}）。
     *
     * <p>用在「一条数据一个 sheet」（{@code perRow}）上：一张 sheet 一份单据、一份单据一节，
     * 页码就该一份份从 1 数起。没重编的那几节（同一条数据的第 2、3 张模板）Word 自己会接着上一节数。
     *
     * <p><b>做不到的那半</b>：{@code perRowPage} 把所有单据拼进了同一张 sheet，也就是同一节，
     * 而 Word 的页码只能按节重编（一张表格中间插不了分节符，见 {@link #rowBreaks}）——
     * 那个模式下 Word 出的页码仍是整份连续的。要精确出纸走 PDF 那条路。
     */
    private void restartNumbering(CTSectPr sect) {
        CTPageNumber num = sect.isSetPgNumType() ? sect.getPgNumType() : sect.addNewPgNumType();
        num.setStart(BigInteger.ONE);
    }

    /** 这张 sheet 是不是一份新单据的开头（{@code mzDocBreaks} 非空）。 */
    private boolean startsDoc(IntFunction<List<Integer>> docBreaksOf, int index) {
        if (docBreaksOf == null) {
            return false;
        }
        List<Integer> breaks = docBreaksOf.apply(index);
        return breaks != null && !breaks.isEmpty();
    }

    /** 整份文档里有没有「页码按单据重编」这回事 —— 页眉页脚只有一套，域名要一次定下来。 */
    private boolean anyDocBreaks(int sheets, IntFunction<List<Integer>> docBreaksOf) {
        for (int i = 0; i < sheets; i++) {
            if (startsDoc(docBreaksOf, i)) {
                return true;
            }
        }
        return false;
    }

    /** 该 sheet 那一节的正文宽度（twip）= 纸张宽 - 左右页边距，也就是表格能占的宽度。 */
    private int printableWidth(XSSFSheet sheet) {
        PrintSetup ps = sheet.getPrintSetup();
        int[] mm = PAPER_SIZES.getOrDefault(ps.getPaperSize(), PAPER_SIZES.get(PrintSetup.A4_PAPERSIZE));
        int width = mmToTwips(ps.getLandscape() ? mm[1] : mm[0]);
        int left = inchToTwips(sheet.getMargin(PageMargin.LEFT));
        int right = inchToTwips(sheet.getMargin(PageMargin.RIGHT));
        return Math.max(width - left - right, TWIPS_PER_INCH);
    }

    /** body 级的 {@code sectPr} 就是**最后一节**的页面设置；POI 建页眉时会先建出来，别再加第二个。 */
    private CTSectPr bodySectPr(XWPFDocument doc) {
        CTBody body = doc.getDocument().getBody();
        return body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
    }

    /**
     * 插一个「下一页」分节符，返回它的 {@code sectPr}（这一节的页面设置写在里面）。
     *
     * <p>分节符自带分页，所以不再另插 page break。
     *
     * <p>页眉页脚的引用要从 body 那一节抄过来：一节没写 {@code headerReference} 时 Word 是往
     * **前**一节找，而唯一那套页眉页脚挂在 body（最后一节）上 —— 不抄的话前面几节一个页眉都没有，
     * 页头页尾只出现在最后一张 sheet 上。
     */
    private CTSectPr sectionBreak(XWPFDocument doc, CTSectPr from) {
        XWPFParagraph p = doc.createParagraph();
        // 分节符所在的这一段是空段，别让它占出一行的高度
        p.setSpacingBefore(0);
        p.setSpacingAfter(0);
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTSectPr sect = ppr.isSetSectPr() ? ppr.getSectPr() : ppr.addNewSectPr();
        for (CTHdrFtrRef ref : from.getHeaderReferenceList()) {
            sect.addNewHeaderReference().set(ref.copy());
        }
        for (CTHdrFtrRef ref : from.getFooterReferenceList()) {
            sect.addNewFooterReference().set(ref.copy());
        }
        sect.addNewType().setVal(STSectionMark.NEXT_PAGE);
        return sect;
    }

    /** 页眉/页脚要占掉的高度（twip）：边距 + 一行字高；没设页眉页脚返回 0。 */
    private int reserve(XSSFSheet sheet, boolean header, int margin) {
        String raw = header ? headerText(sheet) : footerText(sheet);
        if (raw == null) {
            return 0;
        }
        // 磅 -> twip：1 磅 = 20 twip
        return margin + (int) Math.round(fontSizeOf(raw) * PageConfigDTO.LINE_HEIGHT * 20);
    }

    /* ------------------------------ 页眉页脚 / 水印 ------------------------------ */

    /**
     * 把 xlsx 的页眉页脚搬成 docx 的页眉页脚，并在页眉里放水印图形。
     *
     * <p>水印必须落在**页眉**里：Word 的水印就是一个绝对定位的艺术字图形，只有放在页眉里
     * 才会每页都出现。它是绝对定位的，不占页眉的行高，所以和页头文字放在同一段里也没关系
     * （Word 自己做的水印也是这么放的）。
     *
     * <p>页码写成 {@code PAGE} / {@code NUMPAGES} 域而不是写死的数字 —— 这条 Word 路出的是可以
     * 继续编辑的文档，用户加两行内容，页码得跟着变。
     *
     * @param perDoc 页码按单据重编（见 {@link #restartNumbering}）：此时「共几页」要数的是
     *               **本节**（{@code SECTIONPAGES}）而不是整份文档
     */
    private void applyHeaderFooter(XWPFDocument doc, XSSFSheet sheet, int printable,
                                   WatermarkDTO watermark, boolean perDoc) {
        String header = headerText(sheet);
        String footer = footerText(sheet);
        boolean hasWatermark = watermark != null && !watermark.isBlank();
        String sheetName = sheet.getSheetName();

        if (header != null || hasWatermark) {
            XWPFParagraph p = firstParagraph(doc.createHeader(HeaderFooterType.DEFAULT));
            if (hasWatermark) {
                addWatermark(p, watermark);
            }
            if (header != null) {
                writeSections(p, header, printable, sheetName, perDoc);
            }
        }
        if (footer != null) {
            writeSections(firstParagraph(doc.createFooter(HeaderFooterType.DEFAULT)),
                    footer, printable, sheetName, perDoc);
        }
    }

    /** 新建的页眉页脚自带一个空段落，复用它而不是再加一段 —— 多一段就多一行，会把正文顶下去。 */
    private XWPFParagraph firstParagraph(XWPFHeaderFooter part) {
        return part.getParagraphs().isEmpty() ? part.createParagraph() : part.getParagraphs().get(0);
    }

    /** xlsx 的奇数页页眉；没设置返回 null。 */
    private String headerText(XSSFSheet sheet) {
        String raw = ((XSSFOddHeader) sheet.getOddHeader()).getText();
        return raw == null || raw.isEmpty() ? null : raw;
    }

    private String footerText(XSSFSheet sheet) {
        String raw = ((XSSFOddFooter) sheet.getOddFooter()).getText();
        return raw == null || raw.isEmpty() ? null : raw;
    }

    private float fontSizeOf(String raw) {
        for (String section : HeaderFooterText.split(raw)) {
            Integer size = HeaderFooterText.parse(section, HeaderFooterText.Ctx.of(1, 1, "")).fontSize();
            if (size != null && size > 0) {
                return size;
            }
        }
        return DEFAULT_HEADER_FONT_SIZE;
    }

    /**
     * 把左中右三段写进一个段落：Word 里没有「三段式页眉」，靠居中和右对齐两个制表位实现
     * —— 左段直接写，然后跳一个制表位写中段（居中制表位在正文中点），再跳一个写右段（右对齐制表位在正文右边）。
     */
    private void writeSections(XWPFParagraph p, String raw, int printable, String sheetName, boolean perDoc) {
        String[] sections = HeaderFooterText.split(raw);
        float size = fontSizeOf(raw);

        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTTabs tabs = ppr.isSetTabs() ? ppr.getTabs() : ppr.addNewTabs();
        addTab(tabs, STTabJc.CENTER, printable / 2);
        addTab(tabs, STTabJc.RIGHT, printable);
        p.setSpacingBefore(0);
        p.setSpacingAfter(0);

        for (int i = 0; i < sections.length; i++) {
            if (i > 0) {
                // 制表位要跳：中段/右段为空时也得跳，否则右段会跑到中间去
                XWPFRun tab = p.createRun();
                tab.setFontSize((int) size);
                tab.addTab();
            }
            writeParts(p, sections[i], size, sheetName, perDoc);
        }
    }

    private void addTab(CTTabs tabs, STTabJc.Enum align, int pos) {
        CTTabStop tab = tabs.addNewTab();
        tab.setVal(align);
        tab.setPos(BigInteger.valueOf(pos));
    }

    /**
     * 一段里的文字直接写成 run，页码/总页数写成 Word 的域。
     *
     * <p>{@code perDoc}（页码按单据重编）时总页数用 {@code SECTIONPAGES}（本节页数）而不是
     * {@code NUMPAGES}（整份页数）—— 一份单据就是一节，「第 1 页 共 2 页」说的是这一份。
     * 一条数据有几张模板（一份单据横跨几节）时这个数只数得到当前那一节，是这条路的已知偏差。
     */
    private void writeParts(XWPFParagraph p, String section, float size, String sheetName, boolean perDoc) {
        HeaderFooterText.Parsed parsed = HeaderFooterText.parseParts(section, HeaderFooterText.Ctx.of(1, 1, sheetName));
        for (HeaderFooterText.Part part : parsed.parts()) {
            switch (part.kind()) {
                case TEXT -> {
                    XWPFRun run = p.createRun();
                    run.setFontSize((int) size);
                    run.setText(part.text());
                }
                case PAGE -> addField(p, "PAGE", size);
                case PAGES -> addField(p, perDoc ? "SECTIONPAGES" : "NUMPAGES", size);
            }
        }
    }

    /**
     * 插一个 Word 域，形如 {@code { PAGE }}。
     *
     * <p>只用 {@code createRun()} 建 run：域是 begin / instrText / separate / end 四段拼出来的，
     * 顺序错了 Word 就把它当普通文字显示。POI 的 {@code addNewFldSimple} 走的是另一套元素，
     * 和 run 混在一段里时 XmlBeans 未必按调用顺序排，所以这里一律用 run。
     */
    private void addField(XWPFParagraph p, String instr, float size) {
        p.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
        XWPFRun instrRun = p.createRun();
        instrRun.setFontSize((int) size);
        CTText text = instrRun.getCTR().addNewInstrText();
        text.setStringValue(" " + instr + " ");
        text.setSpace(SpaceAttribute.Space.PRESERVE);
        p.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
        // 分隔符与结束符之间是「上次算出来的值」，留空让 Word 自己填
        p.createRun().getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
    }

    /**
     * 往页眉段落里塞一个水印图形（XML 片段见 {@link WordWatermark}）。
     *
     * <p>整段 XML 拼好后替换掉一个空 run —— 逐个元素用 XmlBeans 建太啰嗦，而这段是固定模板。
     *
     * <p>已知边界：<b>页眉里的图形永远在正文下面</b>，所以 Word 的水印会被有底色的单元格盖住，
     * 而 PDF / 浏览器打印那两条路是把水印画在内容之上的。Word 侧没有「浮在正文上」的水印可用。
     */
    private void addWatermark(XWPFParagraph p, WatermarkDTO wm) {
        try {
            p.createRun().getCTR().set(CTR.Factory.parse(WordWatermark.runXml(wm)));
        } catch (Exception e) {
            // 水印画不出来不该让整份导出失败
            log.warn("Word 水印生成失败，已跳过: {}", e.getMessage());
        }
    }

    /* ------------------------------ 表格 ------------------------------ */

    private void writeSheet(XWPFDocument doc, XSSFSheet sheet, String printArea, int printable,
                            DataFormatter formatter, FormulaEvaluator evaluator) {
        int[] cols = columnRange(sheet, printArea);
        if (cols == null) {
            return;
        }
        int firstRow = Math.max(0, sheet.getFirstRowNum());
        int lastRow = lastRow(sheet);
        if (lastRow < firstRow) {
            return;
        }

        int[] widths = columnWidths(sheet, cols, printable);
        int colCount = widths.length;
        Map<String, CellPicture> pictures = readPictures(sheet);

        XWPFTable table = doc.createTable(lastRow - firstRow + 1, colCount);
        layoutTable(table, widths);
        Set<Integer> breaks = rowBreaks(sheet);

        for (int r = firstRow; r <= lastRow; r++) {
            XWPFTableRow row = table.getRow(r - firstRow);
            applyRowHeight(row, sheet.getRow(r));
            if (r > firstRow && breaks.contains(r)) {
                pageBreakBefore(row);
            }
            for (int c = cols[0]; c <= cols[1]; c++) {
                XWPFTableCell cell = row.getCell(c - cols[0]);
                if (cell == null) {
                    continue;
                }
                setCellWidth(cell, widths[c - cols[0]]);
                writeCell(cell, sheet, r, c, formatter, evaluator, pictures.get(r + "_" + c), cols, widths);
            }
        }
        applyMerges(table, sheet, cols, firstRow, lastRow, widths);
    }

    /**
     * xlsx 里的手动行分页符，换算成「哪几行必须另起一页」。
     *
     * <p>POI 的 {@code getRowBreaks()} 还的是「在这一行**下方**断页」，所以要加一。
     */
    private Set<Integer> rowBreaks(XSSFSheet sheet) {
        int[] breaks = sheet.getRowBreaks();
        if (breaks == null || breaks.length == 0) {
            return Set.of();
        }
        Set<Integer> out = new HashSet<>();
        for (int b : breaks) {
            out.add(b + 1);
        }
        return out;
    }

    /**
     * 让这一行从新的一页开始。
     *
     * <p>一张 sheet 在 Word 里是**一张表**，表中间没法插分节符或空段落（会把表切断），
     * 所以分页只能标在行首格的段落上：{@code w:pPr/w:pageBreakBefore} 对表格行同样有效。
     * 「每条数据一页」（{@code content.splitMode=perRowPage}）在 Word 这条路上就是这么落地的。
     */
    private void pageBreakBefore(XWPFTableRow row) {
        XWPFTableCell cell = row.getCell(0);
        if (cell == null) {
            return;
        }
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        p.setPageBreak(true);
    }

    /* ------------------------------ 图片 ------------------------------ */

    /** 一张锚在 (r,c) 上的图片；{@code r2/c2} 是它铺到的末行末列（合并区时不止一格）。 */
    private record CellPicture(int r2, int c2, byte[] data, int type) {
    }

    /**
     * 读出 xlsx 里锚在单元格上的图片，按锚点格子建索引（报表的图片单元格由
     * {@code ExcelExporter#applyImages} 写入）。
     *
     * <p>只认两点锚定的图片。这里只要「图片占了哪几行哪几列」，格内偏移用不上 ——
     * Word 的格子宽度是重新分配过的，图片要在 Word 的格子里重新装一次（{@link #addPicture}）。
     * 末行末列见 {@link #lastIndex}。
     */
    private Map<String, CellPicture> readPictures(XSSFSheet sheet) {
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing == null) {
            return Map.of();
        }
        Map<String, CellPicture> pictures = new HashMap<>();
        for (XSSFShape shape : drawing.getShapes()) {
            if (!(shape instanceof XSSFPicture picture)) {
                continue;
            }
            try {
                ClientAnchor anchor = picture.getClientAnchor();
                byte[] data = anchor == null ? null : picture.getPictureData().getData();
                if (data == null || data.length == 0) {
                    continue;
                }
                int type = picture.getPictureData().getPictureType();
                if (!ImageLoader.supported(type)) {
                    log.warn("Word 不支持的图片格式(type={})，已跳过", type);
                    continue;
                }
                pictures.put(anchor.getRow1() + "_" + anchor.getCol1(), new CellPicture(
                        lastIndex(anchor.getRow1(), anchor.getRow2(), anchor.getDy2()),
                        lastIndex(anchor.getCol1(), anchor.getCol2(), anchor.getDx2()),
                        data, type));
            } catch (Exception e) {
                log.warn("读取 xlsx 中的图片失败，已跳过: {}", e.getMessage());
            }
        }
        return pictures;
    }

    /**
     * 锚点的终点落在第几行/第几列（含）。
     *
     * <p>OOXML 的终点是「第 to 格往里 offset」：offset 大于 0 说明图片确实伸进了 to 这一格；
     * offset 正好是 0 则表示贴着 to 的左/上边，占的是它**前面**那一格
     * （Excel 自己插入的图片就是这么写的）。差这一格，图片就会被按多一行的高度重新装一次。
     */
    private int lastIndex(int from, int to, int offset) {
        return offset > 0 ? Math.max(from, to) : Math.max(from, to - 1);
    }

    /**
     * 把图片放进单元格：等比例装进这一格并居中，不拉伸（{@link ImageFit#contain}）。
     *
     * <p>格子的宽高必须按 **Word 这边的**算：列宽被重新分配到正文宽度了（{@link #columnWidths}），
     * 照搬 xlsx 的列宽会让图片撑破格子。也正因为格子的宽高比与 Excel 里的不同，这里是拿图片
     * 自己的宽高比重新装一次，而不是照抄 xlsx 里锚点框的大小 —— 锚点框只用来定位在哪一格。
     * 高度用 Excel 的行高（Word 表格的行高就是照搬过来的，两边一致）。
     */
    private void addPicture(XWPFParagraph p, XSSFSheet sheet, CellPicture pic, int r, int c,
                            int[] cols, int[] widths) {
        int twips = 0;
        for (int i = Math.max(c, cols[0]); i <= Math.min(pic.c2(), cols[1]); i++) {
            twips += widths[i - cols[0]];
        }
        float points = 0;
        for (int i = r; i <= pic.r2(); i++) {
            Row row = sheet.getRow(i);
            points += row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints();
        }
        if (twips <= 0 || points <= 0) {
            return;
        }
        // twip -> 磅是 /20。只在宽度上让出单元格的左右内边距，高度不让 ——
        // Word 表格的上下内边距默认是 0，行高本来就只有十几磅，再让就没了
        ImageFit.Box box = ImageFit.contain(
                Math.max(twips / 20d - CELL_SIDE_PADDING_PT, 1), points, pic.data());
        try (ByteArrayInputStream in = new ByteArrayInputStream(pic.data())) {
            // Word 的图片尺寸单位是 EMU。居中靠段落的对齐（图片是行内元素，没有绝对定位）
            p.setAlignment(ParagraphAlignment.CENTER);
            p.createRun().addPicture(in, pic.type(), "cell.img",
                    Units.toEMU(box.width()), Units.toEMU(box.height()));
        } catch (Exception e) {
            log.warn("Word 中的图片写入失败，已跳过 r={} c={}: {}", r, c, e.getMessage());
        }
    }

    /**
     * 表格占满正文宽度，并且用固定布局 —— 不然 Word 会按内容自己重新分配列宽，
     * 设计器里排好的列宽比例就没了。
     *
     * <p>固定布局下 Word 是按 {@code <w:tblGrid>} 定列的，缺了它列宽就不作数
     * （POI 建表时不会写），所以这里必须把每一列的宽度显式写进去。
     */
    private void layoutTable(XWPFTable table, int[] widths) {
        CTTbl ctTbl = table.getCTTbl();
        CTTblPr pr = ctTbl.getTblPr();
        CTTblWidth w = pr.isSetTblW() ? pr.getTblW() : pr.addNewTblW();
        int total = 0;
        for (int x : widths) {
            total += x;
        }
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(total));
        if (!pr.isSetTblLayout()) {
            pr.addNewTblLayout();
        }
        pr.getTblLayout().setType(STTblLayoutType.FIXED);

        CTTblGrid grid = ctTbl.getTblGrid() != null ? ctTbl.getTblGrid() : ctTbl.addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) {
            grid.removeGridCol(0);
        }
        for (int width : widths) {
            grid.addNewGridCol().setW(BigInteger.valueOf(width));
        }
        clearTableBorders(pr);
    }

    /**
     * 抹掉表格级的默认边框，边框一律由单元格自己声明（{@link #applyBorders}）。
     *
     * <p>POI 建表时会写一整套 {@code <w:tblBorders>}：六条边全是 {@code val="single"}，
     * <b>而且不带 {@code w:color}</b>。不带颜色在 OOXML 里等于 {@code auto}，Word 解析 auto
     * 是拿它跟单元格底色比对的 —— 底色深的格子（表头那种）边框就被判成<b>白色</b>，
     * 看着像是「边框没了」。而且这套默认边框会画在每一条格子边上，Excel 里明明没画边框的地方
     * 也有线，与 PDF 那条路（只画 Excel 里有的边）对不上。
     *
     * <p>所以整套设成 {@code nil}：Word 里单元格边框优先于表格边框，声明了的照画，没声明的不画。
     */
    private void clearTableBorders(CTTblPr pr) {
        CTTblBorders borders = pr.isSetTblBorders() ? pr.getTblBorders() : pr.addNewTblBorders();
        nil(borders.isSetTop() ? borders.getTop() : borders.addNewTop());
        nil(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom());
        nil(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft());
        nil(borders.isSetRight() ? borders.getRight() : borders.addNewRight());
        nil(borders.isSetInsideH() ? borders.getInsideH() : borders.addNewInsideH());
        nil(borders.isSetInsideV() ? borders.getInsideV() : borders.addNewInsideV());
    }

    /**
     * 列的范围：**打印区域优先**，没设过就取实际用到的列。
     *
     * <p>Word 的表格不能横向分页，比正文宽的表只会溢出到纸外面，所以宁可按打印区域截断，
     * 也不要把右边多出来的列搬进来。行不跟着截 —— 打印区域是模板期设的，扩展出来的数据行
     * 会长到它下面去，跟着截会把数据切掉。
     */
    private int[] columnRange(XSSFSheet sheet, String printArea) {
        if (printArea != null && !printArea.isBlank()) {
            try {
                AreaReference ref = new AreaReference(printArea, sheet.getWorkbook().getSpreadsheetVersion());
                return new int[]{ref.getFirstCell().getCol(), ref.getLastCell().getCol()};
            } catch (Exception e) {
                log.warn("打印区域[{}]解析失败，按整表的列范围导出: {}", printArea, e.getMessage());
            }
        }
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (int r = Math.max(0, sheet.getFirstRowNum()); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || row.getLastCellNum() <= 0) {
                continue;
            }
            first = Math.min(first, row.getFirstCellNum());
            last = Math.max(last, row.getLastCellNum() - 1);
        }
        for (CellRangeAddress m : sheet.getMergedRegions()) {
            first = Math.min(first, m.getFirstColumn());
            last = Math.max(last, m.getLastColumn());
        }
        return last < 0 ? null : new int[]{Math.max(0, first), last};
    }

    /** 合并区可能伸到最后一个有值的行下面。 */
    private int lastRow(XSSFSheet sheet) {
        int last = sheet.getLastRowNum();
        for (CellRangeAddress m : sheet.getMergedRegions()) {
            last = Math.max(last, m.getLastRow());
        }
        return last;
    }

    /**
     * 各列宽度（twip），按 Excel 里的列宽比例分配整个正文宽度。
     *
     * <p>比例照搬、总宽铺满：窄表不会缩在左上角，宽表也不会溢出到纸外面。
     */
    private int[] columnWidths(XSSFSheet sheet, int[] cols, int printable) {
        int count = cols[1] - cols[0] + 1;
        int[] px = new int[count];
        long total = 0;
        for (int c = cols[0]; c <= cols[1]; c++) {
            int w = Math.max(1, Math.round(sheet.getColumnWidthInPixels(c)));
            px[c - cols[0]] = w;
            total += w;
        }
        int[] twips = new int[count];
        int used = 0;
        for (int i = 0; i < count; i++) {
            twips[i] = total <= 0 ? printable / count : (int) (printable * (long) px[i] / total);
            twips[i] = Math.max(twips[i], TWIPS_PER_PIXEL);
            used += twips[i];
        }
        // 取整的零头补给最后一列，保证总宽正好是正文宽度
        twips[count - 1] += printable - used;
        return twips;
    }

    private void applyRowHeight(XWPFTableRow row, Row excelRow) {
        if (excelRow == null) {
            return;
        }
        // Word 的行高单位是 1/20 磅，正好是 twip
        int twips = Math.round(excelRow.getHeightInPoints() * 20);
        if (twips > 0) {
            row.setHeight(twips);
        }
    }

    private void setCellWidth(XWPFTableCell cell, int twips) {
        CTTcPr pr = tcPr(cell);
        CTTblWidth w = pr.isSetTcW() ? pr.getTcW() : pr.addNewTcW();
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(twips));
    }

    /* ------------------------------ 单元格 ------------------------------ */

    private void writeCell(XWPFTableCell cell, XSSFSheet sheet, int r, int c,
                           DataFormatter formatter, FormulaEvaluator evaluator,
                           CellPicture picture, int[] cols, int[] widths) {
        Row row = sheet.getRow(r);
        Cell src = row == null ? null : row.getCell(c);
        XSSFCellStyle style = src == null ? null : (XSSFCellStyle) src.getCellStyle();

        applyFill(cell, style);
        applyBorders(cell, style);
        applyVerticalAlign(cell, style);

        String text = src == null ? "" : text(src, formatter, evaluator);
        XWPFParagraph p = cell.getParagraphs().get(0);
        p.setAlignment(alignment(style, src));
        // 表格里默认的段前段后间距会把行撑高，去掉才贴近 Excel 的行高
        p.setSpacingBefore(0);
        p.setSpacingAfter(0);
        if (picture != null) {
            addPicture(p, sheet, picture, r, c, cols, widths);
        }
        if (text.isEmpty()) {
            return;
        }
        XWPFRun run = p.createRun();
        applyFont(run, style);
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                run.addBreak();
            }
            run.setText(lines[i], i);
        }
    }

    /** 单元格显示文本：公式先算出来再取格式化后的值。 */
    private String text(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (Exception e) {
            // 公式算不出来时退化成不带公式求值的显示值，别让整份导出失败
            try {
                return formatter.formatCellValue(cell);
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private void applyFont(XWPFRun run, XSSFCellStyle style) {
        XSSFFont font = style == null ? null : style.getFont();
        if (font == null) {
            run.setFontSize(DEFAULT_FONT_SIZE);
            return;
        }
        run.setFontSize(font.getFontHeightInPoints() > 0 ? font.getFontHeightInPoints() : DEFAULT_FONT_SIZE);
        run.setBold(font.getBold());
        run.setItalic(font.getItalic());
        if (font.getFontName() != null && !font.getFontName().isBlank()) {
            run.setFontFamily(font.getFontName());
        }
        String color = hex(font.getXSSFColor());
        if (color != null) {
            run.setColor(color);
        }
    }

    private void applyFill(XWPFTableCell cell, XSSFCellStyle style) {
        if (style == null || style.getFillPattern() != FillPatternType.SOLID_FOREGROUND) {
            return;
        }
        String color = hex(style.getFillForegroundXSSFColor());
        if (color != null) {
            CTTcPr pr = tcPr(cell);
            (pr.isSetShd() ? pr.getShd() : pr.addNewShd()).setVal(STShd.CLEAR);
            pr.getShd().setColor("auto");
            pr.getShd().setFill(color);
        }
    }

    /**
     * Excel 里画了边框的边，在 Word 里也画一条细线，颜色照搬 Excel 里那条边的颜色。
     *
     * <p>取不到颜色（xlsx 里那条边写的是 {@code auto} 或干脆没写颜色，{@code ExcelExporter}
     * 出的边框就是这样）时退回<b>黑色</b>，与 {@code PdfExporter#line} 同一条规则 ——
     * 不能交给 Word 的 auto，它会拿底色去反推，深色表头上的边框直接变成白的。
     */
    private void applyBorders(XWPFTableCell cell, XSSFCellStyle style) {
        if (style == null) {
            return;
        }
        boolean top = style.getBorderTop() != BorderStyle.NONE;
        boolean bottom = style.getBorderBottom() != BorderStyle.NONE;
        boolean left = style.getBorderLeft() != BorderStyle.NONE;
        boolean right = style.getBorderRight() != BorderStyle.NONE;
        if (!top && !bottom && !left && !right) {
            return;
        }
        CTTcPr pr = tcPr(cell);
        CTTcBorders borders = pr.isSetTcBorders() ? pr.getTcBorders() : pr.addNewTcBorders();
        if (top) {
            border(borders.isSetTop() ? borders.getTop() : borders.addNewTop(),
                    style.getTopBorderXSSFColor());
        }
        if (bottom) {
            border(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom(),
                    style.getBottomBorderXSSFColor());
        }
        if (left) {
            border(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft(),
                    style.getLeftBorderXSSFColor());
        }
        if (right) {
            border(borders.isSetRight() ? borders.getRight() : borders.addNewRight(),
                    style.getRightBorderXSSFColor());
        }
    }

    private void border(CTBorder b, XSSFColor color) {
        String hex = hex(color);
        b.setVal(STBorder.SINGLE);
        b.setSz(BigInteger.valueOf(BORDER_SIZE));
        b.setColor(hex == null ? "000000" : hex);
    }

    /** 一条「不画」的边（{@code nil}），比不写更明确：能压掉从表格样式继承来的边框。 */
    private void nil(CTBorder b) {
        b.setVal(STBorder.NIL);
    }

    private void applyVerticalAlign(XWPFTableCell cell, XSSFCellStyle style) {
        VerticalAlignment va = style == null ? VerticalAlignment.CENTER : style.getVerticalAlignment();
        CTTcPr pr = tcPr(cell);
        (pr.isSetVAlign() ? pr.getVAlign() : pr.addNewVAlign()).setVal(switch (va) {
            case TOP -> STVerticalJc.TOP;
            case BOTTOM -> STVerticalJc.BOTTOM;
            default -> STVerticalJc.CENTER;
        });
    }

    /** 没设过对齐时按 Excel 的规矩：数字右对齐，其余左对齐。 */
    private ParagraphAlignment alignment(XSSFCellStyle style, Cell cell) {
        HorizontalAlignment ha = style == null ? HorizontalAlignment.GENERAL : style.getAlignment();
        if (ha == HorizontalAlignment.GENERAL) {
            boolean numeric = cell != null && switch (cell.getCellType()) {
                case NUMERIC -> true;
                case FORMULA -> cell.getCachedFormulaResultType() == org.apache.poi.ss.usermodel.CellType.NUMERIC;
                default -> false;
            };
            return numeric ? ParagraphAlignment.RIGHT : ParagraphAlignment.LEFT;
        }
        return switch (ha) {
            case CENTER, CENTER_SELECTION -> ParagraphAlignment.CENTER;
            case RIGHT -> ParagraphAlignment.RIGHT;
            default -> ParagraphAlignment.LEFT;
        };
    }

    /* ------------------------------ 合并 ------------------------------ */

    /**
     * 还原合并区：横向用 gridSpan（被吃掉的格子要删掉），纵向用 vMerge。
     *
     * <p>两个容易写错的地方：
     * <ol>
     *   <li><b>合并后的格子要改宽度</b>：{@code tcW} 得是被跨掉的那几列宽度之和。
     *       只留第一列的宽度，Word 按固定布局排出来的合并格就比该占的窄，整行跟着错位 ——
     *       表现就是「合并单元格没算对」。</li>
     *   <li><b>从右往左处理</b>：删格子只影响它右边的下标，所以先做靠右的合并，
     *       左边的合并区下标才不用跟着修正。反过来做就会串位。</li>
     * </ol>
     */
    private void applyMerges(XWPFTable table, XSSFSheet sheet, int[] cols,
                            int firstRow, int lastRow, int[] widths) {
        List<CellRangeAddress> merges = new ArrayList<>(sheet.getMergedRegions());
        merges.sort((a, b) -> Integer.compare(b.getFirstColumn(), a.getFirstColumn()));

        for (CellRangeAddress m : merges) {
            int r0 = Math.max(m.getFirstRow(), firstRow);
            int r1 = Math.min(m.getLastRow(), lastRow);
            int c0 = Math.max(m.getFirstColumn(), cols[0]);
            int c1 = Math.min(m.getLastColumn(), cols[1]);
            if (r1 < r0 || c1 < c0 || (r1 == r0 && c1 == c0)) {
                continue;
            }
            int idx = c0 - cols[0];
            int span = c1 - c0 + 1;
            int width = 0;
            for (int i = idx; i < idx + span; i++) {
                width += widths[i];
            }

            for (int r = r0; r <= r1; r++) {
                XWPFTableRow row = table.getRow(r - firstRow);
                XWPFTableCell cell = row == null ? null : row.getCell(idx);
                if (cell == null) {
                    continue;
                }
                if (span > 1) {
                    CTTcPr pr = tcPr(cell);
                    (pr.isSetGridSpan() ? pr.getGridSpan() : pr.addNewGridSpan())
                            .setVal(BigInteger.valueOf(span));
                    // 被合并吃掉的格子从行里删掉，否则整行会多出 span-1 格
                    for (int k = 0; k < span - 1 && row.getTableCells().size() > idx + 1; k++) {
                        row.removeCell(idx + 1);
                    }
                    setCellWidth(cell, width);
                }
                if (r1 > r0) {
                    CTTcPr pr = tcPr(cell);
                    (pr.isSetVMerge() ? pr.getVMerge() : pr.addNewVMerge())
                            .setVal(r == r0 ? STMerge.RESTART : STMerge.CONTINUE);
                }
            }
        }
    }

    /* ------------------------------ 小工具 ------------------------------ */

    private CTTcPr tcPr(XWPFTableCell cell) {
        return cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
    }

    /** XSSFColor -> "RRGGBB"，取不到返回 null。 */
    private String hex(XSSFColor color) {
        if (color == null) {
            return null;
        }
        byte[] rgb = color.getRGB();
        if (rgb == null || rgb.length < 3) {
            return null;
        }
        return String.format("%02X%02X%02X", rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF);
    }

    private int mmToTwips(int mm) {
        return (int) Math.round(mm / 25.4 * TWIPS_PER_INCH);
    }

    private int inchToTwips(double inch) {
        return (int) Math.round(inch * TWIPS_PER_INCH);
    }
}
