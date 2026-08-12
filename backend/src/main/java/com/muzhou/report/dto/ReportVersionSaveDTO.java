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
     * 生效起始时刻（闭端），**null 表示不限**（从最早算起）。
     *
     * <p>所以这个字段必须能被显式清空 —— 服务实现里走 UpdateWrapper 的 set()，
     * 不能用 updateById（MyBatis-Plus 默认跳过 null 字段，清不掉）。
     */
    private LocalDateTime effectiveFrom;

    /**
     * 生效结束时刻（**开端**，这一刻起不再生效），**null 表示不限**（一直有效）。
     *
     * <p>同 {@link #effectiveFrom} 必须能被清空。两端都填时结束必须晚于起始，
     * 否则那是个永远命中不了的空区间，服务实现里直接拦掉。
     */
    private LocalDateTime effectiveTo;

    /**
     * 匹配条件（{@link VersionMatchRuleDTO} 的 JSON 数组串），见 CONTRACT §4.1。
     *
     * <p>同 {@link #effectiveFrom}：空串/空数组表示「无条件匹配」，必须能被显式清空。
     */
    private String matchRules;

    /** 1 启用（参与自动选择）/ 0 停用 */
    private Integer status;

    private String remark;
}
