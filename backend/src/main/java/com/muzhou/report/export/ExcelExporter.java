package com.muzhou.report.export;

import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.PageConfigDTO;
import com.muzhou.report.engine.CellFormatter;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFOddFooter;
import org.apache.poi.xssf.usermodel.XSSFOddHeader;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * 把渲染结果导出为 .xlsx。
 *
 * <p>注意：POI 的 CellStyle 数量上限约 64000，必须按「样式指纹」复用，
 * 否则一张几千行的报表就会把工作簿撑爆。
 *
 * <p>页面设置（含页头页尾）写在这里一处，PDF / Word 两条路都是把这份 xlsx 读回来再转，
 * 照着里面的设置分页 —— 所以这里的 bug 会同时体现在那两种格式上。
 * 唯一不在 xlsx 里的是**水印**：Excel 没有水印这个概念，导出的 xlsx 不带水印，
 * 它由 PDF / Word 的转换器各自去画（见 {@code RenderServiceImpl}）。
 *
 * <p><b>图片也走这一处</b>：图片单元格（{@code type=img} / {@code base64}）在这里被取回字节、
 * 锚到单元格上写进 xlsx，下游的 PDF / Word 四条路都从这份 xlsx 里读回来（见
 * {@link #applyImages}），不必各自再实现一遍「地址怎么变成字节」。
 */
@Slf4j
@Component
public class ExcelExporter {

    /**
     * FortuneSheet 默认列宽 / 行高（px），见 @fortune-sheet/core 的 defaultSettings。
     *
     * <p>设计器里没被拖动过的行列不会写进 {@code config.columnlen / rowlen}，但它们在画布上
     * 仍然占这么宽/这么高。Excel 自己的默认列宽约 64px、默认行高 15pt(20px)，跟这里对不上，
     * 不显式补齐的话导出后整表几何就和设计器不是一回事，分页位置自然也对不上。
     */
    private static final int DEFAULT_COL_WIDTH_PX = 73;
    private static final int DEFAULT_ROW_HEIGHT_PX = 19;

    /** 没设过字号的单元格在 Excel 里按默认字体渲染（Calibri 11pt），量文字时按这个算。 */
    private static final float DEFAULT_FONT_SIZE_PT = 11f;

    /**
     * 行距系数与上下内边距（磅）。与前端 `utils/wrapHeight.js` 用的是同一个公式 ——
     * 两边算出同一个行高，设计器画布上的分页位置才和导出的文件对得上。
     *
     * <p>1.35 是照 Excel 的自动行高来的（11pt 的一行占 15pt），比画布上按 measureText 量出来的
     * 略松：中文字体的 ascent/descent 比西文大，卡着字面高度算，Excel 里就会看到最后一行被削掉。
     */
    private static final float LINE_HEIGHT = (float) PageConfigDTO.LINE_HEIGHT;
    private static final float PADDING_Y_PT = 2f;

    /** 单元格左右内边距合计（磅）。 */
    private static final float PADDING_X_PT = 3f;

    /**
     * Excel 的行高上限（磅）—— 这是**文件格式的硬限制**，不是我们的选择。
     *
     * <p>写超了 Excel 打开时会当成异常值（轻则悄悄压回去，重则提示「发现不可读取的内容」），
     * 所以宁可在这里夹住。长备注格按真实文字撑高时很容易越过它
     * （{@link #applyWrapRowHeights}，一段几百字的说明落在窄列里就有七百多磅）。
     *
     * <p><b>PDF 不受这条限制</b>：它虽然是从这份 xlsx 读回行高的，但
     * {@code PdfExporter#growWrapRows} 会按真字体重新量一遍再撑回去，超高行由它劈开跨页印。
     * 也就是说「Excel 里那一格看不全」是 Excel 自己的天花板，PDF 那条路仍然是完整的。
     */
    private static final float MAX_ROW_HEIGHT_PT = 409.5f;

    private final ImageLoader imageLoader;

    public ExcelExporter(MzProperties props) {
        this.imageLoader = new ImageLoader(props == null ? null : props.getImage());
    }

    public byte[] export(List<Map<String, Object>> sheets) {
        return export(sheets, (PageConfigDTO) null);
    }

    /**
     * @param pageConfig 打印设置，写入每个 sheet 的页面设置；为 null 时用 POI 默认值
     */
    public byte[] export(List<Map<String, Object>> sheets, PageConfigDTO pageConfig) {
        return export(sheets, i -> pageConfig);
    }

    /**
     * @param pageConfigOf 按 sheet 下标取打印设置 —— 打印设置是可以按 sheet 单独设的
     *                     （见 {@code ReportContentDTO#pageConfigOf}），一份宽表和一份窄表
     *                     可以各用各的纸张方向。返回 null 表示该 sheet 用 POI 默认值。
     */
    public byte[] export(List<Map<String, Object>> sheets, IntFunction<PageConfigDTO> pageConfigOf) {
        long start = System.currentTimeMillis();
        long[] segs = new long[4];
        try (XSSFWorkbook wb = buildWorkbook(sheets, pageConfigOf, segs);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            long writeStart = System.currentTimeMillis();
            wb.write(out);
            byte[] bytes = out.toByteArray();
            log.debug("xlsx({} bytes) 内层耗时: 写格子 {}ms → 边框合并 {}ms → 行高 {}ms → 图片 {}ms → 序列化 {}ms → 合计 {}ms",
                    bytes.length, segs[0], segs[1], segs[2], segs[3],
                    System.currentTimeMillis() - writeStart, System.currentTimeMillis() - start);
            return bytes;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Excel 导出失败", e);
            throw new BizException("Excel 导出失败: " + e.getMessage());
        }
    }

    /**
     * 只构建工作簿、**不序列化**：PDF / Word 那两条路直接吃这个对象接着转，省掉
     * 「写出成 xlsx 字节再解析回来」的往返。页面设置、页眉页脚、分页符照旧全写在
     * workbook 里，「PDF/Word 从 ExcelExporter 的产物读回来」的契约不变，只是跳过了
     * 字节这一站。**调用方负责 close**。
     */
    public XSSFWorkbook exportWorkbook(List<Map<String, Object>> sheets, IntFunction<PageConfigDTO> pageConfigOf) {
        long start = System.currentTimeMillis();
        long[] segs = new long[4];
        XSSFWorkbook wb = buildWorkbook(sheets, pageConfigOf, segs);
        log.debug("xlsx 工作簿构建(不序列化): 写格子 {}ms → 边框合并 {}ms → 行高 {}ms → 图片 {}ms → 合计 {}ms",
                segs[0], segs[1], segs[2], segs[3], System.currentTimeMillis() - start);
        return wb;
    }

    /** @param segs 内层分段计时（跨 sheet 累加）：0=写格子 1=边框合并列宽 2=行高 3=图片 */
    private XSSFWorkbook buildWorkbook(List<Map<String, Object>> sheets,
                                       IntFunction<PageConfigDTO> pageConfigOf, long[] segs) {
        XSSFWorkbook wb = new XSSFWorkbook();
        try {
            Map<String, CellStyle> styleCache = new HashMap<>();
            // 边框样式指纹与基础样式无关（边框是「叠加」在已有样式之上的），必须单独复用，
            // 否则会退化成直接修改 cell.getCellStyle() 拿到的共享样式对象，导致边框「串」到
            // 所有共享同一底色/字体样式（含默认样式）的其它单元格上。
            // key = 基础样式下标 + 四条边的指纹（线型和颜色都算进去，同一份底色配不同边框是不同样式）
            Map<String, XSSFCellStyle> borderCache = new HashMap<>();
            // 图片按地址缓存：同一张图（表头的 logo、按分组重复的产品图）在一份报表里常常出现
            // 几十上百次，缓存之后一个地址只下载/解码一遍
            Map<String, Integer> pictureCache = new HashMap<>();
            if (sheets == null || sheets.isEmpty()) {
                applyPageSetup(wb, wb.createSheet("Sheet1"), 0, pageConfigOf.apply(0));
            } else {
                // 哪几张要把起始页号钉成 1（单据、封面都在这里一起算）
                boolean[] pins = pagePins(sheets, pageConfigOf);
                for (int i = 0; i < sheets.size(); i++) {
                    XSSFSheet sheet = writeSheet(wb, sheets.get(i), i, styleCache, borderCache, pictureCache, segs);
                    applyPageSetup(wb, sheet, i, pageConfigOf.apply(i));
                    if (pins[i]) {
                        pinPageStart(sheet);
                    }
                }
            }
            return wb;
        } catch (Exception e) {
            try {
                wb.close();
            } catch (Exception ignore) {
                // 构建已经失败了，关不上也只能随它去
            }
            log.error("Excel 导出失败", e);
            throw new BizException("Excel 导出失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private XSSFSheet writeSheet(XSSFWorkbook wb, Map<String, Object> sheetData, int index,
                                 Map<String, CellStyle> styleCache, Map<String, XSSFCellStyle> borderCache,
                                 Map<String, Integer> pictureCache, long[] segs) {
        String name = sheetData.get("name") == null ? ("Sheet" + (index + 1))
                : String.valueOf(sheetData.get("name"));
        XSSFSheet sheet = wb.createSheet(safeSheetName(wb, name, index));

        long t = System.currentTimeMillis();
        List<WrapCell> wrapCells = new ArrayList<>();
        List<ImageCell> imageCells = new ArrayList<>();
        Object cdObj = sheetData.get("celldata");
        if (cdObj instanceof List<?> celldata) {
            for (Object o : celldata) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> cd = (Map<String, Object>) o;
                Integer r = num(cd.get("r"));
                Integer c = num(cd.get("c"));
                if (r == null || c == null) {
                    continue;
                }
                Object vObj = cd.get("v");
                Map<String, Object> v = vObj instanceof Map ? (Map<String, Object>) vObj : Map.of();

                Row row = sheet.getRow(r);
                if (row == null) {
                    row = sheet.createRow(r);
                }
                Cell cell = row.createCell(c);
                writeValue(wb, cell, v, styleCache);
                collectWrapCell(wrapCells, r, c, v);
                collectImageCell(imageCells, r, c, v);
            }
        }

        segs[0] += System.currentTimeMillis() - t;

        Map<String, Object> config = sheetData.get("config") instanceof Map
                ? (Map<String, Object>) sheetData.get("config") : Map.of();
        sheet.setDefaultRowHeightInPoints(pxToPoints(DEFAULT_ROW_HEIGHT_PX));
        t = System.currentTimeMillis();
        applyConfig(wb, sheet, config, borderCache);
        // 列宽放在最后：applyBorders 会为边框区域补出单元格，最大列号到这一步才定下来
        applyColumnWidths(sheet, config.get("columnlen"));
        segs[1] += System.currentTimeMillis() - t;
        // 行高再放到列宽之后：自动换行折几行取决于列宽
        t = System.currentTimeMillis();
        applyWrapRowHeights(sheet, wrapCells);
        segs[2] += System.currentTimeMillis() - t;
        // 图片放到最后：它锚在单元格上，行高列宽定下来了才知道要画多大
        t = System.currentTimeMillis();
        applyImages(wb, sheet, imageCells, pictureCache);
        segs[3] += System.currentTimeMillis() - t;
        applyRowBreaks(sheet, sheetData.get("mzRowBreaks"));
        // 起始页号（mzDocBreaks）不在这里管：钉在哪一张要看整本的情况，见 pagePins
        return sheet;
    }

    /**
     * 写行分页符（{@code sheet.mzRowBreaks}，由 {@code engine/SheetConcat} 挂上来）。
     *
     * <p>值是**每一段的起始行**，而 POI 的 {@code setRowBreak(r)} 是「在第 r 行**下方**断页」
     * （XSSF 底层存的是 {@code id = r + 1}），所以要减一。0 行没有上一行，跳过。
     *
     * <p>PDF / Word 那几条路都是把这份 xlsx 读回来的，分页符也从这里读（{@code getRowBreaks()}），
     * 别在各条路上再实现一遍。
     */
    private void applyRowBreaks(XSSFSheet sheet, Object breaksObj) {
        if (!(breaksObj instanceof List<?> breaks)) {
            return;
        }
        for (Object o : breaks) {
            Integer r = num(o);
            if (r != null && r > 0) {
                sheet.setRowBreak(r - 1);
            }
        }
    }

    /** 这张 sheet 的起始页号钉成 1（{@code useFirstPageNumber}），Excel 从它重新数 {@code &P}。 */
    private void pinPageStart(XSSFSheet sheet) {
        PrintSetup ps = sheet.getPrintSetup();
        ps.setUsePage(true);
        ps.setPageStart((short) 1);
    }

    /**
     * 哪几张 sheet 要把起始页号钉成 1。
     *
     * <p>Excel 的 {@code &P} 在「打印整个工作簿」时是**跨 sheet 连续**的，所以「一条数据一个 sheet」
     * 出来的第 7 张单据上会印「第 7 页」，封面后面那张正文会印「第 2 页」。把某张 sheet 的
     * 起始页号钉成 1（{@code useFirstPageNumber}）Excel 就从它重新数；不钉的保持 auto，接着上一张。
     *
     * <p>钉在哪一张要看整本的情况，所以放在这里一次算完，而不是各 sheet 自己判断：
     * <ul>
     *   <li><b>单据模式</b>（{@code mzDocBreaks} 非空的那几张是各份单据的起点）：钉在每份单据里
     *       <b>第一张印页码</b>的 sheet 上。一条数据出「封面/正文/总结」三张时单据的起点是封面，
     *       钉在封面上的话正文第一页在 Excel 里印出来还是「第 2 页」—— 封面自己不印页码，
     *       钉了也看不见。整份单据都不印页码时退回钉在起点上（老行为）。</li>
     *   <li><b>普通报表</b>：Excel 本来就从整本第 1 页数起，只有「前面几张不印页码」时才要钉
     *       （封面 + 正文 + 总结那种）。</li>
     * </ul>
     *
     * <p><b>做不到的那半</b>：{@code &N}（总页数）在 Excel 里恒是整个打印任务的页数，没有
     * 「本单据共几页」这种代码，封面那几页也照样算在里面；一张 sheet 内部（{@code perRowPage}
     * 拼出来的那张）更是连重编页码都表达不了。要两个数都准就走 PDF
     * （{@code PdfExporter#numbering} 是逐页自己画的）。
     */
    private boolean[] pagePins(List<Map<String, Object>> sheets, IntFunction<PageConfigDTO> pageConfigOf) {
        int n = sheets.size();
        boolean[] pins = new boolean[n];
        boolean[] numbered = new boolean[n];
        for (int i = 0; i < n; i++) {
            PageConfigDTO cfg = pageConfigOf.apply(i);
            numbered[i] = cfg != null && HeaderFooterText.hasPageNumber(
                    HeaderFooterText.toExcelCode(cfg.getHeader()),
                    HeaderFooterText.toExcelCode(cfg.getFooter()));
        }

        List<Integer> docStarts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (sheets.get(i).get("mzDocBreaks") instanceof List<?> breaks && !breaks.isEmpty()) {
                docStarts.add(i);
            }
        }
        if (docStarts.isEmpty()) {
            for (int i = 0; i < n; i++) {
                if (numbered[i]) {
                    // 第一张就印页码的话不必钉：Excel 本来就从 1 数
                    if (i > 0) {
                        pins[i] = true;
                    }
                    break;
                }
            }
            return pins;
        }
        for (int d = 0; d < docStarts.size(); d++) {
            int start = docStarts.get(d);
            int end = d + 1 < docStarts.size() ? docStarts.get(d + 1) - 1 : n - 1;
            int pin = start;
            for (int i = start; i <= end; i++) {
                if (numbered[i]) {
                    pin = i;
                    break;
                }
            }
            pins[pin] = true;
        }
        return pins;
    }

    /** 开了自动换行、可能需要撑高所在行的单元格。 */
    private record WrapCell(int r, int c, String text, float fontSize) {
    }

    /** 图片单元格：{@code v.mzImg.src}，见 {@code GridCell#image}。 */
    private record ImageCell(int r, int c, String src) {
    }

    @SuppressWarnings("unchecked")
    private void collectImageCell(List<ImageCell> out, int r, int c, Map<String, Object> v) {
        if (!(v.get("mzImg") instanceof Map<?, ?> img)) {
            return;
        }
        Object src = ((Map<String, Object>) img).get("src");
        if (src != null && !String.valueOf(src).isBlank()) {
            out.add(new ImageCell(r, c, String.valueOf(src)));
        }
    }

    /**
     * 把图片单元格的图片锚到格子上写进 xlsx。
     *
     * <p>锚定的那个框**不是整格，而是图片等比例装进这一格之后的框**（{@link ImageFit#contain}，
     * 居中）：Excel 是把图片拉伸到填满锚点框的，锚成整格就等于把图片拉变形。所以这里先算出
     * 「装得下且不变形」的宽高，再把它换算成锚点的 (列, 列内偏移) / (行, 行内偏移)。
     * PDF / Word 那两条路都是读它（见 {@code PdfExporter#readImages}、
     * {@code WordExporter#readPictures}），于是 Excel / PDF / Word 里图片的大小位置完全一致。
     *
     * <p>锚定方式用 {@code MOVE_DONT_RESIZE}（跟着格子走，但不跟着格子缩放）：
     * 两点锚定 + 跟随缩放的话，只要列宽变了图片就会被横向拉长。
     *
     * <p>取不到图（地址打不开、超时、不是认得出的图片格式）只跳过这一张，导出照常进行。
     */
    private void applyImages(XSSFWorkbook wb, XSSFSheet sheet, List<ImageCell> cells,
                             Map<String, Integer> pictureCache) {
        if (cells.isEmpty()) {
            return;
        }
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        for (ImageCell ic : cells) {
            Integer picture = pictureCache.get(ic.src());
            if (picture == null) {
                ImageLoader.Picture loaded = ImageLoader.toPicture(imageLoader.load(ic.src()));
                if (loaded == null) {
                    // 取不到/放不进的也记进缓存（记成 -1），同一个坏地址出现 500 次不必重试 500 遍
                    pictureCache.put(ic.src(), -1);
                    continue;
                }
                picture = wb.addPicture(loaded.data(), loaded.type());
                pictureCache.put(ic.src(), picture);
            }
            if (picture < 0) {
                continue;
            }
            CellRangeAddress region = regionAt(sheet, ic.r(), ic.c());
            int r1 = region == null ? ic.r() : region.getFirstRow();
            int c1 = region == null ? ic.c() : region.getFirstColumn();
            int r2 = region == null ? ic.r() : region.getLastRow();
            int c2 = region == null ? ic.c() : region.getLastColumn();

            double boxW = 0;
            for (int c = c1; c <= c2; c++) {
                boxW += sheet.getColumnWidthInPixels(c);
            }
            double boxH = 0;
            for (int r = r1; r <= r2; r++) {
                boxH += rowHeightPx(sheet, r);
            }
            ImageFit.Box box = ImageFit.contain(boxW, boxH,
                    wb.getAllPictures().get(picture).getData());

            int[] left = marker(sheet, c1, c2, box.left(), true);
            int[] right = marker(sheet, c1, c2, box.left() + box.width(), true);
            int[] top = marker(sheet, r1, r2, box.top(), false);
            int[] bottom = marker(sheet, r1, r2, box.top() + box.height(), false);

            ClientAnchor anchor = drawing.createAnchor(left[1], top[1], right[1], bottom[1],
                    left[0], top[0], right[0], bottom[0]);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
            drawing.createPicture(anchor, picture);
        }
    }

    /**
     * 把「距这块格子左上角 offset 像素」换算成锚点要的 {@code {行列号, 格内偏移(EMU)}}。
     *
     * <p>OOXML 的锚点不是绝对坐标，而是「第几个格子 + 格子内偏移」，所以要沿着行/列一路减过去
     * 才知道落在哪一格里。落到最后一格之外时钉在末格的边上（图片是装进去的，正常不会发生）。
     *
     * @param horizontal true 数列宽，false 数行高
     */
    private int[] marker(XSSFSheet sheet, int from, int to, double offset, boolean horizontal) {
        double left = Math.max(offset, 0);
        for (int i = from; i <= to; i++) {
            double size = horizontal ? sheet.getColumnWidthInPixels(i) : rowHeightPx(sheet, i);
            if (left < size || i == to) {
                return new int[]{i, Units.pixelToEMU((int) Math.round(Math.min(left, size)))};
            }
            left -= size;
        }
        return new int[]{to, 0};
    }

    /** 行高（px）：没建过的行按 sheet 的默认行高。 */
    private double rowHeightPx(XSSFSheet sheet, int r) {
        Row row = sheet.getRow(r);
        float points = row == null ? sheet.getDefaultRowHeightInPoints() : row.getHeightInPoints();
        return points / 0.75f;
    }

    private void collectWrapCell(List<WrapCell> out, int r, int c, Map<String, Object> v) {
        if (!"2".equals(str(v.get("tb")))) {
            return;
        }
        Object m = v.get("m") == null ? v.get("v") : v.get("m");
        String text = m == null ? "" : String.valueOf(m);
        if (text.isBlank()) {
            return;
        }
        Integer fs = num(v.get("fs"));
        out.add(new WrapCell(r, c, text, fs == null || fs <= 0 ? DEFAULT_FONT_SIZE_PT : fs));
    }

    /**
     * 把开了自动换行的行撑到放得下折行后的文字。
     *
     * <p>非做不可：**Excel 打开文件时不会重算行高**，行高就是文件里存着的那个数（自动行高只在
     * 应用里编辑时才触发）。不在这里算好，导出的表就是「换行了但只看得到第一行」，
     * PDF / Word 照着同一份行高分页，一样被切。
     *
     * <p>设计器里写死的模板行高（{@code config.rowlen}）只按模板里那几个字算过，
     * 而扩展出来的数据行文字长度是运行时才知道的 —— 那些行只能在这里补。
     *
     * <p>只增不减：用户拖出来的行高比算出来的高时保持不动。跨行合并的单元格跳过，
     * 高度该摊给哪一行没有确定答案，Excel 自己也不给合并区做自动行高。
     */
    private void applyWrapRowHeights(XSSFSheet sheet, List<WrapCell> cells) {
        if (cells.isEmpty()) {
            return;
        }
        Map<Integer, Float> needed = new HashMap<>();
        for (WrapCell wc : cells) {
            CellRangeAddress region = regionAt(sheet, wc.r(), wc.c());
            if (region != null && region.getLastRow() > region.getFirstRow()) {
                continue;
            }
            int firstCol = region == null ? wc.c() : region.getFirstColumn();
            int lastCol = region == null ? wc.c() : region.getLastColumn();
            float widthPt = 0;
            for (int c = firstCol; c <= lastCol; c++) {
                widthPt += sheet.getColumnWidthInPixels(c) * 0.75f;
            }
            widthPt -= PADDING_X_PT;
            if (widthPt <= 0) {
                continue;
            }
            int lines = countLines(wc.text(), wc.fontSize(), widthPt);
            // 只折了一行的格子按原样：它本来就放得下，撑高只会平白多出一截空白
            if (lines <= 1) {
                continue;
            }
            float height = lines * wc.fontSize() * LINE_HEIGHT + PADDING_Y_PT;
            needed.merge(wc.r(), height, Math::max);
        }
        needed.forEach((r, height) -> {
            Row row = sheet.getRow(r);
            if (row == null) {
                row = sheet.createRow(r);
            }
            if (height > MAX_ROW_HEIGHT_PT) {
                log.warn("第 {} 行按文字量出来要 {}pt，超过 Excel 的行高上限 {}pt，已夹住 —— "
                                + "这一格在 Excel 里会显示不全，要完整出纸请走 PDF",
                        r + 1, Math.round(height), MAX_ROW_HEIGHT_PT);
                height = MAX_ROW_HEIGHT_PT;
            }
            if (row.getHeightInPoints() < height - 0.1f) {
                row.setHeightInPoints(height);
            }
        });
    }

    /** (r,c) 所属的合并区域，未合并返回 null。 */
    private CellRangeAddress regionAt(XSSFSheet sheet, int r, int c) {
        for (CellRangeAddress ca : sheet.getMergedRegions()) {
            if (ca.isInRange(r, c)) {
                return ca;
            }
        }
        return null;
    }

    /**
     * 文字在给定宽度下折成几行。
     *
     * <p>折行本身在 {@link RowSpill#wrapEstimate} 一处（字宽按「全角 1 个字号、半角半个字号」估，
     * 不去加载字体）—— {@code WordExporter} 的续行用的是同一份，两处算出来的行数必须一样，
     * 否则「Excel 里 12 行、Word 里切在第 11 行」。
     */
    private int countLines(String text, float fontSize, float maxWidth) {
        return Math.max(RowSpill.wrapEstimate(text, fontSize, maxWidth).size(), 1);
    }

    /** 把报表的打印设置写进 sheet 的页面设置，使导出的 xlsx 直接按设计器里的设定出纸。 */
    private void applyPageSetup(XSSFWorkbook wb, XSSFSheet sheet, int index, PageConfigDTO cfg) {
        if (cfg == null) {
            return;
        }
        PrintSetup ps = sheet.getPrintSetup();
        ps.setPaperSize(paperSize(cfg.getPaperSize()));
        ps.setLandscape("landscape".equalsIgnoreCase(cfg.getOrientation()));

        int scale = clamp(cfg.getScale() == null ? 100 : cfg.getScale(), 10, 400);
        // 限宽的意思就是「这张表只该有一页宽」，导出时按一页宽出纸兜底。
        // 因为 px -> Excel 列宽是有量化误差的（Excel 以「默认字体最大数字宽」的 1/256 为单位），
        // 内容宽度贴着页宽时差一两个像素就会把最后一列挤到第二页；老报表也可能本来就比一页宽
        // （设计器会提示「压缩到一页宽」，但用户未必点）—— 两种情况都表现为「导出的 Excel 打印不全」。
        // Excel 的 fit-to-page 只缩不放，所以本来就装得下的表仍然是 100%，不会被放大。
        // 用户显式调过缩放（scale != 100）时以他的设置为准，不擅自接管。
        boolean fitWidth = Boolean.TRUE.equals(cfg.getFitToWidth())
                || (Boolean.TRUE.equals(cfg.getLimitWidth()) && scale == 100);
        if (fitWidth) {
            // 所有列压到一页宽，行数不限
            sheet.setFitToPage(true);
            ps.setFitWidth((short) 1);
            ps.setFitHeight((short) 0);
        } else {
            sheet.setFitToPage(false);
            ps.setScale((short) scale);
        }

        // POI 的页边距单位是英寸
        sheet.setMargin(PageMargin.TOP, mmToInch(cfg.getMarginTop()));
        sheet.setMargin(PageMargin.BOTTOM, mmToInch(cfg.getMarginBottom()));
        sheet.setMargin(PageMargin.LEFT, mmToInch(cfg.getMarginLeft()));
        sheet.setMargin(PageMargin.RIGHT, mmToInch(cfg.getMarginRight()));

        applyHeaderFooter(sheet, cfg);

        String area = cfg.getPrintArea();
        if (area != null && !area.isBlank()) {
            try {
                CellRangeAddress ca = CellRangeAddress.valueOf(area.trim());
                wb.setPrintArea(index, ca.getFirstColumn(), ca.getLastColumn(), ca.getFirstRow(), ca.getLastRow());
            } catch (Exception e) {
                log.warn("打印区域[{}]非法，已忽略", area);
            }
        }

        applyTitleRows(sheet, cfg);
    }

    /**
     * 顶端标题行（跨页重复的表头）-> xlsx 的 {@code _xlnm.Print_Titles}。
     *
     * <p><b>只在这一处翻译</b>：PDF（{@code PdfExporter#readTitleRows}）与 Word
     * （{@code WordExporter#titleRows}）都是从 xlsx 读回来的，与页头页尾同一套路数。
     * 非法值忽略并记 warn —— 表头不重复只是难看，不该让整份导出失败。
     *
     * <p><b>「必须是内容范围最上面的连续若干行」在这里也要把关</b>，不能只靠设计器的弹窗拦：
     * 手写 content / 直接调接口能绕过前端。Excel 本身支持任意位置的重复行（先印上方内容、
     * 从出现那页起每页重复），而 PDF 是照行流分页的、只认最上面的 —— 这里放过去的话
     * 「Excel 里能重复、PDF 里没有」，三条路就分叉了。判定与 {@code PdfExporter#readTitleRows}
     * 同一套：起点必须盖住内容第一行（设了打印区域按区域第一行，否则按 A1 —— 与
     * {@code PdfExporter#contentRange} 的「内容从 A1 起算」一致），且不能把内容全吃掉。
     */
    private void applyTitleRows(XSSFSheet sheet, PageConfigDTO cfg) {
        CellRangeAddress rows = parseRowRange(cfg.getTitleRows());
        if (rows == null) {
            return;
        }
        CellRangeAddress area = null;
        if (cfg.getPrintArea() != null && !cfg.getPrintArea().isBlank()) {
            try {
                area = CellRangeAddress.valueOf(cfg.getPrintArea().trim());
            } catch (Exception ignored) {
                // 打印区域非法时上面已经 warn 过、按「整表」出纸，标题行也按整表判
            }
        }
        int first = area == null ? 0 : area.getFirstRow();
        int last = area == null ? sheet.getLastRowNum() : Math.min(area.getLastRow(), sheet.getLastRowNum());
        if (rows.getFirstRow() > first || rows.getLastRow() < first) {
            log.warn("顶端标题行[{}]不在内容范围最上面（内容从第 {} 行起），已忽略", cfg.getTitleRows(), first + 1);
            return;
        }
        if (rows.getLastRow() >= last) {
            log.warn("顶端标题行[{}]盖住了全部内容，已忽略", cfg.getTitleRows());
            return;
        }
        sheet.setRepeatingRows(rows);
    }

    /**
     * {@code "1:3"} / {@code "$1:$3"} -> 0 起算的行区间（列填 -1 = 整行），非法或空返回 null。
     */
    static CellRangeAddress parseRowRange(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.trim().replace("$", "").split(":");
        try {
            int first = Integer.parseInt(parts[0].trim()) - 1;
            int last = Integer.parseInt(parts[parts.length - 1].trim()) - 1;
            if (parts.length > 2 || first < 0 || last < first) {
                throw new NumberFormatException(text);
            }
            return new CellRangeAddress(first, last, -1, -1);
        } catch (RuntimeException e) {
            log.warn("顶端标题行[{}]非法，已忽略（应形如 1:3）", text);
            return null;
        }
    }

    /**
     * 把页头 / 页尾写进 sheet 的页眉页脚。
     *
     * <p>页头页尾**只存在 xlsx 里这一份**：PDF / Word 两条路都是「先出 xlsx 再转」，
     * 它们从这里读回来（{@link HeaderFooterText#split}），所以不必给转换器再传一遍。
     *
     * <p>写的是「奇数页页眉」({@code oddHeader})：没有单独设置首页/偶数页页眉时，
     * Excel 对所有页都用它。
     */
    private void applyHeaderFooter(XSSFSheet sheet, PageConfigDTO cfg) {
        String header = HeaderFooterText.toExcelCode(cfg.getHeader());
        if (!header.isEmpty()) {
            ((XSSFOddHeader) sheet.getOddHeader()).setText(header);
            sheet.setMargin(PageMargin.HEADER, mmToInch(cfg.getHeader().getMargin()));
        }
        String footer = HeaderFooterText.toExcelCode(cfg.getFooter());
        if (!footer.isEmpty()) {
            ((XSSFOddFooter) sheet.getOddFooter()).setText(footer);
            sheet.setMargin(PageMargin.FOOTER, mmToInch(cfg.getFooter().getMargin()));
        }
    }

    /** 纸张名 -> POI PrintSetup 常量，未知按 A4。 */
    private short paperSize(String name) {
        if (name == null) {
            return PrintSetup.A4_PAPERSIZE;
        }
        return switch (name.trim().toUpperCase()) {
            case "A3" -> PrintSetup.A3_PAPERSIZE;
            case "A5" -> PrintSetup.A5_PAPERSIZE;
            case "B4" -> PrintSetup.B4_PAPERSIZE;
            case "B5" -> PrintSetup.B5_PAPERSIZE;
            case "LETTER" -> PrintSetup.LETTER_PAPERSIZE;
            case "LEGAL" -> PrintSetup.LEGAL_PAPERSIZE;
            default -> PrintSetup.A4_PAPERSIZE;
        };
    }

    private double mmToInch(Integer mm) {
        return (mm == null ? 10 : mm) / 25.4d;
    }

    private int clamp(int n, int min, int max) {
        return Math.min(Math.max(n, min), max);
    }

    /** 写值：数字写数值、日期写日期并套格式、其余写字符串。 */
    private void writeValue(XSSFWorkbook wb, Cell cell, Map<String, Object> v,
                            Map<String, CellStyle> styleCache) {
        Object raw = v.get("v");
        String display = v.get("m") == null ? null : String.valueOf(v.get("m"));
        Map<String, Object> ct = v.get("ct") instanceof Map ? (Map<String, Object>) v.get("ct") : null;
        String t = ct == null ? "g" : String.valueOf(ct.getOrDefault("t", "g"));
        String fa = ct == null ? "General" : String.valueOf(ct.getOrDefault("fa", "General"));

        String formula = v.get("f") == null ? null : String.valueOf(v.get("f"));
        if (formula != null && formula.startsWith("=") && formula.length() > 1) {
            try {
                cell.setCellFormula(formula.substring(1));
            } catch (Exception e) {
                // 公式非法时退化为文本，保证导出不中断
                cell.setCellValue(display == null ? formula : display);
            }
        } else if ("n".equals(t)) {
            BigDecimal d = CellFormatter.toDecimal(raw);
            if (d != null) {
                cell.setCellValue(d.doubleValue());
            } else {
                cell.setCellValue(display == null ? "" : display);
            }
        } else if ("d".equals(t)) {
            LocalDateTime dt = CellFormatter.toDateTime(raw);
            if (dt != null) {
                cell.setCellValue(dt);
            } else {
                cell.setCellValue(display == null ? "" : display);
            }
        } else if ("s".equals(t)) {
            // 显式的文本格（金额中文大写就是这么出的）：值本身是数字，但要的就是那段文字，
            // 落到下面的 Number 分支会把大写写回阿拉伯数字
            cell.setCellValue(display != null ? display : (raw == null ? "" : String.valueOf(raw)));
        } else if (raw instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (raw instanceof Boolean b) {
            cell.setCellValue(b);
        } else if (raw instanceof LocalDate ld) {
            cell.setCellValue(ld);
        } else {
            cell.setCellValue(display != null ? display : (raw == null ? "" : String.valueOf(raw)));
        }

        CellStyle style = resolveStyle(wb, v, t, fa, styleCache);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    /** 按样式指纹复用 CellStyle。 */
    private CellStyle resolveStyle(XSSFWorkbook wb, Map<String, Object> v, String t, String fa,
                                   Map<String, CellStyle> cache) {
        String bg = str(v.get("bg"));
        String fc = str(v.get("fc"));
        String bl = str(v.get("bl"));
        String it = str(v.get("it"));
        String ff = str(v.get("ff"));
        String fs = str(v.get("fs"));
        String ht = str(v.get("ht"));
        String vt = str(v.get("vt"));
        String tb = str(v.get("tb"));

        String fingerprint = String.join("|", bg, fc, bl, it, ff, fs, ht, vt, tb, t, fa);
        // 全默认（无任何样式、无自定义格式）时不建 CellStyle：POI 的样式数量有上限，得省着用
        String bare = fingerprint.replace("|", "");
        if (bare.isEmpty() || bare.equals("General")) {
            return null;
        }
        CellStyle cached = cache.get(fingerprint);
        if (cached != null) {
            return cached;
        }

        XSSFCellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        boolean fontTouched = false;

        if ("1".equals(bl)) {
            font.setBold(true);
            fontTouched = true;
        }
        if ("1".equals(it)) {
            font.setItalic(true);
            fontTouched = true;
        }
        if (!ff.isEmpty() && !ff.matches("\\d+")) {
            font.setFontName(ff);
            fontTouched = true;
        }
        Integer size = num(v.get("fs"));
        if (size != null && size > 0) {
            font.setFontHeightInPoints(size.shortValue());
            fontTouched = true;
        }
        XSSFColor fontColor = toColor(fc);
        if (fontColor != null && font instanceof org.apache.poi.xssf.usermodel.XSSFFont xf) {
            xf.setColor(fontColor);
            fontTouched = true;
        }
        if (fontTouched) {
            style.setFont(font);
        }

        XSSFColor bgColor = toColor(bg);
        if (bgColor != null) {
            style.setFillForegroundColor(bgColor);
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        // FortuneSheet: ht 0=居中 1=左 2=右；vt 0=中 1=上 2=下
        style.setAlignment(switch (ht) {
            case "0" -> HorizontalAlignment.CENTER;
            case "2" -> HorizontalAlignment.RIGHT;
            case "1" -> HorizontalAlignment.LEFT;
            default -> HorizontalAlignment.GENERAL;
        });
        style.setVerticalAlignment(switch (vt) {
            case "1" -> VerticalAlignment.TOP;
            case "2" -> VerticalAlignment.BOTTOM;
            default -> VerticalAlignment.CENTER;
        });
        // FortuneSheet: tb 0=截断 1=溢出 2=自动换行。只有 2 对应 Excel 的「自动换行」，
        // 0/1 都是 Excel 的默认表现（不换行），不用显式设置。
        // 注意行高：rowlen 里有值的行会被写成固定行高（customHeight），Excel 不会为换行再撑高，
        // 没被拖过的行则跟随默认行高自动撑开 —— 与设计器里的表现一致。
        if ("2".equals(tb)) {
            style.setWrapText(true);
        }

        if ("d".equals(t)) {
            style.setDataFormat(wb.createDataFormat().getFormat(toExcelDateFormat(fa)));
        } else if ("s".equals(t)) {
            style.setDataFormat(wb.createDataFormat().getFormat("@"));
        } else if ("n".equals(t) && !"General".equals(fa)) {
            try {
                style.setDataFormat(wb.createDataFormat().getFormat(fa));
            } catch (Exception ignored) {
                // 格式串不被 Excel 接受时忽略，保留默认
            }
        }

        cache.put(fingerprint, style);
        return style;
    }

    /**
     * Java 的日期格式串转成 Excel 认识的格式（用户可以在设计器里自定义，见 CONTRACT §4）。
     *
     * <p>差别只在月份与小时：Excel 的月是小写 {@code mm}（跟在 h 后面才读作分钟，所以 Java 的
     * {@code mm} 原样留着正好还是分钟），24 小时制是 {@code hh}。
     *
     * <p>**中文之类的字面量裸着写，不加引号也不加反斜杠**：Excel 两种都认，但 PDF / Word 两条路
     * 取显示文本用的是 POI 的 {@code DataFormatter}，它对日期格式里的引号和反斜杠不脱壳
     * ——`yyyy"年"mm"月"dd"日"` 会原样打成 {@code 2024"年"01"月"31"日"}。
     */
    private String toExcelDateFormat(String fa) {
        if (fa == null || fa.isBlank() || "General".equals(fa)) {
            return "yyyy-mm-dd";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fa.length(); i++) {
            char ch = fa.charAt(i);
            switch (ch) {
                case 'M' -> out.append('m');            // Java 的月 -> Excel 的月
                case 'H' -> out.append('h');            // 不写 AM/PM 时 Excel 的 h 就是 24 小时制
                case '"', '\\' -> { }                   // 引号/反斜杠 POI 不脱壳，直接丢掉
                default -> out.append(ch);              // y d h m s 与中文、分隔符原样留着
            }
        }
        return out.toString();
    }

    private XSSFColor toColor(String hex) {
        if (hex == null || !hex.startsWith("#") || (hex.length() != 7 && hex.length() != 4)) {
            return null;
        }
        try {
            String h = hex.substring(1);
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 合并区域、行高、列宽、边框。 */
    @SuppressWarnings("unchecked")
    private void applyConfig(XSSFWorkbook wb, XSSFSheet sheet, Map<String, Object> config,
                             Map<String, XSSFCellStyle> borderCache) {
        Object mergeObj = config.get("merge");
        if (mergeObj instanceof Map<?, ?> mm) {
            for (Object val : mm.values()) {
                if (!(val instanceof Map)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) val;
                Integer r = num(m.get("r"));
                Integer c = num(m.get("c"));
                Integer rs = num(m.get("rs"));
                Integer cs = num(m.get("cs"));
                if (r == null || c == null) {
                    continue;
                }
                int rowSpan = rs == null || rs < 1 ? 1 : rs;
                int colSpan = cs == null || cs < 1 ? 1 : cs;
                if (rowSpan == 1 && colSpan == 1) {
                    continue;
                }
                try {
                    sheet.addMergedRegion(new CellRangeAddress(r, r + rowSpan - 1, c, c + colSpan - 1));
                } catch (Exception e) {
                    log.warn("跳过非法合并区域 r={} c={} rs={} cs={}: {}", r, c, rowSpan, colSpan, e.getMessage());
                }
            }
        }

        Object rowlenObj = config.get("rowlen");
        if (rowlenObj instanceof Map<?, ?> rl) {
            rl.forEach((k, v) -> {
                Integer r = num(k);
                Integer px = num(v);
                if (r == null || px == null || px <= 0) {
                    return;
                }
                Row row = sheet.getRow(r);
                if (row == null) {
                    row = sheet.createRow(r);
                }
                // 拖出来的行高同样受 Excel 的上限约束，见 MAX_ROW_HEIGHT_PT
                row.setHeightInPoints(Math.min(pxToPoints(px), MAX_ROW_HEIGHT_PT));
            });
        }

        Object borderObj = config.get("borderInfo");
        if (borderObj instanceof List<?> bl) {
            applyBorders(wb, sheet, bl, borderCache);
        }
    }

    /**
     * 设置列宽。
     *
     * <p>只写 {@code columnlen} 里有的列是不够的：剩下的列会落到 Excel 自己的默认列宽（约 64px），
     * 而设计器按 {@link #DEFAULT_COL_WIDTH_PX} 算，整表宽度就和画布上看到的对不上了。
     * 所以这里从第 0 列一直补到「实际会被打印到的最后一列」。
     */
    private void applyColumnWidths(XSSFSheet sheet, Object columnlenObj) {
        Map<Integer, Integer> px = new HashMap<>();
        int maxCol = -1;
        if (columnlenObj instanceof Map<?, ?> cl) {
            for (Map.Entry<?, ?> e : cl.entrySet()) {
                Integer c = num(e.getKey());
                Integer w = num(e.getValue());
                if (c != null && c >= 0 && w != null && w > 0) {
                    px.put(c, w);
                    maxCol = Math.max(maxCol, c);
                }
            }
        }
        for (Row row : sheet) {
            maxCol = Math.max(maxCol, row.getLastCellNum() - 1);
        }
        // 合并区域不会为被吃掉的格子建 Cell，但打印范围要算上它们
        for (CellRangeAddress ca : sheet.getMergedRegions()) {
            maxCol = Math.max(maxCol, ca.getLastColumn());
        }
        for (int c = 0; c <= maxCol; c++) {
            sheet.setColumnWidth(c, pxToColumnWidth(px.getOrDefault(c, DEFAULT_COL_WIDTH_PX)));
        }
    }

    /**
     * 像素 -> POI 列宽单位（1/256 个「默认字体最大数字宽」，Calibri 11 @96dpi 下为 7px）。
     *
     * <p>向下取整而不是四舍五入：宁可每列窄不到一个像素，也不要因为进位让整表比设计的更宽 ——
     * 内容宽度本来就贴着一页宽，多出一个像素就是多出一页。
     */
    private int pxToColumnWidth(int px) {
        int units = (int) Math.floor(px * 256.0 / Units.DEFAULT_CHARACTER_WIDTH);
        return Math.max(1, Math.min(255 * 256, units));
    }

    /** 像素 -> 磅（96dpi 下 1px = 0.75pt）。 */
    private float pxToPoints(int px) {
        return px * 0.75f;
    }

    /**
     * 把 {@code config.borderInfo} 落成 Excel 的单元格边框，**线型和颜色都照搬**。
     *
     * <p>早先这里一律写「THIN + 不写颜色」，只还原「有边框」这一层语义。不写颜色在 OOXML 里
     * 等于 {@code auto}，Excel 按底色反推、通常渲染成黑色 —— 于是**用户在设计器里把边框设成白色
     * （常见的「把某条线擦掉」的做法），导出的 Excel / PDF / Word 里照旧是一条黑线**；
     * PDF / Word 那两条路是读这份 xlsx 的，取不到颜色时各自退回黑色（见
     * {@code PdfExporter#line} / {@code WordExporter#border}），所以三种格式一起错。
     * 同理 {@code border-none}（「无边框」）当时也被当成「有边框」，反而画出了一圈线。
     *
     * <p>颜色只写在这一处：xlsx 里存对了，PDF / Word 自然跟着对。
     */
    private void applyBorders(XSSFWorkbook wb, XSSFSheet sheet, List<?> borderInfo,
                              Map<String, XSSFCellStyle> borderCache) {
        Map<Long, CellBorders> computed = computeBorders(borderInfo);
        for (Map.Entry<Long, CellBorders> e : computed.entrySet()) {
            CellBorders borders = e.getValue();
            if (borders.isEmpty()) {
                continue;
            }
            int r = (int) (e.getKey() >> 32);
            int c = (int) (e.getKey() & 0xffffffffL);
            Row row = sheet.getRow(r);
            if (row == null) {
                row = sheet.createRow(r);
            }
            Cell cell = row.getCell(c);
            if (cell == null) {
                cell = row.createCell(c);
            }
            cell.setCellStyle(withBorder(wb, cell.getCellStyle(), borders, borderCache));
        }
    }

    /**
     * 把 {@code borderInfo} 那一串「区域 + 边框类型」摊成每个格子的四条边。
     *
     * <p>**顺序即优先级**：后写的覆盖先写的（用户先画一圈黑框、再把某条边刷成白色，
     * 生效的是白色），所以只能顺序合成一份结果再统一写，不能边遍历边往单元格上盖。
     * 这份合成逻辑对着 FortuneSheet 的 {@code getBorderInfoComputeRange} 来 —— 画布上怎么显示，
     * 导出就该是什么样。
     */
    @SuppressWarnings("unchecked")
    private Map<Long, CellBorders> computeBorders(List<?> borderInfo) {
        Map<Long, CellBorders> out = new LinkedHashMap<>();
        for (Object o : borderInfo) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) o;
            if ("cell".equals(str(entry.get("rangeType")))) {
                applyCellBorderEntry(out, entry.get("value"));
            } else {
                applyRangeBorderEntry(out, entry);
            }
        }
        return out;
    }

    /** {@code rangeType=cell}：直接给出某一格四条边（复制粘贴带过来的就是这一种），null 表示这条边没有。 */
    @SuppressWarnings("unchecked")
    private void applyCellBorderEntry(Map<Long, CellBorders> out, Object valueObj) {
        if (!(valueObj instanceof Map)) {
            return;
        }
        Map<String, Object> value = (Map<String, Object>) valueObj;
        Integer r = num(value.get("row_index"));
        Integer c = num(value.get("col_index"));
        if (r == null || c == null || r < 0 || c < 0) {
            return;
        }
        CellBorders borders = out.computeIfAbsent(key(r, c), k -> new CellBorders());
        borders.top = sideOf(value.get("t"));
        borders.bottom = sideOf(value.get("b"));
        borders.left = sideOf(value.get("l"));
        borders.right = sideOf(value.get("r"));
    }

    /** {@code rangeType=range}：一个区域 + 一种边框类型（工具栏上画的边框都是这一种）。 */
    @SuppressWarnings("unchecked")
    private void applyRangeBorderEntry(Map<Long, CellBorders> out, Map<String, Object> entry) {
        Object rangeObj = entry.get("range");
        if (!(rangeObj instanceof List<?> ranges)) {
            return;
        }
        String type = str(entry.get("borderType"));
        if (type.isBlank()) {
            type = "border-all";
        }
        // 斜线：xlsx 里存得下（diagonal），但报表里没有这个用法，也没有别的通道能画，直接跳过
        if ("border-slash".equals(type)) {
            return;
        }
        BorderSide side = new BorderSide(borderStyleOf(entry.get("style")), colorOf(entry.get("color")));
        for (Object rObj : ranges) {
            if (!(rObj instanceof Map)) {
                continue;
            }
            Map<String, Object> range = (Map<String, Object>) rObj;
            int[] rows = pair(range.get("row"));
            int[] cols = pair(range.get("column"));
            if (rows == null || cols == null) {
                continue;
            }
            applyBorderType(out, type, side, rows[0], rows[1], cols[0], cols[1]);
        }
    }

    private void applyBorderType(Map<Long, CellBorders> out, String type, BorderSide side,
                                 int r1, int r2, int c1, int c2) {
        r1 = Math.max(r1, 0);
        c1 = Math.max(c1, 0);
        for (int r = r1; r <= r2; r++) {
            for (int c = c1; c <= c2; c++) {
                switch (type) {
                    case "border-none" -> clear(out, r, c, r == r1, r == r2, c == c1, c == c2);
                    case "border-all" -> {
                        set(out, r, c, side, true, true, true, true);
                        // 区域边界上还要把外面那一圈**已经有边框**的邻格朝向这里的那条边一起改掉：
                        // Excel 里相邻两格共用一条线，只改自己这边的话邻格那条老边框仍然画得出来
                        // ——「刷成白色却还是黑线」就是这么来的。邻格本来没边框就别凭空加。
                        neighbors(out, r, c, side, r == r1, r == r2, c == c1, c == c2);
                    }
                    case "border-outside" -> {
                        set(out, r, c, side, r == r1, r == r2, c == c1, c == c2);
                        neighbors(out, r, c, side, r == r1, r == r2, c == c1, c == c2);
                    }
                    case "border-inside" -> set(out, r, c, side, r > r1, r < r2, c > c1, c < c2);
                    case "border-horizontal" -> set(out, r, c, side, r > r1, r < r2, false, false);
                    case "border-vertical" -> set(out, r, c, side, false, false, c > c1, c < c2);
                    case "border-top" -> {
                        if (r == r1) {
                            set(out, r, c, side, true, false, false, false);
                            neighbors(out, r, c, side, true, false, false, false);
                        }
                    }
                    case "border-bottom" -> {
                        if (r == r2) {
                            set(out, r, c, side, false, true, false, false);
                            neighbors(out, r, c, side, false, true, false, false);
                        }
                    }
                    case "border-left" -> {
                        if (c == c1) {
                            set(out, r, c, side, false, false, true, false);
                            neighbors(out, r, c, side, false, false, true, false);
                        }
                    }
                    case "border-right" -> {
                        if (c == c2) {
                            set(out, r, c, side, false, false, false, true);
                            neighbors(out, r, c, side, false, false, false, true);
                        }
                    }
                    default -> log.debug("未知的边框类型，跳过: {}", type);
                }
            }
        }
    }

    private void set(Map<Long, CellBorders> out, int r, int c, BorderSide side,
                     boolean top, boolean bottom, boolean left, boolean right) {
        if (!top && !bottom && !left && !right) {
            return;
        }
        CellBorders borders = out.computeIfAbsent(key(r, c), k -> new CellBorders());
        if (top) {
            borders.top = side;
        }
        if (bottom) {
            borders.bottom = side;
        }
        if (left) {
            borders.left = side;
        }
        if (right) {
            borders.right = side;
        }
    }

    /** 把邻格朝向本格的那条边一起改成同样的样式，**只改已经有边框记录的邻格**。 */
    private void neighbors(Map<Long, CellBorders> out, int r, int c, BorderSide side,
                           boolean top, boolean bottom, boolean left, boolean right) {
        if (top && r > 0) {
            facing(out, r - 1, c, b -> b.bottom = side);
        }
        if (bottom) {
            facing(out, r + 1, c, b -> b.top = side);
        }
        if (left && c > 0) {
            facing(out, r, c - 1, b -> b.right = side);
        }
        if (right) {
            facing(out, r, c + 1, b -> b.left = side);
        }
    }

    /** 「无边框」：本格四条边全去掉，边界上再把邻格朝向这里的那条边也去掉（同样只动已有记录的）。 */
    private void clear(Map<Long, CellBorders> out, int r, int c,
                       boolean top, boolean bottom, boolean left, boolean right) {
        out.remove(key(r, c));
        if (top && r > 0) {
            facing(out, r - 1, c, b -> b.bottom = null);
        }
        if (bottom) {
            facing(out, r + 1, c, b -> b.top = null);
        }
        if (left && c > 0) {
            facing(out, r, c - 1, b -> b.right = null);
        }
        if (right) {
            facing(out, r, c + 1, b -> b.left = null);
        }
    }

    private void facing(Map<Long, CellBorders> out, int r, int c, Consumer<CellBorders> edit) {
        CellBorders borders = out.get(key(r, c));
        if (borders != null) {
            edit.accept(borders);
        }
    }

    private long key(int r, int c) {
        return ((long) r << 32) | (c & 0xffffffffL);
    }

    /** FortuneSheet 的 {@code {style, color}}，null / 缺项表示这条边没有。 */
    @SuppressWarnings("unchecked")
    private BorderSide sideOf(Object o) {
        if (!(o instanceof Map)) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) o;
        BorderStyle style = borderStyleOf(m.get("style"));
        return style == BorderStyle.NONE ? null : new BorderSide(style, colorOf(m.get("color")));
    }

    /**
     * FortuneSheet 的线型码 -> POI 的 {@link BorderStyle}。
     *
     * <p>码值与 Excel 的边框线型一一对应（见 @fortune-sheet/core 的 {@code setLineDash}），
     * 没写就是 1（细实线，也是工具栏画边框时的默认值）。
     */
    private BorderStyle borderStyleOf(Object o) {
        Integer code = num(o);
        if (code == null) {
            return BorderStyle.THIN;
        }
        return switch (code) {
            case 0 -> BorderStyle.NONE;
            case 2 -> BorderStyle.HAIR;
            case 3 -> BorderStyle.DOTTED;
            case 4 -> BorderStyle.DASHED;
            case 5 -> BorderStyle.DASH_DOT;
            case 6 -> BorderStyle.DASH_DOT_DOT;
            case 7 -> BorderStyle.DOUBLE;
            case 8 -> BorderStyle.MEDIUM;
            case 9 -> BorderStyle.MEDIUM_DASHED;
            case 10 -> BorderStyle.MEDIUM_DASH_DOT;
            case 11 -> BorderStyle.MEDIUM_DASH_DOT_DOT;
            case 12 -> BorderStyle.SLANTED_DASH_DOT;
            case 13 -> BorderStyle.THICK;
            default -> BorderStyle.THIN;
        };
    }

    /** 边框颜色，规范成 "#RRGGBB"；取不到返回 null（写进 xlsx 就是 auto，下游按黑色画）。 */
    private String colorOf(Object o) {
        String hex = str(o);
        return toColor(hex) == null ? null : hex;
    }

    /**
     * 返回「base 样式 + 这四条边」的样式，按「基础样式 + 边框指纹」复用，避免样式数量爆炸
     * （POI 的 CellStyle 上限约 64000）。
     *
     * <p>不能直接 {@code cell.getCellStyle()} 后原地 setBorderXxx：POI 的 CellStyle 是按样式索引
     * 共享的（未显式设置样式的单元格全部指向同一个「默认样式」），原地修改会把边框「泄漏」到
     * 所有共享该样式的其它单元格上。
     */
    private XSSFCellStyle withBorder(XSSFWorkbook wb, CellStyle base, CellBorders borders,
                                     Map<String, XSSFCellStyle> cache) {
        String key = base.getIndex() + "|" + borders.fingerprint();
        XSSFCellStyle cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        XSSFCellStyle bordered = wb.createCellStyle();
        bordered.cloneStyleFrom(base);
        applySide(bordered, borders.top, XSSFCellStyle::setBorderTop, XSSFCellStyle::setTopBorderColor);
        applySide(bordered, borders.bottom, XSSFCellStyle::setBorderBottom, XSSFCellStyle::setBottomBorderColor);
        applySide(bordered, borders.left, XSSFCellStyle::setBorderLeft, XSSFCellStyle::setLeftBorderColor);
        applySide(bordered, borders.right, XSSFCellStyle::setBorderRight, XSSFCellStyle::setRightBorderColor);
        cache.put(key, bordered);
        return bordered;
    }

    private void applySide(XSSFCellStyle style, BorderSide side,
                           BiConsumer<XSSFCellStyle, BorderStyle> setStyle,
                           BiConsumer<XSSFCellStyle, XSSFColor> setColor) {
        if (side == null) {
            setStyle.accept(style, BorderStyle.NONE);
            return;
        }
        setStyle.accept(style, side.style());
        XSSFColor color = toColor(side.color());
        if (color != null) {
            setColor.accept(style, color);
        }
    }

    /** 一条边：线型 + 颜色（"#RRGGBB"，null = 不写颜色，Excel 按 auto 处理）。 */
    private record BorderSide(BorderStyle style, String color) {
        String fingerprint() {
            return style.name() + ":" + (color == null ? "" : color);
        }
    }

    /** 一个格子的四条边，null 表示这条边没有边框。 */
    private static final class CellBorders {
        private BorderSide top;
        private BorderSide bottom;
        private BorderSide left;
        private BorderSide right;

        boolean isEmpty() {
            return top == null && bottom == null && left == null && right == null;
        }

        String fingerprint() {
            return fp(top) + "," + fp(bottom) + "," + fp(left) + "," + fp(right);
        }

        private String fp(BorderSide side) {
            return side == null ? "-" : side.fingerprint();
        }
    }

    private int[] pair(Object o) {
        if (o instanceof List<?> l && l.size() >= 2) {
            Integer a = num(l.get(0));
            Integer b = num(l.get(1));
            if (a != null && b != null) {
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
        }
        return null;
    }

    /**
     * Excel 工作表名不能含 : \ / ? * [ ]，且不超过 31 字符、不能与已有的重名。
     *
     * <p>重名要特别处理：{@code createSheet} 遇到重名直接抛异常，会让整份导出失败。
     * 而重名是能真实出现的 —— 名字超长被截到 31 字符后可能撞车，模板里也可能本来就有同名 sheet。
     * 与其整份导出报错，不如加个序号后缀退化成能用的名字。
     */
    private String safeSheetName(XSSFWorkbook wb, String name, int index) {
        String s = name.replaceAll("[\\\\/:*?\\[\\]]", "_").trim();
        if (s.isEmpty()) {
            s = "Sheet" + (index + 1);
        }
        if (s.length() > 31) {
            s = s.substring(0, 31);
        }
        String unique = s;
        for (int n = 2; wb.getSheet(unique) != null; n++) {
            String suffix = "(" + n + ")";
            String base = s.length() + suffix.length() > 31 ? s.substring(0, 31 - suffix.length()) : s;
            unique = base + suffix;
        }
        return unique;
    }

    private Integer num(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
