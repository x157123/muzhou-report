package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** 渲染/导出请求体：{"params": {...}, "sheetIndex": 0} */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RenderRequestDTO implements Serializable {

    private Map<String, Object> params = new LinkedHashMap<>();

    /**
     * 只导出**渲染结果**里的第几张 sheet，null = 整本。目前只有 PDF 导出接口认这个字段 ——
     * 它是给预览页的「打印」按钮用的：浏览器一次只显示一张工作表，打印出来自然也只该是那一张。
     *
     * <p>下标是渲染结果的下标（拆分模式下结果 sheet 是模板的 N 倍），打印设置照旧走
     * {@link ReportContentDTO#pageConfigOfRendered} 映射回模板。
     */
    private Integer sheetIndex;

    /**
     * 用哪一版版式渲染，省略 = 按报表的版本切换规则自动选（见 CONTRACT §4「版本」）。
     *
     * <p><b>只能走请求体，不能挂在地址栏 query 上</b>：地址上的 query 会被
     * {@code utils/params.js#queryParams} 原样透传成报表参数（CONTRACT §5），
     * {@code ?versionId=xxx} 会被塞进 SQL 的 {@code ${versionId}} 里去。前端要用 URL 保持版本状态，
     * 就得把 {@code versionId} 当成**保留参数名**在 {@code queryParams} 里剔除（已经这么做了）。
     */
    private String versionId;
}
