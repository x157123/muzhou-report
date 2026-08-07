package com.muzhou.report.dto;

import com.muzhou.report.entity.MzDataset;
import com.muzhou.report.entity.MzDatasetField;
import com.muzhou.report.entity.MzDatasetParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 数据集详情（含字段/参数子表）。见 docs/CONTRACT.md §3.2 GET /{id} 与 GET /list。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DatasetDetailDTO implements Serializable {

    private MzDataset dataset;

    private List<MzDatasetField> fields;

    private List<MzDatasetParam> params;
}
