package com.muzhou.report.engine.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渲染输出网格。
 *
 * <p>引擎采用「顺序生成」而非「原地插入」：按模板行依次向下 emit 输出行，
 * 因此不需要昂贵且容易出错的行移动操作，行号偏移通过 {@link #rowMapping} 表达。
 */
@Data
public class RenderGrid {

    /** key = "r_c" */
    private final Map<String, GridCell> cells = new LinkedHashMap<>();

    private int maxRow = -1;
    private int maxCol = -1;

    /** 合并信息，key = "r_c"，value = {r,c,rs,cs} */
    private final Map<String, Map<String, Object>> merge = new LinkedHashMap<>();

    /** 行高，key = 输出行号 */
    private final Map<String, Object> rowlen = new LinkedHashMap<>();

    /** 列宽，key = 输出列号 */
    private final Map<String, Object> columnlen = new LinkedHashMap<>();

    private final List<Object> borderInfo = new ArrayList<>();

    /** 模板行号 -> 输出行号列表 */
    private final Map<Integer, List<Integer>> rowMapping = new LinkedHashMap<>();

    /** 模板列号 -> 输出列号（横向扩展后首列） */
    private final Map<Integer, Integer> colMapping = new LinkedHashMap<>();

    public static String key(int r, int c) {
        return r + "_" + c;
    }

    public void put(GridCell cell) {
        cells.put(key(cell.getR(), cell.getC()), cell);
        maxRow = Math.max(maxRow, cell.getR());
        maxCol = Math.max(maxCol, cell.getC());
    }

    public GridCell get(int r, int c) {
        return cells.get(key(r, c));
    }

    public int size() {
        return cells.size();
    }

    public void addMerge(int r, int c, int rs, int cs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("r", r);
        m.put("c", c);
        m.put("rs", rs);
        m.put("cs", cs);
        merge.put(key(r, c), m);
    }

    public void mapRow(int templateRow, int outRow) {
        rowMapping.computeIfAbsent(templateRow, k -> new ArrayList<>()).add(outRow);
    }

    /** 模板行 -> 该行第一个输出行；无映射时原样返回。 */
    public int firstOutRow(int templateRow) {
        List<Integer> list = rowMapping.get(templateRow);
        return (list == null || list.isEmpty()) ? templateRow : list.get(0);
    }

    /** 模板行 -> 该行最后一个输出行；无映射时原样返回。 */
    public int lastOutRow(int templateRow) {
        List<Integer> list = rowMapping.get(templateRow);
        return (list == null || list.isEmpty()) ? templateRow : list.get(list.size() - 1);
    }

    /** 模板列 -> 输出列；无映射时原样返回。 */
    public int outCol(int templateCol) {
        return colMapping.getOrDefault(templateCol, templateCol);
    }
}
