package com.muzhou.report.dto;

import com.muzhou.report.entity.MzDatasetField;
import com.muzhou.report.entity.MzDatasetParam;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 数据集新增/编辑入参 = MzDataset 全字段 + fields + params。见 docs/CONTRACT.md §3.2。
 */
@Data
public class DatasetSaveDTO implements Serializable {

    /** 更新时必填。 */
    private String id;

    private String name;

    private String code;

    /** 作用范围：空 = 公共数据集；非空 = 该报表的内部数据集。见 CONTRACT §3.2。 */
    private String reportId;

    private String datasourceId;

    /** sql / api / json。 */
    private String type;

    /** 返回结果形态：list 集合 / page 分页。见 CONTRACT §3.2。 */
    private String resultType;

    private String sqlText;

    private String apiUrl;

    private String apiMethod;

    private String apiHeaders;

    private String jsonText;

    private String remark;

    private Integer status;

    private List<MzDatasetField> fields;

    private List<MzDatasetParam> params;
}
