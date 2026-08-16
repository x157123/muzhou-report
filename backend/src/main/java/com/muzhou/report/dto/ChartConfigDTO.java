package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 图表单元格（{@code cellConfig.type=chart}）的配置，见 docs/CONTRACT.md §4。
 *
 * <p>它与别的单元格配置最大的不同：**取的不是一行的一个字段，而是整个数据集的多行多列**。
 * 所以图表格不参与行带扩展（{@code expandType} 恒 none），也不进
 * {@link CellConfigDTO#isDataBound()} —— 那条路是「按字段取当前行的值」。
 *
 * <p><b>聚合挂在每条系列上而不是格子上</b>：数据集给的往往是明细行（一行一笔订单），
 * 而图表要的是「按月份汇总的销售额」；同一张图里「金额求和 + 单量计数」也是常见配法，
 * 所以聚合方式必须逐条系列给。格子上那个 {@code aggregate} 是「整列算成一个值」的语义，
 * 与这里的「按类目分组算」是两回事，别混用。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChartConfigDTO implements Serializable {

    /** bar 柱状 | hbar 横向柱状 | line 折线 | area 面积 | pie 饼图 */
    private String chartType = "bar";

    /** 类目轴字段（饼图是扇区名） */
    private String categoryField = "";

    /** 数值系列，至少一条才画得出来 */
    private List<Series> series = new ArrayList<>();

    /** 图表标题，空 = 不画标题带 */
    private String title = "";

    private String categoryAxisTitle = "";

    private String valueAxisTitle = "";

    private boolean showLegend = true;

    /** top | bottom | left | right；只有一条系列时图例本来就没什么可说的，仍按配置画 */
    private String legendPosition = "bottom";

    /** 柱顶/点上/扇区上印不印数值 */
    private boolean showValueLabels;

    /** 配色主题，见 {@code engine/ChartRenderer#THEMES}；认不出的名字退回 default */
    private String theme = "default";

    /**
     * 类目上限，超出部分截断并记 warn。
     *
     * <p>一张纸上挤 500 根柱子本来也看不清，而明细型数据集动辄几百上千行 —— 不设上限的话
     * 出来的是一片黑。默认 30 是「A4 横向一张图上还认得出 x 轴文字」的量级。
     */
    private Integer maxCategories = 30;

    /** 一条数值系列。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Series implements Serializable {

        /** 取哪个字段的值 */
        private String field = "";

        /** 图例上显示的名字，空 = 用字段名 */
        private String name = "";

        /**
         * 在同一类目内怎么把多行算成一个数：sum | avg | max | min | count | none。
         *
         * <p>{@code none} = 不聚合，同类目取第一条 —— 数据集本身已经是汇总结果时用。
         */
        private String aggregate = "sum";

        public String displayName() {
            return name == null || name.isBlank() ? field : name;
        }
    }

    /** 饼图：只画第一条系列（一个饼说不了两组数）。 */
    public boolean isPie() {
        return "pie".equals(chartType);
    }

    /** 横向柱状：x/y 轴互换。 */
    public boolean isHorizontal() {
        return "hbar".equals(chartType);
    }

    /** 折线与面积共用一套画法，区别只在要不要填下面那一块。 */
    public boolean isLineLike() {
        return "line".equals(chartType) || "area".equals(chartType);
    }

    public boolean isArea() {
        return "area".equals(chartType);
    }

    /** 有效的系列（字段名非空）；配了一半的空行不参与。 */
    public List<Series> validSeries() {
        List<Series> out = new ArrayList<>();
        if (series == null) {
            return out;
        }
        for (Series s : series) {
            if (s != null && s.getField() != null && !s.getField().isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 配全了没有 —— 类目字段与至少一条系列。
     *
     * <p>没配全就当空格子（记 warn），不画一张空坐标系：设计器里配到一半保存过的报表，
     * 出纸时留白比留一个空框好看得多。
     */
    public boolean valid() {
        return categoryField != null && !categoryField.isBlank() && !validSeries().isEmpty();
    }

    public int categoryLimit() {
        return maxCategories == null || maxCategories <= 0 ? 30 : maxCategories;
    }
}
