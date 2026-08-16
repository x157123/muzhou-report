package com.muzhou.report.engine;

import com.muzhou.report.dto.ChartConfigDTO;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图表单元格（{@code type=chart}）的出图：把一份数据集画成一张 PNG。
 *
 * <p><b>它是图片单元格的延长线，不是第五条导出路</b> —— 与 {@link BarcodeGenerator} 同一套路数：
 * 在**渲染时**于服务端画成图、归一化成 {@code data:} URI 交给 {@code GridCell#image}，于是预览、
 * Excel、PDF、Word 拿到的是同一张图，下游一处都不用改（它们只认 {@code v.mzImg.src}，
 * 认不出这张图是下载来的、编成码的、还是画出来的）。放到前端画的话，导出那三条路都在服务端，
 * 定时任务直接调导出接口时就**永远没有图**。
 *
 * <p><b>为什么自己用 Java2D 画，而不是引一个图表库</b>：出图这件事拢共就是「算刻度 + 画矩形/折线/扇形」，
 * 而引一个库要连着它的许可、体积与升级一起背；本项目在 PDF 那条路上已经因为
 * Aspose 的商业授权栽过一次（见 CLAUDE.md）。真要换成别的实现，换的只有本类
 * —— 上下游只认 {@link #dataUri} 这一个入口。
 *
 * <p><b>尺寸按目标框来，不是固定尺寸再等比装进去</b>（与条码唯一的实质差异）：条码等比缩放不失真，
 * 而图表的轴文字、图例、数值标签是**画在图里**的，等比放大 3 倍会让字大得离谱、缩小到 1/3 会糊成一团。
 * 所以这里按格子（合并区则整块）的实际像素出图，宽高比与格子一致，
 * {@code export/ImageFit#contain} 算出来的缩放系数正好是 1，三条导出路一行不用改。
 *
 * <p><b>画不出来就出空格</b>（记一条说清是哪一种的 warn）：数据为空、类目字段不存在、系列全不是数字。
 * 一份 500 行的报表里有一条脏数据就整份渲染失败，没法交代 —— 同 {@link BarcodeGenerator}
 * 与 {@code export/ImageLoader} 对坏数据的态度。
 */
@Slf4j
public final class ChartRenderer {

    private ChartRenderer() {
    }

    /**
     * 超采样倍数：屏幕上 400×240 的框打到纸上约 150 DPI，1 倍出图会糊。
     * 字号跟着一起放大，所以最终视觉大小不变。
     */
    static final int SUPERSAMPLE = 2;

    /** 出图尺寸下限：再小的格子也给一张看得出形状的图。 */
    static final int MIN_PX = 160;

    /** 出图尺寸上限：挡住「把图表格拖成整页」时画出一张几十 MB 的 PNG。 */
    static final int MAX_PX = 2400;

    /** 配色主题。认不出的名字退回 default（同条码那边不认识的码制退回默认）。 */
    private static final Map<String, Color[]> THEMES = new LinkedHashMap<>();

    static {
        // 服务器上没有显示器，AWT 一旦当成有头环境就可能去连 X11 并抛 HeadlessException。
        // Spring Boot 启动时本来就会设它，这里再钉一次是给「不起 Spring 的单测」与
        // 被别的容器加载时用的（必须赶在 AWT 初始化之前，所以放在静态块最前面）
        System.setProperty("java.awt.headless", "true");

        THEMES.put("default", colors("5B8FF9", "5AD8A6", "F6BD16", "E8684A", "6DC8EC",
                "9270CA", "FF9D4D", "269A99", "5D7092", "FF99C3"));
        THEMES.put("office", colors("4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5",
                "70AD47", "264478", "9E480E", "636363", "997300"));
        THEMES.put("mono", colors("1F3864", "2E5496", "4472C4", "6A8ED4", "8FAADC",
                "B4C7E7", "D9E2F3", "31859C", "4BACC6", "92CDDC"));
    }

    private static final Color AXIS = new Color(0x99, 0x99, 0x99);
    private static final Color GRID = new Color(0xE6, 0xE6, 0xE6);
    private static final Color TEXT = new Color(0x33, 0x33, 0x33);
    private static final Color TEXT_WEAK = new Color(0x66, 0x66, 0x66);

    /** 类目值为空时归到这一组 —— 整行丢掉的话，图上的合计与表里的合计对不上。 */
    static final String EMPTY_CATEGORY = "(空)";

    /**
     * 把一份数据集画成图。
     *
     * @param cfg  图表配置
     * @param rows 整份数据（已由取数那头决定是全量还是这一份单据的那几行）
     * @param boxW 目标框宽（像素，见 {@code ChartProcessor#boxOf}）
     * @param boxH 目标框高
     * @return 可直接当 {@code <img src>} 用的 data URI；画不出来返回 null（该格当空格处理）
     */
    public static String dataUri(ChartConfigDTO cfg, List<Map<String, Object>> rows, double boxW, double boxH) {
        if (cfg == null || !cfg.valid()) {
            log.warn("图表配置不完整（类目字段与数值系列都要配），该格已当空格处理");
            return null;
        }
        if (rows == null || rows.isEmpty()) {
            log.warn("图表数据为空，该格已当空格处理: 类目字段={}", cfg.getCategoryField());
            return null;
        }
        ChartData data = aggregate(cfg, rows);
        if (data.categories().isEmpty()) {
            log.warn("图表按[{}]分组后没有任何类目，该格已当空格处理", cfg.getCategoryField());
            return null;
        }
        try {
            int w = clampPx(boxW * SUPERSAMPLE);
            int h = clampPx(boxH * SUPERSAMPLE);
            BufferedImage image = draw(cfg, data, w, h);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            log.warn("图表出图失败，该格已当空格处理: 类型={}, 类目字段={}, 原因={}",
                    cfg.getChartType(), cfg.getCategoryField(), e.toString());
            return null;
        }
    }

    /* ------------------------- 数据 ------------------------- */

    /** 画图要的那份数据：类目 + 每条系列在各类目上的值。 */
    record ChartData(List<String> categories, List<SeriesData> series, boolean truncated) {
    }

    record SeriesData(String name, double[] values) {
    }

    /**
     * 按类目分组聚合。
     *
     * <p>类目顺序按**首次出现**的顺序（LinkedHashMap）—— 数据集自己 ORDER BY 过的顺序就是用户
     * 想要的顺序，重新排序反而对不上表里的行序。
     *
     * <p>类目数超过上限时截断并记 warn：一张纸上挤 500 根柱子本来也看不清，而明细型数据集
     * 动辄几百上千行。
     */
    static ChartData aggregate(ChartConfigDTO cfg, List<Map<String, Object>> rows) {
        List<ChartConfigDTO.Series> series = cfg.validSeries();
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Object cat = value(row, cfg.getCategoryField());
            String key = cat == null || String.valueOf(cat).isBlank()
                    ? EMPTY_CATEGORY : String.valueOf(cat);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        int limit = cfg.categoryLimit();
        boolean truncated = grouped.size() > limit;
        if (truncated) {
            log.warn("图表类目数 {} 超过上限 {}，只画前 {} 个: 类目字段={}",
                    grouped.size(), limit, limit, cfg.getCategoryField());
        }

        List<String> categories = new ArrayList<>();
        for (String key : grouped.keySet()) {
            if (categories.size() >= limit) {
                break;
            }
            categories.add(key);
        }

        List<SeriesData> out = new ArrayList<>();
        for (ChartConfigDTO.Series s : series) {
            double[] values = new double[categories.size()];
            for (int i = 0; i < categories.size(); i++) {
                values[i] = reduce(grouped.get(categories.get(i)), s);
            }
            out.add(new SeriesData(s.displayName(), values));
        }
        return new ChartData(categories, out, truncated);
    }

    /** 同一类目内把多行算成一个数。 */
    private static double reduce(List<Map<String, Object>> rows, ChartConfigDTO.Series s) {
        String aggregate = s.getAggregate() == null ? "sum" : s.getAggregate().trim().toLowerCase(Locale.ROOT);
        if ("count".equals(aggregate)) {
            // 计数只看「这一格有没有值」，不要求它是数字 —— 「按状态统计单量」绑的往往是文本字段
            int n = 0;
            for (Map<String, Object> row : rows) {
                Object v = value(row, s.getField());
                if (v != null && !String.valueOf(v).isBlank()) {
                    n++;
                }
            }
            return n;
        }
        List<Double> nums = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Double d = toDouble(value(row, s.getField()));
            if (d != null) {
                nums.add(d);
            }
        }
        if (nums.isEmpty()) {
            return 0;
        }
        return switch (aggregate) {
            case "avg" -> nums.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case "max" -> nums.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case "min" -> nums.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            // none = 不聚合，同类目取第一条（数据集本身已经是汇总结果时用）
            case "none" -> nums.get(0);
            default -> nums.stream().mapToDouble(Double::doubleValue).sum();
        };
    }

    /**
     * 按字段名取值。
     *
     * <p>大小写敏感（同 {@code ExpandProcessor} 的 {@code row.get(field)}），取不到再退回小写
     * —— 与 {@code ReportRenderEngine#fieldText}、{@code LinkedDataFetcher#fieldValue} 那两处的兜底
     * 同一条规则，理由也一样：字段名是用户在配置里手写的。
     */
    static Object value(Map<String, Object> row, String field) {
        if (row == null || field == null || field.isBlank()) {
            return null;
        }
        if (row.containsKey(field)) {
            return row.get(field);
        }
        return row.get(field.toLowerCase(Locale.ROOT));
    }

    /** 值转数字：数字直接用，字符串洗掉千分位/货币符号/百分号再试，转不了返回 null。 */
    static Double toDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        boolean percent = s.endsWith("%");
        String cleaned = s.replaceAll("[,，\\s¥$€£]", "");
        if (percent) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        try {
            double d = new BigDecimal(cleaned).doubleValue();
            return percent ? d / 100 : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ------------------------- 绘制 ------------------------- */

    static BufferedImage draw(ChartConfigDTO cfg, ChartData data, int w, int h) {
        // TYPE_INT_RGB 不是 ARGB：这张图要进 xlsx / PDF / Word，透明通道在那几头的支持参差，
        // 白底最省事（格子本来就是白的）。面积图那点半透明照样混合得出来
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);

            float base = fontSize(w, h);
            Color[] palette = palette(cfg.getTheme());
            Rect area = new Rect(base * 0.8, base * 0.8, w - base * 1.6, h - base * 1.6);

            area = drawTitle(g, cfg, area, base);
            area = drawLegend(g, cfg, data, area, base, palette);

            if (cfg.isPie()) {
                drawPie(g, cfg, data, area, base, palette);
            } else {
                drawAxisChart(g, cfg, data, area, base, palette);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * 字号按图的短边定，再钳进一个区间。
     *
     * <p>固定字号在小格子上会把整张图糊满、在大格子上又细得看不见 —— 图表是按目标框出图的，
     * 尺寸差得很远。
     */
    private static float fontSize(int w, int h) {
        return (float) clamp(Math.min(w, h) * 0.045, 10, 34);
    }

    /** 一块矩形区域（用 double 记，省得逐处取整累出偏差）。 */
    private record Rect(double x, double y, double w, double h) {
        double right() {
            return x + w;
        }

        double bottom() {
            return y + h;
        }
    }

    private static Rect drawTitle(Graphics2D g, ChartConfigDTO cfg, Rect area, float base) {
        String title = cfg.getTitle();
        if (title == null || title.isBlank()) {
            return area;
        }
        Font font = ChartFonts.of(base * 1.15f, true);
        g.setFont(font);
        g.setColor(TEXT);
        Rectangle2D box = font.getStringBounds(title, g.getFontRenderContext());
        double y = area.y() + box.getHeight() * 0.85;
        g.drawString(title, (float) (area.x() + (area.w() - box.getWidth()) / 2), (float) y);
        double used = box.getHeight() * 1.5;
        return new Rect(area.x(), area.y() + used, area.w(), area.h() - used);
    }

    /**
     * 图例。上下摆时横排（超宽自动折行），左右摆时竖排。
     *
     * @return 去掉图例之后剩下的绘图区
     */
    private static Rect drawLegend(Graphics2D g, ChartConfigDTO cfg, ChartData data,
                                   Rect area, float base, Color[] palette) {
        if (!cfg.isShowLegend()) {
            return area;
        }
        // 饼图的图例列的是类目（一个饼只有一条系列，列系列名等于什么都没说）
        List<String> labels = new ArrayList<>();
        if (cfg.isPie()) {
            labels.addAll(data.categories());
        } else {
            for (SeriesData s : data.series()) {
                labels.add(s.name());
            }
        }
        if (labels.isEmpty()) {
            return area;
        }
        String position = cfg.getLegendPosition() == null ? "bottom" : cfg.getLegendPosition();
        if ("none".equals(position)) {
            return area;
        }

        Font font = ChartFonts.of(base * 0.85f, false);
        g.setFont(font);
        double markW = base * 0.9;
        double gap = base * 0.7;
        double lineH = base * 1.4;

        if ("left".equals(position) || "right".equals(position)) {
            double width = 0;
            for (String label : labels) {
                width = Math.max(width, font.getStringBounds(label, g.getFontRenderContext()).getWidth());
            }
            width += markW + gap * 1.5;
            // 图例最多占三分之一，剩下的留给图本身 —— 类目名很长时不能让图被挤没
            width = Math.min(width, area.w() / 3);
            double x = "left".equals(position) ? area.x() : area.right() - width;
            double y = area.y() + (area.h() - labels.size() * lineH) / 2;
            for (int i = 0; i < labels.size(); i++) {
                drawLegendItem(g, labels.get(i), x, y + i * lineH, markW, gap, base, palette[i % palette.length]);
            }
            return "left".equals(position)
                    ? new Rect(area.x() + width, area.y(), area.w() - width, area.h())
                    : new Rect(area.x(), area.y(), area.w() - width, area.h());
        }

        // 上/下：横排，一行放不下就折行
        List<List<Integer>> lines = new ArrayList<>();
        List<Integer> line = new ArrayList<>();
        double used = 0;
        for (int i = 0; i < labels.size(); i++) {
            double itemW = markW + gap * 0.5
                    + font.getStringBounds(labels.get(i), g.getFontRenderContext()).getWidth() + gap;
            if (!line.isEmpty() && used + itemW > area.w()) {
                lines.add(line);
                line = new ArrayList<>();
                used = 0;
            }
            line.add(i);
            used += itemW;
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }

        double height = lines.size() * lineH;
        double top = "top".equals(position) ? area.y() : area.bottom() - height;
        for (int li = 0; li < lines.size(); li++) {
            List<Integer> items = lines.get(li);
            double lineW = 0;
            for (int idx : items) {
                lineW += markW + gap * 0.5
                        + font.getStringBounds(labels.get(idx), g.getFontRenderContext()).getWidth() + gap;
            }
            double x = area.x() + (area.w() - lineW) / 2;
            for (int idx : items) {
                drawLegendItem(g, labels.get(idx), x, top + li * lineH, markW, gap, base,
                        palette[idx % palette.length]);
                x += markW + gap * 0.5
                        + font.getStringBounds(labels.get(idx), g.getFontRenderContext()).getWidth() + gap;
            }
        }
        return "top".equals(position)
                ? new Rect(area.x(), area.y() + height, area.w(), area.h() - height)
                : new Rect(area.x(), area.y(), area.w(), area.h() - height);
    }

    private static void drawLegendItem(Graphics2D g, String label, double x, double y,
                                       double markW, double gap, float base, Color color) {
        double markH = base * 0.55;
        g.setColor(color);
        g.fill(new Rectangle2D.Double(x, y + base * 0.42, markW, markH));
        g.setColor(TEXT_WEAK);
        g.drawString(label, (float) (x + markW + gap * 0.5), (float) (y + base * 1.0));
    }

    /* ------------------------- 柱 / 横向柱 / 折线 / 面积 ------------------------- */

    private static void drawAxisChart(Graphics2D g, ChartConfigDTO cfg, ChartData data,
                                      Rect area, float base, Color[] palette) {
        boolean horizontal = cfg.isHorizontal();
        double[] range = valueRange(data);
        double min = range[0];
        double max = range[1];
        double step = niceStep(max - min);
        // 刻度要落在整数格上，否则轴上出现 0.7 / 1.4 这种读不出来的刻度
        min = Math.floor(min / step) * step;
        max = Math.ceil(max / step) * step;
        if (max - min < 1e-9) {
            max = min + step;
        }

        Font tickFont = ChartFonts.of(base * 0.85f, false);
        g.setFont(tickFont);

        // 数值轴标签占的宽（竖着画时在左边）/ 类目轴标签占的高
        double valueLabelW = 0;
        for (double v = min; v <= max + 1e-9; v += step) {
            valueLabelW = Math.max(valueLabelW,
                    tickFont.getStringBounds(fmt(v), g.getFontRenderContext()).getWidth());
        }
        double catLabelW = 0;
        for (String c : data.categories()) {
            catLabelW = Math.max(catLabelW,
                    tickFont.getStringBounds(c, g.getFontRenderContext()).getWidth());
        }
        double lineH = base * 1.3;

        double axisTitleW = cfg.getValueAxisTitle() != null && !cfg.getValueAxisTitle().isBlank() ? base * 1.3 : 0;
        double axisTitleH = cfg.getCategoryAxisTitle() != null && !cfg.getCategoryAxisTitle().isBlank() ? base * 1.3 : 0;

        double left = area.x() + axisTitleW + (horizontal ? Math.min(catLabelW, area.w() / 3) : valueLabelW) + base * 0.5;
        double bottom = area.bottom() - axisTitleH - lineH;
        Rect plot = new Rect(left, area.y() + base * 0.4, area.right() - left - base * 0.4,
                bottom - area.y() - base * 0.4);
        if (plot.w() <= 0 || plot.h() <= 0) {
            return;
        }

        drawAxisTitles(g, cfg, area, plot, base);

        // 网格线与刻度
        g.setStroke(new BasicStroke(Math.max(1f, base * 0.05f)));
        for (double v = min; v <= max + 1e-9; v += step) {
            if (horizontal) {
                double x = plot.x() + (v - min) / (max - min) * plot.w();
                g.setColor(GRID);
                g.draw(new java.awt.geom.Line2D.Double(x, plot.y(), x, plot.bottom()));
                g.setColor(TEXT_WEAK);
                Rectangle2D b = tickFont.getStringBounds(fmt(v), g.getFontRenderContext());
                g.drawString(fmt(v), (float) (x - b.getWidth() / 2), (float) (plot.bottom() + lineH * 0.85));
            } else {
                double y = plot.bottom() - (v - min) / (max - min) * plot.h();
                g.setColor(GRID);
                g.draw(new java.awt.geom.Line2D.Double(plot.x(), y, plot.right(), y));
                g.setColor(TEXT_WEAK);
                Rectangle2D b = tickFont.getStringBounds(fmt(v), g.getFontRenderContext());
                g.drawString(fmt(v), (float) (plot.x() - b.getWidth() - base * 0.3),
                        (float) (y + b.getHeight() * 0.32));
            }
        }

        // 轴线（0 线加深：有负值时它不在底边上）
        g.setColor(AXIS);
        double zero = horizontal
                ? plot.x() + (0 - min) / (max - min) * plot.w()
                : plot.bottom() - (0 - min) / (max - min) * plot.h();
        if (horizontal) {
            g.draw(new java.awt.geom.Line2D.Double(clamp(zero, plot.x(), plot.right()), plot.y(),
                    clamp(zero, plot.x(), plot.right()), plot.bottom()));
            g.draw(new java.awt.geom.Line2D.Double(plot.x(), plot.bottom(), plot.right(), plot.bottom()));
        } else {
            g.draw(new java.awt.geom.Line2D.Double(plot.x(), clamp(zero, plot.y(), plot.bottom()),
                    plot.right(), clamp(zero, plot.y(), plot.bottom())));
            g.draw(new java.awt.geom.Line2D.Double(plot.x(), plot.y(), plot.x(), plot.bottom()));
        }

        int n = data.categories().size();
        double slot = (horizontal ? plot.h() : plot.w()) / n;

        // 类目标签：画不下就隔几个画一个（旋转 45° 在小图上更难认）
        int stride = 1;
        if (!horizontal && catLabelW > 0) {
            stride = (int) Math.ceil((catLabelW + base * 0.6) / Math.max(slot, 1));
            stride = Math.max(1, stride);
        }
        g.setFont(tickFont);
        g.setColor(TEXT_WEAK);
        for (int i = 0; i < n; i++) {
            String label = data.categories().get(i);
            if (horizontal) {
                double y = plot.y() + slot * (i + 0.5);
                String shown = ellipsis(g, tickFont, label, Math.min(catLabelW, area.w() / 3));
                Rectangle2D b = tickFont.getStringBounds(shown, g.getFontRenderContext());
                g.drawString(shown, (float) (plot.x() - b.getWidth() - base * 0.3),
                        (float) (y + b.getHeight() * 0.32));
            } else if (i % stride == 0) {
                double x = plot.x() + slot * (i + 0.5);
                String shown = ellipsis(g, tickFont, label, slot * stride);
                Rectangle2D b = tickFont.getStringBounds(shown, g.getFontRenderContext());
                g.drawString(shown, (float) (x - b.getWidth() / 2), (float) (plot.bottom() + lineH * 0.85));
            }
        }

        if (cfg.isLineLike()) {
            drawLines(g, cfg, data, plot, min, max, slot, base, palette);
        } else {
            drawBars(g, cfg, data, plot, min, max, slot, base, palette, horizontal, zero);
        }
    }

    private static void drawAxisTitles(Graphics2D g, ChartConfigDTO cfg, Rect area, Rect plot, float base) {
        Font font = ChartFonts.of(base * 0.9f, false);
        g.setFont(font);
        g.setColor(TEXT_WEAK);
        String x = cfg.getCategoryAxisTitle();
        if (x != null && !x.isBlank()) {
            Rectangle2D b = font.getStringBounds(x, g.getFontRenderContext());
            g.drawString(x, (float) (plot.x() + (plot.w() - b.getWidth()) / 2), (float) (area.bottom()));
        }
        String y = cfg.getValueAxisTitle();
        if (y != null && !y.isBlank()) {
            // 竖排轴标题：转 90° 画，转回来别忘了（后面还要接着画别的）
            Rectangle2D b = font.getStringBounds(y, g.getFontRenderContext());
            double cx = area.x() + base * 0.9;
            double cy = plot.y() + (plot.h() + b.getWidth()) / 2;
            g.rotate(-Math.PI / 2, cx, cy);
            g.drawString(y, (float) cx, (float) cy);
            g.rotate(Math.PI / 2, cx, cy);
        }
    }

    private static void drawBars(Graphics2D g, ChartConfigDTO cfg, ChartData data, Rect plot,
                                 double min, double max, double slot, float base,
                                 Color[] palette, boolean horizontal, double zero) {
        int seriesCount = data.series().size();
        double groupW = slot * 0.72;
        double barW = groupW / seriesCount;
        Font labelFont = ChartFonts.of(base * 0.75f, false);

        for (int si = 0; si < seriesCount; si++) {
            SeriesData s = data.series().get(si);
            g.setColor(palette[si % palette.length]);
            for (int i = 0; i < s.values().length; i++) {
                double v = s.values()[i];
                if (horizontal) {
                    double center = plot.y() + slot * (i + 0.5);
                    double y = center - groupW / 2 + si * barW;
                    double x1 = plot.x() + (0 - min) / (max - min) * plot.w();
                    double x2 = plot.x() + (v - min) / (max - min) * plot.w();
                    double x = Math.min(x1, x2);
                    double len = Math.abs(x2 - x1);
                    g.fill(new Rectangle2D.Double(x, y, Math.max(len, 0.5), barW * 0.86));
                    if (cfg.isShowValueLabels()) {
                        drawValueLabel(g, labelFont, fmt(v), x + len + base * 0.25,
                                y + barW * 0.66, false);
                        g.setColor(palette[si % palette.length]);
                    }
                } else {
                    double center = plot.x() + slot * (i + 0.5);
                    double x = center - groupW / 2 + si * barW;
                    double y1 = plot.bottom() - (0 - min) / (max - min) * plot.h();
                    double y2 = plot.bottom() - (v - min) / (max - min) * plot.h();
                    double y = Math.min(y1, y2);
                    double len = Math.abs(y2 - y1);
                    g.fill(new Rectangle2D.Double(x, y, barW * 0.86, Math.max(len, 0.5)));
                    if (cfg.isShowValueLabels()) {
                        drawValueLabel(g, labelFont, fmt(v), x + barW * 0.43,
                                y - base * 0.25, true);
                        g.setColor(palette[si % palette.length]);
                    }
                }
            }
        }
        // 0 线（有负值时不在底边）画在柱子上面，否则被柱子盖住
        g.setColor(AXIS);
        if (horizontal) {
            double x = clamp(zero, plot.x(), plot.right());
            g.draw(new java.awt.geom.Line2D.Double(x, plot.y(), x, plot.bottom()));
        } else {
            double y = clamp(zero, plot.y(), plot.bottom());
            g.draw(new java.awt.geom.Line2D.Double(plot.x(), y, plot.right(), y));
        }
    }

    private static void drawLines(Graphics2D g, ChartConfigDTO cfg, ChartData data, Rect plot,
                                  double min, double max, double slot, float base, Color[] palette) {
        Font labelFont = ChartFonts.of(base * 0.75f, false);
        float stroke = Math.max(1.5f, base * 0.12f);
        for (int si = 0; si < data.series().size(); si++) {
            SeriesData s = data.series().get(si);
            Color color = palette[si % palette.length];
            double[] xs = new double[s.values().length];
            double[] ys = new double[s.values().length];
            for (int i = 0; i < s.values().length; i++) {
                xs[i] = plot.x() + slot * (i + 0.5);
                ys[i] = plot.bottom() - (s.values()[i] - min) / (max - min) * plot.h();
            }

            if (cfg.isArea() && xs.length > 0) {
                // 面积填到 0 线（不是填到底边）—— 有负值时填到底边会把负的那一段也涂上
                double zeroY = plot.bottom() - (0 - min) / (max - min) * plot.h();
                zeroY = clamp(zeroY, plot.y(), plot.bottom());
                Path2D.Double path = new Path2D.Double();
                path.moveTo(xs[0], zeroY);
                for (int i = 0; i < xs.length; i++) {
                    path.lineTo(xs[i], ys[i]);
                }
                path.lineTo(xs[xs.length - 1], zeroY);
                path.closePath();
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
                g.fill(path);
            }

            g.setColor(color);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < xs.length; i++) {
                g.draw(new java.awt.geom.Line2D.Double(xs[i - 1], ys[i - 1], xs[i], ys[i]));
            }
            double dot = Math.max(stroke * 1.6, base * 0.18);
            for (int i = 0; i < xs.length; i++) {
                g.fill(new java.awt.geom.Ellipse2D.Double(xs[i] - dot / 2, ys[i] - dot / 2, dot, dot));
                if (cfg.isShowValueLabels()) {
                    drawValueLabel(g, labelFont, fmt(s.values()[i]), xs[i], ys[i] - base * 0.4, true);
                    g.setColor(color);
                }
            }
        }
        g.setStroke(new BasicStroke(1f));
    }

    private static void drawValueLabel(Graphics2D g, Font font, String text, double x, double y, boolean center) {
        g.setFont(font);
        g.setColor(TEXT_WEAK);
        Rectangle2D b = font.getStringBounds(text, g.getFontRenderContext());
        g.drawString(text, (float) (center ? x - b.getWidth() / 2 : x), (float) y);
    }

    /* ------------------------- 饼 ------------------------- */

    private static void drawPie(Graphics2D g, ChartConfigDTO cfg, ChartData data,
                                Rect area, float base, Color[] palette) {
        // 一个饼说不了两组数，只画第一条系列
        SeriesData s = data.series().get(0);
        double total = 0;
        for (double v : s.values()) {
            total += Math.max(v, 0);
        }
        if (total <= 0) {
            log.warn("饼图的数值合计为 0（或全是负数），该格已当空格处理: 系列={}", s.name());
            return;
        }

        double size = Math.min(area.w(), area.h()) * 0.92;
        double cx = area.x() + area.w() / 2;
        double cy = area.y() + area.h() / 2;
        Rectangle2D.Double bounds = new Rectangle2D.Double(cx - size / 2, cy - size / 2, size, size);

        Font labelFont = ChartFonts.of(base * 0.8f, false);
        double start = 90;
        for (int i = 0; i < s.values().length; i++) {
            double v = Math.max(s.values()[i], 0);
            if (v <= 0) {
                continue;
            }
            double extent = -360.0 * v / total;
            g.setColor(palette[i % palette.length]);
            g.fill(new Arc2D.Double(bounds, start, extent, Arc2D.PIE));
            // 扇区之间描一道白边，相邻同色系的两块才分得开
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(Math.max(1f, base * 0.08f)));
            g.draw(new Arc2D.Double(bounds, start, extent, Arc2D.PIE));

            if (cfg.isShowValueLabels()) {
                double mid = Math.toRadians(start + extent / 2);
                double lx = cx + Math.cos(mid) * size * 0.31;
                double ly = cy - Math.sin(mid) * size * 0.31;
                String text = pct(v / total);
                g.setFont(labelFont);
                Rectangle2D b = labelFont.getStringBounds(text, g.getFontRenderContext());
                g.setColor(Color.WHITE);
                g.drawString(text, (float) (lx - b.getWidth() / 2), (float) (ly + b.getHeight() * 0.32));
            }
            start += extent;
        }
        g.setStroke(new BasicStroke(1f));
    }

    /* ------------------------- 小工具 ------------------------- */

    /** 数值范围：**必须含 0**（柱状图不从 0 起就是在骗人），全 0 时给一个 0~1 的空轴。 */
    private static double[] valueRange(ChartData data) {
        double min = 0;
        double max = 0;
        for (SeriesData s : data.series()) {
            for (double v : s.values()) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (Math.abs(max - min) < 1e-9) {
            max = min + 1;
        }
        return new double[]{min, max};
    }

    /** 「好看的」刻度间隔：1 / 2 / 5 × 10^n，让轴上出现的是 0、50、100 而不是 0、37、74。 */
    static double niceStep(double range) {
        if (range <= 0 || Double.isNaN(range) || Double.isInfinite(range)) {
            return 1;
        }
        double raw = range / 5;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double nice = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10;
        return nice * mag;
    }

    /** 刻度与数值标签的写法：大数带千分位，小数最多两位且去掉末尾的 0。 */
    static String fmt(double v) {
        if (Math.abs(v) >= 1000 || v == Math.rint(v)) {
            return new DecimalFormat("#,##0.##").format(v);
        }
        return new DecimalFormat("0.##").format(v);
    }

    private static String pct(double ratio) {
        return new DecimalFormat("0.#").format(ratio * 100) + "%";
    }

    /** 放不下就截断加省略号 —— 类目名动辄「华东大区第一分公司」这么长。 */
    private static String ellipsis(Graphics2D g, Font font, String text, double maxWidth) {
        if (maxWidth <= 0 || font.getStringBounds(text, g.getFontRenderContext()).getWidth() <= maxWidth) {
            return text;
        }
        for (int i = text.length() - 1; i > 0; i--) {
            String cut = text.substring(0, i) + "…";
            if (font.getStringBounds(cut, g.getFontRenderContext()).getWidth() <= maxWidth) {
                return cut;
            }
        }
        return "…";
    }

    private static Color[] palette(String theme) {
        Color[] p = THEMES.get(theme == null || theme.isBlank() ? "default" : theme);
        return p == null ? THEMES.get("default") : p;
    }

    private static Color[] colors(String... hex) {
        Color[] out = new Color[hex.length];
        for (int i = 0; i < hex.length; i++) {
            out[i] = new Color(Integer.parseInt(hex[i], 16));
        }
        return out;
    }

    private static int clampPx(double px) {
        return (int) Math.round(clamp(px, MIN_PX, MAX_PX));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
