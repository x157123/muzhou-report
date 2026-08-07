package com.muzhou.report.engine.model;

import com.muzhou.report.dto.CellConfigDTO;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 模板单元格（解析自 FortuneSheet celldata + cellConfigs）。 */
@Data
public class TemplateCell {

    private int r;
    private int c;

    /** 原始值 */
    private Object rawValue;

    /** 模板文本（可能含 #{}, ${}, !{} 或以 = 开头的公式） */
    private String text;

    /** 样式属性（去掉 v/m/ct/f 之后的其余键） */
    private Map<String, Object> style = new LinkedHashMap<>();

    /** 原生 Excel 公式（含前导 =） */
    private String formula;

    /** 单元格配置 */
    private CellConfigDTO config;
}
