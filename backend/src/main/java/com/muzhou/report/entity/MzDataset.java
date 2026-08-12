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

    /**
     * 作用范围的第二维：空串 = 该报表**全版本共用**（也是公共数据集恒有的值）；
     * 非空 = 只属于 {@code mz_report_version} 里的那一版。
     *
     * <p>同 {@link #reportId}，恒为非 null（空串代表不限版本）—— 唯一索引
     * {@code uk_dataset_code_scope_v(code, report_id, version_id)} 里 NULL 是可以重复的。
     *
     * <p>取数时按「版本级 → 报表级 → 公共」的顺序找同 code 的那一个，所以某一版想换个接口，
     * 建一个同 code 的版本级数据集就行，模板里的 {@code #{code.字段}} 一个字都不用改。
     */
    private String versionId;

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
