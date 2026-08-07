package com.muzhou.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 数据集参数。见 docs/CONTRACT.md §2 mz_dataset_param。
 * 注意：该表没有 deleted 列，不做逻辑删除。
 */
@Data
@TableName("mz_dataset_param")
public class MzDatasetParam implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String datasetId;

    private String paramName;

    private String paramText;

    /** string|number|date|boolean。 */
    private String paramType;

    private String defaultValue;

    /** 0/1。 */
    private Integer required;

    private Integer sortNo;
}
