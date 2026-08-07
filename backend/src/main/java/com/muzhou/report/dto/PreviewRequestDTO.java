package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/** 设计器免保存预览请求体：{"reportId": "...", "content": {...}, "params": {...}} */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PreviewRequestDTO implements Serializable {

    /** 正在设计的报表 id：内部数据集按报表隔离，不带上它 {@code #{code.字段}} 会解析不到。 */
    private String reportId;

    private ReportContentDTO content;

    /**
     * 正在预览的是哪一版，**只用于回显**（原样放进 {@code RenderResult.versionId}）。
     *
     * <p>免保存预览渲染的就是请求里带过来的这份 content，不走版本选择 ——
     * 设计器画布上是哪一版，预览出来就是哪一版。
     */
    private String versionId;

    private Map<String, Object> params = new LinkedHashMap<>();
}
