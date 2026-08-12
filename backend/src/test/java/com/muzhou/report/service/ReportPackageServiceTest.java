package com.muzhou.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.muzhou.report.dto.DatasetSaveDTO;
import com.muzhou.report.dto.ReportImportResultDTO;
import com.muzhou.report.dto.ReportPackageDTO;
import com.muzhou.report.entity.MzDataset;
import com.muzhou.report.entity.MzDatasetField;
import com.muzhou.report.entity.MzDatasource;
import com.muzhou.report.entity.MzReport;
import com.muzhou.report.entity.MzReportVersion;
import com.muzhou.report.service.impl.ReportPackageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报表导入导出。锁的是这块最容易写错的两处：
 *
 * <ol>
 *   <li><b>导出时数据集引用要扫全</b> —— 单元格绑定、主接口、父子关联的主表/子表三处，
 *       漏一处就是「导过去某个数据集没跟来，报表在正式环境取不到数」；</li>
 *   <li><b>导入的三种策略</b> —— 跳过 / 覆盖（报表 id 不变，版式与内部数据集整套替换）/
 *       另建一张（编码让开）；以及<b>公共数据集只新建不覆盖</b>，它被多张报表共用。</li>
 * </ol>
 *
 * <p>纯 mock 测试，不起 Spring、不碰数据库（同项目里其它测试的路数）。
 */
class ReportPackageServiceTest {

