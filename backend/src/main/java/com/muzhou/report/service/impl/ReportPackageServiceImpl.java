package com.muzhou.report.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muzhou.report.common.BizException;
import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.dto.DatasetLinkDTO;
import com.muzhou.report.dto.DatasetSaveDTO;
import com.muzhou.report.dto.ReportContentDTO;
import com.muzhou.report.dto.ReportImportResultDTO;
import com.muzhou.report.dto.ReportPackageDTO;
import com.muzhou.report.entity.MzDataset;
import com.muzhou.report.entity.MzDatasetField;
import com.muzhou.report.entity.MzDatasetParam;
import com.muzhou.report.entity.MzDatasource;
import com.muzhou.report.entity.MzReport;
import com.muzhou.report.entity.MzReportVersion;
import com.muzhou.report.service.DatasetService;
import com.muzhou.report.service.DatasourceService;
import com.muzhou.report.service.ReportPackageService;
import com.muzhou.report.service.ReportService;
import com.muzhou.report.service.ReportVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表导入导出实现。见 docs/CONTRACT.md §3.3。
 *
 * <p>三条必须记住的规矩（都写在 {@link ReportPackageDTO} 的类注释里）：
 * <ol>
 *   <li><b>包里只有 code，没有主键</b> —— UUID 跨环境必定对不上；</li>
 *   <li><b>数据源不进包</b> —— 它带着业务库地址和口令，两个环境本来就该不一样，
 *       包里只记数据集绑的 {@code datasourceCode}，对不上就报 warning 让人去补；</li>
 *   <li><b>公共数据集只新建、不覆盖</b> —— 它被多张报表共用，为了导一张报表改掉别人的取数是灾难。</li>
 * </ol>
 */
@Slf4j
@Service
public class ReportPackageServiceImpl implements ReportPackageService {

    /** 包文件标识，导入时用它认这是不是本项目导出的包。 */
    private static final String FILE_TYPE = "muzhou-report-package";

    private static final int FORMAT_VERSION = 1;

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 公共数据集的 report_id，同 {@code DatasetServiceImpl#PUBLIC}。 */
    private static final String PUBLIC = "";

    private final ReportService reportService;

    private final ReportVersionService versionService;

    private final DatasetService datasetService;

    private final DatasourceService datasourceService;

    private final ObjectMapper objectMapper;

    /**
     * 逐张报表开事务用。
     *
     * <p>不用 {@code @Transactional} 是因为**自调用不走代理** —— 循环里调自己的方法，
     * 注解压根不生效，整包会退化成「一张失败全部回滚」。要么给自己注入一个代理，
     * 要么就是这个：显式一点，也少一处 {@code @Lazy} 循环依赖。
     */
    private final TransactionTemplate txTemplate;

    /** 同 {@code RenderServiceImpl}：容器里有两个 ObjectMapper，必须显式 Qualifier，故手写构造器。 */
    public ReportPackageServiceImpl(ReportService reportService,
                                    ReportVersionService versionService,
                                    DatasetService datasetService,
                                    DatasourceService datasourceService,
                                    TransactionTemplate txTemplate,
                                    @Qualifier("mzObjectMapper") ObjectMapper objectMapper) {
        this.reportService = reportService;
        this.versionService = versionService;
        this.datasetService = datasetService;
        this.datasourceService = datasourceService;
        this.txTemplate = txTemplate;
        this.objectMapper = objectMapper;
    }

    /* ------------------------------ 导出 ------------------------------ */

    @Override
    public byte[] exportPackage(List<String> reportIds) {
        if (reportIds == null || reportIds.isEmpty()) {
            throw new BizException("请先选择要导出的报表");
        }
        ReportPackageDTO pkg = new ReportPackageDTO();
        pkg.setFileType(FILE_TYPE);
        pkg.setFormatVersion(FORMAT_VERSION);
        pkg.setExportTime(LocalDateTime.now());

        // 数据源 id→code 在整包里复用一份：几张报表共用一个数据源是常态
        Map<String, String> datasourceCodes = new HashMap<>();
        for (String id : reportIds) {
            MzReport report = reportService.getById(id);
            if (report == null) {
                throw new BizException("报表不存在: " + id);
            }
            pkg.getReports().add(toItem(report, datasourceCodes));
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pkg);
        } catch (Exception e) {
            throw new BizException("导出失败: " + e.getMessage());
        }
    }

