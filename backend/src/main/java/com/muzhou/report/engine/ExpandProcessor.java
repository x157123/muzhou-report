package com.muzhou.report.engine;

import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.engine.model.GridCell;
import com.muzhou.report.engine.model.RenderGrid;
import com.muzhou.report.engine.model.SheetTemplate;
import com.muzhou.report.engine.model.TemplateCell;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;

/**
 * 扩展处理器 —— 引擎的核心，见 docs/CONTRACT.md §7。
 *
 * <p>采用「顺序生成」策略：按模板行自上而下依次向输出网格 emit 行，
 * 扩展行带一次 emit n 行。相比「先铺满再插入行」的做法，这里不需要移动已有单元格，
 * 行号偏移天然由 {@link RenderGrid#getRowMapping()} 记录，公式偏移直接查表即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpandProcessor {

    private final CellFormatter formatter;
    private final FormulaEvaluator evaluator;
    private final MzProperties props;

    /**
     * 图表里的字用哪款字体 —— **复用 PDF 那份配置**（`muzhou.report.pdf.font-path`）。
     *
     * <p>「这台服务器上的中文字体在哪」是同一件事，没道理让人为图表再配一遍；配了 PDF 却发现
     * 图表还是方框，那才是最难查的。{@link ChartFonts} 是静态的（同 {@link BarcodeGenerator}
     * 那样的纯工具类，测试里直接调不必起 Spring），所以在这里把配置递进去一次。
     */
    @jakarta.annotation.PostConstruct
    void configureChartFont() {
        ChartFonts.configure(props.getPdf() == null ? null : props.getPdf().getFontPath());
    }

    /** 待求值的 Aviator 单元格（必须等整个网格生成后才能算区间函数）。 */
    private record PendingFormula(int r, int c, String expression, Map<String, Object> rowData,
                                  CellConfigDTO config) {
    }

    /**
     * 处理一张工作表。
     *
     * @param st       模板
     * @param datasets 数据集数据（key = datasetCode）
     * @param params   报表参数
     */
    public RenderGrid process(SheetTemplate st,
                              Map<String, List<Map<String, Object>>> datasets,
                              Map<String, Object> params) {
        RenderGrid grid = new RenderGrid();
        List<PendingFormula> pending = new ArrayList<>();
        List<int[]> nativeFormulaCells = new ArrayList<>();
        // 图表格同样「先占位、末尾统一处理」：出图要按格子的实际像素来，
        // 而那要等扩展做完、合并与行高列宽都偏移过了才定得下来（见 ChartProcessor）
        List<ChartProcessor.ChartCell> charts = new ArrayList<>();

        int outRow = 0;
        for (int tr = 0; tr <= st.getMaxRow(); tr++) {
            List<TemplateCell> cells = st.row(tr);
            if (cells.isEmpty()) {
                // 空行也要占位，否则下方内容会整体上移，破坏排版
                grid.mapRow(tr, outRow);
                outRow++;
                continue;
            }

            String bandDataset = findBandDataset(cells);
            if (bandDataset == null) {
                emitRow(grid, cells, outRow, null, -1, datasets, params, pending, nativeFormulaCells, charts);
                grid.mapRow(tr, outRow);
                outRow++;
            } else {
                List<Map<String, Object>> rows = datasets.getOrDefault(bandDataset, List.of());
                int n = Math.max(rows.size(), 1);
                int bandStart = outRow;
                // **展开之前先算一遍**：下面那个 for 一次就把整条行带 emit 完，检查放在循环后面的话
                // 一条 20000 行 × 50 列的行带会先把 100 万个 GridCell 建出来才轮到报错 ——
                // 守卫拦住了 CPU，没拦住那一次内存峰值
                checkCapacity(grid.size() + (long) n * cells.size());
                for (int i = 0; i < n; i++) {
                    Map<String, Object> rowData = i < rows.size() ? rows.get(i) : null;
                    emitRow(grid, cells, outRow, rowData, i, datasets, params, pending, nativeFormulaCells, charts);
                    grid.mapRow(tr, outRow);
                    outRow++;
                }
                applyGroupMerge(grid, cells, bandStart, n);
            }

            // 兜底再量一次实际值：上面那个预判按「行带行数 × 模板行内格数」估，
            // 横向扩展（expandType=right）会在 emitRow 里就地把一格摊成 n 格，估不进去
            checkCapacity(grid.size());
        }

        remapSheetConfig(st, grid);
        applyMergeMarkers(grid);
        shiftNativeFormulas(grid, nativeFormulaCells, st);
        evaluatePending(grid, pending, params, datasets);
        // 排在最后：出图要按格子的实际像素来，行高列宽与合并要等上面几步都搬完才作数
        ChartProcessor.process(grid, charts, datasets);
        return grid;
    }

    /**
     * 单元格总数的闸门。
     *
     * @param cells 已有的 + 即将生成的单元格数；用 long 收，行数 × 列数很容易溢出 int
     */
    private void checkCapacity(long cells) {
        if (cells > props.getMaxCells()) {
            throw new BizException("渲染结果过大（超过 " + props.getMaxCells()
                    + " 个单元格），请缩小数据范围或增加过滤条件");
        }
    }

    /** 该模板行属于哪个数据集的行带（取第一个纵向扩展单元格）。 */
    private String findBandDataset(List<TemplateCell> cells) {
        for (TemplateCell tc : cells) {
            CellConfigDTO cfg = tc.getConfig();
            if (cfg != null && cfg.isExpandDown() && notBlank(cfg.getDatasetCode())) {
                return cfg.getDatasetCode();
            }
        }
        return null;
    }

    /**
     * 生成一行输出。横向扩展会在这里就地展开，并把右侧单元格整体后移。
     */
    private void emitRow(RenderGrid grid, List<TemplateCell> cells, int outRow,
                         Map<String, Object> rowData, int rowIndex,
                         Map<String, List<Map<String, Object>>> datasets,
                         Map<String, Object> params,
                         List<PendingFormula> pending,
                         List<int[]> nativeFormulaCells,
                         List<ChartProcessor.ChartCell> charts) {
        int colOffset = 0;
        for (TemplateCell tc : cells) {
            CellConfigDTO cfg = tc.getConfig();
            int outC = tc.getC() + colOffset;

            if (cfg != null && cfg.isExpandRight() && notBlank(cfg.getDatasetCode())) {
                List<Map<String, Object>> rows = datasets.getOrDefault(cfg.getDatasetCode(), List.of());
                int n = Math.max(rows.size(), 1);
                for (int j = 0; j < n; j++) {
                    Map<String, Object> row = j < rows.size() ? rows.get(j) : null;
                    Object v = row == null ? null : row.get(cfg.getField());
                    GridCell cell = buildCell(outRow, outC + j, v, tc, cfg,
                            affixedText(tc, cfg, v, row, datasets, params));
                    applyImageFallback(cell, cfg, row, datasets);
                    grid.put(cell);
                }
                grid.getColMapping().putIfAbsent(tc.getC(), outC);
                colOffset += n - 1;
                continue;
            }

            Object value = resolveValue(tc, cfg, rowData, rowIndex, datasets, params);

            if (cfg != null && "formula".equals(cfg.getType()) && notBlank(cfg.getExpression())) {
                // 先占位，等网格建好再求值
                GridCell placeholder = buildCell(outRow, outC, null, tc, cfg);
                grid.put(placeholder);
                pending.add(new PendingFormula(outRow, outC, cfg.getExpression(), rowData, cfg));
                grid.getColMapping().putIfAbsent(tc.getC(), outC);
                continue;
            }

            GridCell cell = buildCell(outRow, outC, value, tc, cfg,
                    affixedText(tc, cfg, value, rowData, datasets, params));
            applyImageFallback(cell, cfg, rowData, datasets);
            if (tc.getFormula() != null) {
                cell.setFormula(tc.getFormula());
                nativeFormulaCells.add(new int[]{outRow, outC, tc.getR(), tc.getC()});
            }
            grid.put(cell);
            if (cfg != null && cfg.isChart()) {
                charts.add(new ChartProcessor.ChartCell(outRow, outC, cfg));
            }
            grid.getColMapping().putIfAbsent(tc.getC(), outC);
        }
    }

    /** 计算单元格的最终值。 */
    private Object resolveValue(TemplateCell tc, CellConfigDTO cfg, Map<String, Object> rowData,
                                int rowIndex, Map<String, List<Map<String, Object>>> datasets,
                                Map<String, Object> params) {
        if (cfg == null) {
            return tc.getRawValue();
        }

        // 聚合单元格：不随行扩展，只在行带首行输出一次整表聚合值
        if ("data".equals(cfg.getType()) && !cfg.isNoAggregate()) {
            if (rowIndex > 0) {
                return null;
            }
            return aggregate(cfg, datasets);
        }

        // 图片单元格（img / base64 / barcode / qrcode）取值方式与 data 完全一致，区别只在 buildCell 里当图片画
        if (cfg.isDataBound() && notBlank(cfg.getField())) {
            return boundValue(cfg, cfg.getField(), rowData, datasets);
        }

        // 文本 / 参数：替换 ${param}，并支持文本中内联 #{ds.field}
        String text = tc.getText();
        if (text == null || text.isBlank()) {
            return tc.getRawValue();
        }
        String replaced = substitute(text, params, rowData, datasets);
        // 整格就是一个 token 时，尽量保留原始类型（数字/日期），便于 Excel 导出与公式计算
        if (!replaced.equals(text)) {
            return replaced;
        }
        return tc.getRawValue() != null ? tc.getRawValue() : text;
    }

    /**
     * 按「单元格绑的那个数据集 + 指定字段」取当前行的值。
     *
     * <p>字段单独收而不是直接取 {@code cfg.getField()}：{@link #applyImageFallback} 要在同一行上
     * 取另一个字段（兜底字段），取法与主字段必须是同一套（含「不扩展的格子取第一行」这条）。
     */
    private Object boundValue(CellConfigDTO cfg, String field, Map<String, Object> rowData,
                              Map<String, List<Map<String, Object>>> datasets) {
        if (rowData != null) {
            return rowData.get(field);
        }
        if ("none".equals(cfg.getExpandType())) {
            // 不扩展的数据格取数据集第一行
            List<Map<String, Object>> rows = datasets.getOrDefault(cfg.getDatasetCode(), List.of());
            return rows.isEmpty() ? null : rows.get(0).get(field);
        }
        return null;
    }

    /**
     * 图片单元格没出成图时的兜底：改用 {@code fallbackField} 那个字段的值当**普通文字**写
     * （见 {@link CellConfigDTO#getFallbackField()}）。
     *
     * <p>放在 {@link #buildCell} 之后而不是里面：图片格在 buildCell 里被清成「空值 + 空显示文本」，
     * 这里把它当普通文字格重新填一遍就行，三条导出路与前端预览都认不出区别
     * （{@code v.mzImg} 没挂上，自然就是一个文字格）。
     *
     * <p>兜底文本**不按 cfg 格式化**：图片格的 formatType 在设计器里压根不给配（留着的是上一个
     * 类型的模板），照它格式化会把姓名之流套进数字格式里。
     */
    private void applyImageFallback(GridCell cell, CellConfigDTO cfg, Map<String, Object> rowData,
                                    Map<String, List<Map<String, Object>>> datasets) {
        if (cfg == null || !cfg.isImage() || !notBlank(cfg.getFallbackField())) {
            return;
        }
        if (cell.getImage() != null && !cell.getImage().isBlank()) {
            return;
        }
        Object v = boundValue(cfg, cfg.getFallbackField(), rowData, datasets);
        if (v == null || String.valueOf(v).isBlank()) {
            return;
        }
        CellFormatter.Formatted f = formatter.format(v, null);
        cell.setValue(v);
        cell.setDisplay(f.display());
        cell.setCt(f.ct());
    }

    /**
     * 数据格文本里除占位符之外还写了别的字时（`#{orders.amount}元`、`共 #{orders.cnt} 件`），
     * 把取到的值填回原文，前后缀原样留着。
     *
     * <p>数据格的值来自 {@link #resolveValue}，只有字段值那一个 ——「元」是写在格子文本里的，
     * 不这么填回去就会被值整个顶掉。param 类型走的是文本替换那条路，本来就保留前后缀，
     * 这里是把 data 类型对齐过来。
     *
     * <p>返回 null 表示「没有前后缀，按老规矩输出原始值」——**整格就是一个占位符时必须走这条**，
     * 值要保持数字/日期的原始类型，Excel 导出才写得成数值、公式才算得动。
     *
     * @return 填好的整格文本；不需要前后缀时返回 null
     */
    private String affixedText(TemplateCell tc, CellConfigDTO cfg, Object value,
                               Map<String, Object> rowData,
                               Map<String, List<Map<String, Object>>> datasets,
                               Map<String, Object> params) {
        if (cfg == null || !"data".equals(cfg.getType())) {
            return null;
        }
        String text = tc.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        // 找到「配置绑的那个字段」对应的占位符，只有它才该被值替换；
        // 文本里其它数据集的占位符交给 substitute 按普通文本处理
        Matcher m = TemplateParser.DATA_TOKEN.matcher(text);
        int start = -1;
        int end = -1;
        while (m.find()) {
            if (m.group(1).equals(cfg.getDatasetCode())
                    && (!notBlank(cfg.getField()) || m.group(2).equalsIgnoreCase(cfg.getField()))) {
                start = m.start();
                end = m.end();
                break;
            }
        }
        // 文本里没有占位符，或与配置对不上（面板上改过绑定）：没有可留的前后缀
        if (start < 0 || (start == 0 && end == text.length())) {
            return null;
        }
        // 没取到值（字段为空、聚合格的非首行）：整格留空，别单剩一个「元」
        if (value == null) {
            return null;
        }
        return substitute(text.substring(0, start), params, rowData, datasets)
                + formatter.format(value, cfg).display()
                + substitute(text.substring(end), params, rowData, datasets);
    }

    /** 替换文本中的 ${param} 与 #{ds.field}。 */
    private String substitute(String text, Map<String, Object> params, Map<String, Object> rowData,
                              Map<String, List<Map<String, Object>>> datasets) {
        String out = text;

        Matcher pm = TemplateParser.PARAM_TOKEN.matcher(out);
        if (pm.find()) {
            pm.reset();
            StringBuilder sb = new StringBuilder();
            while (pm.find()) {
                Object v = params == null ? null : params.get(pm.group(1));
                pm.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
            }
            pm.appendTail(sb);
            out = sb.toString();
        }

        Matcher dm = TemplateParser.DATA_TOKEN.matcher(out);
        if (dm.find()) {
            dm.reset();
            StringBuilder sb = new StringBuilder();
            while (dm.find()) {
                String code = dm.group(1);
                String field = dm.group(2);
                Object v = null;
                if (rowData != null) {
                    v = rowData.get(field);
                } else {
                    List<Map<String, Object>> rows = datasets.getOrDefault(code, List.of());
                    if (!rows.isEmpty()) {
                        v = rows.get(0).get(field);
                    }
                }
                dm.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
            }
            dm.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    /** 对整个数据集的某个字段做聚合。 */
    private Object aggregate(CellConfigDTO cfg, Map<String, List<Map<String, Object>>> datasets) {
        List<Map<String, Object>> rows = datasets.getOrDefault(cfg.getDatasetCode(), List.of());
        String field = cfg.getField();
        String agg = cfg.getAggregate();

        if ("count".equals(agg)) {
            return BigDecimal.valueOf(rows.size());
        }
        List<BigDecimal> nums = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            BigDecimal d = CellFormatter.toDecimal(row.get(field));
            if (d != null) {
                nums.add(d);
            }
        }
        if (nums.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return switch (agg) {
            case "sum" -> nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case "avg" -> nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(nums.size()), 6, RoundingMode.HALF_UP);
            case "max" -> nums.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            case "min" -> nums.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            default -> BigDecimal.ZERO;
        };
    }

    /** 组装输出单元格（含格式化）。 */
    private GridCell buildCell(int r, int c, Object value, TemplateCell tc, CellConfigDTO cfg) {
        return buildCell(r, c, value, tc, cfg, null);
    }

    /**
     * 组装输出单元格。
     *
     * @param affixed 带前后缀的整格文本（见 {@link #affixedText}）；非 null 时它就是最终的值与显示
     *                文本，**不再按 cfg 格式化**（值已经在 affixedText 里格式化过一遍，
     *                再套一次数字格式只会把「1,234.00元」判成非数字白跑），ct 也退回常规文本，
     *                否则 Excel 那头见到 ct.t=n 会去写数值，前后缀又丢了
     */
    private GridCell buildCell(int r, int c, Object value, TemplateCell tc, CellConfigDTO cfg,
                               String affixed) {
        GridCell cell = new GridCell(r, c);
        if (tc.getStyle() != null && !tc.getStyle().isEmpty()) {
            cell.setStyle(new LinkedHashMap<>(tc.getStyle()));
        }
        // 图片单元格：值是图片地址/base64/要编成条码的那串字，格子本身不出文字 —— 照常输出的话，
        // 导出的 Excel 里会写进一长串 base64，预览里也是图片下面压着一行乱码
        if (cfg != null && cfg.isImage()) {
            cell.setImage(CellImage.src(cfg, value));
            cell.setValue("");
            cell.setDisplay("");
            cell.setCt(formatter.format("", null).ct());
            return cell;
        }

        cell.setValue(affixed != null ? affixed : value);
        CellFormatter.Formatted f = affixed != null
                ? formatter.format(affixed, null)
                : formatter.format(value, cfg);
        cell.setDisplay(f.display());
        cell.setCt(f.ct());
        return cell;
    }

    /**
     * 行带内的分组合并：groupType=group 的列，纵向相邻且值相等的单元格合并成一格。
     *
     * <p>只对 {@code data} 类型生效。图片单元格（img / base64 / barcode / qrcode）的值已经被清空成文字空串
     * （图片走 {@code GridCell#image}），按值比对会把整列判成「全都一样」合并成一格。
     */
    private void applyGroupMerge(RenderGrid grid, List<TemplateCell> cells, int bandStart, int n) {
        if (n <= 1) {
            return;
        }
        for (TemplateCell tc : cells) {
            CellConfigDTO cfg = tc.getConfig();
            if (cfg == null || !cfg.isGroup() || !"data".equals(cfg.getType())) {
                continue;
            }
            int c = grid.outCol(tc.getC());
            int runStart = 0;
            for (int i = 1; i <= n; i++) {
                boolean end = (i == n);
                if (!end) {
                    GridCell prev = grid.get(bandStart + i - 1, c);
                    GridCell cur = grid.get(bandStart + i, c);
                    Object a = prev == null ? null : prev.getValue();
                    Object b = cur == null ? null : cur.getValue();
                    if (Objects.equals(str(a), str(b))) {
                        continue;
                    }
                }
                int runLen = i - runStart;
                if (runLen > 1) {
                    grid.addMerge(bandStart + runStart, c, runLen, 1);
                    // 合并区内除首格外置空，避免 Excel 导出出现重复文字
                    for (int k = runStart + 1; k < i; k++) {
                        GridCell dup = grid.get(bandStart + k, c);
                        if (dup != null) {
                            dup.setValue(null);
                            dup.setDisplay("");
                        }
                    }
                }
                runStart = i;
            }
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 把模板 sheet 的 merge / rowlen / columnlen / borderInfo 偏移到输出坐标。
     */
    @SuppressWarnings("unchecked")
    private void remapSheetConfig(SheetTemplate st, RenderGrid grid) {
        Map<String, Object> cfg = st.getConfig();
        if (cfg == null) {
            return;
        }

        Object mergeObj = cfg.get("merge");
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
                int rowSpan = rs == null ? 1 : rs;
                int outC = grid.outCol(c);
                int colSpan = cs == null ? 1 : cs;
                int outCEnd = grid.outCol(c + colSpan - 1);
                List<Integer> bandRows = grid.getRowMapping().get(r);

                if (rowSpan == 1 && bandRows != null && bandRows.size() > 1) {
                    // 合并整个落在一条行带内（模板里只占一行）：行带展开成 n 行，这个合并要跟着
                    // **复制 n 份**，每条数据一份。拉成一个跨 n 行的大合并只会显示第一条数据，
                    // 后面 n-1 条全被盖住。
                    for (int outR : bandRows) {
                        grid.addMerge(outR, outC, 1, outCEnd - outC + 1);
                    }
                } else {
                    int outR = grid.firstOutRow(r);
                    // 模板中跨 rowSpan 行的合并，在输出里要覆盖这些模板行展开后的全部行
                    int outEnd = grid.lastOutRow(r + rowSpan - 1);
                    grid.addMerge(outR, outC, outEnd - outR + 1, outCEnd - outC + 1);
                }
            }
        }

        Object rowlenObj = cfg.get("rowlen");
        if (rowlenObj instanceof Map<?, ?> rl) {
            rl.forEach((k, v) -> {
                Integer tr = num(k);
                if (tr == null) {
                    return;
                }
                List<Integer> outRows = grid.getRowMapping().get(tr);
                if (outRows != null) {
                    outRows.forEach(or -> grid.getRowlen().put(String.valueOf(or), v));
                }
            });
        }

        Object collenObj = cfg.get("columnlen");
        if (collenObj instanceof Map<?, ?> cl) {
            cl.forEach((k, v) -> {
                Integer tc = num(k);
                if (tc != null) {
                    grid.getColumnlen().put(String.valueOf(grid.outCol(tc)), v);
                }
            });
        }

        Object borderObj = cfg.get("borderInfo");
        if (borderObj instanceof List<?> bl) {
            for (Object b : bl) {
                if (b instanceof Map) {
                    grid.getBorderInfo().addAll(remapBorder((Map<String, Object>) b, grid));
                }
            }
        }
    }

    /**
     * 按最终的 merge 表重建单元格上的 mc 标记。
     *
     * <p>FortuneSheet 画一个合并区要 config.merge 与单元格自己的 mc **两份对得上**：
     * 首格的 mc 带 rs/cs，被盖住的格子 mc 只指回首格，缺一边就画不出来。而模板里的 mc 记的是
     * **模板坐标**，扩展后行号全变了，照抄过去会指向别人；分组合并（{@link #applyGroupMerge}）
     * 产生的合并模板里更是压根没有 mc。所以不沿用模板的那份（{@link TemplateParser} 已把 mc
     * 挡在样式外面），统一在这里按渲染结果重建。
     */
    private void applyMergeMarkers(RenderGrid grid) {
        for (Map<String, Object> m : grid.getMerge().values()) {
            Integer r = num(m.get("r"));
            Integer c = num(m.get("c"));
            Integer rs = num(m.get("rs"));
            Integer cs = num(m.get("cs"));
            if (r == null || c == null) {
                continue;
            }
            int rowSpan = rs == null ? 1 : rs;
            int colSpan = cs == null ? 1 : cs;
            for (int i = 0; i < rowSpan; i++) {
                for (int j = 0; j < colSpan; j++) {
                    GridCell cell = grid.get(r + i, c + j);
                    if (cell == null) {
                        // 被盖住的格子模板里可能根本没有（分组合并就是这样），补一个空格子，
                        // 否则前端在那一格上找不到 mc，合并区会缺一块
                        cell = new GridCell(r + i, c + j);
                        cell.setDisplay("");
                        grid.put(cell);
                    }
                    Map<String, Object> mc = new LinkedHashMap<>();
                    mc.put("r", r);
                    mc.put("c", c);
                    if (i == 0 && j == 0) {
                        mc.put("rs", rowSpan);
                        mc.put("cs", colSpan);
                    }
                    cell.getStyle().put("mc", mc);
                }
            }
        }
    }

    /** 边框区域的行列同步偏移（一条模板边框可能对应输出上的好几条，见 {@link #remapCellBorder}）。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> remapBorder(Map<String, Object> border, RenderGrid grid) {
        if ("cell".equals(border.get("rangeType"))) {
            return remapCellBorder(border, grid);
        }
        Map<String, Object> copy = new LinkedHashMap<>(border);
        Object rangeObj = copy.get("range");
        if (rangeObj instanceof List<?> ranges) {
            List<Object> newRanges = new ArrayList<>();
            for (Object rObj : ranges) {
                if (!(rObj instanceof Map)) {
                    newRanges.add(rObj);
                    continue;
                }
                Map<String, Object> range = new LinkedHashMap<>((Map<String, Object>) rObj);
                Object rowRange = range.get("row");
                if (rowRange instanceof List<?> rr && rr.size() >= 2) {
                    Integer s = num(rr.get(0));
                    Integer e = num(rr.get(1));
                    if (s != null && e != null) {
                        range.put("row", List.of(grid.firstOutRow(s), grid.lastOutRow(e)));
                    }
                }
                Object colRange = range.get("column");
                if (colRange instanceof List<?> cr && cr.size() >= 2) {
                    Integer s = num(cr.get(0));
                    Integer e = num(cr.get(1));
                    if (s != null && e != null) {
                        range.put("column", List.of(grid.outCol(s), grid.outCol(e)));
                    }
                }
                newRanges.add(range);
            }
            copy.put("range", newRanges);
        }
        return List.of(copy);
    }

    /**
     * {@code rangeType=cell} 那种逐格边框（复制粘贴时 FortuneSheet 写下的就是这种）的偏移。
     *
     * <p>它说的是**一个模板格**的四条边，而扩展之后那一行变成了 n 行 —— 所以不是挪一下坐标，
     * 而是**复制成 n 条**，跟行带上的合并是同一个道理（只挪不复制的话，一条行带里只有
     * 第一行画得出边框）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> remapCellBorder(Map<String, Object> border, RenderGrid grid) {
        Object valueObj = border.get("value");
        Map<String, Object> value = valueObj instanceof Map ? (Map<String, Object>) valueObj : null;
        Integer r = value == null ? null : num(value.get("row_index"));
        Integer c = value == null ? null : num(value.get("col_index"));
        if (r == null || c == null) {
            return List.of(border);
        }
        int outC = grid.outCol(c);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int outR = grid.firstOutRow(r); outR <= grid.lastOutRow(r); outR++) {
            Map<String, Object> copy = new LinkedHashMap<>(border);
            Map<String, Object> moved = new LinkedHashMap<>(value);
            moved.put("row_index", outR);
            moved.put("col_index", outC);
            copy.put("value", moved);
            out.add(copy);
        }
        return out;
    }

    /** 原生 Excel 公式的引用偏移。 */
    private void shiftNativeFormulas(RenderGrid grid, List<int[]> cells, SheetTemplate st) {
        for (int[] pos : cells) {
            GridCell cell = grid.get(pos[0], pos[1]);
            if (cell == null || cell.getFormula() == null) {
                continue;
            }
            String shifted = A1RefUtils.shiftFormulaSmart(cell.getFormula(),
                    grid::firstOutRow, grid::lastOutRow, grid::outCol);
            cell.setFormula(shifted);
            cell.setDisplay(shifted);
        }
    }

    /** 网格生成完毕后统一求值 Aviator 表达式。 */
    private void evaluatePending(RenderGrid grid, List<PendingFormula> pending,
                                 Map<String, Object> params,
                                 Map<String, List<Map<String, Object>>> datasets) {
        for (PendingFormula pf : pending) {
            Object v = evaluator.eval(pf.expression(), params, pf.rowData(), datasets, grid);
            GridCell cell = grid.get(pf.r(), pf.c());
            if (cell == null) {
                continue;
            }
            cell.setValue(v);
            CellFormatter.Formatted f = formatter.format(v, pf.config());
            cell.setDisplay(f.display());
            cell.setCt(f.ct());
        }
    }

    private Integer num(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
