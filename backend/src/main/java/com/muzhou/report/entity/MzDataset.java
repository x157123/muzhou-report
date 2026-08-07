package com.muzhou.report.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据集。见 docs/CONTRACT.md §2 mz_dataset。
 */
@Data
@TableName("mz_dataset")
public class MzDataset implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String name;

    /** 单元格中 #{code.field} 使用，在同一作用范围（见 {@link #reportId}）内唯一。 */
    private String code;

    /**
     * 作用范围：空串 = 公共数据集，所有报表都能用；非空 = 该报表的内部数据集，只有它自己能用。
     *
     * <p>恒为非 null（空串代表公共），唯一索引 {@code uk_dataset_code_scope(code, report_id)}
     * 才能拦住重名 —— NULL 在唯一索引里是可以重复的。
     */
    private String reportId;

    private String datasourceId;

    /** sql / api / json。 */
    private String type;

    /**
     * 返回结果形态：{@code list} 集合（默认，整份取回）/ {@code page} 分页
     * （响应里带数据数组 + 总数，翻页靠接口地址里的 {@code ${pageNo}} / {@code ${pageSize}}）。
     *
     * <p>只对 api / json 有意义，sql 恒为 list。
     */
    private String resultType;

    private String sqlText;

    private String apiUrl;

    private String apiMethod;

    private String apiHeaders;

    private String jsonText;

    private String remark;

    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