    private ReportPackageDTO.Item toItem(MzReport report, Map<String, String> datasourceCodes) {
        ReportPackageDTO.Item item = new ReportPackageDTO.Item();
        item.setName(report.getName());
        item.setCode(report.getCode());
        item.setType(report.getType());
        item.setVersionConfig(report.getVersionConfig());
        item.setRemark(report.getRemark());
        item.setStatus(report.getStatus());

        // 老报表可能还没有版本行，先把 mz_report.content 迁成 v1，否则导出的是一张没有版式的报表
        versionService.ensureMigrated(report.getId());
        List<MzReportVersion> versions = versionService.listWithContent(report.getId());
        for (MzReportVersion v : versions) {
            ReportPackageDTO.Version out = new ReportPackageDTO.Version();
            out.setVersionNo(v.getVersionNo());
            out.setName(v.getName());
            out.setContent(v.getContent());
            out.setEffectiveFrom(v.getEffectiveFrom());
            out.setMatchRules(v.getMatchRules());
            out.setIsDefault(v.getIsDefault());
            out.setStatus(v.getStatus());
            out.setRemark(v.getRemark());
            item.getVersions().add(out);
        }

        // 内部数据集：报表的一部分，整套带走
        Set<String> internalCodes = new LinkedHashSet<>();
        for (MzDataset ds : datasetService.listByReport(report.getId())) {
            internalCodes.add(ds.getCode());
            item.getDatasets().add(toDataset(ds, false, datasourceCodes));
        }
        // 内容里引用到的公共数据集：不带的话报表进了目标环境就是一张取不到数的空表
        for (String code : referencedCodes(versions)) {
            if (internalCodes.contains(code)) {
                continue;
            }
            MzDataset ds = datasetService.getByCode(null, code);
            if (ds != null) {
                item.getDatasets().add(toDataset(ds, true, datasourceCodes));
            }
        }
        return item;
    }

    private ReportPackageDTO.Dataset toDataset(MzDataset ds, boolean shared, Map<String, String> codes) {
        ReportPackageDTO.Dataset out = new ReportPackageDTO.Dataset();
        out.setName(ds.getName());
        out.setCode(ds.getCode());
        out.setShared(shared);
        out.setDatasourceCode(datasourceCode(ds.getDatasourceId(), codes));
        out.setType(ds.getType());
        out.setResultType(ds.getResultType());
        out.setSqlText(ds.getSqlText());
        out.setApiUrl(ds.getApiUrl());
        out.setApiMethod(ds.getApiMethod());
        out.setApiHeaders(ds.getApiHeaders());
        out.setJsonText(ds.getJsonText());
        out.setRemark(ds.getRemark());
        out.setStatus(ds.getStatus());
        for (MzDatasetField f : datasetService.listFields(ds.getId())) {
            // 主键与外键不进包（导入时按目标环境重新发号），留着只会让文件更难读
            f.setId(null);
            f.setDatasetId(null);
            out.getFields().add(f);
        }
        for (MzDatasetParam p : datasetService.listParams(ds.getId())) {
            p.setId(null);
            p.setDatasetId(null);
            out.getParams().add(p);
        }
        return out;
    }

    /** 数据源 id → code（包里按 code 引用）；查不到就留空，导入那头会报 warning。 */
    private String datasourceCode(String datasourceId, Map<String, String> cache) {
        if (!StringUtils.hasText(datasourceId)) {
            return null;
        }
        return cache.computeIfAbsent(datasourceId, id -> {
            MzDatasource ds = datasourceService.getById(id);
            return ds == null ? null : ds.getCode();
        });
    }

    /**
     * 扫出这些版式引用到的数据集 code。
     *
     * <p>三处都要扫：单元格绑定、主接口（{@code primaryDataset}，可能一个单元格都没绑它）、
     * 父子关联的主表/子表（子表往往也只出现在关联配置里）。漏一处就是「导过去某个数据集没跟来」。
     */
    private Set<String> referencedCodes(List<MzReportVersion> versions) {
        Set<String> codes = new LinkedHashSet<>();
        for (MzReportVersion v : versions) {
            if (!StringUtils.hasText(v.getContent())) {
                continue;
            }
            ReportContentDTO content;
            try {
                content = objectMapper.readValue(v.getContent(), ReportContentDTO.class);
            } catch (Exception e) {
                // 内容坏了也照样导出（content 原样进包），只是挑不出它引用了谁
                log.warn("报表版本[{}]的内容解析失败，跳过数据集扫描: {}", v.getId(), e.getMessage());
                continue;
            }
            add(codes, content.getPrimaryDataset());
            if (content.getCellConfigs() != null) {
                for (CellConfigDTO cfg : content.getCellConfigs().values()) {
                    if (cfg != null) {
                        add(codes, cfg.getDatasetCode());
                    }
                }
            }
            if (content.getDatasetLinks() != null) {
                for (DatasetLinkDTO link : content.getDatasetLinks()) {
                    if (link != null) {
                        add(codes, link.getMaster());
                        add(codes, link.getChild());
                    }
                }
            }
        }
        return codes;
    }

