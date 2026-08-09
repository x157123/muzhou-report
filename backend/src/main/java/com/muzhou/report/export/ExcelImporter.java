package com.muzhou.report.export;

import com.muzhou.report.common.BizException;
import com.muzhou.report.dto.HeaderFooterDTO;
import com.muzhou.report.dto.PageConfigDTO;
import com.muzhou.report.dto.ReportContentDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFOddFooter;
import org.apache.poi.xssf.usermodel.XSSFOddHeader;
import org.apache.poi.xssf.usermodel.XSSFPrintSetup;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * xlsx -> 报表版式（{@link ReportContentDTO}）：把一张现成的 Excel 读成设计器能打开的模板，
 * 省掉照着它重画一遍表头、边框、合并、列宽的功夫。
 *
 * <p><b>只做静态版式，不做数据绑定</b>：产出的 {@code cellConfigs} 恒为空，
 * 占位符与取数由用户导入之后在设计器里手工配。所以这里既不识别 {@code #{}}、
 * 也不推断扩展行带 —— 猜错了让人逐格去拆，比自己画还慢。
 *
 * <p><b>它是 {@link ExcelExporter} 的逆映射，两个类必须对着改。</b>下面每一项都能在正向那边
 * 找到对应的一处（{@code resolveStyle} / {@code applyConfig} / {@code applyBorders} /
 * {@code applyPageSetup} …），改任一边都要看另一边，否则「导进来再导出去」就变了样
 * ——{@code ExcelImporterTest} 的往返用例锁着这件事。
 *
 * <p><b>数值和日期一律落成文字。</b>渲染时每个格子的 {@code ct} 都会被
 * {@code CellFormatter} 按 cellConfig 重建（没配过的格子拿到的是 {@code General}），
 * 模板里带的 {@code ct.fa} 根本活不到出纸那一步 —— 老实照搬 Excel 的「值 + 格式串」的话，
 * {@code 1,234.00} 会出成 {@code 1234}、日期会出成序列号 {@code 45878}
 * （{@code CellFormatter#toDateTime} 不认序列号）。所以这里用 {@code DataFormatter} 取
 * 「Excel 里显示成什么样」的那串文字直接当值，代价是这些格子在导出的 xlsx 里是文本、
 * 不能在 Excel 端继续参与计算。原本是数值的格子会补一个 {@code ct={fa:"@",t:"s"}}，
 * 免得 {@code 1,234.00} 被前端或二次导出又识别回数字。
 *
 * <p>纯 POJO、不依赖 Spring（同 {@code engine} 那几个类），挂接口的地方直接
 * {@code ExcelImporter.parse(bytes)} 即可。**一个实例只活一次导入**（内部攒着 warning）,
 * 所以对外只暴露静态入口。
 *
 * <p><b>带不过来的东西</b>：水印（xlsx 里没有这个概念）、条件格式、数据验证、图表、图片、
 * 透视表、批注、艺术字、冻结窗格、隐藏行列、分组折叠、自动筛选。删除线/下划线/文字旋转
 * 也不读 —— {@link ExcelExporter} 那头不写它们，读进来就成了「导入看得见、导出没有」的
 * 不对称，排查起来比少一点格式更费劲。每一项都会记一条 warning。
 */
@Slf4j
public final class ExcelImporter {

    /**
     * 闸门与开关。上限的缺省值与 {@code application.yml} 的 {@code muzhou.report.*} 对齐
     * —— 这个类不依赖 Spring，拿不到 {@code MzProperties}，所以由调用方传。
     */
    public record Options(int maxRows, int maxCells, boolean skipHiddenSheets) {

        public static Options defaults() {
            return new Options(20000, 500000, true);
        }
    }

    /**
     * @param content  可直接交给设计器的报表内容（{@code cellConfigs} 为空）
     * @param warnings 人话写的「哪些东西没带过来」，逐条给用户看 —— 不说清楚就会变成
     *                 「导进来不对」的报障
     */
    public record Result(ReportContentDTO content, List<String> warnings) {
    }

    /** 像素 -> 磅是 {@code ExcelExporter#pxToPoints} 的 0.75，这里反过来。 */
    private static final float POINTS_PER_PX = 0.75f;

    private final Options opts;

    private final List<String> warnings = new ArrayList<>();

    /** 取「Excel 里显示的那串文字」。中文环境下日期/货币的默认格式才和用户看到的一致。 */
    private final DataFormatter formatter = new DataFormatter(Locale.CHINA);

    private ExcelImporter(Options opts) {
        this.opts = opts == null ? Options.defaults() : opts;
    }

    public static Result parse(byte[] xlsx) {
        return parse(xlsx, Options.defaults());
    }

    public static Result parse(byte[] xlsx, Options opts) {
        return new ExcelImporter(opts).run(xlsx);
    }

    private Result run(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException("Excel 文件是空的");
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            return convert(wb);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("解析 Excel 失败", e);
            throw new BizException("这个文件打不开，只支持 .xlsx（Excel 2007 及以后的格式）");
        }
    }

    // ------------------------------------------------------------------
    // 工作簿
    // ------------------------------------------------------------------

    private ReportContentDTO content(List<Map<String, Object>> sheets, List<PageConfigDTO> configs) {
        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(sheets);
        content.setSplitMode("single");
        // 报表级取第一张的设置；其余 sheet 只有跟它不一样才单独记一份。
        // pageConfigs 的语义就是「只存改过的」，一张张都钉上一份几乎相同的设置，
        // 以后改一次纸张要改 N 处
        content.setPageConfig(configs.get(0));
        Map<String, PageConfigDTO> overrides = new LinkedHashMap<>();
        for (int i = 1; i < configs.size(); i++) {
            if (!configs.get(0).equals(configs.get(i))) {
                overrides.put(String.valueOf(i), configs.get(i));
            }
        }
        content.setPageConfigs(overrides);
        return content;
    }

    private Result convert(XSSFWorkbook wb) {
        List<Map<String, Object>> sheets = new ArrayList<>();
        List<PageConfigDTO> configs = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            if (opts.skipHiddenSheets() && (wb.isSheetHidden(i) || wb.isSheetVeryHidden(i))) {
                warn("工作表[" + wb.getSheetName(i) + "]是隐藏的，已跳过");
                continue;
            }
            XSSFSheet sheet = wb.getSheetAt(i);
            sheets.add(convertSheet(wb, sheet, sheets.size()));
            configs.add(pageConfig(wb, sheet, i));
        }
        if (sheets.isEmpty()) {
            throw new BizException("这个 Excel 里没有可导入的工作表");
        }
        return new Result(content(sheets, configs), warnings);
    }

    // ------------------------------------------------------------------
    // 一张 sheet
    // ------------------------------------------------------------------

    private Map<String, Object> convertSheet(XSSFWorkbook wb, XSSFSheet sheet, int index) {
        int maxRow = sheet.getLastRowNum();
        int maxCol = maxColumn(sheet);
        checkCapacity(sheet.getSheetName(), maxRow, maxCol);
        noticeUnsupported(sheet);

        Map<String, Map<String, Object>> cells = new LinkedHashMap<>();
        Map<String, Object> merge = new LinkedHashMap<>();
        Map<String, Object> rowlen = new LinkedHashMap<>();
        Map<String, Object> columnlen = new LinkedHashMap<>();
        List<Map<String, Object>> borderInfo = new ArrayList<>();

        for (int r = 0; r <= maxRow; r++) {
            XSSFRow row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            // 行高逐行照搬：Excel 默认约 17.6px、设计器默认 19px，不写就一路偏，
            // 攒几十行分页位置就和出纸对不上了
            rowlen.put(String.valueOf(r), Math.round(row.getHeightInPoints() / POINTS_PER_PX));
            for (int c = 0; c < row.getLastCellNum(); c++) {
                XSSFCell cell = row.getCell(c);
                if (cell != null) {
                    convertCell(wb, cell, r, c, sheet.getSheetName(), cells, borderInfo);
                }
            }
        }

        // 列宽同理逐列全写：Excel 默认约 64px，而 ExcelExporter#applyColumnWidths 按
        // DEFAULT_COL_WIDTH_PX=73 给没写的列补默认值，缺一列整表就偏窄
        for (int c = 0; c <= maxCol; c++) {
            columnlen.put(String.valueOf(c), Math.round(sheet.getColumnWidthInPixels(c)));
        }

        applyMerges(sheet, cells, merge);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("merge", merge);
        config.put("rowlen", rowlen);
        config.put("columnlen", columnlen);
        config.put("borderInfo", borderInfo);

        List<Map<String, Object>> celldata = new ArrayList<>(cells.values());
        celldata.sort(Comparator.<Map<String, Object>>comparingInt(m -> (int) m.get("r"))
                .thenComparingInt(m -> (int) m.get("c")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", sheet.getSheetName());
        out.put("id", "sheet_" + (index + 1));
        out.put("order", index);
        out.put("status", index == 0 ? 1 : 0);
        // 行列留余量：导进来之后还要往下加行带、加合计行
        out.put("row", Math.max(maxRow + 10, 60));
        out.put("column", Math.max(maxCol + 3, 20));
        out.put("celldata", celldata);
        out.put("config", config);
        return out;
    }

    /** 内容范围的最后一列：格子与合并区都算上。 */
    private int maxColumn(XSSFSheet sheet) {
        int max = -1;
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            XSSFRow row = sheet.getRow(r);
            if (row != null) {
                max = Math.max(max, row.getLastCellNum() - 1);
            }
        }
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            max = Math.max(max, region.getLastColumn());
        }
        return Math.max(max, 0);
    }

    private void checkCapacity(String name, int maxRow, int maxCol) {
        if (maxRow + 1 > opts.maxRows()) {
            throw new BizException("工作表[" + name + "]有 " + (maxRow + 1) + " 行，超过上限 "
                    + opts.maxRows() + " 行");
        }
        if ((long) (maxRow + 1) * (maxCol + 1) > opts.maxCells()) {
            throw new BizException("工作表[" + name + "]的单元格数超过上限 " + opts.maxCells());
        }
    }

    /** 带不过来的东西逐张 sheet 报一次 —— 不说清楚就会变成「导进来不对」的报障。 */
    private void noticeUnsupported(XSSFSheet sheet) {
        String name = sheet.getSheetName();
        if (sheet.getDrawingPatriarch() != null) {
            warn("工作表[" + name + "]里的图片/图表/形状没有导入");
        }
        try {
            if (sheet.getSheetConditionalFormatting().getNumConditionalFormattings() > 0) {
                warn("工作表[" + name + "]里的条件格式没有导入");
            }
            if (!sheet.getDataValidations().isEmpty()) {
                warn("工作表[" + name + "]里的数据有效性没有导入");
            }
        } catch (Exception e) {
            // 这两样只是提示，读不出来不该把整份导入拖下水
            log.debug("读取工作表[{}]的条件格式/数据有效性失败", name, e);
        }
    }

    // ------------------------------------------------------------------
    // 单元格：值 + 样式 + 边框
    // ------------------------------------------------------------------

    private void convertCell(XSSFWorkbook wb, XSSFCell cell, int r, int c, String sheetName,
                             Map<String, Map<String, Object>> cells, List<Map<String, Object>> borderInfo) {
        XSSFCellStyle cs = cell.getCellStyle();
        appendBorder(borderInfo, r, c, cs);

        String text = cellText(cell);
        String formula = formulaOf(cell);
        Map<String, Object> style = style(wb, cs, sheetName);
        if (text.isEmpty() && formula == null && style.isEmpty()) {
            // 空白格不占 celldata：边框已经收进 borderInfo，不需要一个空条目挂着
            return;
        }
        if (formula == null && text.startsWith("=")) {
            // TemplateParser 会把「文本以 = 开头」的格子当成原生公式，且没有转义手段
            warn("工作表[" + sheetName + "]第 " + (r + 1) + " 行第 " + (c + 1)
                    + " 列的文字以 = 开头，渲染时会被当成公式");
        }

        Map<String, Object> v = new LinkedHashMap<>(style);
        v.put("v", text);
        v.put("m", text);
        if (numeric(cell)) {
            // 值已经是「1,234.00」这样的文字了，标成文本免得前端或二次导出又识别回数字
            Map<String, Object> ct = new LinkedHashMap<>();
            ct.put("fa", "@");
            ct.put("t", "s");
            v.put("ct", ct);
        }
        if (formula != null) {
            v.put("f", formula);
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("r", r);
        entry.put("c", c);
        entry.put("v", v);
        cells.put(r + "_" + c, entry);
    }

    /**
     * 取「Excel 里显示的那串文字」。
     *
     * <p>公式格走**缓存值**而不是重算：重算要 {@code FormulaEvaluator}，遇到 POI 不认识的函数
     * 就整格抛异常，而缓存值就是用户存盘时看到的那个数。
     */
    private String cellText(XSSFCell cell) {
        CellType type = cell.getCellType();
        if (type != CellType.FORMULA) {
            return formatter.formatCellValue(cell);
        }
        XSSFCellStyle cs = cell.getCellStyle();
        return switch (cell.getCachedFormulaResultType()) {
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(),
                    cs.getDataFormat(), cs.getDataFormatString());
            case STRING -> cell.getRichStringCellValue().getString();
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            default -> "";
        };
    }

    /** 原生公式原样带走，渲染时由 {@code A1RefUtils} 跟着扩展偏移、交给前端算。 */
    private String formulaOf(XSSFCell cell) {
        if (cell.getCellType() != CellType.FORMULA) {
            return null;
        }
        try {
            String f = cell.getCellFormula();
            return f == null || f.isBlank() ? null : "=" + f;
        } catch (Exception e) {
            warn("有公式读不出来（" + e.getMessage() + "），已只保留它算出来的值");
            return null;
        }
    }

    /** 原本是不是数值（含日期）—— 决定要不要补文本格式的 ct，见类注释。 */
    private boolean numeric(XSSFCell cell) {
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        return type == CellType.NUMERIC;
    }

    /**
     * 单元格样式 -> FortuneSheet 的样式键，是 {@code ExcelExporter#resolveStyle} 的逆。
     *
     * <p>**与工作簿默认字体相同的字号/字体名不写**：一张几千格的表，每格都挂一份
     * {@code ff/fs} 会把 content 撑大好几倍，而它们本来就是默认值。
     */
    private Map<String, Object> style(XSSFWorkbook wb, XSSFCellStyle cs, String sheetName) {
        Map<String, Object> style = new LinkedHashMap<>();
        if (cs == null) {
            return style;
        }

        XSSFFont font = cs.getFont();
        XSSFFont def = wb.getFontAt(0);
        if (font != null) {
            if (font.getBold()) {
                style.put("bl", 1);
            }
            if (font.getItalic()) {
                style.put("it", 1);
            }
            String name = font.getFontName();
            if (name != null && !name.isBlank() && (def == null || !name.equals(def.getFontName()))) {
                style.put("ff", name);
            }
            short size = font.getFontHeightInPoints();
            if (size > 0 && (def == null || size != def.getFontHeightInPoints())) {
                style.put("fs", (int) size);
            }
            String color = hex(font.getXSSFColor());
            if (color != null) {
                style.put("fc", color);
            }
        }

        if (cs.getFillPattern() == FillPatternType.SOLID_FOREGROUND) {
            String bg = hex(cs.getFillForegroundXSSFColor());
            if (bg != null) {
                style.put("bg", bg);
            }
        } else if (cs.getFillPattern() != FillPatternType.NO_FILL) {
            warn("工作表[" + sheetName + "]里有非实心的图案填充，只支持纯色底纹，已忽略");
        }

        // FortuneSheet: ht 0=居中 1=左 2=右；vt 0=中 1=上 2=下。
        // GENERAL 不写 —— ExcelExporter 那头也是「没有 ht 就写 GENERAL」，两边对称
        switch (cs.getAlignment()) {
            case CENTER -> style.put("ht", 0);
            case LEFT -> style.put("ht", 1);
            case RIGHT -> style.put("ht", 2);
            default -> { }
        }
        switch (cs.getVerticalAlignment()) {
            case TOP -> style.put("vt", 1);
            case BOTTOM -> style.put("vt", 2);
            case CENTER -> style.put("vt", 0);
            default -> { }
        }
        if (cs.getWrapText()) {
            style.put("tb", "2");
        }
        return style;
    }

    /**
     * 颜色 -> {@code #RRGGBB}。
     *
     * <p>{@code getRGBWithTint} 而不是 {@code getRGB}：Office 主题色是「主题基色 + tint」，
     * 只取基色的话「浅色 60% 的蓝」会读成纯蓝。索引色由 POI 按工作簿的调色板还原，
     * {@code auto}（跟随系统前景色）返回 null，当没设过处理。
     */
    private String hex(XSSFColor color) {
        if (color == null || color.isAuto()) {
            return null;
        }
        byte[] rgb = color.getRGBWithTint();
        if (rgb == null) {
            rgb = color.getRGB();
        }
        if (rgb == null || rgb.length < 3) {
            return null;
        }
        int off = rgb.length == 4 ? 1 : 0;      // ARGB 时跳过 alpha
        return String.format("#%02X%02X%02X", rgb[off] & 0xFF, rgb[off + 1] & 0xFF, rgb[off + 2] & 0xFF);
    }

    /**
     * 边框逐格照搬成 {@code rangeType=cell}（{@code ExcelExporter#applyCellBorderEntry} 的逆）。
     *
     * <p>不必去重：xlsx 里相邻两格虽然共用一条线，但逐格形态写回去时
     * {@code applyCellBorderEntry} 也只管自己这一格、不动邻居，1:1 搬过来就是对称的。
     * **线型和颜色都要照搬** —— 只还原「有没有边框」的话，用户擦成白色的那条线导出后又是黑的。
     */
    private void appendBorder(List<Map<String, Object>> borderInfo, int r, int c, XSSFCellStyle cs) {
        if (cs == null) {
            return;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        boolean any = side(value, "t", cs.getBorderTop(), cs.getTopBorderXSSFColor());
        any |= side(value, "b", cs.getBorderBottom(), cs.getBottomBorderXSSFColor());
        any |= side(value, "l", cs.getBorderLeft(), cs.getLeftBorderXSSFColor());
        any |= side(value, "r", cs.getBorderRight(), cs.getRightBorderXSSFColor());
        if (!any) {
            return;
        }
        value.put("row_index", r);
        value.put("col_index", c);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("rangeType", "cell");
        entry.put("value", value);
        borderInfo.add(entry);
    }

    /** 一条边；{@code NONE} 不写这一项（{@code sideOf} 见到缺项就是「这条边没有」）。 */
    private boolean side(Map<String, Object> value, String key, BorderStyle style, XSSFColor color) {
        if (style == null || style == BorderStyle.NONE) {
            return false;
        }
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("style", borderCode(style));
        String hex = hex(color);
        if (hex != null) {
            // 取不到颜色时不写：写进 xlsx 就是 auto，PDF / Word 各自退回黑色，与正向一致
            side.put("color", hex);
        }
        value.put(key, side);
        return true;
    }

    /** POI 的线型 -> FortuneSheet 的线型码，是 {@code ExcelExporter#borderStyleOf} 的逆。 */
    private int borderCode(BorderStyle style) {
        return switch (style) {
            case NONE -> 0;
            case HAIR -> 2;
            case DOTTED -> 3;
            case DASHED -> 4;
            case DASH_DOT -> 5;
            case DASH_DOT_DOT -> 6;
            case DOUBLE -> 7;
            case MEDIUM -> 8;
            case MEDIUM_DASHED -> 9;
            case MEDIUM_DASH_DOT -> 10;
            case MEDIUM_DASH_DOT_DOT -> 11;
            case SLANTED_DASH_DOT -> 12;
            case THICK -> 13;
            default -> 1;                   // THIN 与其它没有对应码的一律细实线
        };
    }

    // ------------------------------------------------------------------
    // 合并
    // ------------------------------------------------------------------

    /**
     * 合并区 -> {@code config.merge} + 每格的 {@code v.mc}。
     *
     * <p>被盖住的格子要补一个指回首格的 {@code mc} 并**清掉文字**：FortuneSheet 画合并要
     * 两份数据对得上，而留着文字的话渲染时那一格会照样输出一遍。
     */
    private void applyMerges(XSSFSheet sheet, Map<String, Map<String, Object>> cells,
                             Map<String, Object> merge) {
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            int r = region.getFirstRow();
            int c = region.getFirstColumn();
            int rs = region.getLastRow() - r + 1;
            int cs = region.getLastColumn() - c + 1;
            if (rs <= 1 && cs <= 1) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("r", r);
            m.put("c", c);
            m.put("rs", rs);
            m.put("cs", cs);
            merge.put(r + "_" + c, m);

            valueOf(cells, r, c).put("mc", new LinkedHashMap<>(m));
            for (int rr = r; rr < r + rs; rr++) {
                for (int cc = c; cc < c + cs; cc++) {
                    if (rr == r && cc == c) {
                        continue;
                    }
                    Map<String, Object> v = valueOf(cells, rr, cc);
                    v.remove("v");
                    v.remove("m");
                    v.remove("ct");
                    v.remove("f");
                    Map<String, Object> marker = new LinkedHashMap<>();
                    marker.put("r", r);
                    marker.put("c", c);
                    v.put("mc", marker);
                }
            }
        }
    }

    /** 取（必要时补建）某一格的 v 对象。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> valueOf(Map<String, Map<String, Object>> cells, int r, int c) {
        Map<String, Object> entry = cells.computeIfAbsent(r + "_" + c, k -> {
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("r", r);
            created.put("c", c);
            created.put("v", new LinkedHashMap<String, Object>());
            return created;
        });
        return (Map<String, Object>) entry.get("v");
    }

    // ------------------------------------------------------------------
    // 页面设置（ExcelExporter#applyPageSetup 的逆）
    // ------------------------------------------------------------------

    private PageConfigDTO pageConfig(XSSFWorkbook wb, XSSFSheet sheet, int wbIndex) {
        PageConfigDTO cfg = new PageConfigDTO();
        XSSFPrintSetup ps = sheet.getPrintSetup();

        cfg.setPaperSize(paperName(sheet, ps));
        cfg.setOrientation(ps.getLandscape() ? "landscape" : "portrait");
        cfg.setMarginTop(mm(sheet.getMargin(PageMargin.TOP)));
        cfg.setMarginBottom(mm(sheet.getMargin(PageMargin.BOTTOM)));
        cfg.setMarginLeft(mm(sheet.getMargin(PageMargin.LEFT)));
        cfg.setMarginRight(mm(sheet.getMargin(PageMargin.RIGHT)));

        // 「适应一页宽」与缩放互斥。注意正向那头有个兜底：limitWidth 且用户没调过缩放时
        // 也会写成 fitToPage（见 ExcelExporter#applyPageSetup），所以从 xlsx 反读分不清
        // 「用户设了适应页宽」还是「限宽兜底」—— 一律按前者收，这一项是不可逆的
        if (sheet.getFitToPage() && ps.getFitWidth() == 1) {
            cfg.setFitToWidth(true);
            cfg.setScale(100);
        } else {
            cfg.setFitToWidth(false);
            short scale = ps.getScale();
            cfg.setScale(Math.min(Math.max(scale <= 0 ? 100 : scale, 10), 400));
        }

        cfg.setPrintArea(printArea(wb, sheet, wbIndex));
        cfg.setTitleRows(titleRows(sheet, cfg.getPrintArea()));

        HeaderFooterDTO header = HeaderFooterText.fromExcelCode(
                ((XSSFOddHeader) sheet.getOddHeader()).getText());
        if (!header.isBlank()) {
            header.setMargin(mm(sheet.getMargin(PageMargin.HEADER)));
        }
        cfg.setHeader(header);
        HeaderFooterDTO footer = HeaderFooterText.fromExcelCode(
                ((XSSFOddFooter) sheet.getOddFooter()).getText());
        if (!footer.isBlank()) {
            footer.setMargin(mm(sheet.getMargin(PageMargin.FOOTER)));
        }
        cfg.setFooter(footer);
        return cfg;
    }

    /**
     * 纸张名，是 {@code ExcelExporter#paperSize} 的逆。
     *
     * <p><b>没显式写过纸张的按 A4</b>：OOXML 里 {@code paperSize} 的缺省值是 1（Letter），
     * 而国内的表多半是 A4 存的、只是没落这个属性 —— 照读的话每张导进来都成了 Letter。
     */
    private String paperName(XSSFSheet sheet, XSSFPrintSetup ps) {
        boolean explicit = sheet.getCTWorksheet().isSetPageSetup()
                && sheet.getCTWorksheet().getPageSetup().isSetPaperSize();
        if (!explicit) {
            return "A4";
        }
        short size = ps.getPaperSize();
        if (size == PrintSetup.A3_PAPERSIZE) {
            return "A3";
        } else if (size == PrintSetup.A4_PAPERSIZE) {
            return "A4";
        } else if (size == PrintSetup.A5_PAPERSIZE) {
            return "A5";
        } else if (size == PrintSetup.B4_PAPERSIZE) {
            return "B4";
        } else if (size == PrintSetup.B5_PAPERSIZE) {
            return "B5";
        } else if (size == PrintSetup.LETTER_PAPERSIZE) {
            return "Letter";
        } else if (size == PrintSetup.LEGAL_PAPERSIZE) {
            return "Legal";
        }
        warn("工作表[" + sheet.getSheetName() + "]用的纸张本系统不支持，已按 A4 导入");
        return "A4";
    }

    /** {@code Sheet1!$A$1:$D$20} -> {@code A1:D20}；多个区域只取第一个。 */
    private String printArea(XSSFWorkbook wb, XSSFSheet sheet, int wbIndex) {
        String area = wb.getPrintArea(wbIndex);
        if (area == null || area.isBlank()) {
            return "";
        }
        if (area.contains(",")) {
            warn("工作表[" + sheet.getSheetName() + "]设了多块打印区域，只导入第一块");
            area = area.substring(0, area.indexOf(','));
        }
        int bang = area.lastIndexOf('!');
        return area.substring(bang + 1).replace("$", "").trim();
    }

    /**
     * 顶端标题行，校验与 {@code ExcelExporter#applyTitleRows} / {@code PdfExporter#readTitleRows}
     * 同一套：**只认内容范围最上面的连续若干行**，落在中间的三条导出路语义对不齐。
     */
    private String titleRows(XSSFSheet sheet, String printArea) {
        CellRangeAddress rows = sheet.getRepeatingRows();
        if (rows == null) {
            return "";
        }
        int first = 0;
        int last = sheet.getLastRowNum();
        if (printArea != null && !printArea.isBlank()) {
            try {
                CellRangeAddress area = CellRangeAddress.valueOf(printArea);
                first = area.getFirstRow();
                last = Math.min(area.getLastRow(), last);
            } catch (Exception ignored) {
                // 打印区域读不出来时按整表判，同正向
            }
        }
        String text = (rows.getFirstRow() + 1) + ":" + (rows.getLastRow() + 1);
        if (rows.getFirstRow() > first || rows.getLastRow() < first) {
            warn("工作表[" + sheet.getSheetName() + "]的顶端标题行[" + text
                    + "]不在内容最上面，没有导入");
            return "";
        }
        if (rows.getLastRow() >= last) {
            warn("工作表[" + sheet.getSheetName() + "]的顶端标题行[" + text
                    + "]盖住了全部内容，没有导入");
            return "";
        }
        return text;
    }

    /** POI 的页边距单位是英寸。 */
    private int mm(double inch) {
        return (int) Math.round(inch * 25.4);
    }

    // ------------------------------------------------------------------

    /** 同一条只报一次 —— 逐格发现的问题（非实心填充之类）会重复很多遍。 */
    private void warn(String message) {
        if (!warnings.contains(message)) {
            warnings.add(message);
            log.info("导入 Excel: {}", message);
        }
    }
}
