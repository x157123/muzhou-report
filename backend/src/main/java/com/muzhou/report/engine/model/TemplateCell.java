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

    /**
     * 模板里那份 {@code ct}，仅当它是**富文本格**（{@code ct.t = inlineStr}）时才有值。
     *
     * <p>格子里按了 Alt+Enter（或粘进多行文字）之后，FortuneSheet 就把这一格改存成
     * {@code ct.t=inlineStr} + {@code ct.s}（分段文字），同时**删掉 v / m**
     * ——「换行」本身也是靠 {@code ct.s} 里的 {@code \r\n} 表达的。文字已由
     * {@link com.muzhou.report.engine.TemplateParser} 拼回 {@link #text}，这里留一份原样的
     * {@code ct}：格子没被数据绑定顶掉时原样输出，预览里的分行位置才和设计器里一模一样
     * （见 {@code ExpandProcessor#buildCell}）。
     */
    private Map<String, Object> inlineCt;
}
