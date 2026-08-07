package com.muzhou.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 数据集字段。见 docs/CONTRACT.md §2 mz_dataset_field。
 * 注意：该表没有 deleted 列，不做逻辑删除。
 */
@Data
@TableName("mz_dataset_field")
public class MzDatasetField implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String datasetId;

    private String fieldName;

    private String fieldText;

    /** string|number|date|boolean。 */
    private String fieldType;

    private Integer sortNo;
}
