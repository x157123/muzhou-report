package com.muzhou.report.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据源。见 docs/CONTRACT.md §2 mz_datasource。
 */
@Data
@TableName("mz_datasource")
public class MzDatasource implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 显示名。 */
    private String name;

    /** 动态数据源 key，英文数字下划线，唯一。 */
    private String code;

    /** mysql/postgresql/oracle/sqlserver/h2。 */
    private String dbType;

    private String driverClassName;

    private String url;

    private String username;

    /**
     * 业务库口令。
     *
     * <p><b>只进不出</b>（{@code WRITE_ONLY}）：这个实体既当入参又当出参，以前口令会随
     * {@code GET /api/datasource/page|list|{id}} 明文回给任何调用方。本项目不做鉴权
     * （见 docs/OPTIMIZATION.md §3），能访问到接口的人本来就不该顺手拿到所有业务库的口令。
     *
     * <p>不能用 {@code @JsonIgnore} —— 那会把**两个**方向都挡掉，新建/修改数据源时
     * 前端就再也提交不上来了。出参没有这一项之后，编辑时前端拿不到原值，所以
     * {@code DatasourceServiceImpl#update} 约定「提交空口令 = 不修改」。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private String remark;

    /** 1 启用 0 停用。 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
