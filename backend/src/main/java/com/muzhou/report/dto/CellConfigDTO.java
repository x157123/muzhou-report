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

    /** text | data | formula | param | img | base64 */
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
     * 是否「值取自数据集的某个字段」。
     *
     * <p>图片单元格（{@code img} / {@code base64}）的取数方式与 {@code data} 完全一样
     * ——同样按 datasetCode + field 取值、同样参与扩展，区别只在于取到的值是当图片画还是当文字写。
     * 所以扩展、取值这些地方一律判这个，而不是判 {@code type == "data"}。
     */
    public boolean isDataBound() {
        return "data".equals(type) || isImage();
    }

    /**
     * 是否为图片单元格：{@code img} 的值是图片地址，{@code base64} 的值是 base64 串。
     *
     * <p>两者都按单元格（合并区则是整块）的宽高铺满，且**不再输出文字**
     * ——否则导出的 Excel 里会写进一长串 base64。
     */
    public boolean isImage() {
        return "img".equals(type) || "base64".equals(type);
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
