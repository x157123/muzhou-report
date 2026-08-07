package com.muzhou.report.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.muzhou.report.dto.ReportVersionSaveDTO;
import com.muzhou.report.entity.MzReportVersion;
import com.muzhou.report.version.ReportVersionResolver;

import java.util.List;

/**
 * 报表版本管理。见 docs/CONTRACT.md §3.3。
 *
 * <p>版本化的是**版式**（content），不是数据集：数据集是「取数」，跨版本共用。
 * 每个版本持有一份完整的 {@code ReportContent}，报表级另有一条「版本切换规则」
 * （{@code mz_report.version_config}）决定这一次渲染用哪一版。
 */
public interface ReportVersionService extends IService<MzReportVersion> {

    /**
     * 懒迁移：这张报表一条版本行都没有时，用 {@code mz_report.content} 建 v1
     * （默认、启用、{@code effectiveFrom} 空）。老报表零感知。
     *
     * <p>挂在 {@code getDetail} 和渲染入口上；靠唯一索引 {@code uk_version_report_no} 兜幂等。
     * 迁移之后 {@code mz_report.content} 不再读写（**不做双写** —— 双写必然漂移）。
     */
    void ensureMigrated(String reportId);

    /** 版本列表（**不含 content**，同 {@code /page} 不传大字段的规矩），按 versionNo 升序。 */
    List<MzReportVersion> list(String reportId);

    /**
     * 取某一版（**含 content**）。
     *
     * @param versionId 为空 = 默认版本
     * @throws com.muzhou.report.common.BizException 版本不存在或不属于这张报表
     */
    MzReportVersion detail(String reportId, String versionId);

    /** 给 {@link ReportVersionResolver} 用的候选列表（不含 content）。 */
    List<ReportVersionResolver.Candidate> candidates(String reportId);

    /**
     * 从某一版复制出新版本：content 原样，{@code status=0} 停用、{@code effectiveFrom} 空、
     * 不是默认版本 —— 复制出来先是一份草稿，设计好、配好生效时间再启用。
     *
     * @return 新版本 id
     */
    String copyVersion(String reportId, String versionId);

    /** 改版本元信息（name / effectiveFrom / status / remark）。 */
    boolean updateMeta(ReportVersionSaveDTO dto);

    /** 设为默认（基准）版本，报表内恰好一条。 */
    boolean setDefault(String reportId, String versionId);

    /** 删除版本。默认版本、最后一个启用版本不许删。 */
    boolean removeVersion(String versionId);

    /** 把 content 写进某一版（versionId 为空 = 默认版本）。 */
    boolean saveContent(String reportId, String versionId, String content);

    /** 报表被删除时，它的版本行跟着逻辑删除。 */
    void removeByReport(String reportId);

    /** 报表被复制时，**所有版本行一起复制**（新 id，保留 versionNo / effectiveFrom / status / isDefault）。 */
    void copyToReport(String fromReportId, String toReportId);

    /**
     * 体检某一版：数据集不随版本走（跨版本共用），所以改 SQL 会同时影响所有版本，
     * 删字段会让老版本模板里的 {@code #{code.field}} 取不到数。这里扫一遍这一版引用到的
     * {@code datasetCode} / {@code field} 还在不在，顺便比对与默认版本之间
     * 「主接口 / 输出方式 / 父子关联」是否一致（不一致的那几项在渲染时以默认版本为准）。
     *
     * @return 人话写的问题列表，空表示没查出问题
     */
    List<String> check(String reportId, String versionId);
}
