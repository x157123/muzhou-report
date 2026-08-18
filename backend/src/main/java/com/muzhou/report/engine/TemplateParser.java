package com.muzhou.report.engine;

import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.dto.ReportContentDTO;
import com.muzhou.report.engine.model.SheetTemplate;
import com.muzhou.report.engine.model.TemplateCell;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@link ReportContentDTO} 解析成 {@link SheetTemplate} 列表。
 *
 * <p>没有显式 cellConfig 的单元格会按文本自动推断类型，这样用户直接在格子里手写
 * {@code #{orders.amount}} 也能工作，不必非得走属性面板。
 */
@Component
public class TemplateParser {

    /** #{datasetCode.field} */
    public static final Pattern DATA_TOKEN =
            Pattern.compile("#\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_$]*)\\s*}");

    /** !{aviator 表达式} */
    public static final Pattern FORMULA_TOKEN = Pattern.compile("!\\{(.+?)}", Pattern.DOTALL);

    /** ${paramName} */
    public static final Pattern PARAM_TOKEN = Pattern.compile("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}");

    /**
     * v 对象里不属于「样式」的键，样式提取时要排除。
     *
     * <p>v/m/ct/f 是值；mc 是合并标记，记的是**模板坐标**，扩展后行号全变了，
     * 照抄到输出格子上就会指向别人，由 {@link ExpandProcessor} 按渲染结果重建。
     */
    private static final Set<String> VALUE_KEYS = Set.of("v", "m", "ct", "f", "mc");

    /**
     * 解析出模板列表，**顺序就是出纸顺序** —— 按各 sheet 的 {@code order} 排，不是数组顺序。
     *
     * <p>设计器里拖动标签重排工作表时，FortuneSheet 只改各 sheet 的 {@code order}、
     * <b>不动 sheets 数组的顺序</b>（见 @fortune-sheet/react 的 SheetTab.onDrop：它把
     * 排好序的那一份重编 order 写回去，数组本身原地不动），标签栏也是按 {@code order} 显示的。
     * 照数组顺序出纸的话，设计器/预览上看到的是拖过的顺序、导出的 Excel/PDF/Word 仍是老顺序
     * —— 「拖完保存了，导出还是原来的顺序」就是这么来的。
     *
     * <p><b>{@code sheetIndex} 仍是数组下标，不跟着排</b>：{@code cellConfigs} 与
     * {@code pageConfigs} 都按数组下标寻址（CONTRACT §4），跟着排就等于把所有绑定和打印设置
     * 挪到别人身上。于是「谁先出纸」与「配置怎么寻址」各归各的，两边都不必迁移老数据。
     *
     * <p>排序是稳定的：{@code order} 缺失时退回数组下标（见 {@link #parseSheet}），
     * 相同 {@code order} 的按原数组顺序 —— 老报表（order 恒等于下标）行为一字不变。
     */
    public List<SheetTemplate> parse(ReportContentDTO content) {
        List<SheetTemplate> templates = new ArrayList<>();
        List<Map<String, Object>> sheets = content.getSheets();
        if (sheets == null || sheets.isEmpty()) {
            return templates;
        }
        for (int i = 0; i < sheets.size(); i++) {
            templates.add(parseSheet(sheets.get(i), i, content.getCellConfigs()));
        }
        templates.sort(Comparator.comparingInt(SheetTemplate::getOrder));
        return templates;
    }

    @SuppressWarnings("unchecked")
    private SheetTemplate parseSheet(Map<String, Object> sheet, int index, Map<String, CellConfigDTO> configs) {
        SheetTemplate st = new SheetTemplate();
        st.setSheetIndex(index);
        st.setName(str(sheet.get("name"), "Sheet" + (index + 1)));
        st.setId(str(sheet.get("id"), "sheet_" + (index + 1)));
        st.setOrder(intOf(sheet.get("order"), index));

        Object cfg = sheet.get("config");
        if (cfg instanceof Map) {
            st.setConfig(new LinkedHashMap<>((Map<String, Object>) cfg));
        }
        // 保留除 celldata/config/data 之外的原始属性（row/column/zoomRatio 等）
        Map<String, Object> raw = new LinkedHashMap<>(sheet);
        raw.remove("celldata");
        raw.remove("config");
        // data 是 FortuneSheet 运行时的二维展开结构，历史模板里可能被误存进来；
        // 若原样透传，前端会优先按 data 渲染，导致看到的是模板而不是渲染结果
        raw.remove("data");
        st.setRaw(raw);

        List<?> list = cellList(sheet);
        if (list.isEmpty()) {
            return st;
        }
        for (Object o : list) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> cell = (Map<String, Object>) o;
            Integer r = intBox(cell.get("r"));
            Integer c = intBox(cell.get("c"));
            if (r == null || c == null) {
                continue;
            }
            TemplateCell tc = new TemplateCell();
            tc.setR(r);
            tc.setC(c);

            Object vObj = cell.get("v");
            if (vObj instanceof Map) {
                Map<String, Object> v = (Map<String, Object>) vObj;
                tc.setRawValue(v.get("v"));
                Object m = v.get("m");
                tc.setText(m != null ? String.valueOf(m)
                        : (v.get("v") == null ? "" : String.valueOf(v.get("v"))));
                Object f = v.get("f");
                if (f != null && String.valueOf(f).startsWith("=")) {
                    tc.setFormula(String.valueOf(f));
                }
                Map<String, Object> style = new LinkedHashMap<>();
                v.forEach((k, val) -> {
                    if (!VALUE_KEYS.contains(k)) {
                        style.put(k, val);
                    }
                });
                tc.setStyle(style);
                applyInlineString(tc, v, style);
            } else if (vObj != null) {
                tc.setRawValue(vObj);
                tc.setText(String.valueOf(vObj));
            } else {
                tc.setText("");
            }

            // 文本里直接写的 =SUM(...) 也当公式处理
            if (tc.getFormula() == null && tc.getText() != null && tc.getText().startsWith("=")) {
                tc.setFormula(tc.getText());
            }

            tc.setConfig(resolveConfig(configs, index, r, c, tc.getText()));
            st.addCell(tc);
        }
        return st;
    }

    /**
     * 富文本格（{@code ct.t = inlineStr}）的文字回填。
     *
     * <p>格子里按了 Alt+Enter、或者粘进一段多行文字之后，FortuneSheet **不再往 v / m 里写值**，
     * 而是把这一格改存成 {@code ct.t=inlineStr} + {@code ct.s}（分段文字，段与段之间的
     * {@code \r\n} 就是换行本身），并显式 {@code delete curv.v / curv.m}
     * （见 @fortune-sheet/core 的 updateCell）。上面按 {@code m -> v} 取文字的那两行在这种格子上
     * 只会取到空串 —— 报障「表格文本里有换行符就渲染不出来」说的就是它：设计器里看得见，
     * 一预览 / 导出（Excel / PDF / Word 三条路都从渲染结果出发）那一格就是空的。
     *
     * <p>拼法与 FortuneSheet 自己的取值口径一致（{@code getCellValue} 对 inlineStr 是
     * {@code ct.s.reduce((p, c) => p + (c.v ?? ""), "")}）：把各段的 {@code v} 顺次接起来。
     * 换行统一成 {@code \n}：Excel 单元格里的换行符本来就是 {@code \n}，写 {@code \r\n} 进去
     * Excel 会多显示一个小方块；PDF / Word 两条路的折行（{@code text.split("\r\n|\r|\n")}）
     * 两种都认，不受影响。
     *
     * <p>带换行的格子顺便补上 {@code tb=2}（自动换行）：FortuneSheet 画富文本格时是不看 {@code tb} 的
     * ——{@code ct.s} 里有换行就分行，所以设计器里从来不用手动开这个开关；而 Excel 不开
     * 「自动换行」就不认单元格里的 {@code \n}（挤成一行，行高也不会撑开）。不补的话就是
     * 「设计器和 PDF / Word 里分了行、导出的 Excel 里没分」。已经设过 {@code tb} 的不动。
     *
     * @param v     celldata 里那一格的 v 对象
     * @param style 已经提取好的样式（{@link #VALUE_KEYS} 之外的那些键）
     */
    @SuppressWarnings("unchecked")
    private void applyInlineString(TemplateCell tc, Map<String, Object> v, Map<String, Object> style) {
        if (!(v.get("ct") instanceof Map<?, ?> ctMap) || !"inlineStr".equals(ctMap.get("t"))
                || !(ctMap.get("s") instanceof List<?> segments) || segments.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Object seg : segments) {
            if (seg instanceof Map<?, ?> sm && sm.get("v") != null) {
                sb.append(sm.get("v"));
            }
        }
        String text = sb.toString().replace("\r\n", "\n").replace('\r', '\n');
        if (text.isEmpty()) {
            return;
        }
        tc.setRawValue(text);
        tc.setText(text);
        tc.setInlineCt((Map<String, Object>) ctMap);
        if (text.indexOf('\n') >= 0) {
            style.putIfAbsent("tb", "2");
        }
    }

    /**
     * 取 sheet 的单元格列表：优先 celldata；若模板是 FortuneSheet 运行时结构
     * （二维 data，无 celldata），则展开成等价的 celldata 条目。
     */
    private List<Map<String, Object>> cellList(Map<String, Object> sheet) {
        List<Map<String, Object>> cells = new ArrayList<>();
        if (sheet.get("celldata") instanceof List<?> cd && !cd.isEmpty()) {
            for (Object o : cd) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cell = (Map<String, Object>) m;
                    cells.add(cell);
                }
            }
            return cells;
        }
        if (sheet.get("data") instanceof List<?> rows) {
            for (int r = 0; r < rows.size(); r++) {
                if (!(rows.get(r) instanceof List<?> cols)) {
                    continue;
                }
                for (int c = 0; c < cols.size(); c++) {
                    Object v = cols.get(c);
                    if (v == null) {
                        continue;
                    }
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("r", r);
                    cell.put("c", c);
                    cell.put("v", v);
                    cells.add(cell);
                }
            }
        }
        return cells;
    }

    /**
     * 取显式配置；没有则按文本推断。
     */
    private CellConfigDTO resolveConfig(Map<String, CellConfigDTO> configs, int sheetIndex,
                                        int r, int c, String text) {
        if (configs != null) {
            CellConfigDTO cfg = configs.get(sheetIndex + "_" + r + "_" + c);
            if (cfg != null) {
                cfg.setSheetIndex(sheetIndex);
                cfg.setR(r);
                cfg.setC(c);
                normalize(cfg);
                return cfg;
            }
        }
        CellConfigDTO cfg = new CellConfigDTO();
        cfg.setSheetIndex(sheetIndex);
        cfg.setR(r);
        cfg.setC(c);
        if (text != null && !text.isBlank()) {
            Matcher dm = DATA_TOKEN.matcher(text);
            Matcher fm = FORMULA_TOKEN.matcher(text);
            if (dm.find()) {
                cfg.setType("data");
                cfg.setDatasetCode(dm.group(1));
                cfg.setField(dm.group(2));
                // 手写的数据格默认纵向扩展，符合直觉
                cfg.setExpandType("down");
            } else if (fm.find()) {
                cfg.setType("formula");
                cfg.setExpression(fm.group(1));
            } else if (PARAM_TOKEN.matcher(text).find()) {
                cfg.setType("param");
            }
        }
        normalize(cfg);
        return cfg;
    }

    /** 补齐空字段，避免下游到处判空。 */
    private void normalize(CellConfigDTO cfg) {
        if (isBlank(cfg.getType())) {
            cfg.setType("text");
        }
        if (isBlank(cfg.getExpandType())) {
            cfg.setExpandType("none");
        }
        if (isBlank(cfg.getGroupType())) {
            cfg.setGroupType("list");
        }
        if (isBlank(cfg.getAggregate())) {
            cfg.setAggregate("none");
        }
        if (isBlank(cfg.getFormatType())) {
            cfg.setFormatType("text");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private int intOf(Object o, int def) {
        Integer v = intBox(o);
        return v == null ? def : v;
    }

    private Integer intBox(Object o) {
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
}
