package com.muzhou.report.dto;

import com.muzhou.report.entity.MzDatasetParam;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * /dataset/parse 与 /dataset/preview 的公共入参。见 docs/CONTRACT.md §3.2。
 */
@Data
public class DatasetParseDTO implements Serializable {

    private String datasourceId;

    /** sql / api / json。 */
    private String type;

    /** 返回结果形态：list 集合 / page 分页。 */
    private String resultType;

    private String sqlText;

    private String apiUrl;

    private String apiMethod;

    private String apiHeaders;

    private String jsonText;

    /** 已配置的参数（parse 时用于保留已有配置，preview 时用于取值来源）。 */
    private List<MzDatasetParam> params;

    /** preview 专用：参数取值。 */
    private java.util.Map<String, Object> paramValues;

    /** preview 专用，默认 1。 */
    private Long pageNo = 1L;

    /** preview 专用，默认取 MzProperties.previewRows。 */
    private Long pageSize;
}