    private ReportService reportService;
    private ReportVersionService versionService;
    private DatasetService datasetService;
    private DatasourceService datasourceService;
    private ReportPackageServiceImpl service;

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        reportService = mock(ReportService.class);
        versionService = mock(ReportVersionService.class);
        datasetService = mock(DatasetService.class);
        datasourceService = mock(DatasourceService.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        // 事务模板在测试里就是「原地把回调跑掉」
        doAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof Consumer) {
                ((Consumer<Object>) arg).accept(null);
            } else if (arg instanceof TransactionCallbackWithoutResult cb) {
                cb.doInTransaction(null);
            }
            return null;
        }).when(tx).executeWithoutResult(any());
        service = new ReportPackageServiceImpl(reportService, versionService, datasetService,
                datasourceService, tx, mapper);
    }

    /* ------------------------------ 导出 ------------------------------ */

    @Test
    @DisplayName("导出：单元格 / 主接口 / 父子关联三处引用到的公共数据集都进包，内部数据集整套带走")
    void exportCollectsReferencedDatasets() throws Exception {
        MzReport report = report("R1", "sales", "销售单");
        when(reportService.getById("R1")).thenReturn(report);
        when(versionService.listWithContent("R1")).thenReturn(List.of(version("V1", 1, """
                {"primaryDataset":"orders",
                 "cellConfigs":{"0_1_1":{"datasetCode":"items","field":"name"}},
                 "datasetLinks":[{"master":"orders","child":"ship"}]}
                """)));
        // 内部数据集：items（绑在 ds1 上）
        MzDataset items = dataset("D1", "items", "R1", "ds1");
        when(datasetService.listByReport("R1")).thenReturn(List.of(items));
        when(datasetService.getByCode(isNull(), isNull(), eq("orders")))
                .thenReturn(dataset("D2", "orders", "", "ds1"));
        when(datasetService.getByCode(isNull(), isNull(), eq("ship")))
                .thenReturn(dataset("D3", "ship", "", null));
        when(datasetService.listFields(any())).thenReturn(List.of(field("F1", "D1", "name")));
        when(datasetService.listParams(any())).thenReturn(List.of());
        when(datasourceService.getById("ds1")).thenReturn(datasource("ds1", "biz"));

        ReportPackageDTO pkg = mapper.readValue(service.exportPackage(List.of("R1")), ReportPackageDTO.class);

        assertEquals(1, pkg.getReports().size());
        ReportPackageDTO.Item item = pkg.getReports().get(0);
        assertEquals("sales", item.getCode());
        assertEquals(1, item.getVersions().size());
        assertEquals(List.of("items", "orders", "ship"),
                item.getDatasets().stream().map(ReportPackageDTO.Dataset::getCode).toList(),
                "内部集 + 主接口 + 单元格绑定 + 父子关联的子表，一个都不能少");
        assertEquals(List.of(false, true, true),
                item.getDatasets().stream().map(ReportPackageDTO.Dataset::getShared).toList(),
                "公共数据集要标出来，导入那头才知道「只新建不覆盖」");
        // 数据源按 code 引用（id 跨环境对不上），数据源本身不进包
        assertEquals("biz", item.getDatasets().get(0).getDatasourceCode());
        assertNull(item.getDatasets().get(2).getDatasourceCode());
        // 主键不进包
        assertNull(item.getDatasets().get(0).getFields().get(0).getId());
        assertNull(item.getDatasets().get(0).getFields().get(0).getDatasetId());
    }

    @Test
    @DisplayName("导出：版本级数据集标出 versionNo，且不挡住同 code 的公共集进包")
    void exportMarksVersionScopedDatasets() throws Exception {
        MzReport report = report("R1", "sales", "销售单");
        when(reportService.getById("R1")).thenReturn(report);
        // 两版：v1 用公共的 orders，v2 自己盖了一份 orders
        when(versionService.listWithContent("R1")).thenReturn(List.of(
                version("V1", 1, "{\"primaryDataset\":\"orders\"}"),
                version("V2", 2, "{\"primaryDataset\":\"orders\"}")));
        MzDataset own = dataset("D1", "orders", "R1", "ds1");
        own.setVersionId("V2");
        when(datasetService.listByReport("R1")).thenReturn(List.of(own));
        when(datasetService.getByCode(isNull(), isNull(), eq("orders")))
                .thenReturn(dataset("D2", "orders", "", "ds1"));
        when(datasetService.listFields(any())).thenReturn(List.of());
        when(datasetService.listParams(any())).thenReturn(List.of());
        when(datasourceService.getById("ds1")).thenReturn(datasource("ds1", "biz"));

        ReportPackageDTO pkg = mapper.readValue(service.exportPackage(List.of("R1")), ReportPackageDTO.class);
        List<ReportPackageDTO.Dataset> datasets = pkg.getReports().get(0).getDatasets();

        assertEquals(2, datasets.size(), "v2 自己那份盖的只是 v2，v1 仍要用公共那份 —— 两份都得进包");
        assertEquals(2, datasets.get(0).getVersionNo(), "版本级的按 versionNo 认亲（包里没有版本 id）");
        assertFalse(datasets.get(0).getShared());
        assertNull(datasets.get(1).getVersionNo(), "公共集不属于任何版本");
        assertTrue(datasets.get(1).getShared());
    }

    /* ------------------------------ 导入 ------------------------------ */

    @Test
    @DisplayName("导入 skip：目标环境已有同编码报表时原样保留，版式与数据集一概不动")
    void importSkipsExisting() {
        when(reportService.getByCode("sales")).thenReturn(report("R9", "sales", "线上的销售单"));

        ReportImportResultDTO result = service.importPackage(pack(), ReportPackageService.MODE_SKIP);

        assertEquals(1, result.getSkipped());
        assertEquals(0, result.getCreated() + result.getUpdated() + result.getFailed());
        assertEquals("skipped", result.getItems().get(0).getAction());
        verify(versionService, never()).replaceVersions(any(), any());
        verify(datasetService, never()).replaceReportDatasets(any(), any());
    }

    @Test
    @DisplayName("导入 overwrite：报表 id 不变，版式与内部数据集整套替换；公共数据集已存在则只提示不覆盖")
    void importOverwritesInPlace() {
        when(reportService.getByCode("sales")).thenReturn(report("R9", "sales", "线上的销售单"));
        when(datasetService.getByCode(isNull(), isNull(), eq("orders")))
                .thenReturn(dataset("D9", "orders", "", "ds1"));
        // 数据源在目标环境不存在 —— 不拦，只报 warning
        when(datasourceService.getByCode("biz")).thenReturn(null);

        ReportImportResultDTO result = service.importPackage(pack(), ReportPackageService.MODE_OVERWRITE);

        assertEquals(1, result.getUpdated());
        verify(reportService, never()).create(any());
        verify(versionService).replaceVersions(eq("R9"), any());

        ArgumentCaptor<List<DatasetSaveDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(datasetService).replaceReportDatasets(eq("R9"), captor.capture());
        assertEquals(List.of("items"), captor.getValue().stream().map(DatasetSaveDTO::getCode).toList(),
                "公共数据集不该混进内部数据集那一批");
        assertNull(captor.getValue().get(0).getDatasourceId(), "数据源对不上时留空，等人进来补");
        // 公共数据集已存在：沿用目标环境那份，只记一条 warning
        verify(datasetService, never()).create(any());
        List<String> warnings = result.getItems().get(0).getWarnings();
        assertEquals(2, warnings.size(), "一条数据源缺失 + 一条公共数据集沿用");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("数据源[biz]")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("公共数据集[orders]")), warnings.toString());
    }

    @Test
    @DisplayName("导入：版本级数据集按 versionNo 落到目标环境那一版上，对不上号就退回全版本共用")
    void importPlacesVersionScopedDatasets() {
        when(reportService.getByCode("sales")).thenReturn(report("R9", "sales", "线上的销售单"));
        // 目标环境把包里的 v2 重新发号成了 v7，但映射里记的仍是**包里那个号**
        when(versionService.replaceVersions(eq("R9"), any())).thenReturn(Map.of(1, "NV1", 2, "NV7"));

        ReportImportResultDTO result = service.importPackage(packWithVersionScoped(),
                ReportPackageService.MODE_OVERWRITE);

        ArgumentCaptor<List<DatasetSaveDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(datasetService).replaceReportDatasets(eq("R9"), captor.capture());
        Map<String, String> scopeByCode = new LinkedHashMap<>();
        captor.getValue().forEach(d -> scopeByCode.put(d.getCode(), d.getVersionId()));
        assertEquals("NV7", scopeByCode.get("orders"), "包里写着只属于 v2，要落到目标环境这一版的新 id 上");
        assertNull(scopeByCode.get("items"), "没写 versionNo 的是全版本共用");
        assertNull(scopeByCode.get("ghost"), "包里没有 v9，退回全版本共用而不是挂一个不存在的版本");
        assertTrue(result.getItems().get(0).getWarnings().stream().anyMatch(w -> w.contains("ghost")),
                result.getItems().get(0).getWarnings().toString());
    }

    @Test
    @DisplayName("导入 copy：编码撞了就加后缀另建一张，两份并存")
    void importCopyRenamesCode() {
        when(reportService.getByCode("sales")).thenReturn(report("R9", "sales", "线上的销售单"));
        when(reportService.create(any())).thenReturn("R10");

        ReportImportResultDTO result = service.importPackage(pack(), ReportPackageService.MODE_COPY);

        assertEquals(1, result.getCreated());
        ArgumentCaptor<MzReport> captor = ArgumentCaptor.forClass(MzReport.class);
        verify(reportService).create(captor.capture());
        assertTrue(captor.getValue().getCode().startsWith("sales_import_"), captor.getValue().getCode());
        assertEquals(captor.getValue().getCode(), result.getItems().get(0).getCode(),
                "结果里要写出真正用上的编码，否则人找不到导进来的那张");
        verify(versionService).replaceVersions(eq("R10"), any());
    }

    @Test
    @DisplayName("导入：一张报表失败不影响别的，失败原因逐条列出来")
    void importReportsFailuresPerItem() {
        // 第一张没有版式（手工改坏的包），第二张正常
        String json = """
                {"fileType":"muzhou-report-package","reports":[
                  {"name":"坏的","code":"bad","versions":[]},
                  {"name":"好的","code":"good","versions":[{"versionNo":1,"content":"{}","isDefault":1}]}
                ]}
                """;
        when(reportService.getByCode(any())).thenReturn(null);
        when(reportService.create(any())).thenReturn("R11");

        ReportImportResultDTO result = service.importPackage(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ReportPackageService.MODE_SKIP);

        assertEquals(1, result.getFailed());
        assertEquals(1, result.getCreated());
        assertEquals("failed", result.getItems().get(0).getAction());
        assertTrue(result.getItems().get(0).getMessage().contains("没有任何版式"));
        assertEquals("created", result.getItems().get(1).getAction());
    }

    /* ------------------------------ 夹具 ------------------------------ */

    /** 一张报表：内部集 items + 公共集 orders，各绑在数据源 biz 上。 */
    private byte[] pack() {
        String json = """
                {"fileType":"muzhou-report-package","formatVersion":1,"reports":[{
                  "name":"销售单","code":"sales","type":"sheet","status":1,
                  "versions":[{"versionNo":1,"content":"{}","isDefault":1,"status":1}],
                  "datasets":[
                    {"name":"明细","code":"items","shared":false,"datasourceCode":"biz","type":"sql",
                     "sqlText":"select 1","fields":[{"fieldName":"name"}],"params":[]},
                    {"name":"订单","code":"orders","shared":true,"datasourceCode":"biz","type":"sql",
                     "sqlText":"select 2","fields":[],"params":[]}
                  ]}]}
                """;
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 两版的报表：items 全版本共用、orders 只属于 v2、ghost 标着一个包里没有的 v9。 */
    private byte[] packWithVersionScoped() {
        String json = """
                {"fileType":"muzhou-report-package","formatVersion":1,"reports":[{
                  "name":"销售单","code":"sales","type":"sheet","status":1,
                  "versions":[{"versionNo":1,"content":"{}","isDefault":1,"status":1},
                              {"versionNo":2,"content":"{}","isDefault":0,"status":1}],
                  "datasets":[
                    {"name":"明细","code":"items","shared":false,"type":"sql","sqlText":"select 1"},
                    {"name":"订单","code":"orders","shared":false,"versionNo":2,"type":"sql","sqlText":"select 2"},
                    {"name":"孤儿","code":"ghost","shared":false,"versionNo":9,"type":"sql","sqlText":"select 3"}
                  ]}]}
                """;
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private MzReport report(String id, String code, String name) {
        MzReport r = new MzReport();
        r.setId(id);
        r.setCode(code);
        r.setName(name);
        r.setType("sheet");
        r.setStatus(1);
        return r;
    }

    private MzReportVersion version(String id, int no, String content) {
        MzReportVersion v = new MzReportVersion();
        v.setId(id);
        v.setVersionNo(no);
        v.setContent(content);
        v.setIsDefault(1);
        v.setStatus(1);
        return v;
    }

    private MzDataset dataset(String id, String code, String reportId, String datasourceId) {
        MzDataset ds = new MzDataset();
        ds.setId(id);
        ds.setCode(code);
        ds.setName(code);
        ds.setReportId(reportId);
        ds.setDatasourceId(datasourceId);
        ds.setType("sql");
        ds.setStatus(1);
        return ds;
    }

    private MzDatasetField field(String id, String datasetId, String name) {
        MzDatasetField f = new MzDatasetField();
        f.setId(id);
        f.setDatasetId(datasetId);
        f.setFieldName(name);
        return f;
    }

    private MzDatasource datasource(String id, String code) {
        MzDatasource ds = new MzDatasource();
        ds.setId(id);
        ds.setCode(code);
        return ds;
    }
}
