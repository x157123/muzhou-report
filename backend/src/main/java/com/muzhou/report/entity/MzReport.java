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
 * 报表定义，见 docs/CONTRACT.md §2。
 */
@Data
@TableName("mz_report")
public class MzReport implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 报表名称 */
    private String name;

    /** 报表编码，唯一 */
    private String code;

    /** 报表类型，目前固定 sheet */
    private String type;

    /**
     * 报表内容 JSON，见 CONTRACT §4。
     *
     * <p><b>库里这一列已废弃</b>（CONTRACT §2 标了 deprecated）：内容按版本存在
     * {@code mz_report_version.content} 里，本列只留着给老库做一次性懒迁移
     * （{@code ReportVersionService#ensureMigrated}）。**不做双写** —— 双写必然漂移。
     *
     * <p>但它仍是接口上的 content 字段：{@code GET /api/report/{id}} 把选中版本的内容装在这里
     * 回给前端，{@code PUT /api/report} 也从这里读要写进哪一版的内容（配合 {@link #versionId}），
     * 前端因此完全不必知道 content 换了个存法。
     */
    private String content;

    /**
     * 版本切换规则（报表级），见 CONTRACT §4：
     * {@code {"source":"field","field":"order_date","fallback":"default"}}。
     *
     * <p>放报表级而不是 content 里 —— content 本身就是被版本化的那个东西，规则放进去就成了
     * 「每个版本各有一套怎么选自己」，逻辑成环。
     */
    private String versionConfig;

    /**
     * 读/写的是哪一版，**不是库里的列**（{@code @TableField(exist = false)}）。
     *
     * <p>{@code GET /api/report/{id}?versionId=} 回填它说明这份 content 出自哪一版；
     * {@code PUT /api/report} 带上它表示把 content 写进那一版，省略 = 默认版本。
     */
    @TableField(exist = false)
    private String versionId;

    private String remark;

    /** 1 启用 0 停用 */
    private Integer status;

    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