    private void add(Set<String> codes, String code) {
        if (StringUtils.hasText(code)) {
            codes.add(code.trim());
        }
    }

    /* ------------------------------ 导入 ------------------------------ */

    @Override
    public ReportImportResultDTO importPackage(byte[] json, String mode) {
        ReportPackageDTO pkg = parse(json);
        String strategy = StringUtils.hasText(mode) ? mode : MODE_SKIP;
        if (!MODE_SKIP.equals(strategy) && !MODE_OVERWRITE.equals(strategy) && !MODE_COPY.equals(strategy)) {
            throw new BizException("未知的导入策略: " + mode);
        }
        ReportImportResultDTO result = new ReportImportResultDTO();
        for (ReportPackageDTO.Item item : pkg.getReports()) {
            ReportImportResultDTO.Item one = new ReportImportResultDTO.Item();
            one.setName(item.getName());
            one.setCode(item.getCode());
            try {
                // 一张报表一个事务：第三张失败不该把前两张已经导好的一起回滚掉
                txTemplate.executeWithoutResult(status -> importOne(item, strategy, one));
            } catch (BizException e) {
                one.setAction("failed");
                one.setMessage(e.getMessage());
            } catch (Exception e) {
                log.error("导入报表[{}]失败", item.getCode(), e);
                one.setAction("failed");
                one.setMessage(String.valueOf(e.getMessage()));
            }
            switch (String.valueOf(one.getAction())) {
                case "created" -> result.setCreated(result.getCreated() + 1);
                case "updated" -> result.setUpdated(result.getUpdated() + 1);
                case "skipped" -> result.setSkipped(result.getSkipped() + 1);
                default -> result.setFailed(result.getFailed() + 1);
            }
            result.getItems().add(one);
        }
        return result;
    }

    private ReportPackageDTO parse(byte[] json) {
        if (json == null || json.length == 0) {
            throw new BizException("文件是空的");
        }
        ReportPackageDTO pkg;
        try {
            pkg = objectMapper.readValue(json, ReportPackageDTO.class);
        } catch (Exception e) {
            throw new BizException("不是合法的报表导出文件: " + e.getMessage());
        }
        if (pkg == null || pkg.getReports() == null || pkg.getReports().isEmpty()) {
            throw new BizException("文件里没有任何报表");
        }
        if (StringUtils.hasText(pkg.getFileType()) && !FILE_TYPE.equals(pkg.getFileType())) {
            throw new BizException("不是报表导出文件（fileType=" + pkg.getFileType() + "）");
        }
        return pkg;
    }

    /** 导入一张报表；行为记在 {@code out} 上（事务由调用方开，抛异常即整张回滚）。 */
    private void importOne(ReportPackageDTO.Item item, String mode, ReportImportResultDTO.Item out) {
        if (!StringUtils.hasText(item.getCode())) {
            throw new BizException("报表编码为空");
        }
        if (item.getVersions() == null || item.getVersions().isEmpty()) {
            throw new BizException("报表[" + item.getCode() + "]没有任何版式");
        }
        MzReport exist = reportService.getByCode(item.getCode());

        String reportId;
        if (exist != null && MODE_SKIP.equals(mode)) {
            out.setAction("skipped");
            out.setMessage("目标环境已有编码为 " + item.getCode() + " 的报表");
            return;
        }
        if (exist != null && MODE_OVERWRITE.equals(mode)) {
            reportId = exist.getId();
            // 报表 id 保持不变 —— 外部系统是拿 id 打开设计器/预览的（甚至就用业务 KEY 当 id），
            // 覆盖时换一个的话那些链接全断了
            MzReport meta = new MzReport();
            meta.setId(reportId);
            meta.setName(item.getName());
            meta.setType(item.getType());
            meta.setVersionConfig(item.getVersionConfig());
            meta.setRemark(item.getRemark());
            meta.setStatus(item.getStatus());
            // overwriteMeta 而不是 update：包里没写备注/版本切换规则时，目标环境那份要跟着清掉
            reportService.overwriteMeta(meta);
            out.setAction("updated");
        } else {
            MzReport report = new MzReport();
            report.setName(item.getName());
            // copy 策略下同编码的两份要并存，编码加后缀让开；skip/overwrite 走到这里说明没撞
            report.setCode(exist == null ? item.getCode() : uniqueCode(item.getCode()));
            report.setType(item.getType());
            report.setVersionConfig(item.getVersionConfig());
            report.setRemark(item.getRemark());
            report.setStatus(item.getStatus());
            // 走 create 而不是 save：名称/编码的合法性校验、缺省值、建 v1 都在里面
            reportId = reportService.create(report);
            out.setCode(report.getCode());
            out.setAction("created");
            if (exist != null) {
                out.getWarnings().add("目标环境已有编码 " + item.getCode()
                        + " 的报表，本次新建的这张编码为 " + report.getCode());
            }
        }

        // 版式整套替换（新建出来的那张只有一个空白 v1，同样在这里被换掉）
        versionService.replaceVersions(reportId, toVersions(item.getVersions()));
        importDatasets(reportId, item, out);
    }

