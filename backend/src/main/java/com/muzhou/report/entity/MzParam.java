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
 * 全局参数：一份系统级的参数定义，所有报表共用。见 docs/CONTRACT.md §2 mz_param、§3.5。
 *
 * <p>列名跟 {@code mz_dataset_param} 一套（{@code param_name/param_text/param_type}）而不是
 * {@code name/text/type}：{@code text} 在几种数据库里是类型名，当列名要处处加引号。
 * 转成接口上的 {@code ReportParam} 时由 {@code ParamServiceImpl#toDefinition} 改回 name/text/type。
 */
@Data
@TableName("mz_param")
public class MzParam implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 参数名，单元格里写 {@code ${param_name}}，全局唯一。 */
    private String paramName;

    /** 显示名，参数表单上的 label。 */
    private String paramText;

    /** string / number / date / boolean。 */
    private String paramType;

    /** input / number / date / daterange / select。 */
    private String widget;

    private String defaultValue;

    /** 1 必填：缺值时渲染直接报错，与报表参数同一条规则。 */
    private Integer required;

    /** 下拉选项，JSON 数组 {@code [{value,label}]}，widget=select 时用。 */
    private String options;

    private String remark;

    /** 1 启用 0 停用。**停用 = 不参与合并**，等于这个参数不存在。 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
