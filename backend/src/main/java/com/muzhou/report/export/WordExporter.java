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
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrPr;
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
import java.util.Arrays;
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

    /** 文字行距系数，与 {@code ExcelExporter#LINE_HEIGHT}、前端 {@code utils/wrapHeight.js} 同一个数。 */
    private static final float LINE_HEIGHT = (float) PageConfigDTO.LINE_HEIGHT;

    /**
     * 续行时正文高度按九成算。
     *
     * <p>切在哪儿是我们**估**出来的（字宽按字号估、行距按 1.35 估），而真正排版的是 Word。
     * 估短了没事（那一段留白一点），估长了 Word 就把整段挤到下一页去、前一页大片空白 ——
     * 所以宁可切得保守些。
     */
    private static final float SPILL_SAFETY = 0.9f;

    /** 续行时一段至少要有这么高才值得切（磅）：再小就不如整段换页。 */
    private static final float MIN_PART_PT = 12;

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
     * 搬成 docx 的页眉页脚。{@code pageConfig} 是为**水印**和**续行开关**
     * （{@code rowOverflow}，见 {@link #plan}）而来 —— 这两样 xlsx 里都没有对应的表达。
     * 两者同样是**整份文档一套、跟第一张 sheet**。
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
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            return convert(wb, xlsx.length + " bytes", pageConfig, docBreaksOf);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Word 导出失败", e);
            throw new BizException("Word 导出失败: " + e.getMessage());
        }
    }

    /**
     * workbook 直通版：{@code ExcelExporter#exportWorkbook} 构建的对象直接转，省掉
     * 「序列化成 xlsx 字节再解析回来」的往返，其余与字节版走同一段代码。
     * **wb 由调用方负责关闭**。
     */
    public byte[] convert(XSSFWorkbook wb, PageConfigDTO pageConfig, IntFunction<List<Integer>> docBreaksOf) {
        return convert(wb, "直通", pageConfig, docBreaksOf);
    }

    private byte[] convert(XSSFWorkbook wb, String src, PageConfigDTO pageConfig,
                           IntFunction<List<Integer>> docBreaksOf) {
        long start = System.currentTimeMillis();
        try (XWPFDocument doc = new XWPFDocument();
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
                writeSheet(doc, sheet, wb.getPrintArea(i), printable, formatter, evaluator,
                        pageConfig != null && pageConfig.isRowSplit());
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
            log.debug("xlsx({}) -> docx({} bytes) 耗时 {}ms",
                    src, bytes.length, System.currentTimeMillis() - start);
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

        CTPageSz pgSz = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        pgSz.setW(BigInteger.valueOf(width));
        pgSz.setH(BigInteger.valueOf(height));
        pgSz.setOrient(landscape ? STPageOrientation.LANDSCAPE : STPageOrientation.PORTRAIT);

        int headerMargin = inchToTwips(sheet.getMargin(PageMargin.HEADER));
        int footerMargin = inchToTwips(sheet.getMargin(PageMargin.FOOTER));

        int[] margins = bodyMargins(sheet);
        CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        mar.setLeft(BigInteger.valueOf(inchToTwips(sheet.getMargin(PageMargin.LEFT))));
        mar.setRight(BigInteger.valueOf(inchToTwips(sheet.getMargin(PageMargin.RIGHT))));
        mar.setTop(BigInteger.valueOf(margins[0]));
        mar.setBottom(BigInteger.valueOf(margins[1]));
        mar.setHeader(BigInteger.valueOf(headerMargin));
        mar.setFooter(BigInteger.valueOf(footerMargin));
    }

    /**
     * 正文的上/下页边距（twip）。
     *
     * <p>页眉页脚画在页边距里，页边距不够高时把正文推下去（和 Excel / PDF 那条路同一个规则）。
     * 续行要算「一页放得下多少正文」（{@link #bodyHeightPt}），用的必须是这一份 ——
     * 各算各的就会与实际出纸对不上。
     */
    private int[] bodyMargins(XSSFSheet sheet) {
        int headerMargin = inchToTwips(sheet.getMargin(PageMargin.HEADER));
        int footerMargin = inchToTwips(sheet.getMargin(PageMargin.FOOTER));
        return new int[]{
                Math.max(inchToTwips(sheet.getMargin(PageMargin.TOP)), reserve(sheet, true, headerMargin)),
                Math.max(inchToTwips(sheet.getMargin(PageMargin.BOTTOM)), reserve(sheet, false, footerMargin))};
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
                            DataFormatter formatter, FormulaEvaluator evaluator, boolean spill) {
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

        Set<Integer> breaks = rowBreaks(sheet);
        int titleR1 = titleRows(sheet, firstRow, lastRow);
        List<Part> parts = plan(sheet, cols, widths, firstRow, lastRow, titleR1, breaks, spill,
                formatter, evaluator);

        XWPFTable table = doc.createTable(parts.size(), colCount);
        layoutTable(table, widths);

        // 源行 -> 它占了表格的哪几行（续行时一个源行会出好几行），合并要照它换算
        int[] partFirst = new int[lastRow - firstRow + 1];
        int[] partLast = new int[lastRow - firstRow + 1];
        Arrays.fill(partFirst, -1);

        for (int k = 0; k < parts.size(); k++) {
            Part part = parts.get(k);
            int r = part.src();
            if (partFirst[r - firstRow] < 0) {
                partFirst[r - firstRow] = k;
            }
            partLast[r - firstRow] = k;

            XWPFTableRow row = table.getRow(k);
            if (part.height() >= 0) {
                setRowHeight(row, part.height());
            } else {
                applyRowHeight(row, sheet.getRow(r));
            }
            if (r <= titleR1) {
                // 顶端标题行：Word 自己会在每一页的表格上方重画这几行
                row.setRepeatHeader(true);
            }
            if (r > firstRow && part.head() && breaks.contains(r)) {
                pageBreakBefore(row);
            }
            if (part.text() != null) {
                // 切好的段不许 Word 再断一次：两套断行叠加，位置对不上
                cantSplit(row);
            }
            for (int c = cols[0]; c <= cols[1]; c++) {
                XWPFTableCell cell = row.getCell(c - cols[0]);
                if (cell == null) {
                    continue;
                }
                setCellWidth(cell, widths[c - cols[0]]);
                // 图片切不开，只放在第一段里
                CellPicture picture = part.head() ? pictures.get(r + "_" + c) : null;
                writeCell(cell, sheet, r, c, formatter, evaluator, picture, cols, widths,
                        part.text() == null ? null : part.text().getOrDefault(c, ""));
            }
        }
        applyMerges(table, sheet, cols, firstRow, lastRow, widths, partFirst, partLast);
    }

    /* ------------------------------ 续行 ------------------------------ */

    /**
     * 表格的一行。普通情况下一个源行出一行，续行时一个源行出好几行。
     *
     * @param src    对应 xlsx 里的第几行
     * @param height 这一行多高（磅）；<b>负数 = 照搬 xlsx 里的行高</b>（没切过的行走这条）
     * @param head   是不是该源行的第一段（分页符只挂在它上面，图片也只放它里面）
     * @param text   列 -> 这一段该印的文字；<b>null = 整格文字原样印</b>（没切过的行）
     */
    private record Part(int src, float height, boolean head, Map<Integer, String> text) {
    }

    /** 一格量出来的文字：折出来的那些行 + 它们在行内的排布。 */
    private record CellLines(int col, List<RowSpill.Line> lines, RowSpill.Lines geom, float height) {
    }

    /**
     * 排出这张表要占表格的哪些行 —— 也就是「哪些超高行要切成几段」。
     *
     * <p>没开续行（{@code pageConfig.rowOverflow != split}）时原样一行一行排，与老行为一模一样。
     *
     * <p>开了续行就得<b>自己模拟一遍分页</b>：Word 是转结构不是渲染，切完要真的多出一行，
     * 而「切在哪儿」只有知道页有多高、每行多高才说得出来。这两样都拿得到 —— 页高来自
     * 这张 sheet 的页面设置，行高按 Word 自己的列宽把文字折一遍算出来
     * （{@link #measureRow}；<b>不能照搬 xlsx 里的行高</b>：那份被 Excel 的 409.5pt 行高上限夹过，
     * 三页高的备注格在 xlsx 里也就 409.5pt，照它判就永远不会触发续行）。
     *
     * <p>切口交给 {@link RowSpill#snapCut} 对到两行文字之间，与 PDF 那条路同一份算法 ——
     * 不然同一张报表 PDF 切在第 12 行下面、Word 切在第 11 行下面，对着看就是两份东西。
     *
     * <p><b>估算与 Word 的实际排版必有出入</b>（字宽是估的、Word 的行距也未必正好 1.35），
     * 所以正文高度按 {@link #SPILL_SAFETY} 打折 —— 估短了那一段留白一点，估长了 Word 会把整段
     * 挤到下一页去、前一页大片空白。
     *
     * <p>不参与续行的：<b>跨行合并</b>的行（高度该摊给哪一行没有确定答案，与 PDF / Excel 那两处
     * 同一条规则）、顶端标题行（每页重画，本来就该短）。
     */
    private List<Part> plan(XSSFSheet sheet, int[] cols, int[] widths, int firstRow, int lastRow,
                            int titleR1, Set<Integer> breaks, boolean spill,
                            DataFormatter formatter, FormulaEvaluator evaluator) {
        List<Part> out = new ArrayList<>();
        if (!spill) {
            for (int r = firstRow; r <= lastRow; r++) {
                out.add(new Part(r, -1, true, null));
            }
            return out;
        }

        // 一页放得下多少正文：可打印高度扣掉每页都要重画的标题行，再打个安全折扣
        float cap = bodyHeightPt(sheet);
        for (int r = firstRow; r <= titleR1; r++) {
            cap -= rowHeightPt(sheet, r);
        }
        cap *= SPILL_SAFETY;

        float used = 0;
        for (int r = firstRow; r <= lastRow; r++) {
            if (breaks.contains(r)) {
                used = 0;
            }
            List<CellLines> cells = r > titleR1 && !inRowMerge(sheet, r)
                    ? measureRow(sheet, cols, widths, r, formatter, evaluator) : null;
            float h = Math.max(rowHeightPt(sheet, r), height(cells));
            if (cap <= 0 || cells == null || h <= cap) {
                // 装得进一页的行照旧整行走：放不下就整行挪到下一页
                if (used + h > cap) {
                    used = 0;
                }
                used += h;
                out.add(new Part(r, -1, true, null));
                continue;
            }

            // 比一页还高：从本页剩下的地方开始切，一页页接着印
            List<RowSpill.Lines> grids = cells.stream().map(CellLines::geom).toList();
            float printed = 0;
            while (true) {
                float room = cap - used;
                if (h - printed <= room + 0.01f) {
                    out.add(part(r, cells, printed, h, printed <= 0));
                    used += h - printed;
                    break;
                }
                float at = room > MIN_PART_PT ? RowSpill.snapCut(printed + room, grids) : printed;
                if (at - printed <= MIN_PART_PT) {
                    if (used > 0) {
                        // 本页剩下的地方切不出一段，换页再来
                        used = 0;
                        continue;
                    }
                    // 整页都放不下一行字（字比一页还高）：硬切，不然这里就是个死循环
                    at = printed + room;
                }
                out.add(part(r, cells, printed, at, printed <= 0));
                printed = at;
                used = 0;
            }
        }
        return out;
    }

    /** 切出一段：每一格取「完整落在 {@code from..to} 里」的那几行文字。 */
    private Part part(int r, List<CellLines> cells, float from, float to, boolean head) {
        Map<Integer, String> text = new HashMap<>();
        for (CellLines cl : cells) {
            int a = RowSpill.linesAbove(cl.geom(), from);
            int b = RowSpill.linesAbove(cl.geom(), to);
            StringBuilder sb = new StringBuilder();
            for (int i = a; i < b; i++) {
                RowSpill.Line line = cl.lines().get(i);
                // 段内的折行交给 Word 自己再折一次（它的字体与我们估的不一样，硬折会长短不齐），
                // 只有原文里真有的换行才写成换行
                if (i > a && line.head()) {
                    sb.append('\n');
                }
                sb.append(line.text());
            }
            text.put(cl.col(), sb.toString());
        }
        return new Part(r, to - from, head, text);
    }

    /** 这一行按 Word 的列宽把文字折一遍，量出每一格占几行；整行没有文字时返回 null。 */
    private List<CellLines> measureRow(XSSFSheet sheet, int[] cols, int[] widths, int r,
                                       DataFormatter formatter, FormulaEvaluator evaluator) {
        Row row = sheet.getRow(r);
        if (row == null) {
            return null;
        }
        List<CellLines> out = new ArrayList<>();
        for (int c = cols[0]; c <= cols[1]; c++) {
            Cell cell = row.getCell(c);
            if (cell == null) {
                continue;
            }
            String text = text(cell, formatter, evaluator);
            if (text.isEmpty()) {
                continue;
            }
            CellRangeAddress region = regionAt(sheet, r, c);
            if (region != null && (region.getFirstRow() != r || region.getFirstColumn() != c)) {
                // 跨列合并只在锚点那一格算，被吃掉的格子是空的
                continue;
            }
            double twips = 0;
            for (int i = Math.max(region == null ? c : region.getFirstColumn(), cols[0]);
                 i <= Math.min(region == null ? c : region.getLastColumn(), cols[1]); i++) {
                twips += widths[i - cols[0]];
            }
            // twip -> 磅是 /20，再让出单元格的左右内边距
            float width = (float) Math.max(twips / 20 - CELL_SIDE_PADDING_PT, 1);
            XSSFCellStyle style = (XSSFCellStyle) cell.getCellStyle();
            XSSFFont font = style == null ? null : style.getFont();
            float size = font == null || font.getFontHeightInPoints() <= 0
                    ? DEFAULT_FONT_SIZE : font.getFontHeightInPoints();
            // Word 表格里的文字一律自动换行，不看 Excel 那边开没开
            List<RowSpill.Line> lines = RowSpill.wrapEstimate(text, size, width);
            float lineHeight = size * LINE_HEIGHT;
            // 行距即字面高 = 「行与行之间没有空隙」：量不到真字体，往保守的一侧靠，
            // 切口一律对到整行上
            out.add(new CellLines(c, lines,
                    new RowSpill.Lines(0, lineHeight, lineHeight, lines.size()),
                    lines.size() * lineHeight));
        }
        return out.isEmpty() ? null : out;
    }

    /** 这一行按文字量出来多高（磅）。 */
    private float height(List<CellLines> cells) {
        float h = 0;
        if (cells != null) {
            for (CellLines cl : cells) {
                h = Math.max(h, cl.height());
            }
        }
        return h;
    }

    /** 这一行有没有被跨行合并盖住。 */
    private boolean inRowMerge(XSSFSheet sheet, int r) {
        for (CellRangeAddress m : sheet.getMergedRegions()) {
            if (m.getLastRow() > m.getFirstRow() && r >= m.getFirstRow() && r <= m.getLastRow()) {
                return true;
            }
        }
        return false;
    }

    /** (r,c) 所属的合并区域，未合并返回 null。 */
    private CellRangeAddress regionAt(XSSFSheet sheet, int r, int c) {
        for (CellRangeAddress m : sheet.getMergedRegions()) {
            if (m.isInRange(r, c)) {
                return m;
            }
        }
        return null;
    }

    /** xlsx 里第 r 行的行高（磅）。 */
    private float rowHeightPt(XSSFSheet sheet, int r) {
        Row row = sheet.getRow(r);
        return row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints();
    }

    /** 这一节的正文高度（磅）= 纸张高 - 上下页边距（页边距已为页眉页脚让过位，与 {@link #applyPageSetup} 同源）。 */
    private float bodyHeightPt(XSSFSheet sheet) {
        PrintSetup ps = sheet.getPrintSetup();
        int[] mm = PAPER_SIZES.getOrDefault(ps.getPaperSize(), PAPER_SIZES.get(PrintSetup.A4_PAPERSIZE));
        int height = mmToTwips(ps.getLandscape() ? mm[0] : mm[1]);
        int[] margins = bodyMargins(sheet);
        return Math.max(height - margins[0] - margins[1], 0) / 20f;
    }

    /** 这一行不许 Word 自己再断开（{@code w:cantSplit}）。 */
    private void cantSplit(XWPFTableRow row) {
        CTTrPr pr = row.getCtRow().isSetTrPr() ? row.getCtRow().getTrPr() : row.getCtRow().addNewTrPr();
        if (pr.sizeOfCantSplitArray() == 0) {
            pr.addNewCantSplit();
        }
    }

    private void setRowHeight(XWPFTableRow row, float points) {
        int twips = Math.round(points * 20);
        if (twips > 0) {
            row.setHeight(twips);
        }
    }

    /**
     * xlsx 里的顶端标题行（{@code _xlnm.Print_Titles}，由 {@code ExcelExporter#applyTitleRows} 写入）
     * -> 标题行的末行；没有则返回 {@code firstRow - 1}。
     *
     * <p>判定与 {@code PdfExporter#readTitleRows} 同一套：必须是表格最上面的连续若干行，
     * 而且不能把整张表都吃掉。Word 的 {@code w:tblHeader} 本来就只认表格开头的连续行，
     * 落在中间的标记根本不生效。
     */
    private int titleRows(XSSFSheet sheet, int firstRow, int lastRow) {
        CellRangeAddress rows = sheet.getRepeatingRows();
        if (rows == null || rows.getFirstRow() > firstRow || rows.getLastRow() < firstRow
                || rows.getLastRow() >= lastRow) {
            return firstRow - 1;
        }
        return rows.getLastRow();
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

    /**
     * @param part 续行时这一段该印的文字（见 {@link #plan}）；null = 整格文字原样印。
     *             切开的格子一律<b>顶对齐</b>：接着上一段往下印的文字要贴着格子上边，
     *             居中的话每一段的文字都各自缩在自己那一格的中间，看着断断续续
     */
    private void writeCell(XWPFTableCell cell, XSSFSheet sheet, int r, int c,
                           DataFormatter formatter, FormulaEvaluator evaluator,
                           CellPicture picture, int[] cols, int[] widths, String part) {
        Row row = sheet.getRow(r);
        Cell src = row == null ? null : row.getCell(c);
        XSSFCellStyle style = src == null ? null : (XSSFCellStyle) src.getCellStyle();

        applyFill(cell, style);
        applyBorders(cell, style);
        if (part == null) {
            applyVerticalAlign(cell, style);
        } else {
            (tcPr(cell).isSetVAlign() ? tcPr(cell).getVAlign() : tcPr(cell).addNewVAlign())
                    .setVal(STVerticalJc.TOP);
        }

        String text = part != null ? part : src == null ? "" : text(src, formatter, evaluator);
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
     *   <li><b>一个源行未必只占表格的一行</b>：续行时超高行被切成了好几行
     *       （{@link #plan}），横向合并要在它的<b>每一段</b>上都做一次，否则第二段起整行多出
     *       {@code span-1} 格。纵向合并的行不参与续行，所以那一维仍是一一对应的。</li>
     * </ol>
     *
     * @param partFirst 源行（下标 {@code r - firstRow}）-> 它在表格里的第一行
     * @param partLast  源行 -> 它在表格里的最后一行
     */
    private void applyMerges(XWPFTable table, XSSFSheet sheet, int[] cols,
                            int firstRow, int lastRow, int[] widths,
                            int[] partFirst, int[] partLast) {
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
                int from = partFirst[r - firstRow];
                if (from < 0) {
                    continue;
                }
                for (int k = from; k <= partLast[r - firstRow]; k++) {
                    XWPFTableRow row = table.getRow(k);
                    XWPFTableCell cell = row == null ? null : row.getCell(idx);
                    if (cell == null) {
                        continue;
                    }
                    if (span > 1) {
                        CTTcPr pr = tcPr(cell);
                        (pr.isSetGridSpan() ? pr.getGridSpan() : pr.addNewGridSpan())
                                .setVal(BigInteger.valueOf(span));
                        // 被合并吃掉的格子从行里删掉，否则整行会多出 span-1 格
                        for (int n = 0; n < span - 1 && row.getTableCells().size() > idx + 1; n++) {
                            row.removeCell(idx + 1);
                        }
                        setCellWidth(cell, width);
                    }
                    if (r1 > r0) {
                        CTTcPr pr = tcPr(cell);
                        (pr.isSetVMerge() ? pr.getVMerge() : pr.addNewVMerge())
                                .setVal(k == from && r == r0 ? STMerge.RESTART : STMerge.CONTINUE);
                    }
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
