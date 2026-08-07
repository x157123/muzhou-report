package com.muzhou.report.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muzhou.report.common.BizException;
import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.dto.DatasetLinkDTO;
import com.muzhou.report.dto.ReportContentDTO;
import com.muzhou.report.dto.ReportVersionSaveDTO;
import com.muzhou.report.entity.MzDataset;
import com.muzhou.report.entity.MzDatasetField;
import com.muzhou.report.entity.MzReport;
import com.muzhou.report.entity.MzReportVersion;
import com.muzhou.report.mapper.MzReportMapper;
import com.muzhou.report.mapper.MzReportVersionMapper;
import com.muzhou.report.service.DatasetService;
import com.muzhou.report.service.ReportVersionService;
import com.muzhou.report.version.ReportVersionResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 报表版本服务实现。见 docs/CONTRACT.md §3.3。
 *
 * <p>刻意依赖 {@link MzReportMapper} 而不是 {@code ReportService}：报表服务要调版本服务
 * （详情/保存/复制/删除都得落到某一版上），两边互相注入就是循环依赖。
 */
@Slf4j
@Service
public class ReportVersionServiceImpl extends ServiceImpl<MzReportVersionMapper, MzReportVersion>
        implements ReportVersionService {

    private final MzReportMapper reportMapper;

    private final DatasetService datasetService;

    private final ObjectMapper objectMapper;

    /** 同 {@code RenderServiceImpl}：容器里有两个 ObjectMapper，必须显式 Qualifier，故手写构造器。 */
    public ReportVersionServiceImpl(MzReportMapper reportMapper,
                                    DatasetService datasetService,
                                    @Qualifier("mzObjectMapper") ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.datasetService = datasetService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureMigrated(String reportId) {
        if (!StringUtils.hasText(reportId) || exists(byReport(reportId))) {
            return;
        }
        MzReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException("报表不存在");
        }
        MzReportVersion v = new MzReportVersion();
        v.setReportId(reportId);
        v.setVersionNo(1);
        // 老报表的那份版式原样搬过来当 v1：默认、启用、生效起始为空（区间左端 -∞）
        v.setContent(report.getContent());
        v.setEffectiveFrom(null);
        v.setIsDefault(1);
        v.setStatus(1);
        v.setCreateBy(report.getCreateBy());
        try {
            save(v);
        } catch (DuplicateKeyException e) {
            // 两个请求同时打到懒迁移上：唯一索引 uk_version_report_no 兜底，谁先谁算
            log.debug("报表[{}]的 v1 已被并发创建，跳过迁移", reportId);
        }
    }

    @Override
    public List<MzReportVersion> list(String reportId) {
        LambdaQueryWrapper<MzReportVersion> w = byReport(reportId);
        // 列表不传大字段，和 /report/page 一个规矩
        w.select(MzReportVersion.class, info -> !"content".equals(info.getColumn()));
        w.orderByAsc(MzReportVersion::getVersionNo);
        return list(w);
    }

    @Override
    public MzReportVersion detail(String reportId, String versionId) {
        ensureMigrated(reportId);
        if (StringUtils.hasText(versionId)) {
            MzReportVersion v = getById(versionId);
            if (v == null || !Objects.equals(v.getReportId(), reportId)) {
                throw new BizException("版本不存在或不属于该报表");
            }
            return v;
        }
        // 只捞默认版本那一行。**别整份 list 出来再挑** —— content 是 CLOB，一张报表五个版本
        // 就是每次渲染白读几 MB，而这条路是渲染/导出的必经之路
        // 不写 last("limit 1")：默认版本按设计就只有一条，而那个写法把 SQL 方言焊死在 H2/MySQL 上
        MzReportVersion own = getOne(byReport(reportId).eq(MzReportVersion::getIsDefault, 1)
                .orderByAsc(MzReportVersion::getVersionNo), false);
        if (own != null) {
            return own;
        }
        // 一个都没标默认（不该出现）：退回 versionNo 最小的那一版，别让报表打不开。
        // 先用不带 content 的列表定位，再按 id 捞那一行，同样避免把所有 CLOB 读出来
        List<MzReportVersion> all = list(reportId);
        if (all.isEmpty()) {
            throw new BizException("报表还没有任何版本");
        }
        return getById(all.get(0).getId());
    }

    @Override
    public List<ReportVersionResolver.Candidate> candidates(String reportId) {
        List<ReportVersionResolver.Candidate> out = new ArrayList<>();
        for (MzReportVersion v : list(reportId)) {
            out.add(new ReportVersionResolver.Candidate(v.getId(), v.getVersionNo(), v.getName(),
                    v.getEffectiveFrom(), isDefault(v), v.getStatus() == null || v.getStatus() == 1));
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String copyVersion(String reportId, String versionId) {
        MzReportVersion source = detail(reportId, versionId);
        MzReportVersion copy = new MzReportVersion();
        copy.setReportId(reportId);
        copy.setVersionNo(nextVersionNo(reportId));
        copy.setContent(source.getContent());
        // 复制出来先是一份草稿：停用、没有生效时间、不是默认版本 ——
        // 一复制就参与自动选择的话，会立刻把某一段区间抢过去
        copy.setStatus(0);
        copy.setEffectiveFrom(null);
        copy.setIsDefault(0);
        copy.setRemark("从 " + label(source) + " 复制");
        copy.setCreateBy(source.getCreateBy());
        save(copy);
        return copy.getId();
    }

    @Override
    public boolean updateMeta(ReportVersionSaveDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getId())) {
            throw new BizException("版本 id 不能为空");
        }
        MzReportVersion old = getById(dto.getId());
        if (old == null) {
            throw new BizException("版本不存在");
        }
        if (isDefault(old) && dto.getStatus() != null && dto.getStatus() == 0) {
            throw new BizException("默认版本不能停用 —— 判定值取不到时要靠它兜底");
        }
        // 走 set() 而不是 updateById：effectiveFrom 置空表示「最早的那一版」，
        // MyBatis-Plus 默认跳过 null 字段，用 updateById 永远清不掉。
        // 代价是**自动填充不生效**（update(Wrapper) 没有实体，MetaObjectHandler 不介入），
        // update_time 得自己写 —— 版本管理页上那一列否则永远停在创建时间
        LambdaUpdateWrapper<MzReportVersion> w = new LambdaUpdateWrapper<>();
        w.eq(MzReportVersion::getId, dto.getId())
                .set(MzReportVersion::getName, trimToNull(dto.getName()))
                .set(MzReportVersion::getEffectiveFrom, dto.getEffectiveFrom())
                .set(MzReportVersion::getRemark, trimToNull(dto.getRemark()))
                .set(MzReportVersion::getUpdateTime, LocalDateTime.now());
        if (dto.getStatus() != null) {
            w.set(MzReportVersion::getStatus, dto.getStatus());
        }
        return update(w);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean setDefault(String reportId, String versionId) {
        MzReportVersion target = detail(reportId, versionId);
        update(new LambdaUpdateWrapper<MzReportVersion>()
                .eq(MzReportVersion::getReportId, reportId)
                .set(MzReportVersion::getIsDefault, 0));
        LambdaUpdateWrapper<MzReportVersion> w = new LambdaUpdateWrapper<MzReportVersion>()
                .eq(MzReportVersion::getId, target.getId())
                .set(MzReportVersion::getIsDefault, 1)
                // 同 updateMeta：update(Wrapper) 不走自动填充，update_time 自己写
                .set(MzReportVersion::getUpdateTime, LocalDateTime.now());
        // 默认版本是「判定不了时用哪一版」的兜底，停用着当默认没有意义，顺手启用
        if (target.getStatus() != null && target.getStatus() == 0) {
            w.set(MzReportVersion::getStatus, 1);
        }
        return update(w);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeVersion(String versionId) {
        MzReportVersion v = getById(versionId);
        if (v == null) {
            throw new BizException("版本不存在");
        }
        if (isDefault(v)) {
            throw new BizException("默认版本不能删除，请先把别的版本设为默认");
        }
        if (v.getStatus() == null || v.getStatus() == 1) {
            long enabled = count(byReport(v.getReportId()).eq(MzReportVersion::getStatus, 1));
            if (enabled <= 1) {
                throw new BizException("这是最后一个启用中的版本，删了就没有版式可用了");
            }
        }
        return removeById(versionId);
    }

    @Override
    public boolean saveContent(String reportId, String versionId, String content) {
        MzReportVersion target = detail(reportId, versionId);
        MzReportVersion update = new MzReportVersion();
        update.setId(target.getId());
        update.setContent(content);
        return updateById(update);
    }

    @Override
    public void removeByReport(String reportId) {
        if (StringUtils.hasText(reportId)) {
            remove(byReport(reportId));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copyToReport(String fromReportId, String toReportId) {
        if (!StringUtils.hasText(fromReportId) || !StringUtils.hasText(toReportId)) {
            return;
        }
        List<MzReportVersion> sources = list(byReport(fromReportId).orderByAsc(MzReportVersion::getVersionNo));
        for (MzReportVersion s : sources) {
            MzReportVersion copy = new MzReportVersion();
            copy.setReportId(toReportId);
            // 版本号/生效时间/启停/默认标记原样保留 —— 副本要和原报表是同一套时间轴
            copy.setVersionNo(s.getVersionNo());
            copy.setName(s.getName());
            copy.setContent(s.getContent());
            copy.setEffectiveFrom(s.getEffectiveFrom());
            copy.setIsDefault(s.getIsDefault());
            copy.setStatus(s.getStatus());
            copy.setRemark(s.getRemark());
            copy.setCreateBy(s.getCreateBy());
            save(copy);
        }
    }

    @Override
    public List<String> check(String reportId, String versionId) {
        MzReportVersion target = detail(reportId, versionId);
        List<String> problems = new ArrayList<>();
        ReportContentDTO content = parse(target.getContent(), problems);
        if (content == null) {
            return problems;
        }

        // 一、这一版引用到的数据集 / 字段还在不在（数据集跨版本共用，改 SQL 会波及所有版本）。
        // 一张报表几百个单元格绑的往往是同两三个数据集，所以按 code 收敛：
        // 每个数据集只查一次、字段名只捞一次，别在单元格循环里打 N+1
        Map<String, Set<String>> fieldsByCode = new LinkedHashMap<>();
        Set<String> reported = new LinkedHashSet<>();
        // content 里显式写了 "cellConfigs": null 时字段就是 null（DTO 的初始值只在缺这一项时生效）
        Map<String, CellConfigDTO> cellConfigs = content.getCellConfigs() == null
                ? Map.of() : content.getCellConfigs();
        for (CellConfigDTO cfg : cellConfigs.values()) {
            String code = cfg == null ? null : cfg.getDatasetCode();
            if (!StringUtils.hasText(code)) {
                continue;
            }
            Set<String> fields = fieldsByCode.computeIfAbsent(code, c -> loadFieldNames(reportId, c));
            if (fields == null) {
                // 数据集没了：这一句一张报表只说一次
                if (reported.add(code)) {
                    problems.add("数据集[" + code + "]已不存在，引用它的单元格取不到数");
                }
                continue;
            }
            String field = cfg.getField();
            if (StringUtils.hasText(field) && !fields.contains(field.toLowerCase())
                    && reported.add(code + "." + field)) {
                problems.add("数据集[" + code + "]里没有字段[" + field + "]了");
            }
        }

        // 二、与默认版本之间那几项「按默认版本为准」的设置是否一致 ——
        // 不一致不是错，但这一版上配的那份不会生效，得让人知道
        MzReportVersion base = detail(reportId, null);
        if (!Objects.equals(base.getId(), target.getId())) {
            ReportContentDTO baseContent = parse(base.getContent(), new ArrayList<>());
            if (baseContent != null) {
                diff(problems, "主接口", baseContent.getPrimaryDataset(), content.getPrimaryDataset());
                diff(problems, "输出方式", baseContent.getSplitMode(), content.getSplitMode());
                diff(problems, "父子关联", links(baseContent), links(content));
            }
        }
        return problems;
    }

    /* ------------------------------ 内部 ------------------------------ */

    private LambdaQueryWrapper<MzReportVersion> byReport(String reportId) {
        return new LambdaQueryWrapper<MzReportVersion>().eq(MzReportVersion::getReportId, reportId);
    }

    private boolean isDefault(MzReportVersion v) {
        return v.getIsDefault() != null && v.getIsDefault() == 1;
    }

    private String label(MzReportVersion v) {
        return StringUtils.hasText(v.getName()) ? v.getName() : "v" + v.getVersionNo();
    }

    /**
     * 发下一个版本号。
     *
     * <p>取的是**含已删除行**的最大值（{@link MzReportVersionMapper#maxVersionNo}）：
     * 逻辑删除不会把行删掉，而唯一索引 {@code uk_version_report_no} 管着全部行。
     */
    private int nextVersionNo(String reportId) {
        return baseMapper.maxVersionNo(reportId) + 1;
    }

    private String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private ReportContentDTO parse(String json, List<String> problems) {
        if (!StringUtils.hasText(json)) {
            problems.add("这一版还没有内容，请在设计器里保存一次");
            return null;
        }
        try {
            return objectMapper.readValue(json, ReportContentDTO.class);
        } catch (Exception e) {
            problems.add("这一版的内容格式非法: " + e.getMessage());
            return null;
        }
    }

    /** 这个数据集的字段名（小写），数据集本身不存在时返回 null。 */
    private Set<String> loadFieldNames(String reportId, String code) {
        MzDataset ds = datasetService.getByCode(reportId, code);
        if (ds == null) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        for (MzDatasetField f : datasetService.listFields(ds.getId())) {
            if (f.getFieldName() != null) {
                names.add(f.getFieldName().toLowerCase());
            }
        }
        return names;
    }

    private void diff(List<String> problems, String what, Object base, Object mine) {
        if (!Objects.equals(norm(base), norm(mine))) {
            problems.add(what + "与默认版本不一致（默认版本是 " + show(base) + "，这一版是 " + show(mine)
                    + "）—— 渲染时以默认版本为准，这一版上配的那份不生效");
        }
    }

    private Object norm(Object v) {
        return v instanceof String s && s.isBlank() ? null : v;
    }

    private String show(Object v) {
        Object n = norm(v);
        return n == null ? "空" : String.valueOf(n);
    }

    /** 父子关联比对成「master→child」的有序串，避免 DatasetLinkDTO 有没有 equals 的问题。 */
    private String links(ReportContentDTO content) {
        List<DatasetLinkDTO> list = content.getDatasetLinks();
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (DatasetLinkDTO l : list) {
            sb.append(l.getMaster()).append("→").append(l.getChild()).append(';');
        }
        return sb.toString();
    }
}
