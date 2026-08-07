package com.muzhou.report.engine.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 渲染输出的单元格。 */
@Data
public class GridCell {

    private int r;
    private int c;

    /** 原始值（数字保持数字类型，供 Excel 导出与前端计算） */
    private Object value;

    /** 显示文本（已格式化） */
    private String display;

    /** FortuneSheet 样式属性（bg/fc/bl/ff/fs/ht/vt 等），不含 v/m/ct */
    private Map<String, Object> style = new LinkedHashMap<>();

    /** FortuneSheet 的单元格类型描述 ct */
    private Map<String, Object> ct;

    /** 原生 Excel 公式（含前导 =），已完成引用偏移 */
    private String formula;

    /**
     * 图片地址（{@code type=img} 是 URL，{@code type=base64} 是 data URI），非图片单元格为 null。
     *
     * <p>它是图片在整条链路上**唯一的载体**：输出到 celldata 的 {@code v.mzImg.src}，
     * 前端据此摆出 FortuneSheet 的浮动图片，{@code ExcelExporter} 据此把图片锚进 xlsx，
     * 而 PDF / Word 四条导出路都是从那份 xlsx 读回来的。
     */
    private String image;

    public GridCell(int r, int c) {
        this.r = r;
        this.c = c;
    }

    /** 转成 FortuneSheet celldata 的 v 对象。 */
    public Map<String, Object> toCellValue() {
        Map<String, Object> v = new LinkedHashMap<>(style);
        v.put("v", value);
        v.put("m", display == null ? (value == null ? "" : String.valueOf(value)) : display);
        if (ct != null) {
            v.put("ct", ct);
        }
        if (formula != null && !formula.isBlank()) {
            v.put("f", formula);
        }
        if (image != null && !image.isBlank()) {
            v.put("mzImg", Map.of("src", image));
        }
        return v;
    }
}
