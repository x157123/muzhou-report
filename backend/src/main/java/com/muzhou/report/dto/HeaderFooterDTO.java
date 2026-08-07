package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

/**
 * 页头 / 页尾，见 docs/CONTRACT.md §4。
 *
 * <p>左中右三段，三段都为空视为「没设置」。文字里可以写占位符（{@code ${page}} 等，
 * 见 {@link com.muzhou.report.export.HeaderFooterText}），导出时展开成实际的页码/日期。
 *
 * <p><b>画在页边距里</b>（和 Excel 一样）：{@link #margin} 是页头到纸张上边（页尾到纸张下边）
 * 的距离，正文仍然从 {@code marginTop} 开始。所以上/下页边距要留得比「页头边距 + 字高」大，
 * 否则页头会压到正文上 —— 各处都按 {@code PageConfigDTO#effectiveMarginTop} 兜底把正文推下去。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HeaderFooterDTO implements Serializable {

    /** 左段文字 */
    private String left = "";

    /** 居中文字 */
    private String center = "";

    /** 右段文字 */
    private String right = "";

    /**
     * 字号的缺省值（磅）。
     *
     * <p>导出那三条路要在**读回 xlsx 之后**再判一次「页头占多高」，而 xlsx 的页眉串里
     * 不一定写了字号（没设过就没有 {@code &09} 那一段）—— 取不到时必须退回同一个数，
     * 否则同一份报表在 PDF 里和在设计器画布上的分页位置就对不上了。
     * 引用处：{@code PdfExporter#DEFAULT_HEADER_FONT_SIZE}、{@link PageConfigDTO#reserveMm}。
     */
    public static final int DEFAULT_FONT_SIZE = 9;

    /** 页边距的缺省值（mm），理由同 {@link #DEFAULT_FONT_SIZE}。 */
    public static final int DEFAULT_MARGIN = 5;

    /** 字号（磅） */
    private Integer fontSize = DEFAULT_FONT_SIZE;

    /** 页头距纸张上边 / 页尾距纸张下边，单位 mm */
    private Integer margin = DEFAULT_MARGIN;

    /** 三段都没有文字 = 没设置页头/页尾。 */
    public boolean isBlank() {
        return blank(left) && blank(center) && blank(right);
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
