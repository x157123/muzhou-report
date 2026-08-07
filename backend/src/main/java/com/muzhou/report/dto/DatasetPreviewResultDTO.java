package com.muzhou.report.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * /dataset/preview 出参。见 docs/CONTRACT.md §3.2。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DatasetPreviewResultDTO implements Serializable {

    private List<String> columns;

    private List<Map<String, Object>> records;

    /** 分页型数据集（resultType=page）里接口自报的总条数；集合型为 null。 */
    private Long total;
}