    private List<MzReportVersion> toVersions(List<ReportPackageDTO.Version> versions) {
        List<MzReportVersion> out = new ArrayList<>();
        for (ReportPackageDTO.Version v : versions) {
            MzReportVersion row = new MzReportVersion();
            row.setVersionNo(v.getVersionNo());
            row.setName(v.getName());
            row.setContent(v.getContent());
            row.setEffectiveFrom(v.getEffectiveFrom());
            row.setMatchRules(v.getMatchRules());
            row.setIsDefault(v.getIsDefault());
            row.setStatus(v.getStatus());
            row.setRemark(v.getRemark());
            out.add(row);
        }
        return out;
    }

    /**
     * 导入这张报表带的数据集。
     *
     * <p>内部数据集整套替换（它是报表的一部分）；<b>公共数据集只在目标环境没有同 code 时才建</b> ——
     * 它被多张报表共用，为了导一张报表改掉别人的取数是灾难，已存在就沿用目标环境那份并记一条 warning。
     */
    private void importDatasets(String reportId, ReportPackageDTO.Item item, ReportImportResultDTO.Item out) {
        List<ReportPackageDTO.Dataset> packaged = item.getDatasets() == null ? List.of() : item.getDatasets();
        // 数据源按 code 找，一个包里多半就那么一两个，缓存一份
        Map<String, String> datasourceIds = new LinkedHashMap<>();
        List<DatasetSaveDTO> internal = new ArrayList<>();
        for (ReportPackageDTO.Dataset ds : packaged) {
            DatasetSaveDTO dto = toSaveDTO(ds, resolveDatasource(ds, datasourceIds, out));
            if (Boolean.TRUE.equals(ds.getShared())) {
                MzDataset own = datasetService.getByCode(null, ds.getCode());
                if (own != null) {
                    out.getWarnings().add("公共数据集[" + ds.getCode() + "]目标环境已存在，沿用目标环境的定义（未覆盖）");
                    continue;
                }
                dto.setReportId(PUBLIC);
                datasetService.create(dto);
            } else {
                internal.add(dto);
            }
        }
        datasetService.replaceReportDatasets(reportId, internal);
    }

    /** 包里记的是数据源 code，换成目标环境的 id；换不到就留空并记一条 warning。 */
    private String resolveDatasource(ReportPackageDTO.Dataset ds, Map<String, String> cache,
                                     ReportImportResultDTO.Item out) {
        String code = ds.getDatasourceCode();
        if (!StringUtils.hasText(code)) {
            return null;
        }
        if (cache.containsKey(code)) {
            return cache.get(code);
        }
        MzDatasource own = datasourceService.getByCode(code);
        String id = own == null ? null : own.getId();
        cache.put(code, id);
        if (id == null) {
            // 不拦：版式和数据集都是对的，只差一个连接，让人进来补比整张报表导不进去强
            out.getWarnings().add("数据源[" + code + "]在本环境不存在，数据集[" + ds.getCode()
                    + "]需要手工重新绑定数据源");
        }
        return id;
    }

    private DatasetSaveDTO toSaveDTO(ReportPackageDTO.Dataset ds, String datasourceId) {
        DatasetSaveDTO dto = new DatasetSaveDTO();
        dto.setName(ds.getName());
        dto.setCode(ds.getCode());
        dto.setDatasourceId(datasourceId);
        dto.setType(ds.getType());
        dto.setResultType(ds.getResultType());
        dto.setSqlText(ds.getSqlText());
        dto.setApiUrl(ds.getApiUrl());
        dto.setApiMethod(ds.getApiMethod());
        dto.setApiHeaders(ds.getApiHeaders());
        dto.setJsonText(ds.getJsonText());
        dto.setRemark(ds.getRemark());
        dto.setStatus(ds.getStatus());
        dto.setFields(ds.getFields() == null ? List.of() : ds.getFields());
        dto.setParams(ds.getParams() == null ? List.of() : ds.getParams());
        return dto;
    }

    /** copy 策略下让开已被占用的编码：加 _import_时间戳 后缀，同一秒重复导入再追加序号。 */
    private String uniqueCode(String code) {
        String suffix = "_import_" + LocalDateTime.now().format(TS_FORMAT);
        String candidate = code + suffix;
        int seq = 1;
        while (reportService.getByCode(candidate) != null) {
            candidate = code + suffix + "_" + (seq++);
        }
        return candidate;
    }
}
