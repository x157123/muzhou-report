package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 改版本元信息的请求体（{@code PUT /api/report/version}），见 docs/CONTRACT.md §3.3。
 *
 * <p>只改元信息，**不含 content** —— 版式是设计器保存报表时写进去的
 * （{@code PUT /api/report} 带 versionId）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportVersionSaveDTO implements Serializable {

    private String id;

    /** 版本名，空则界面上显示 v{versionNo} */
    private String name;

    /**
     * 生效起始时刻，**null 表示「最早的那一版」**（区间左端 -∞）。
     *
     * <p>所以这个字段必须能被显式清空 —— 服务实现里走 UpdateWrapper 的 set()，
     * 不能用 updateById（MyBatis-Plus 默认跳过 null 字段，清不掉）。
     */
    private LocalDateTime effectiveFrom;

    /** 1 启用（参与自动选择）/ 0 停用 */
    private Integer status;

    private String remark;
}
