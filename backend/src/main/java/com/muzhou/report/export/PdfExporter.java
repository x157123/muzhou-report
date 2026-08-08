package com.muzhou.report.export;

import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.HeaderFooterDTO;
import com.muzhou.report.dto.PageConfigDTO;
import com.muzhou.report.dto.WatermarkDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
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
import org.openpdf.text.Document;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfGState;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.LocalDateTime;
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
 * 把 {@link ExcelExporter} 产出的 .xlsx 转成 .pdf。
 *
 * <p>纯 Java 实现：POI 把 xlsx 读回来（几何、样式、页面设置全在里面），OpenPDF 逐格画到 PDF 上。
 * 不需要在服务器装 LibreOffice / MS Office，也就没有外部进程和字体转换服务要运维。
 *
 * <p>之所以走「先出 xlsx 再转」而不是直接从渲染结果画 PDF：
 * {@code ExcelExporter#applyPageSetup} 已经把 {@code content.pageConfig}（纸张/方向/页边距/
 * 缩放/打印区域）写进 xlsx 的页面设置，这里照着 xlsx 里的设置分页，PDF 与导出的 Excel
 * 出纸一致，「三处共用同一份 pageConfig」的约定在 PDF 上自然成立，不必再实现一遍。
 *
 * <p><b>字体</b>是唯一的环境依赖：PDF 必须内嵌字体才能显示中文，而 JDK 不带中文字体。
 * 默认在系统字体目录里探测（Windows 的微软雅黑/宋体、Linux 的文泉驿/Noto、macOS 的苹方），
 * 探测不到时用 {@code muzhou.report.pdf.font-path} 指一个字体文件。
 *
 * <p>页头页尾从 xlsx 的页眉页脚读回来逐页画（{@link #drawHeaderFooter}），水印则只能由调用方
 * 额外传一份 {@code PageConfigDTO} —— xlsx 里没有水印这个概念，见 {@link #convert(byte[], IntFunction)}。
 *
 * <p>已知边界：只还原报表用得到的那部分 Excel 表现 —— 底色、细边框、字体（名称由统一字体
 * 代替，字号/粗体/斜体/颜色保留）、对齐、合并、自动换行、行高列宽、缩放与分页，
 * 以及锚在单元格上的图片（{@link #readImages}，报表的图片单元格出的就是这种）。
 * 渐变填充、条件格式、图表、浮动摆放的图片不支持。
 */
@Slf4j
@Component
public class PdfExporter {

    /** 边框线宽（磅）。Excel 的 THIN 在屏幕上约 1px，PDF 里 0.5pt 观感接近。 */
    private static final float BORDER_WIDTH = 0.5f;

    /** 单元格文字与边框之间的内边距（磅）。 */
    private static final float PADDING = 2f;

    /** 行距系数，多行文本（单元格里带换行符）时用。 */
    private static final float LINE_SPACING = 1.15f;

    /** 粗体模拟的描边宽度系数（统一字体只有一款字重，粗体靠描边模拟）。 */
    private static final float BOLD_STROKE = 0.033f;

    /** 斜体模拟的倾斜系数。 */
    private static final float ITALIC_SKEW = 0.21f;

    /**
     * 自动换行最多把一行撑到几页高，见 {@link #growWrapRows}。超高行是跨页印的，本来不需要上限，
     * 这道限只为拦住脏数据（一格塞进整篇文章）撑出成百上千页。
     */
    private static final int MAX_WRAP_PAGES = 20;

    /**
     * 页头/页尾的行距系数：与 {@link PageConfigDTO#LINE_HEIGHT} 和前端 {@code utils/print.js}
     * 是同一个数 —— 三处算出同一个「页头占多高」，分页位置才对得上。
     */
    private static final float HEADER_LINE_HEIGHT = (float) PageConfigDTO.LINE_HEIGHT;

    /** 页眉里没写字号时按这个来，与 {@code HeaderFooterDTO#fontSize} 的缺省一致。 */
    private static final float DEFAULT_HEADER_FONT_SIZE = HeaderFooterDTO.DEFAULT_FONT_SIZE;

    /**
     * 自动探测的中文字体候选，按「好看优先」排序；{@code 路径,序号} 是 .ttc 字体集里的第几款。
     */
    private static final List<String> FONT_CANDIDATES = List.of(
            // Windows
            "C:/Windows/Fonts/msyh.ttc,0",      // 微软雅黑
            "C:/Windows/Fonts/simsun.ttc,0",    // 宋体
            "C:/Windows/Fonts/simhei.ttf",      // 黑体
            // Linux
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc,0",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc,0",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0",
            "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
            "/usr/share/fonts/truetype/arphic/uming.ttc,0",
            "/usr/share/fonts/wqy-microhei/wqy-microhei.ttc,0",
            "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc,0",
            // macOS
            "/System/Library/Fonts/PingFang.ttc,0",
            "/Library/Fonts/Arial Unicode.ttf"
    );

    private final MzProperties props;

    /** 惰性加载并常驻的正文字体：探测 + 解析字体文件不便宜，一次就够。 */
    private volatile BaseFont baseFont;

    public PdfExporter(MzProperties props) {
        this.props = props;
    }

    /**
     * xlsx 字节流 -> pdf 字节流（不画水印）。
     *
     * @param xlsx {@link ExcelExporter#export} 的产物
     */
    public byte[] convert(byte[] xlsx) {
        return convert(xlsx, i -> null);
    }

    /**
     * xlsx 字节流 -> pdf 字节流（整份连续编号）。
     *
     * @param xlsx         {@link ExcelExporter#export} 的产物
     * @param pageConfigOf 按 sheet 下标取打印设置，返回 null 表示该 sheet 不画水印
     */
    public byte[] convert(byte[] xlsx, IntFunction<PageConfigDTO> pageConfigOf) {
        return convert(xlsx, pageConfigOf, null);
    }

    /**
     * xlsx 字节流 -> pdf 字节流。
     *
     * <p>页头页尾不用从这里传：{@code ExcelExporter} 已经把它们写进 xlsx 的页眉页脚，
     * 这里读回来即可。{@code pageConfigOf} 只为**水印**而来 —— xlsx 里没有水印这个概念，
     * 存不进去，只能顺着方法签名带下来。{@code docBreaksOf} 同理：xlsx 里没有「页码从这里重编」
     * 这种表达（Excel 只有 sheet 级的 {@code useFirstPageNumber}，一张 sheet 内部无从说起），
     * 所以也只能顺着签名带下来。
     *
     * @param xlsx         {@link ExcelExporter#export} 的产物
     * @param pageConfigOf 按 sheet 下标取打印设置，返回 null 表示该 sheet 不画水印
     * @param docBreaksOf  按 sheet 下标取该 sheet 内**每份单据的起始行**（{@code mzDocBreaks}）：
     *                     页码在那里重编、总页数只数这一份单据。返回 null / 空 = 这张 sheet 延续
     *                     上一张的编号；整个函数传 null = 全篇连续编号（普通报表）
     */
    public byte[] convert(byte[] xlsx, IntFunction<PageConfigDTO> pageConfigOf,
                          IntFunction<List<Integer>> docBreaksOf) {
        return convert(xlsx, pageConfigOf, docBreaksOf, null);
    }

    /**
     * xlsx 字节流 -> pdf 字节流，并让页头页尾里的 {@code ${sheet}} 按**单据**取名。
     *
     * <p>{@code docNamesOf} 与 {@code docBreaksOf} 一一对应（第 k 份单据的名字对着第 k 个起始行）。
     * 「每条数据一页」把 N 份单据拼进了同一张 sheet，那张 sheet 只有一个名字，而纸是一份份
     * 发出去的 —— 每张上该印自己那一份的单号，所以名字也只能顺着方法签名带下来，
     * 和水印、{@code mzDocBreaks} 同一个理由（xlsx 里表达不了）。
     *
     * @param docNamesOf 按 sheet 下标取该 sheet 内每份单据的名字（{@code mzDocNames}）；
     *                   返回 null / 空、或某一项是空串 = 那份仍用工作表名
     */
    public byte[] convert(byte[] xlsx, IntFunction<PageConfigDTO> pageConfigOf,
                          IntFunction<List<Integer>> docBreaksOf,
                          IntFunction<List<String>> docNamesOf) {
        if (xlsx == null || xlsx.length == 0) {
            throw new BizException("PDF 导出失败: 待转换的 Excel 内容为空");
        }
        long start = System.currentTimeMillis();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            long parseMs = System.currentTimeMillis() - start;
            return convert(wb, xlsx.length + " bytes", parseMs, start, pageConfigOf, docBreaksOf, docNamesOf);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("PDF 导出失败", e);
            throw new BizException("PDF 导出失败: " + e.getMessage());
        }
    }

    /**
     * workbook 直通版：{@code ExcelExporter#exportWorkbook} 构建的对象直接转，省掉
     * 「序列化成 xlsx 字节再解析回来」的往返。页面设置等一切几何仍从 workbook 里读，
     * 与字节版走同一段代码。**wb 由调用方负责关闭**。
     */
    public byte[] convert(XSSFWorkbook wb, IntFunction<PageConfigDTO> pageConfigOf,
                          IntFunction<List<Integer>> docBreaksOf,
                          IntFunction<List<String>> docNamesOf) {
        return convert(wb, "直通", 0, System.currentTimeMillis(), pageConfigOf, docBreaksOf, docNamesOf);
    }

    private byte[] convert(XSSFWorkbook wb, String src, long parseMs, long start,
                           IntFunction<PageConfigDTO> pageConfigOf,
                           IntFunction<List<Integer>> docBreaksOf,
                           IntFunction<List<String>> docNamesOf) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            long fontStart = System.currentTimeMillis();
            BaseFont font = font();
            long fontMs = System.currentTimeMillis() - fontStart;
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

            long layoutStart = System.currentTimeMillis();
            long growMs = 0;
            List<Page> pages = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Geom geom = layout(wb, wb.getSheetAt(i), i);
                if (geom != null) {
                    // 撑高要放在分页之前：行高一变，能塞进一页的行数就跟着变
                    long t = System.currentTimeMillis();
                    growWrapRows(geom, font, formatter, evaluator);
                    growMs += System.currentTimeMillis() - t;
                    PageConfigDTO cfg = pageConfigOf == null ? null : pageConfigOf.apply(i);
                    geom.watermark = cfg == null ? null : cfg.getWatermark();
                    geom.docStarts = docStartRows(docBreaksOf == null ? null : docBreaksOf.apply(i),
                            docNamesOf == null ? null : docNamesOf.apply(i), geom);
                    geom.paginate(pages);
                }
            }
            long layoutMs = System.currentTimeMillis() - layoutStart - growMs;

            long drawStart = System.currentTimeMillis();
            // 页面大小逐页可变（不同 sheet 可以不同纸张），首页的必须在构造 Document 时就定下来
            Document doc = new Document(pages.isEmpty() ? PageSize.A4 : pages.get(0).geom.paper, 0, 0, 0, 0);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            doc.open();

            // 页头页尾里的 ${date} / ${time} 全篇取同一个时刻，别出现页与页之间差一秒
            LocalDateTime now = LocalDateTime.now();

            // 页码分组：普通报表全篇一组（1..N），「一条数据一份单据」时一份单据一组、各自从 1 数起
            int[][] numbering = numbering(pages);
            // ${sheet} 印什么：拼在一张 sheet 里的那些单据各印自己的名字，其余仍是工作表名
            String[] names = headerNames(pages);

            for (int i = 0; i < pages.size(); i++) {
                if (i > 0) {
                    doc.setPageSize(pages.get(i).geom.paper);
                    doc.newPage();
                }
                drawPage(writer.getDirectContent(), pages.get(i), font, formatter, evaluator);
                drawHeaderFooter(writer.getDirectContent(), pages.get(i), font,
                        new HeaderFooterText.Ctx(numbering[i][0], numbering[i][1], names[i], now));
                // 水印画在最后 = 压在内容之上。放在内容下面的话，凡是设过底色的单元格
                // （报表的表头几乎都设了）就会把水印盖掉，各处表现还不一致
                drawWatermark(writer.getDirectContent(), pages.get(i), font);
                // 内容是直接画在 direct content 上的，OpenPDF 判断不出这一页有东西，
                // 不显式标记非空的话空页会被丢掉（末页尤其明显）。
                writer.setPageEmpty(false);
            }
            if (pages.isEmpty()) {
                // 空报表也得产出一页，否则 close() 会抛 "The document has no pages"
                writer.setPageEmpty(false);
            }
            doc.close();

            byte[] pdf = out.toByteArray();
            log.debug("xlsx({}) -> pdf({} bytes, {} 页): 解析xlsx {}ms → 字体 {}ms → 排版 {}ms → 撑行高 {}ms → 绘制 {}ms → 合计 {}ms",
                    src, pdf.length, pages.size(), parseMs, fontMs, layoutMs, growMs,
                    System.currentTimeMillis() - drawStart, System.currentTimeMillis() - start);
            return pdf;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("PDF 导出失败", e);
            throw new BizException("PDF 导出失败: " + e.getMessage());
        }
    }

    /* ---------------------------------- 排版 ---------------------------------- */

    /**
     * 一张 sheet 换算到 PDF 坐标系（原点左下、单位磅）后的几何。
     *
     * <p>行高列宽都已经乘过缩放系数，后面画的时候不用再关心缩放。
     */
    static final class Geom {

        private XSSFSheet sheet;
        private String sheetName;
        private Rectangle paper;
        private float marginLeft;
        private float marginRight;
        /** 正文的上/下页边距，已经为页头页尾让过位，见 {@link #reserve} */
        private float marginTop;
        private float marginBottom;
        /** 页头/页尾的左中右三段（还带着 Excel 页眉代码，画的时候才展开） */
        private String[] header;
        private String[] footer;
        private float headerSize;
        private float footerSize;
        /** 页头到纸张上边 / 页尾到纸张下边的距离（磅） */
        private float headerMargin;
        private float footerMargin;
        /**
         * 这张 sheet 参不参与页码统计 = 它的页头页尾里印不印页码，见
         * {@link HeaderFooterText#hasPageNumber}。封面、总结这类没写页码的 sheet 为 false，
         * 它们的页既不占页号也不计入总数。
         */
        private boolean numbered;
        private WatermarkDTO watermark;
        /** 内容范围（含打印区域裁剪） */
        private int r0;
        private int r1;
        private int c0;
        private int c1;
        /** 下标 c-c0 / r-r0 */
        private float[] colWidth;
        private float[] rowHeight;
        private float scale;
        private List<CellRangeAddress> merges;
        /** 锚在单元格上的图片，见 {@link #readImages} */
        private List<CellImage> images;
        /** 必须另起一页的行（下标 r-r0），见 {@link #readRowBreaks}；没有手动分页符时为空集 */
        private Set<Integer> rowBreaks = Set.of();
        /**
         * 顶端标题行的末行（**绝对**行号），标题行恒是 {@code r0..titleR1} 这一段，见
         * {@link #readTitleRows}。{@code r0 - 1} = 没有标题行。
         */
        private int titleR1 = -1;
        /**
         * 行下标（r-r0）-> 这一行文字的行网格 {距行顶多少磅是第一行文字, 行距}，
         * 见 {@link #lineGrid}。只有被撑高过的行才有，劈开超高行时用它对齐切口。
         */
        private final Map<Integer, float[]> lineGrid = new HashMap<>();

        /**
         * 顶端标题行这一页生不生效。除了「配没配」（{@code titleR1}，{@link #readTitleRows}
         * 已做过静态校验）还要看**撑高后的**高度：{@code readTitleRows} 跑在
         * {@code growWrapRows} 之前，标题行里有自动换行格被撑高时还能越过它那道
         * 「不超过半页」的线，占掉大半页的标题行会把正文挤成一行一页。
         * 分页（{@link #paginate}）与绘制（{@code drawPage}）都以这个判定为准 ——
         * 两边不一致的话，行流里没摘掉的标题行会再被标题带盖一遍。
         */
        private boolean titleEnabled() {
            return titleR1 >= r0 && titleHeight() <= availHeight() * 0.5f;
        }

        /** 标题行占的高度（磅）：每页都要为它留出这么多，正文相应少一截 */
        private float titleHeight() {
            float h = 0;
            for (int r = r0; r <= titleR1; r++) {
                h += rowHeight[r - r0];
            }
            return h;
        }
        /**
         * 一份新单据的起始行（**绝对**行号）-> 那份单据的名字，见 {@link #docStartRows}；
         * 普通报表为空表。页码从这些行重编；名字给页头页尾里的 {@code ${sheet}} 用，
         * 空串表示没配（仍印工作表名）。
         */
        private Map<Integer, String> docStarts = Map.of();

        private float availWidth() {
            return paper.getWidth() - marginLeft - marginRight;
        }

        private float availHeight() {
            return paper.getHeight() - marginTop - marginBottom;
        }

        /**
         * 按可打印区域把内容切成若干页。
         *
         * <p>顺序是「先向下、再向右」——与 Excel 打印的默认页序一致：先出完第一批列的所有行，
         * 再出下一批列。
         *
         * <p>配了顶端标题行时**标题行不参与分页**：它从行流里摘掉，每页固定预留它那么高
         * （由 {@link #drawPage} 画在正文上面）。标题行本来就在内容最上面，所以第一页看着
         * 和不配标题行时一模一样，区别只在第二页起也带上表头。
         */
        private void paginate(List<Page> out) {
            int body = titleEnabled() ? titleR1 - r0 + 1 : 0;
            float[] heights = body == 0 ? rowHeight : Arrays.copyOfRange(rowHeight, body, rowHeight.length);
            Set<Integer> breaks = body == 0 ? rowBreaks : new HashSet<>();
            if (body > 0) {
                rowBreaks.stream().filter(i -> i > body).forEach(i -> breaks.add(i - body));
            }
            float avail = availHeight() - (body == 0 ? 0 : titleHeight());
            float[][] grid = new float[heights.length][];
            for (int i = 0; i < heights.length; i++) {
                grid[i] = lineGrid.get(i + body);
            }
            for (int[] cb : split(colWidth, availWidth())) {
                for (RowBlock rb : splitRows(heights, avail, breaks, grid)) {
                    out.add(new Page(this, r0 + body + rb.start(), r0 + body + rb.end(),
                            c0 + cb[0], c0 + cb[1], rb.skip(), rb.height()));
                }
            }
        }

        /**
         * 贪心切块：累计到装不下就断开；单格比一页还大时也至少占一块，不会切成空块。
         *
         * <p>只用在**列**方向：列不能横着劈开（劈开的字两页各半个），比一页还宽的列只能整列占一页，
         * 真正宽到离谱的由 {@link #scaleOf} 缩放兜底。行方向见 {@link #splitRows}。
         */
        private static List<int[]> split(float[] len, float avail) {
            List<int[]> blocks = new ArrayList<>();
            int start = 0;
            float used = 0;
            for (int i = 0; i < len.length; i++) {
                if (i > start && used + len[i] > avail) {
                    blocks.add(new int[]{start, i - 1});
                    start = i;
                    used = 0;
                }
                used += len[i];
            }
            blocks.add(new int[]{start, len.length - 1});
            return blocks;
        }

        /**
         * 行方向切页。规则与列方向同：累计到装不下就断开，{@code forced}（手动分页符，见
         * {@code mzRowBreaks}）无条件断开 —— 「每条数据一页」全靠它。
         *
         * <p><b>比一页还高的行要横着劈开，跨页接着印</b>（{@link RowBlock#skip}）。整行原子地
         * 挪到下一页的话，那一行本来就放不进一页，挪过去照样放不下 —— 只会白白空掉前一页
         * （报障是「第一页只有表头，数据全跑到第二页去了，第一页大片空白」），而且行有多高就
         * 只能画多高，超出的文字被裁掉、再也印不出来。自动换行的备注格动辄比一页还高，
         * 这是唯一还原得了的排法，Word 的表格行（默认「允许跨页断行」）也是这么排的。
         *
         * <p>切口**躲开文字行**：{@code grid} 给出每一行的文字行网格（{@link #lineGrid}），
         * 有网格的行把切口往上对到最近的两行文字之间，免得上下两页各露半个字
         * （Excel / Word 切超高行时是硬切的，这一刀比它们讲究）。只会把切口往**上**挪，
         * 所以不会把一页撑爆。没有网格（{@code grid} 为 null 或该行没被撑高过）时照旧硬切。
         */
        static List<RowBlock> splitRows(float[] len, float avail, Set<Integer> forced) {
            return splitRows(len, avail, forced, null);
        }

        static List<RowBlock> splitRows(float[] len, float avail, Set<Integer> forced, float[][] grid) {
            List<RowBlock> blocks = new ArrayList<>();
            int start = 0;
            // 本页第一行已经在前几页印掉的高度
            float skip = 0;
            float used = 0;
            for (int i = 0; i < len.length; i++) {
                // 本页要印这一行的哪一截：接着上一页往下印时要减掉已经印过的那一段
                float h = len[i] - (i == start ? skip : 0);
                // 余量是给浮点误差留的，免得算出一页 0.001pt 高的空页
                boolean fits = used + h <= avail + 0.01f;
                // 整行本来就装不进一页 = 挪到下一页也放不下，那就从本页剩下的地方开始劈。
                // 剩的地方连 1pt 都不到时不值当劈，照旧换页（也保证下面那个 while 一定在往前走）
                boolean sliceable = avail > 0 && h > avail && avail - used > 1f;
                if (i > start && (forced.contains(i) || (!fits && !sliceable))) {
                    blocks.add(new RowBlock(start, i - 1, skip, used));
                    start = i;
                    skip = 0;
                    used = 0;
                    h = len[i];
                }
                // 这一行本页印掉多少，下一页就从多少接着印；一行占好几页时连劈几刀
                float printed = i == start ? skip : 0;
                while (avail > 0 && used + h > avail + 0.01f && h > avail) {
                    // 切口对到两行文字之间；只往上挪，本页装得下的只会更少
                    float at = snapToLines(printed + avail - used, grid == null ? null : grid[i]);
                    float cut = at - printed;
                    if (cut <= 1f) {
                        cut = avail - used;
                    }
                    blocks.add(new RowBlock(start, i, skip, used + cut));
                    printed += cut;
                    h -= cut;
                    start = i;
                    skip = printed;
                    used = 0;
                }
                used += h;
            }
            blocks.add(new RowBlock(start, len.length - 1, skip, used));
            return blocks;
        }

        /**
         * 把「距行顶 {@code at} 磅」这一刀往上对到最近的两行文字之间。
         *
         * @param grid {距行顶多少磅是第一行文字, 行距}，null = 这一行没有可对齐的文字
         */
        private static float snapToLines(float at, float[] grid) {
            if (grid == null || grid[1] <= 0 || at <= grid[0] + grid[1]) {
                // 没网格，或者这一刀落在第一行文字之内 —— 往上对就把这一页切空了
                return at;
            }
            int lines = (int) Math.floor((at - grid[0]) / grid[1]);
            return grid[0] + lines * grid[1];
        }
    }

    /**
     * 一页所占的行区间。
     *
     * @param skip   第 {@code start} 行已经在前一页印掉的高度（磅）；0 表示这一行从头印起。
     *               超高行被横着劈开时才不为 0，见 {@link Geom#splitRows}
     * @param height 这一段实际印掉的高度（磅）。超高行的切口会为了躲开文字往上挪，
     *               挪出来的那一条**不能**留给下一行的文字露脸，所以画的时候要照它裁
     */
    record RowBlock(int start, int end, float skip, float height) {
    }

    /**
     * 一页：某张 sheet 的一个行列区间。
     *
     * @param skip   第 {@code r0} 行已经在前一页印掉的高度（磅），见 {@link RowBlock#skip}
     * @param height 正文段实际印掉的高度（磅），见 {@link RowBlock#height}
     */
    private record Page(Geom geom, int r0, int r1, int c0, int c1, float skip, float height) {
    }

    /**
     * 一页里连续画的一段行：{@code r0..r1}，上下边界落在 {@code top} / {@code bottom}。
     *
     * <p>一页最多两段：顶端标题行一段、正文一段。{@code bottom} 是这一段**真正印到哪儿**
     * （不是页边距），画的时候照它裁 —— 超高行的切口躲开文字往上挪之后，页底会空出一条，
     * 那条里不能露出下一行文字。
     */
    private record Band(int r0, int r1, float top, float bottom) {
    }

    /**
     * 把 {@code mzDocBreaks}（sheet 内每份单据的起始行）换算成这张 sheet 里「哪几行**起**是新单据」，
     * 顺带带上每份单据的名字（{@code mzDocNames}，与起始行一一对应；没有就是空串）。
     *
     * <p>内容范围可能被打印区域裁过，所以落在范围外的丢掉；范围**之前**有起点就说明这张 sheet
     * 打头的就是一份新单据，记成**正文的第一行**。裁到第一行上的可能不止一份，
     * 此时**后来的覆盖先前的** —— 真正盖住第一行的是最后那一份。
     *
     * <p>「正文的第一行」而不是 {@code r0}：配了顶端标题行时标题带不参与分页，各页的
     * {@code Page#r0} 都从标题带**下面**数起（{@link Geom#paginate}）—— 起点仍钉在 {@code r0}
     * 的话第一份单据永远对不上号，页码与 {@code ${sheet}} 都会错。
     */
    private Map<Integer, String> docStartRows(List<Integer> breaks, List<String> names, Geom g) {
        if (breaks == null || breaks.isEmpty()) {
            return Map.of();
        }
        int bodyStart = g.titleEnabled() ? g.titleR1 + 1 : g.r0;
        Map<Integer, String> out = new HashMap<>();
        for (int i = 0; i < breaks.size(); i++) {
            Integer b = breaks.get(i);
            if (b == null || b > g.r1) {
                continue;
            }
            String name = names == null || i >= names.size() || names.get(i) == null ? "" : names.get(i);
            out.put(Math.max(b, bodyStart), name);
        }
        return out;
    }

    /**
     * 每一页的 {@code ${sheet}} 印什么：这一页所属单据的名字，没有就退回工作表名。
     *
     * <p>「每条数据一页」把 N 份单据拼进了同一张 sheet，工作表名对每一份都一样、说不出这张纸
     * 是哪一份单据；「每条数据一个 sheet」那边这件事是由 sheet 名自己承担的，所以那边
     * {@code mzDocNames} 为空、这里自然退回工作表名，两个模式配的是同一个字段。
     */
    private String[] headerNames(List<Page> pages) {
        String[] out = new String[pages.size()];
        String cur = "";
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            // skip > 0 是上一页那个超高行的后半截，不是新一份单据的开头
            String named = p.skip() > 0 ? null : p.geom().docStarts.get(p.r0());
            if (i == 0 || named != null) {
                // 换单据了（或者刚开篇）：名字跟着换，没配名字的就清空、退回工作表名
                cur = named == null ? "" : named;
            }
            out[i] = cur.isEmpty() ? p.geom().sheetName : cur;
        }
        return out;
    }

    /**
     * 每一页印哪个页码 / 共几页：{@code [i][0]} = 第几页，{@code [i][1]} = 共几页。
     *
     * <p>普通报表（没有 {@code mzDocBreaks}）里全部页是**一组**，也就是 1..N 与整份的页数 ——
     * 多 sheet 报表出的是一份连续的 PDF，读的人数的是这一份文档的页。
     *
     * <p>「一条数据一份单据」（{@code perRow} / {@code perRowPage}）时每份单据自成一组：
     * 单据是一份份发出去的，第 7 张上印「第 7 页 共 20 页」对收到的人没有意义，
     * 该印的是「第 1 页 共 2 页」。一份单据可以跨 sheet（同一条数据的几张模板），
     * 也可以只是一张 sheet 里的一段（{@code perRowPage} 拼在一起的那张）—— 两种都是
     * 「遇到起始行就换一组」，不必分开处理。
     *
     * <p><b>不印页码的 sheet 整张跳过</b>（{@link Geom#numbered}）：封面 + 正文 + 总结这种报表里，
     * 只有正文那张写了页码，它就该从「第 1 页」数起、「共几页」也只数它自己那几页 ——
     * 封面白白占掉一个页号的话，正文第一页印出来是「第 2 页 共 3 页」。跳过的那些页拿不到页号
     * （给 0），反正它们没有页码占位符可印。判定见 {@link HeaderFooterText#hasPageNumber}。
     *
     * <p><b>跳过不能把「新单据从这里开始」一起跳掉</b>：一条数据出封面/正文/总结三张时，
     * 单据的起点落在**封面**那张上，而封面是不参与统计的 —— 见到它就 continue 的话，
     * 各条数据的正文页会被并成一组，「共几页」印出来是**全部数据**的页数而不是这一条的。
     * 所以起点先记在 {@code pending} 上，攒到下一张参与统计的页再另起一组。
     */
    private int[][] numbering(List<Page> pages) {
        int[] group = new int[pages.size()];
        List<Integer> sizes = new ArrayList<>();
        int cur = -1;
        // 下一张参与统计的页要不要另起一组。开篇就是 true：第一张参与统计的页开第一组
        // （不一定是第 0 页 —— 前面可能整张都是封面）
        boolean pending = true;
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            // 先认单据起点，再决定跳不跳 —— 顺序反了就会把起点吞掉。
            // skip > 0 的那页是超高行的后半截，还在同一份单据里，不能再开一组
            if (p.skip() <= 0 && p.geom().docStarts.containsKey(p.r0())) {
                pending = true;
            }
            if (!p.geom().numbered) {
                // 不参与统计：既不占页号，也不计入总数
                group[i] = -1;
                continue;
            }
            if (pending || cur < 0) {
                cur++;
                sizes.add(0);
                pending = false;
            }
            group[i] = cur;
            sizes.set(cur, sizes.get(cur) + 1);
        }
        int[][] out = new int[pages.size()][2];
        int[] seen = new int[sizes.size()];
        for (int i = 0; i < pages.size(); i++) {
            if (group[i] < 0) {
                continue;
            }
            out[i][0] = ++seen[group[i]];
            out[i][1] = sizes.get(group[i]);
        }
        return out;
    }

    /**
     * 一张锚在单元格上的图片。
     *
     * <p>坐标是 OOXML 的锚点形态：起点 = 第 {@code r1} 行第 {@code c1} 列往里 {@code dx1/dy1} 像素，
     * 终点同理。**偏移不能丢**：{@code ExcelExporter#applyImages} 锚的是「图片等比例装进格子后
     * 居中的那个框」而不是整格，丢掉偏移就等于把图片重新拉伸到满格，与 Excel 那边对不上。
     */
    private record CellImage(int r1, int c1, int r2, int c2,
                             double dx1, double dy1, double dx2, double dy2, byte[] data) {
    }

    /** 读 sheet 的几何与页面设置；没有任何内容时返回 null（该 sheet 不出页）。 */
    private Geom layout(XSSFWorkbook wb, XSSFSheet sheet, int index) {
        int[] range = contentRange(wb, sheet, index);
        if (range == null) {
            return null;
        }
        Geom g = new Geom();
        g.sheet = sheet;
        g.sheetName = sheet.getSheetName();
        g.r0 = range[0];
        g.r1 = range[1];
        g.c0 = range[2];
        g.c1 = range[3];

        PrintSetup ps = sheet.getPrintSetup();
        Rectangle paper = paperSize(ps.getPaperSize());
        // 横向不能用 Rectangle#rotate()：它在换宽高的同时把 /Rotate 置成 90，PDF 的 MediaBox
        // 会被换回竖版、显示时再转回来 —— 内容是画在 MediaBox 坐标系里的，就会画到纸外面去。
        // 这里直接造一个横版矩形（rotation 保持 0），画的坐标和纸张一一对应。
        g.paper = ps.getLandscape() ? new Rectangle(paper.getHeight(), paper.getWidth()) : paper;
        // POI 的页边距单位是英寸
        g.marginLeft = (float) (sheet.getMargin(PageMargin.LEFT) * 72);
        g.marginRight = (float) (sheet.getMargin(PageMargin.RIGHT) * 72);
        g.marginTop = (float) (sheet.getMargin(PageMargin.TOP) * 72);
        g.marginBottom = (float) (sheet.getMargin(PageMargin.BOTTOM) * 72);
        readHeaderFooter(sheet, g);

        // 96dpi 下 1px = 0.75pt，与 ExcelExporter 写进去时的换算是同一套
        g.colWidth = new float[g.c1 - g.c0 + 1];
        for (int c = g.c0; c <= g.c1; c++) {
            g.colWidth[c - g.c0] = sheet.getColumnWidthInPixels(c) * 0.75f;
        }
        float defaultRowHeight = sheet.getDefaultRowHeightInPoints();
        g.rowHeight = new float[g.r1 - g.r0 + 1];
        for (int r = g.r0; r <= g.r1; r++) {
            Row row = sheet.getRow(r);
            g.rowHeight[r - g.r0] = row == null ? defaultRowHeight : row.getHeightInPoints();
        }

        g.scale = scaleOf(sheet, ps, g);
        for (int i = 0; i < g.colWidth.length; i++) {
            g.colWidth[i] *= g.scale;
        }
        for (int i = 0; i < g.rowHeight.length; i++) {
            g.rowHeight[i] *= g.scale;
        }
        g.merges = sheet.getMergedRegions();
        g.images = readImages(sheet);
        g.rowBreaks = readRowBreaks(sheet, g.r0, g.r1);
        g.titleR1 = readTitleRows(sheet, g);
        return g;
    }

    /**
     * 读 xlsx 里的顶端标题行（{@code _xlnm.Print_Titles}，由
     * {@code ExcelExporter#applyTitleRows} 写入），返回标题行的**末行**（绝对行号）；
     * 没有则返回 {@code r0 - 1}（= 没有标题行）。
     *
     * <p>标题行是从 {@code r0} 起的连续若干行 —— 落在内容中间的一律不认（见
     * {@code PageConfigDTO#titleRows}）。**也不能把内容全吃掉**：整个内容范围都是标题行的话
     * 正文一行不剩，每页画的都是同一份表头，只会无限出纸。
     */
    private int readTitleRows(XSSFSheet sheet, Geom g) {
        CellRangeAddress rows = sheet.getRepeatingRows();
        if (rows == null) {
            return g.r0 - 1;
        }
        if (rows.getFirstRow() > g.r0 || rows.getLastRow() < g.r0) {
            log.warn("顶端标题行[{}:{}]不在内容范围[{}:{}]的最上面，PDF 里不重复表头",
                    rows.getFirstRow() + 1, rows.getLastRow() + 1, g.r0 + 1, g.r1 + 1);
            return g.r0 - 1;
        }
        if (rows.getLastRow() >= g.r1) {
            log.warn("顶端标题行[{}:{}]盖住了全部内容，PDF 里不重复表头",
                    rows.getFirstRow() + 1, rows.getLastRow() + 1);
            return g.r0 - 1;
        }
        // 标题行几乎占满一页时正文没地方放，每页只挤得下一两行，出纸退化成「一行一页」——
        // 这种配置只能是失误，按没配处理（行高此时已乘过缩放，量出来就是印出来的高度）
        float height = 0;
        for (int r = g.r0; r <= rows.getLastRow(); r++) {
            height += g.rowHeight[r - g.r0];
        }
        if (height > g.availHeight() * 0.5f) {
            log.warn("顶端标题行[{}:{}]占掉一页的一半以上（{}pt / 每页正文 {}pt），PDF 里不重复表头",
                    rows.getFirstRow() + 1, rows.getLastRow() + 1,
                    Math.round(height), Math.round(g.availHeight()));
            return g.r0 - 1;
        }
        return rows.getLastRow();
    }

    /**
     * 读 xlsx 里的手动行分页符，换算成「哪几个行下标必须另起一页」（相对 {@code r0}）。
     *
     * <p>POI 的 {@code getRowBreaks()} 还的是「在这一行**下方**断页」，所以要加一。
     * 「每条数据一页」（{@code content.splitMode=perRowPage}）就是靠这些分页符落地的：
     * 没有它们，两条短数据会被贪心算法塞进同一张纸。
     */
    private Set<Integer> readRowBreaks(XSSFSheet sheet, int r0, int r1) {
        int[] breaks = sheet.getRowBreaks();
        if (breaks == null || breaks.length == 0) {
            return Set.of();
        }
        Set<Integer> out = new HashSet<>();
        for (int b : breaks) {
            int start = b + 1;
            if (start > r0 && start <= r1) {
                out.add(start - r0);
            }
        }
        return out;
    }

    /**
     * 读出 xlsx 里锚在单元格上的图片（报表的图片单元格由 {@code ExcelExporter#applyImages} 写入）。
     *
     * <p>只认**两点锚定**的图片：起止两个角各自带着「第几行第几列 + 格内偏移」，照着画出来的
     * 就是 Excel 里看到的那一块。手工插进 xlsx 的浮动图片（一点锚定 + 尺寸）不在还原范围内。
     */
    private List<CellImage> readImages(XSSFSheet sheet) {
        XSSFDrawing drawing = sheet.getDrawingPatriarch();
        if (drawing == null) {
            return List.of();
        }
        List<CellImage> images = new ArrayList<>();
        for (XSSFShape shape : drawing.getShapes()) {
            if (!(shape instanceof XSSFPicture picture)) {
                continue;
            }
            try {
                org.apache.poi.ss.usermodel.ClientAnchor anchor = picture.getClientAnchor();
                if (anchor == null) {
                    continue;
                }
                byte[] data = picture.getPictureData().getData();
                if (data == null || data.length == 0) {
                    continue;
                }
                images.add(new CellImage(anchor.getRow1(), anchor.getCol1(),
                        Math.max(anchor.getRow1(), anchor.getRow2()),
                        Math.max(anchor.getCol1(), anchor.getCol2()),
                        emuToPx(anchor.getDx1()), emuToPx(anchor.getDy1()),
                        emuToPx(anchor.getDx2()), emuToPx(anchor.getDy2()), data));
            } catch (Exception e) {
                log.warn("读取 xlsx 中的图片失败，已跳过: {}", e.getMessage());
            }
        }
        return images;
    }

    /** 锚点偏移的单位是 EMU，1px = 9525 EMU（96dpi）。 */
    private double emuToPx(int emu) {
        return (double) emu / Units.EMU_PER_PIXEL;
    }

    /**
     * 按 PDF 实际用的那款字体，把开了自动换行的行再撑一次高。
     *
     * <p>xlsx 里的行高是 {@code ExcelExporter#applyWrapRowHeights} **估**出来的：那边不敢加载字体
     * （服务器上未必装了报表用的那款），只能按「全角一个字号、半角半个」算。而这里画字用的是探测
     * 到的中文字体，它的半角字符往往比半个字号宽（微软雅黑的 latin 就是），同一个格宽实际折出的
     * 行数会比估算多一两行 —— 行高不够，垂直居中的文字上下两头就被 clip 掉，看着就是「字被遮挡了
     * 一半」。Excel 是按自己的字体折行、宽度对得上的，所以这个偏差只有 PDF 露馅。
     *
     * <p>这一步能量到真字体，索性照 {@link #wrapLines} 重算一遍：宽度、字号、行距都与
     * {@link #drawText} 同源，算出来多高画出来就多高。规则与 ExcelExporter 保持一致 ——
     * 只增不减（不压掉用户拖出来的行高）、只折了一行的不动、跨行合并跳过（高度该摊给哪一行
     * 没有确定答案）。
     *
     * <p><b>撑到比一页正文还高也照撑</b>：那样的行由 {@link Geom#splitRows} 横着劈开跨页印。
     * 早先这里按一页封顶，于是长备注格恒好一页高 —— 前面那页装不下它、只能整行挪到下一页，
     * 「第一页只剩表头、大片空白」，而且封顶砍掉的文字再也印不出来。只留一道防跑飞的上限
     * （{@link #MAX_WRAP_PAGES} 页），免得一格脏数据撑出成百上千页。
     *
     * <p>顺手把撑高那一格的**文字行网格**记进 {@link Geom#lineGrid}，供劈开时对齐用 ——
     * 这里是唯一同时知道「行有多高」和「文字怎么折」的地方。
     */
    private void growWrapRows(Geom g, BaseFont font, DataFormatter formatter, FormulaEvaluator evaluator) {
        float limit = g.availHeight() * MAX_WRAP_PAGES;
        for (int r = g.r0; r <= g.r1; r++) {
            Row row = g.sheet.getRow(r);
            if (row == null) {
                continue;
            }
            float needed = 0;
            // 撑得最高那一格的 {文字块高, 行距, 垂直对齐}，最后换算成行网格
            float[] tallest = null;
            VerticalAlignment tallestAlign = VerticalAlignment.CENTER;
            for (int c = g.c0; c <= g.c1; c++) {
                Cell cell = row.getCell(c);
                if (cell == null) {
                    continue;
                }
                XSSFCellStyle style = (XSSFCellStyle) cell.getCellStyle();
                if (style == null || !style.getWrapText()) {
                    continue;
                }
                CellRangeAddress region = regionAt(g, r, c);
                if (region != null && (region.getLastRow() > region.getFirstRow()
                        || region.getFirstRow() != r || region.getFirstColumn() != c)) {
                    // 跨行合并跳过；跨列合并只在锚点那一格算，其余格子是空的
                    continue;
                }
                String text = textOf(cell, formatter, evaluator);
                if (text.isEmpty()) {
                    continue;
                }
                float width = 0;
                for (int i = Math.max(region == null ? c : region.getFirstColumn(), g.c0);
                     i <= Math.min(region == null ? c : region.getLastColumn(), g.c1); i++) {
                    width += g.colWidth[i - g.c0];
                }
                // 字号、可用宽度都与 drawText 取同一个值（colWidth 已经乘过缩放）
                XSSFFont f = style.getFont();
                float size = (f == null || f.getFontHeightInPoints() <= 0 ? 11 : f.getFontHeightInPoints()) * g.scale;
                List<String> lines = wrapLines(List.of(text.split("\r\n|\r|\n", -1)), font, size, width - 2 * PADDING);
                if (lines.size() <= 1) {
                    continue;
                }
                // 与 drawText 里的 blockHeight 同一个式子，再留出上下内边距
                float block = LINE_SPACING * size * (lines.size() - 1)
                        + font.getAscentPoint(text, size) - font.getDescentPoint(text, size);
                float h = block + 2 * PADDING;
                if (h > needed) {
                    needed = h;
                    tallest = new float[]{block, LINE_SPACING * size};
                    tallestAlign = verticalAlign(style);
                }
            }
            if (needed > 0) {
                float height = Math.max(g.rowHeight[r - g.r0], Math.min(needed, limit));
                g.rowHeight[r - g.r0] = height;
                g.lineGrid.put(r - g.r0, lineGrid(height, tallest[0], tallest[1], tallestAlign));
            }
        }
    }

    /**
     * 一行文字的「行网格」：{距行顶多少磅是第一行文字的顶边, 行距}。
     *
     * <p>只给 {@link Geom#splitRows} 用 —— 超高行劈开时把切口对到两行文字**之间**，
     * 免得上下两页各露半个字。位置必须与 {@link #drawText} 摆文字块的算法同源：
     * 文字块在格子里怎么放（上/中/下对齐）决定了第一行在哪，往下每 {@code lineHeight} 一行。
     */
    private float[] lineGrid(float rowHeight, float blockHeight, float lineHeight, VerticalAlignment va) {
        float top = switch (va) {
            case TOP -> PADDING;
            case BOTTOM -> rowHeight - PADDING - blockHeight;
            default -> (rowHeight - blockHeight) / 2;
        };
        return new float[]{Math.max(top, 0), lineHeight};
    }

    /** (r,c) 所属的合并区域，未合并返回 null。 */
    private CellRangeAddress regionAt(Geom g, int r, int c) {
        for (CellRangeAddress m : g.merges) {
            if (m.isInRange(r, c)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 缩放系数：优先照 xlsx 里的页面设置（fit-to-page 或固定百分比），最后再兜一层底 ——
     * 单列比一页还宽时按它再压一档，否则这一列会被整列裁掉。
     *
     * <p><b>行方向不参与</b>：比一页还高的行由 {@link Geom#splitRows} 劈开跨页印，不必缩放。
     * 早先这里也照 maxRow 压一档，一格长备注就把**整张表**（连表头带正文）按比例缩小，
     * 而缩完那一行正好一页高、照旧要独占一页 —— 字变小了，第一页的空白一点没少。
     */
    private float scaleOf(XSSFSheet sheet, PrintSetup ps, Geom g) {
        float totalWidth = 0;
        float maxCol = 0;
        for (float w : g.colWidth) {
            totalWidth += w;
            maxCol = Math.max(maxCol, w);
        }

        float scale;
        if (sheet.getFitToPage() && ps.getFitWidth() == 1) {
            // Excel 的「压缩到一页宽」只缩不放，本来装得下就保持 100%
            scale = totalWidth <= 0 ? 1f : Math.min(1f, g.availWidth() / totalWidth);
        } else {
            int pct = ps.getScale() <= 0 ? 100 : ps.getScale();
            scale = Math.min(400, Math.max(10, pct)) / 100f;
        }
        if (maxCol > 0 && maxCol * scale > g.availWidth()) {
            scale = g.availWidth() / maxCol;
        }
        return scale <= 0 ? 1f : scale;
    }

    /**
     * 打印区域优先；没设过就是 A1 到最后一个有内容的行列。返回 {r0,r1,c0,c1}，全空返回 null。
     *
     * <p><b>起点必须是 A1，不能是「第一个有内容的行列」</b>：Excel 没设打印区域时排的是
     * A1 到最后一个有内容的格子，左上角的空行空列照样占位。从第一个有值的格子起算的话，
     * 数据落在 E 列开头的表会被整体挪到左页边距上，内容宽度也跟着少算掉前面那几列 ——
     * 于是「明明设成了 A4 横向，内容却只铺了纵向那么宽」，而 fitToWidth 的缩放系数、
     * 分页位置也都按少算的宽度来，与导出的 Excel 全对不上。
     */
    private int[] contentRange(XSSFWorkbook wb, XSSFSheet sheet, int index) {
        String area = wb.getPrintArea(index);
        if (area != null && !area.isBlank()) {
            try {
                AreaReference ref = new AreaReference(area, wb.getSpreadsheetVersion());
                return new int[]{
                        ref.getFirstCell().getRow(), ref.getLastCell().getRow(),
                        ref.getFirstCell().getCol(), ref.getLastCell().getCol()};
            } catch (Exception e) {
                log.warn("打印区域[{}]解析失败，按整表导出", area);
            }
        }
        int r1 = sheet.getLastRowNum();
        int c1 = -1;
        for (int r = 0; r <= r1; r++) {
            Row row = sheet.getRow(r);
            if (row == null || row.getLastCellNum() <= 0) {
                continue;
            }
            c1 = Math.max(c1, row.getLastCellNum() - 1);
        }
        // 合并区可能伸出实际有值的格子之外
        for (CellRangeAddress m : sheet.getMergedRegions()) {
            c1 = Math.max(c1, m.getLastColumn());
            r1 = Math.max(r1, m.getLastRow());
        }
        if (c1 < 0 || r1 < 0) {
            return null;
        }
        return new int[]{0, r1, 0, c1};
    }

    /**
     * 从 xlsx 的页眉页脚里读回页头页尾，并为它们让出正文空间。
     *
     * <p>页头画在**上页边距里**（和 Excel 一样），所以正常情况下不占正文的地方。
     * 但页边距不一定够高（10mm 的上边距放不下 5mm 边距 + 20pt 的字），这时把正文推到页头
     * 下面去 —— Excel 打印时也是这么做的，不推就会看到页头压在第一行上。
     */
    private void readHeaderFooter(XSSFSheet sheet, Geom g) {
        g.headerMargin = (float) (sheet.getMargin(PageMargin.HEADER) * 72);
        g.footerMargin = (float) (sheet.getMargin(PageMargin.FOOTER) * 72);

        String rawHeader = ((XSSFOddHeader) sheet.getOddHeader()).getText();
        String rawFooter = ((XSSFOddFooter) sheet.getOddFooter()).getText();
        g.header = rawHeader == null || rawHeader.isEmpty() ? null : HeaderFooterText.split(rawHeader);
        g.footer = rawFooter == null || rawFooter.isEmpty() ? null : HeaderFooterText.split(rawFooter);
        g.headerSize = sizeOf(g.header);
        g.footerSize = sizeOf(g.footer);
        // 印不印页码决定这张 sheet 占不占页数，见 numbering
        g.numbered = HeaderFooterText.hasPageNumber(g.header) || HeaderFooterText.hasPageNumber(g.footer);

        if (g.header != null) {
            g.marginTop = Math.max(g.marginTop, g.headerMargin + g.headerSize * HEADER_LINE_HEIGHT);
        }
        if (g.footer != null) {
            g.marginBottom = Math.max(g.marginBottom, g.footerMargin + g.footerSize * HEADER_LINE_HEIGHT);
        }
    }

    /** 页头/页尾的字号：取第一段写着的那个，没写按 9pt（与 {@code HeaderFooterDTO} 的缺省一致）。 */
    private float sizeOf(String[] sections) {
        if (sections == null) {
            return DEFAULT_HEADER_FONT_SIZE;
        }
        for (String s : sections) {
            Integer size = HeaderFooterText.parse(s, HeaderFooterText.Ctx.of(1, 1, "")).fontSize();
            if (size != null && size > 0) {
                return size;
            }
        }
        return DEFAULT_HEADER_FONT_SIZE;
    }

    /** POI 的纸张常量 -> OpenPDF 的纸张，未知按 A4（与 ExcelExporter 的兜底一致）。 */
    private Rectangle paperSize(short poiPaperSize) {
        if (poiPaperSize == PrintSetup.A3_PAPERSIZE) {
            return PageSize.A3;
        } else if (poiPaperSize == PrintSetup.A5_PAPERSIZE) {
            return PageSize.A5;
        } else if (poiPaperSize == PrintSetup.B4_PAPERSIZE) {
            return PageSize.B4;
        } else if (poiPaperSize == PrintSetup.B5_PAPERSIZE) {
            return PageSize.B5;
        } else if (poiPaperSize == PrintSetup.LETTER_PAPERSIZE) {
            return PageSize.LETTER;
        } else if (poiPaperSize == PrintSetup.LEGAL_PAPERSIZE) {
            return PageSize.LEGAL;
        }
        return PageSize.A4;
    }

    /* ---------------------------------- 绘制 ---------------------------------- */

    /**
     * 画一页：（可选的）顶端标题行 + 正文。
     *
     * <p>标题行是**每页重画一遍**的那几行（{@code _xlnm.Print_Titles}），它已经从分页的行流里
     * 摘掉了（见 {@link Geom#paginate}），所以两段各画各的、行区间不会重叠。
     */
    private void drawPage(PdfContentByte cb, Page page, BaseFont font,
                          DataFormatter formatter, FormulaEvaluator evaluator) {
        Geom g = page.geom();
        float top = g.paper.getHeight() - g.marginTop;

        if (g.titleEnabled()) {
            float titleHeight = g.titleHeight();
            drawBand(cb, page, new Band(g.r0, g.titleR1, top, top - titleHeight),
                    font, formatter, evaluator);
            top -= titleHeight;
        }
        // 接着上一页往下印的超高行：把它的**上边界**抬到正文区外面去，露出来的正好是还没印的那一截。
        // 格子照旧按整行的几何画（文字的行距、垂直居中都算在整行上），越界的部分由裁剪挡掉
        drawBand(cb, page, new Band(page.r0(), page.r1(), top + page.skip(), top - page.height()),
                font, formatter, evaluator);
    }

    /**
     * 画一段连续的行。
     *
     * <p>列范围恒是本页那一批列。一页由「标题行」和「正文」两段拼起来，两段用的是同一套画法，
     * 区别只在从哪一行起、上下边界在哪。
     */
    private void drawBand(PdfContentByte cb, Page page, Band band, BaseFont font,
                          DataFormatter formatter, FormulaEvaluator evaluator) {
        Geom g = page.geom();
        int cols = page.c1() - page.c0() + 1;
        int rows = band.r1() - band.r0() + 1;

        // 列的左边界 / 行的上边界（PDF 的 y 向上，所以行是递减的）
        float[] xs = new float[cols + 1];
        xs[0] = g.marginLeft;
        for (int i = 0; i < cols; i++) {
            xs[i + 1] = xs[i] + g.colWidth[page.c0() - g.c0 + i];
        }
        float[] ys = new float[rows + 1];
        ys[0] = band.top();
        for (int i = 0; i < rows; i++) {
            ys[i + 1] = ys[i] - g.rowHeight[band.r0() - g.r0 + i];
        }

        // 裁在这一段真正印到的范围里：超高行是跨页画的，不裁的话前一页的下半截会一直画到纸边上去、
        // 还会盖住页尾；切口为躲开文字往上挪之后页底空出的那一条里，也不能露出下一行文字。
        // 放宽半个线宽，免得贴着边界的那条边框被裁成半条
        cb.saveState();
        cb.rectangle(0, band.bottom() - BORDER_WIDTH, g.paper.getWidth(),
                band.top() - band.bottom() + 2 * BORDER_WIDTH);
        cb.clip();
        cb.newPath();

        boolean[][] taken = new boolean[rows][cols];

        // 合并区整块画一次。锚点可能落在本段之外（合并区被分页切开），照样按锚点取值和样式，
        // 画的矩形裁到本段范围内 —— 与 Excel 打印时把合并区切开的表现一致。
        for (CellRangeAddress m : g.merges) {
            if (m.getLastRow() < band.r0() || m.getFirstRow() > band.r1()
                    || m.getLastColumn() < page.c0() || m.getFirstColumn() > page.c1()) {
                continue;
            }
            for (int r = Math.max(m.getFirstRow(), band.r0()); r <= Math.min(m.getLastRow(), band.r1()); r++) {
                for (int c = Math.max(m.getFirstColumn(), page.c0()); c <= Math.min(m.getLastColumn(), page.c1()); c++) {
                    taken[r - band.r0()][c - page.c0()] = true;
                }
            }
            float[] rect = rect(page, xs, ys, band, m.getFirstRow(), m.getLastRow(),
                    m.getFirstColumn(), m.getLastColumn());
            drawCell(cb, page, band, m.getFirstRow(), m.getFirstColumn(), rect,
                    font, formatter, evaluator, false);
        }

        for (int r = band.r0(); r <= band.r1(); r++) {
            if (g.sheet.getRow(r) == null) {
                continue;
            }
            for (int c = page.c0(); c <= page.c1(); c++) {
                if (taken[r - band.r0()][c - page.c0()]) {
                    continue;
                }
                float[] rect = rect(page, xs, ys, band, r, r, c, c);
                drawCell(cb, page, band, r, c, rect, font, formatter, evaluator, true);
            }
        }

        drawImages(cb, page, band, xs, ys);
        cb.restoreState();
    }

    /**
     * 画锚在单元格上的图片：位置和大小就是锚点框，也就是 Excel 里看到的那一块
     * （图片等比例装进格子后的框，不是整格 —— 见 {@code ExcelExporter#applyImages}）。
     *
     * <p>放在所有格子之后画 = 压在底色和边框之上，与 Excel 里图片浮在网格上层一致。
     *
     * <p>图片整个落在一个格子里，所以只在**起点那一格所在的页**上画一次：按页裁一半的图片
     * 两页各出半张，比整张挪到下一页更难看，而 Excel 打印时也是不切图片的。
     */
    private void drawImages(PdfContentByte cb, Page page, Band band, float[] xs, float[] ys) {
        Geom g = page.geom();
        if (g.images == null || g.images.isEmpty()) {
            return;
        }
        for (CellImage ci : g.images) {
            if (ci.r1() < band.r0() || ci.r1() > band.r1() || ci.c1() < page.c0() || ci.c1() > page.c1()) {
                continue;
            }
            // 锚点偏移是像素，跟着行高列宽一起乘缩放（colWidth/rowHeight 已经乘过了）
            float left = xs[ci.c1() - page.c0()] + pt(ci.dx1()) * g.scale;
            float right = colX(page, xs, ci.c2()) + pt(ci.dx2()) * g.scale;
            float top = ys[ci.r1() - band.r0()] - pt(ci.dy1()) * g.scale;
            float bottom = rowY(band, ys, ci.r2()) - pt(ci.dy2()) * g.scale;
            if (right - left <= 0 || top - bottom <= 0) {
                continue;
            }
            try {
                Image image = Image.getInstance(ci.data());
                image.setAbsolutePosition(left, bottom);
                image.scaleAbsolute(right - left, top - bottom);
                cb.addImage(image);
            } catch (Exception e) {
                // 画不出来的图不该让整份 PDF 失败
                log.warn("PDF 中的图片绘制失败，已跳过 r={} c={}: {}", ci.r1(), ci.c1(), e.getMessage());
            }
        }
    }

    /** 像素 -> 磅（96dpi 下 1px = 0.75pt），与列宽行高的换算同一套。 */
    private float pt(double px) {
        return (float) (px * 0.75);
    }

    /** 第 c 列左边界的页面坐标；越过本页右边界时钉在边界上（图片正常不会跨页）。 */
    private float colX(Page page, float[] xs, int c) {
        return xs[Math.min(Math.max(c - page.c0(), 0), xs.length - 1)];
    }

    /** 第 r 行上边界的页面坐标（越过本段范围时钉在边界上）。 */
    private float rowY(Band band, float[] ys, int r) {
        return ys[Math.min(Math.max(r - band.r0(), 0), ys.length - 1)];
    }

    /**
     * 画页头 / 页尾。
     *
     * <p>位置按 Excel 的规矩：页头的顶边距纸张上边 {@code headerMargin}，页尾的底边距纸张下边
     * {@code footerMargin} —— 都在页边距里，不占正文（正文已经在 {@link #readHeaderFooter}
     * 里为放不下的情形让过位）。
     *
     * <p>{@code ${page}} / {@code ${pages}} 数的是**哪一段**由 {@link #numbering} 定：普通报表
     * 是整份 PDF（多 sheet 也连续编号 —— 导出的是一份连续的文档），「一条数据一份单据」时
     * 是**本单据**。这里只负责把算好的数字画上去。
     */
    private void drawHeaderFooter(PdfContentByte cb, Page page, BaseFont font, HeaderFooterText.Ctx ctx) {
        Geom g = page.geom();
        if (g.header != null) {
            float baseline = g.paper.getHeight() - g.headerMargin - font.getAscentPoint("Ag", g.headerSize);
            drawSections(cb, g, g.header, g.headerSize, baseline, font, ctx);
        }
        if (g.footer != null) {
            float baseline = g.footerMargin - font.getDescentPoint("Ag", g.footerSize);
            drawSections(cb, g, g.footer, g.footerSize, baseline, font, ctx);
        }
    }

    /** 左段贴左页边距、右段贴右页边距、中段在两者之间居中，与 Excel 一致。 */
    private void drawSections(PdfContentByte cb, Geom g, String[] sections, float size,
                              float baseline, BaseFont font, HeaderFooterText.Ctx ctx) {
        float left = g.marginLeft;
        float right = g.paper.getWidth() - g.marginRight;
        cb.saveState();
        cb.setColorFill(Color.BLACK);
        cb.beginText();
        cb.setFontAndSize(font, size);
        for (int i = 0; i < sections.length; i++) {
            String text = HeaderFooterText.parse(sections[i], ctx).text();
            if (text.isEmpty()) {
                continue;
            }
            float w = font.getWidthPoint(text, size);
            float x = switch (i) {
                case HeaderFooterText.CENTER -> (left + right) / 2 - w / 2;
                case HeaderFooterText.RIGHT -> right - w;
                default -> left;
            };
            cb.setTextMatrix(x, baseline);
            cb.showText(text);
        }
        cb.endText();
        cb.restoreState();
    }

    /**
     * 画水印：一行文字斜着压在页面中央。
     *
     * <p>水印是**画在内容之上**的（半透明）。画在下面的话，凡是设过底色的单元格都会把它盖掉，
     * 而报表的表头几乎都有底色 —— 一份表水印忽隐忽现比压在字上更难看。
     */
    private void drawWatermark(PdfContentByte cb, Page page, BaseFont font) {
        WatermarkDTO wm = page.geom().watermark;
        if (wm == null || wm.isBlank()) {
            return;
        }
        Rectangle paper = page.geom().paper;
        String text = wm.getText();
        float size = wm.getFontSize() == null || wm.getFontSize() <= 0 ? 60 : wm.getFontSize();
        double rad = Math.toRadians(clamp(wm.getRotation() == null ? 45 : wm.getRotation(), -90, 90));
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        // 比纸张对角线还长的水印按对角线压一压，不然会画到纸外面去
        float limit = (float) Math.hypot(paper.getWidth(), paper.getHeight()) * 0.9f;
        float width = font.getWidthPoint(text, size);
        if (width > limit && width > 0) {
            size *= limit / width;
            width = limit;
        }
        float height = font.getAscentPoint(text, size);
        // 文字块的中心对到页面中心：沿基线方向退半个宽，垂直基线方向退半个高
        float x = paper.getWidth() / 2 - width / 2 * cos + height / 2 * sin;
        float y = paper.getHeight() / 2 - width / 2 * sin - height / 2 * cos;

        PdfGState gs = new PdfGState();
        gs.setFillOpacity(clamp(wm.getOpacity() == null ? 30 : wm.getOpacity(), 0, 100) / 100f);
        cb.saveState();
        cb.setGState(gs);
        cb.setColorFill(hexColor(wm.getColor(), Color.LIGHT_GRAY));
        cb.beginText();
        cb.setFontAndSize(font, size);
        cb.setTextMatrix(cos, sin, -sin, cos, x, y);
        cb.showText(text);
        cb.endText();
        cb.restoreState();
    }

    /** 行列区间 -> 页面坐标 {left, bottom, right, top}，裁剪到本段（{@code bandR0..bandR1}）范围内。 */
    private float[] rect(Page page, float[] xs, float[] ys, Band band, int rA, int rB, int cA, int cB) {
        float left = xs[Math.max(cA, page.c0()) - page.c0()];
        float right = xs[Math.min(cB, page.c1()) - page.c0() + 1];
        float top = ys[Math.max(rA, band.r0()) - band.r0()];
        float bottom = ys[Math.min(rB, band.r1()) - band.r0() + 1];
        return new float[]{left, bottom, right, top};
    }

    /**
     * 画一格：底色 -> 边框 -> 文字。
     *
     * @param overflow 文字超出格宽时是否允许压到相邻空格上（Excel 的默认表现）；合并区不需要
     */
    private void drawCell(PdfContentByte cb, Page page, Band band, int r, int c, float[] rect, BaseFont font,
                          DataFormatter formatter, FormulaEvaluator evaluator, boolean overflow) {
        Row row = page.geom().sheet.getRow(r);
        Cell cell = row == null ? null : row.getCell(c);
        if (cell == null) {
            return;
        }
        XSSFCellStyle style = (XSSFCellStyle) cell.getCellStyle();
        float left = rect[0];
        float bottom = rect[1];
        float right = rect[2];
        float top = rect[3];

        if (style != null && style.getFillPattern() == FillPatternType.SOLID_FOREGROUND) {
            Color bg = toColor(style.getFillForegroundXSSFColor());
            if (bg != null) {
                cb.setColorFill(bg);
                cb.rectangle(left, bottom, right - left, top - bottom);
                cb.fill();
            }
        }

        if (style != null) {
            drawBorders(cb, style, left, bottom, right, top);
        }

        String text = textOf(cell, formatter, evaluator);
        if (!text.isEmpty()) {
            drawText(cb, page, band, r, c, text, style, font, left, bottom, right, top, overflow);
        }
    }

    private void drawBorders(PdfContentByte cb, XSSFCellStyle style,
                             float left, float bottom, float right, float top) {
        cb.setLineWidth(BORDER_WIDTH);
        line(cb, style.getBorderTop(), toColor(style.getTopBorderXSSFColor()), left, top, right, top);
        line(cb, style.getBorderBottom(), toColor(style.getBottomBorderXSSFColor()), left, bottom, right, bottom);
        line(cb, style.getBorderLeft(), toColor(style.getLeftBorderXSSFColor()), left, top, left, bottom);
        line(cb, style.getBorderRight(), toColor(style.getRightBorderXSSFColor()), right, top, right, bottom);
    }

    private void line(PdfContentByte cb, BorderStyle border, Color color,
                      float x1, float y1, float x2, float y2) {
        if (border == null || border == BorderStyle.NONE) {
            return;
        }
        cb.setColorStroke(color == null ? Color.BLACK : color);
        cb.moveTo(x1, y1);
        cb.lineTo(x2, y2);
        cb.stroke();
    }

    /**
     * 画单元格文字：按对齐方式定位，裁剪到（可能扩展过的）格子范围内，粗体/斜体用描边和倾斜模拟。
     *
     * <p>字体统一用探测到的那一款，不还原 Excel 里各格设置的字体名 —— 逐格加载字体会让 PDF
     * 体积和导出耗时都失控，而报表里字体名的差异远没有字号/粗细/颜色重要。
     */
    private void drawText(PdfContentByte cb, Page page, Band band, int r, int c, String text, XSSFCellStyle style,
                          BaseFont font, float left, float bottom, float right, float top, boolean overflow) {
        XSSFFont f = style == null ? null : style.getFont();
        float size = (f == null || f.getFontHeightInPoints() <= 0 ? 11 : f.getFontHeightInPoints())
                * page.geom().scale;
        boolean bold = f != null && f.getBold();
        boolean italic = f != null && f.getItalic();
        Color color = f == null ? null : toColor(f.getXSSFColor());
        if (color == null) {
            color = Color.BLACK;
        }

        // 开了自动换行的格子按格宽折行，而且不再往相邻空格上溢出 —— 这两件事在 Excel 里是一体的
        boolean wrap = style != null && style.getWrapText();
        List<String> lines = List.of(text.split("\r\n|\r|\n", -1));
        if (wrap) {
            lines = wrapLines(lines, font, size, right - left - 2 * PADDING);
        }
        float widest = 0;
        for (String s : lines) {
            widest = Math.max(widest, font.getWidthPoint(s, size));
        }

        HorizontalAlignment ha = align(style, text);
        float clipLeft = left;
        float clipRight = right;
        if (!wrap && overflow && widest > right - left - 2 * PADDING) {
            // Excel 里放不下的文字会压到相邻的空格上，这里照做，否则长标题会被切掉
            float need = widest - (right - left - 2 * PADDING);
            if (ha != HorizontalAlignment.RIGHT) {
                clipRight += spare(page, r, c, 1, need);
            }
            if (ha != HorizontalAlignment.LEFT) {
                clipLeft -= spare(page, r, c, -1, need);
            }
        }

        float lineHeight = size * LINE_SPACING;
        float ascent = font.getAscentPoint(text, size);
        // descent 是负数（基线以下）
        float descent = font.getDescentPoint(text, size);
        float blockHeight = lineHeight * (lines.size() - 1) + ascent - descent;
        // 首行基线；多行时后续行依次向下 lineHeight
        float baseline = switch (verticalAlign(style)) {
            case TOP -> top - PADDING - ascent;
            case BOTTOM -> bottom + PADDING - descent + lineHeight * (lines.size() - 1);
            // 整块文字在格子里垂直居中，再从块顶推出首行基线
            default -> (top + bottom) / 2 + blockHeight / 2 - ascent;
        };

        cb.saveState();
        cb.rectangle(clipLeft, bottom, clipRight - clipLeft, top - bottom);
        cb.clip();
        cb.newPath();
        cb.setColorFill(color);
        if (bold) {
            // 统一字体只有一款字重，粗体靠给字形描一圈边模拟
            cb.setTextRenderingMode(PdfContentByte.TEXT_RENDER_MODE_FILL_STROKE);
            cb.setColorStroke(color);
            cb.setLineWidth(size * BOLD_STROKE);
        }
        // 本段真正印到的上下边界：超高行是跨页画的，落在本段外面的那些行整行跳过。裁剪只是让
        // 它们看不见，字仍然写在这一页的内容流里 —— PDF 里选中/搜索/复制得到的是整段文字，
        // 每一页都带一份，体积也白白翻倍
        float bodyTop = band.top();
        float bodyBottom = band.bottom();
        // 这一行的切口对齐过文字行（Geom#snapToLines）：那就让一行文字只属于一页 —— 相邻两行的
        // 字面高度比行距略大、边角本来就叠着，露不到一半的那点边角是隔壁页那一行的，别跟着画。
        // 没对齐过的（硬切）照旧两页各画半个字，不然那半个字就彻底没了
        boolean snapped = page.geom().lineGrid.containsKey(r - page.geom().r0);

        cb.beginText();
        cb.setFontAndSize(font, size);
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s.isEmpty()) {
                continue;
            }
            float lineY = baseline - i * lineHeight;
            float inkTop = lineY + ascent;
            float inkBottom = lineY + descent;
            float visible = Math.min(inkTop, bodyTop) - Math.max(inkBottom, bodyBottom);
            if (visible < (snapped ? (inkTop - inkBottom) / 2 : 0.05f)) {
                continue;
            }
            float w = font.getWidthPoint(s, size);
            float x = switch (ha) {
                case RIGHT -> clipRight - PADDING - w;
                case CENTER -> (clipLeft + clipRight) / 2 - w / 2;
                default -> clipLeft + PADDING;
            };
            if (italic) {
                cb.setTextMatrix(1, 0, ITALIC_SKEW, 1, x, lineY);
            } else {
                cb.setTextMatrix(x, lineY);
            }
            cb.showText(s);
        }
        cb.endText();
        cb.restoreState();
    }

    /**
     * 自动换行：按可用宽度把每个自然段再折成多行。
     *
     * <p>中文可以在任意两个字之间断开，西文优先在空格处断，与 Excel / 浏览器的表现一致。
     * 宽度用当前字体实测（{@code getWidthPoint}），不按字数估。
     *
     * <p>行高不在这里管：xlsx 里的行高已经由 {@code ExcelExporter#applyWrapRowHeights}
     * 按同样的折行规则算过了，这里只负责把字画到那么高的格子里。
     */
    List<String> wrapLines(List<String> paragraphs, BaseFont font, float size, float maxWidth) {
        if (maxWidth <= 0) {
            return paragraphs;
        }
        List<String> out = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty() || font.getWidthPoint(paragraph, size) <= maxWidth) {
                out.add(paragraph);
                continue;
            }
            StringBuilder cur = new StringBuilder();
            int lastSpace = -1;
            for (int i = 0; i < paragraph.length(); i++) {
                char ch = paragraph.charAt(i);
                cur.append(ch);
                if (ch == ' ') {
                    lastSpace = cur.length() - 1;
                }
                if (cur.length() > 1 && font.getWidthPoint(cur.toString(), size) > maxWidth) {
                    // 断在最后一个空格处（空格本身丢掉），没有空格就断在最后一个字前面
                    int cut = lastSpace > 0 ? lastSpace : cur.length() - 1;
                    out.add(cur.substring(0, cut));
                    String rest = cur.substring(lastSpace > 0 ? cut + 1 : cut);
                    cur = new StringBuilder(rest);
                    lastSpace = -1;
                }
            }
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * 往 {@code dir} 方向数相邻空格能借出多少宽度，最多借到 {@code need} 或本页边界。
     *
     * @param dir +1 向右，-1 向左
     */
    private float spare(Page page, int r, int c, int dir, float need) {
        Geom g = page.geom();
        float got = 0;
        for (int i = c + dir; i >= page.c0() && i <= page.c1() && got < need; i += dir) {
            Row row = g.sheet.getRow(r);
            Cell neighbour = row == null ? null : row.getCell(i);
            if (neighbour != null && neighbour.getCellType() != CellType.BLANK) {
                break;
            }
            got += g.colWidth[i - g.c0];
        }
        return Math.min(got, need);
    }

    /** 未显式设置对齐时按 Excel 的规矩：数字右对齐，其余左对齐。 */
    private HorizontalAlignment align(XSSFCellStyle style, String text) {
        HorizontalAlignment ha = style == null ? HorizontalAlignment.GENERAL : style.getAlignment();
        if (ha != HorizontalAlignment.GENERAL) {
            return ha;
        }
        return isNumeric(text) ? HorizontalAlignment.RIGHT : HorizontalAlignment.LEFT;
    }

    private VerticalAlignment verticalAlign(XSSFCellStyle style) {
        return style == null ? VerticalAlignment.CENTER : style.getVerticalAlignment();
    }

    private boolean isNumeric(String s) {
        String t = s.trim().replace(",", "").replace("%", "");
        if (t.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(t);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 取单元格的显示文本（带格式）。公式交给 POI 求值，POI 不支持的函数退回缓存值/空串。 */
    private String textOf(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (Exception e) {
            try {
                return formatter.formatCellValue(cell);
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    /** "#RRGGBB" -> Color，解析不了用兜底色。 */
    private Color hexColor(String hex, Color fallback) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) {
            return fallback;
        }
        try {
            return new Color(Integer.parseInt(hex.substring(1), 16));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int clamp(int v, int min, int max) {
        return Math.min(Math.max(v, min), max);
    }

    private Color toColor(XSSFColor color) {
        if (color == null) {
            return null;
        }
        byte[] rgb = color.getRGB();
        if (rgb == null || rgb.length < 3) {
            return null;
        }
        return new Color(rgb[0] & 0xff, rgb[1] & 0xff, rgb[2] & 0xff);
    }

    /* ---------------------------------- 字体 ---------------------------------- */

    /**
     * 取正文字体：配置的优先，其次按系统字体目录探测，都没有就退回 Helvetica。
     *
     * <p>退回 Helvetica 时中文会显示不出来，但导出本身不该因此失败 —— 报表可能全是英文数字，
     * 而且「导出了但中文是空的」比「导出直接报错」更容易让人定位到是字体问题。
     */
    private BaseFont font() {
        BaseFont cached = baseFont;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (baseFont != null) {
                return baseFont;
            }
            List<String> candidates = new ArrayList<>();
            String configured = props.getPdf().getFontPath();
            if (configured != null && !configured.isBlank()) {
                candidates.add(configured.trim());
            }
            candidates.addAll(FONT_CANDIDATES);

            for (String path : candidates) {
                // ".ttc,0" 里逗号后面是字体集里的序号，不是路径的一部分
                int comma = path.lastIndexOf(',');
                String file = comma > 1 ? path.substring(0, comma) : path;
                if (!new File(file).isFile()) {
                    continue;
                }
                try {
                    // IDENTITY_H + 内嵌：中文必须内嵌字体子集才能在别人机器上显示
                    baseFont = BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                    log.info("PDF 导出使用字体: {}", path);
                    return baseFont;
                } catch (Exception e) {
                    log.warn("字体[{}]加载失败: {}", path, e.getMessage());
                }
            }

            log.warn("未找到可用的中文字体，PDF 中的中文将无法显示。"
                    + "请安装中文字体(如 fonts-wqy-zenhei)，或用 muzhou.report.pdf.font-path 指定字体文件");
            try {
                baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
                return baseFont;
            } catch (Exception e) {
                throw new BizException("PDF 导出失败: 字体加载失败 " + e.getMessage());
            }
        }
    }
}
