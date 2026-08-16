package com.muzhou.report.engine;

import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.engine.model.GridCell;
import com.muzhou.report.engine.model.RenderGrid;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图表单元格的出图 pass：在**扩展完成之后**的网格上，把每个图表格画成一张图挂上去。
 *
 * <p><b>为什么是独立的一遍，而不是在 {@code buildCell} 里顺手画</b>：图表要按格子的实际像素出图
 * （见 {@link ChartRenderer} 类注释），而「这一格多宽多高、合并到了哪里」要等**扩展做完**才定得下来
 * —— 行带复制会把下方所有行的 {@code r}、{@code merge}、{@code rowlen} 整体偏移。所以这一遍
 * 排在 {@code applyMergeMarkers} 之后，同前端 {@code utils/cellImage.js} 必须排在
 * {@code applyWrapRowHeights} 之后是一个道理：几何要等布局定下来。
 *
 * <p>做法与公式那条路一样是「先占位、末尾统一处理」：{@code ExpandProcessor#emitRow} 遇到图表格时
 * 只把坐标与配置记进一个列表（{@link ChartCell}），这里再统一出图。
 */
@Slf4j
public final class ChartProcessor {

    private ChartProcessor() {
    }

    /**
     * 一次渲染最多画多少张图。
     *
     * <p>拆分模式下一条数据一张单据，每张单据上的图表都要单独画一遍 —— 500 份单据就是 500 张图，
     * 一张 60KB 的话光图片就 30MB base64 塞进渲染结果。超出后**跳过并 warn**，不报错：
     * 报表还是出得来，只是后面那些图空着；同 {@code maxRows} 截断的态度。
     */
    static final int MAX_CHARTS = 200;

    /** 待出图的图表格：坐标 + 配置（配置里带着数据集 code）。 */
    public record ChartCell(int r, int c, CellConfigDTO cfg) {
    }

    /** 设计器上没设过行高列宽时的默认值，与前端 {@code utils/print.js} 的那两个常量一致。 */
    private static final double DEFAULT_COL_WIDTH = 73;
    private static final double DEFAULT_ROW_HEIGHT = 19;

    /**
     * 逐个图表格出图。
     *
     * @param grid     扩展完成、合并标记已经建好的网格
     * @param charts   {@code ExpandProcessor} 收集到的图表格
     * @param datasets 本次渲染的数据（key = datasetCode）
     */
    public static void process(RenderGrid grid, List<ChartCell> charts,
                               Map<String, List<Map<String, Object>>> datasets) {
        if (charts == null || charts.isEmpty()) {
            return;
        }
        // 同一张 sheet 里画两张一模一样的图时省一次（key 只在本次调用内有效，
        // 所以拆分模式下各份数据不会串 —— 每份都是新的一次调用）
        Map<String, String> cache = new HashMap<>();
        int drawn = 0;
        for (ChartCell chart : charts) {
            GridCell cell = grid.get(chart.r(), chart.c());
            if (cell == null) {
                continue;
            }
            // 无论出不出得来图，图表格都不该在纸上留下那段设计器占位文本（`[图表] xxx`）
            cell.setValue("");
            cell.setDisplay("");

            if (drawn >= MAX_CHARTS) {
                continue;
            }
            CellConfigDTO cfg = chart.cfg();
            List<Map<String, Object>> rows = datasets.get(cfg.getDatasetCode());
            double[] box = boxOf(grid, chart.r(), chart.c());
            String key = cfg.getDatasetCode() + "|" + Math.round(box[0]) + "x" + Math.round(box[1])
                    + "|" + signature(cfg);
            String src = cache.get(key);
            if (src == null) {
                src = ChartRenderer.dataUri(cfg.getChart(), rows, box[0], box[1]);
                if (src != null) {
                    cache.put(key, src);
                }
            }
            if (src != null) {
                cell.setImage(src);
                drawn++;
            }
        }
        if (charts.size() > MAX_CHARTS) {
            log.warn("图表数量 {} 超过一次渲染的上限 {}，其余图表格已留空", charts.size(), MAX_CHARTS);
        }
    }

    /**
     * 图表格占的框有多大（像素）。
     *
     * <p>合并区就是整块（同 {@code ExcelExporter#applyImages} 的 {@code regionAt}）——
     * 图表本来就该画在一片区域上，单格那点大小画不出什么。配置落在合并区**左上角以外**的格子上时
     * 只算它自己那一格：合并信息是按左上角存的，从中间那一格反查不到整块（也没必要，
     * 设计器只会把配置写在选区左上角）。
     */
    static double[] boxOf(RenderGrid grid, int r, int c) {
        int rs = 1;
        int cs = 1;
        Map<String, Object> merge = grid.getMerge().get(RenderGrid.key(r, c));
        if (merge != null) {
            rs = Math.max(intOf(merge.get("rs"), 1), 1);
            cs = Math.max(intOf(merge.get("cs"), 1), 1);
        }
        double w = 0;
        for (int i = 0; i < cs; i++) {
            w += sizeOf(grid.getColumnlen(), c + i, DEFAULT_COL_WIDTH);
        }
        double h = 0;
        for (int i = 0; i < rs; i++) {
            h += sizeOf(grid.getRowlen(), r + i, DEFAULT_ROW_HEIGHT);
        }
        return new double[]{w, h};
    }

    private static double sizeOf(Map<String, Object> lens, int index, double fallback) {
        Object v = lens.get(String.valueOf(index));
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int intOf(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    /** 缓存 key 里代表「这张图长什么样」的那一段。 */
    private static String signature(CellConfigDTO cfg) {
        return cfg.getChart() == null ? "" : String.valueOf(cfg.getChart());
    }
}
