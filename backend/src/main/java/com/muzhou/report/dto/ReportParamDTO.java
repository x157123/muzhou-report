package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报表参数定义，见 docs/CONTRACT.md §4。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportParamDTO implements Serializable {

    private String name;
    private String text;
    /** string | number | date | boolean */
    private String type = "string";
    /** input | number | date | daterange | select */
    private String widget = "input";
    private String defaultValue = "";
    private Boolean required = Boolean.FALSE;
    private List<Map<String, Object>> options = new ArrayList<>();
}
