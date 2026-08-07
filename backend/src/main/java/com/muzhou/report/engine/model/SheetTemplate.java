package com.muzhou.report.engine.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一张工作表的模板。 */
@Data
public class SheetTemplate {

    private int sheetIndex;
    private String name = "Sheet1";
    private String id = "sheet_1";
    private int order;

    /** 模板单元格，按 r 再按 c 分组 */
    private Map<Integer, List<TemplateCell>> rows = new LinkedHashMap<>();

    private int maxRow = -1;
    private int maxCol = -1;

    /** 原 sheet 的 config（merge/rowlen/columnlen/borderInfo） */
    private Map<String, Object> config = new LinkedHashMap<>();

    /** 原 sheet 上的其它属性（row/column 等），输出时保留 */
    private Map<String, Object> raw = new LinkedHashMap<>();

    public void addCell(TemplateCell cell) {
        rows.computeIfAbsent(cell.getR(), k -> new ArrayList<>()).add(cell);
        maxRow = Math.max(maxRow, cell.getR());
        maxCol = Math.max(maxCol, cell.getC());
    }

    /** 取某一模板行的单元格（按列升序），不存在时返回空列表。 */
    public List<TemplateCell> row(int r) {
        List<TemplateCell> list = rows.get(r);
        if (list == null) {
            return List.of();
        }
        list.sort((a, b) -> Integer.compare(a.getC(), b.getC()));
        return list;
    }
}
