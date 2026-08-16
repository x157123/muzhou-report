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
