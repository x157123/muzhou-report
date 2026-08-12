package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

/**
 * 单元格配置，见 docs/CONTRACT.md §4。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CellConfigDTO implements Serializable {

    private Integer sheetIndex = 0;
    private Integer r;
    private Integer c;

    /** text | data | formula | param | img | base64 | barcode | qrcode */
    private String type = "text";

    /** 数据集编码 */
    private String datasetCode = "";

    /** 字段名 */
    private String field = "";

    /** none | down | right */
    private String expandType = "none";

    /** list | group */
    private String groupType = "list";

    /** none | sum | avg | max | min | count */
    private String aggregate = "none";

    /** text | number | currency | percent | date */
    private String formatType = "text";

    private String formatPattern = "";

    /** type=formula 时的 Aviator 表达式 */
    private String expression = "";

    /**
     * 条码码制（ZXing 的 {@code BarcodeFormat} 名）。
     *
     * <p>空串 = 按类型给默认：{@code barcode} 用 {@code CODE_128}，{@code qrcode} 用 {@code QR_CODE}。
     */
    private String barcodeFormat = "";

    /** 二维码纠错级别 L/M/Q/H，空串按 M。对一维码无意义。 */
    private String qrLevel = "";

    /**
     * 条形码下方印不印那串原文，默认印。对二维码无意义（本来就是给机器扫的）。
     *
     * <p>默认 true 而不是 false：老报表的 content 里没有这一项，Jackson 不写它就保持默认，
     * 而「条码下面有一串数字」才是大家认得的样子。
     */
    private boolean barcodeText = true;

    /**
     * 图片单元格没出成图时改用哪个字段的值当**文字**写（同一数据集、同一行）。空 = 不兜底，出空白。
     *
     * <p>签字栏是典型场景：`base64` 绑的那个字段没值（这单还没签）时，格子整块空白，纸上看不出
     * 「是没签，还是这张表压根没这一项」。配上签字人姓名字段，至少还印得出「张三」。
     *
     * <p>「没出成图」指**渲染时**就知道的那几种：字段值为空、base64 是空串、条码编不出来
     * （位数/字符不合码制）。`img` 的地址取不回来是**导出时**才知道的
     * （`export/ImageLoader`），那条路上兜不了底 —— 见 docs/CONTRACT.md §4。
     */
    private String fallbackField = "";

    /**
     * 是否「值取自数据集的某个字段」。
     *
     * <p>图片单元格（{@code img} / {@code base64} / {@code barcode} / {@code qrcode}）的取数方式与
     * {@code data} 完全一样 ——同样按 datasetCode + field 取值、同样参与扩展，区别只在于取到的值是
     * 当图片画还是当文字写。所以扩展、取值这些地方一律判这个，而不是判 {@code type == "data"}。
     */
    public boolean isDataBound() {
        return "data".equals(type) || isImage();
    }

    /**
     * 是否为图片单元格：值不当文字写，而是变成一张图挂到 {@code GridCell#image} 上。
     *
     * <p>四种类型的差别只在「那个值怎么变成图」：{@code img} 的值是图片地址、{@code base64} 的值是
     * base64 串、{@code barcode} / {@code qrcode} 的值是要编成码的那串字（见
     * {@code engine/BarcodeGenerator}）。变完之后都是一个 {@code src}，下游认不出区别。
     *
     * <p>四者都**等比例装进**单元格（合并区则是整块）并居中，且**不再输出文字**
     * ——否则导出的 Excel 里会写进一长串 base64。
     */
    public boolean isImage() {
        return "img".equals(type) || "base64".equals(type) || isBarcode();
    }

    /** 是否为条码单元格（一维条形码 / 二维码）：值是要编成码的那串字。 */
    public boolean isBarcode() {
        return "barcode".equals(type) || "qrcode".equals(type);
    }

    /** 是否为「随数据集纵向扩展」的数据单元格 */
    public boolean isExpandDown() {
        return isDataBound() && "down".equals(expandType) && isNoAggregate();
    }

    /** 是否为「横向扩展」的数据单元格 */
    public boolean isExpandRight() {
        return isDataBound() && "right".equals(expandType) && isNoAggregate();
    }

    public boolean isNoAggregate() {
        return aggregate == null || aggregate.isBlank() || "none".equals(aggregate);
    }

    public boolean isGroup() {
        return "group".equals(groupType);
    }
}
