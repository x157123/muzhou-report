package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

/**
 * 水印，见 docs/CONTRACT.md §4。文字为空 = 没水印。
 *
 * <p><b>导出的 xlsx 里没有水印</b>：Excel 本身没有水印这个概念（Office 里的「水印」是往页眉塞
 * 一张图片伪造的），所以这一项只作用于 PDF / Word 导出和预览页的浏览器打印。
 * 也因此水印不像页头页尾那样能存进 xlsx 让下游读回来，PDF / Word 的转换器要额外拿一份
 * {@link PageConfigDTO}，见 {@code RenderServiceImpl}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WatermarkDTO implements Serializable {

    /** 水印文字；空 = 不画水印 */
    private String text = "";

    /** 字号（磅） */
    private Integer fontSize = 60;

    /** 颜色 #RRGGBB */
    private String color = "#C0C0C0";

    /** 不透明度百分比，0~100 */
    private Integer opacity = 30;

    /** 旋转角度，-90~90（逆时针为正，默认 45 度斜着铺） */
    private Integer rotation = 45;

    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
