package com.muzhou.report.dto;

import com.muzhou.report.entity.MzDatasetField;
import com.muzhou.report.entity.MzDatasetParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * /dataset/parse 出参。见 docs/CONTRACT.md §3.2。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DatasetParseResultDTO implements Serializable {

    private List<MzDatasetField> fields;

    private List<MzDatasetParam> params;
}
