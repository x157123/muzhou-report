package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

/** 报表页面设置，见 docs/CONTRACT.md §4。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PageConfigDTO implements Serializable {

    /** A3 | A4 | A5 | B4 | B5 | Letter | Legal */
    private String paperSize = "A4";

    /** portrait | landscape */
    private String orientation = "portrait";

    /** 页边距，单位 mm */
    private Integer marginTop = 10;
    private Integer marginBottom = 10;
    private Integer marginLeft = 10;
    private Integer marginRight = 10;

    /** 打印缩放百分比，10~400 */
    private Integer scale = 100;

    /** 所有列压缩到一页宽（与 scale 互斥，此项为真时忽略 scale） */
    private Boolean fitToWidth = false;

    /**
     * 限宽：设计器里「会被打印的那些列」宽度之和不得超过一页可打印宽度。
     *
     * <p>主要约束设计阶段的拖动。导出 xlsx 时也会用到：px 换算成 Excel 列宽是有量化误差的
     * （Excel 以「默认字体最大数字宽」的 1/256 为单位），内容宽度贴着页宽时差一两个像素
     * 就会把最后一列挤到第二页。所以 limitWidth 为真且用户没显式调过缩放（scale=100）时，
     * 导出按「一页宽」出纸兜底 —— 见 ExcelExporter#applyPageSetup。
     */
    private Boolean limitWidth = true;

    /** 打印区域，形如 "A1:F30"；空表示整表 */
    private String printArea = "";

    /**
     * 顶端标题行（跨页重复的表头），形如 {@code "1:3"}（1 起算、闭区间）；空表示不重复。
     *
     * <p>存进 xlsx 的 {@code _xlnm.Print_Titles}（{@code ExcelExporter#applyPageSetup} 一处写），
     * PDF / Word 两条路从 xlsx 读回来 —— 与页头页尾一样是「一处翻译、下游读回来」。
     *
     * <p><b>只支持「内容范围最上面的连续若干行」</b>：标题行落在内容中间时三条路的语义都对不齐
     * （Excel 会把它插到每页顶上，而 PDF 是照着行流分页的），一律忽略并记 warn。
     * 设了打印区域时按区域的第一行起算。
     */
    private String titleRows = "";

    /** 页头（左/中/右三段，画在上页边距里）；三段都空 = 没页头 */
    private HeaderFooterDTO header = new HeaderFooterDTO();

    /** 页尾（左/中/右三段，画在下页边距里）；三段都空 = 没页尾 */
    private HeaderFooterDTO footer = new HeaderFooterDTO();

    /** 水印（只作用于 PDF / Word 导出与浏览器打印，xlsx 里没有水印这个概念） */
    private WatermarkDTO watermark = new WatermarkDTO();

    /**
     * 页头/页尾的行距系数。与 {@code ExcelExporter#LINE_HEIGHT}、前端 {@code utils/print.js}
     * 用的是同一个数：三处算出同一个「页头占多高」，分页位置才对得上。
     */
    public static final double LINE_HEIGHT = 1.35;

    /** 页头占掉的高度（mm）：纸张上边到页头文字底部。没设页头返回 0。 */
    public double headerReserveMm() {
        return reserveMm(header);
    }

    /** 页尾占掉的高度（mm）：纸张下边到页尾文字顶部。没设页尾返回 0。 */
    public double footerReserveMm() {
        return reserveMm(footer);
    }

    /**
     * 正文实际的上页边距（mm）。
     *
     * <p>页头画在页边距里，上页边距不够高时正文会被压到 —— 这时把正文推到页头下面，
     * 与 Excel 的表现一致（Excel 也是页边距装不下页眉就顺次下推正文）。
     */
    public double effectiveMarginTopMm() {
        return Math.max(marginTop == null ? 10 : marginTop, headerReserveMm());
    }

    /** 正文实际的下页边距（mm），同 {@link #effectiveMarginTopMm}。 */
    public double effectiveMarginBottomMm() {
        return Math.max(marginBottom == null ? 10 : marginBottom, footerReserveMm());
    }

    private double reserveMm(HeaderFooterDTO hf) {
        if (hf == null || hf.isBlank()) {
            return 0;
        }
        int size = hf.getFontSize() == null || hf.getFontSize() <= 0
                ? HeaderFooterDTO.DEFAULT_FONT_SIZE : hf.getFontSize();
        int margin = hf.getMargin() == null || hf.getMargin() < 0
                ? HeaderFooterDTO.DEFAULT_MARGIN : hf.getMargin();
        // 磅 -> mm
        return margin + size * LINE_HEIGHT / 72 * 25.4;
    }
}
