package com.muzhou.report.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.DatasetRowsDTO;
import com.muzhou.report.dto.PageConfigDTO;
import com.muzhou.report.dto.ReportContentDTO;
import com.muzhou.report.dto.ReportParamDTO;
import com.muzhou.report.dto.RenderResultDTO;
import com.muzhou.report.dto.VersionConfigDTO;
import com.muzhou.report.engine.CachingDataFetcher;
import com.muzhou.report.engine.ReportRenderEngine;
import com.muzhou.report.entity.MzReport;
import com.muzhou.report.export.ExcelExporter;
import com.muzhou.report.export.PdfExporter;
import com.muzhou.report.export.WordExporter;
import com.muzhou.report.service.DatasetService;
import com.muzhou.report.service.ParamService;
import com.muzhou.report.service.RenderService;
import com.muzhou.report.service.ReportService;
import com.muzhou.report.service.ReportVersionService;
import com.muzhou.report.version.ReportVersionResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * 报表渲染服务实现。见 docs/CONTRACT.md §3.4。
 *
 * <p>取数通过 {@link DatasetService#fetchDataByCode} 委派给数据集模块，本类不做任何取数逻辑，
 * 只负责「取报表 -> 选版本 -> 解析 content -> 交给渲染引擎」的编排。
 *
 * <p>递给引擎的取数函数一律**绑定当前报表 id**（{@link #dataFetcher}）：数据集分公共和内部两类，
 * 内部数据集只有它所属的报表能解析出来，所以「谁在渲染」是取数的必要上下文。
 *
 * <p><b>版本在这一层落地，引擎完全不知道有这回事</b>：先按报表的版本切换规则选中一份 content
 * （{@link ReportVersionResolver}），再把它交给引擎。规则依据主接口字段时要先探一次主接口 ——
 * 那一次取数不能白取，所以取数函数外面包了一层 {@link CachingDataFetcher}，引擎随后要同一份数据
 * 直接命中缓存。
 */
@Slf4j
@Service
public class RenderServiceImpl implements RenderService {

    private final ReportService reportService;

    private final ReportVersionService versionService;

    private final DatasetService datasetService;

    /** 全局参数：渲染前并进 content 的参数定义里，见 {@link #withGlobalParams}。 */
    private final ParamService paramService;

    private final ReportRenderEngine renderEngine;

    private final ExcelExporter excelExporter;

    private final PdfExporter pdfExporter;

    private final WordExporter wordExporter;

    private final ObjectMapper objectMapper;

    private final MzProperties props;

    /** 导出的并发名额，见 {@link #withExportPermit}。 */
    private final Semaphore exportPermits;

    /**
     * 手写构造器而非 {@code @RequiredArgsConstructor}：容器里存在两个 {@link ObjectMapper} bean
     * （Spring Boot 自动配置的默认实例，以及 {@code WebConfig} 提供的 "mzObjectMapper"），
     * 必须显式 {@link Qualifier} 精确指定，而 Lombok 默认不会把字段上的 {@code @Qualifier}
     * 搬到生成的构造器参数上。
     */
    public RenderServiceImpl(ReportService reportService,
                             ReportVersionService versionService,
                             DatasetService datasetService,
                             ParamService paramService,
                             ReportRenderEngine renderEngine,
                             ExcelExporter excelExporter,
                             PdfExporter pdfExporter,
                             WordExporter wordExporter,
                             @Qualifier("mzObjectMapper") ObjectMapper objectMapper,
                             MzProperties props) {
        this.reportService = reportService;
        this.versionService = versionService;
        this.datasetService = datasetService;
        this.paramService = paramService;
        this.renderEngine = renderEngine;
        this.excelExporter = excelExporter;
        this.pdfExporter = pdfExporter;
        this.wordExporter = wordExporter;
        this.objectMapper = objectMapper;
        this.props = props;
        this.exportPermits = new Semaphore(Math.max(1, props.getExportConcurrency()), true);
    }

    @Override
    public RenderResultDTO renderReport(String reportId, Map<String, Object> params, String versionId) {
        long start = System.currentTimeMillis();
        RenderResultDTO result = renderSaved(reportId, params, versionId).result();
        log.info("渲染完成: 请求数据 {}ms → 渲染 {}ms → 合计 {}ms", result.getFetchElapsed(),
                result.getExpandElapsed(), System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public RenderResultDTO preview(String reportId, ReportContentDTO content, Map<String, Object> params,
                                   String versionId) {
        if (content == null) {
            throw new BizException("预览内容不能为空");
        }
        // 请求体里这份 content 没经过 parseContent，全局参数得自己并一次（见 withGlobalParams）
        withGlobalParams(content);
        // 免保存预览渲染的就是请求里这份 content —— 设计器画布上是哪一版，预览出来就是哪一版，
        // 不走版本选择（走了反而会拿库里存着的那一版把用户没保存的改动盖掉）
        // 引擎并行取数时会从多个线程同时写，用并发容器（下同）
        Map<String, Long> totals = new ConcurrentHashMap<>();
        // 免保存预览不选版本，也就没有那次探测取数要复用 —— 不必包记忆层
        long start = System.currentTimeMillis();
        RenderResultDTO result = renderEngine.render(content, params, dataFetcher(reportId, totals));
        fillTotal(result, content, totals);
        result.setVersionId(versionId);
        log.info("预览渲染完成: 请求数据 {}ms → 渲染 {}ms → 合计 {}ms", result.getFetchElapsed(),
                result.getExpandElapsed(), System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public List<ReportParamDTO> listParams(String reportId) {
        // 传 false：查一张不存在的报表的参数，该报错而不是凭空建一张再回一份空参数列表
        // parseContent 已经把全局参数并进去了（withGlobalParams），所以参数表单里全局参数就是普通参数
        ReportContentDTO content = parseContent(reportService.getDetail(reportId, null, false).getContent());
        return content.getParams() == null ? List.of() : content.getParams();
    }

    @Override
    public byte[] exportExcel(String reportId, Map<String, Object> params, String versionId) {
        return withExportPermit(() -> {
            long start = System.currentTimeMillis();
            Prepared p = prepareExport(reportId, params, versionId, null);
            long excelStart = System.currentTimeMillis();
            byte[] bytes = excelExporter.export(p.sheets(), p.pageConfigOf());
            log.info("导出Excel完成: 请求数据 {}ms → 渲染 {}ms → 转Excel {}ms → 合计 {}ms",
                    p.result().getFetchElapsed(), p.result().getExpandElapsed(),
                    System.currentTimeMillis() - excelStart, System.currentTimeMillis() - start);
            return bytes;
        });
    }

    /**
     * 导出的并发闸：同时最多 {@code muzhou.report.export-concurrency} 份。
     *
     * <p>导出全程在内存里（PDF 那条路峰值是 POI 对象树 + PDF 字节同时占着堆），
     * 而 {@code maxCells} 只拦得住**单次**规模。不排队的话，十个人同时导一份大报表就能把堆打满，
     * 倒下的是整个服务而不只是那几次导出。
     */
    private byte[] withExportPermit(Supplier<byte[]> task) {
        int wait = props.getExportWaitSeconds();
        boolean acquired = false;
        try {
            acquired = exportPermits.tryAcquire(wait, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("导出被中断");
        }
        if (!acquired) {
            throw new BizException("导出繁忙（已有 " + props.getExportConcurrency()
                    + " 份正在导出，等待超过 " + wait + " 秒），请稍后重试");
        }
        try {
            return task.get();
        } finally {
            exportPermits.release();
        }
    }

    @Override
    public byte[] exportPdf(String reportId, Map<String, Object> params, Integer sheetIndex, String versionId) {
        // PDF 走「先出 xlsx 再转」这一条路：页面设置已经写在 xlsx 里，转换器照着出纸，
        // 不必在 PDF 侧把 pageConfig 再实现一遍。xlsx 不落成字节 —— workbook 对象直通给转换器，
        // 省掉「序列化再解析回来」的往返（200 页的报表约 0.5s）。
        // 唯一的例外是**水印** —— xlsx 里没有水印这个概念，存不进去，只能把设置一起递下去。
        return withExportPermit(() -> {
            long start = System.currentTimeMillis();
            Prepared p = prepareExport(reportId, params, versionId, sheetIndex);
            long excelStart = System.currentTimeMillis();
            try (XSSFWorkbook wb = excelExporter.exportWorkbook(p.sheets(), p.pageConfigOf())) {
                long excelMs = System.currentTimeMillis() - excelStart;
                long pdfStart = System.currentTimeMillis();
                byte[] pdf = pdfExporter.convert(wb, p.pageConfigOf(), p.docBreaksOf(), p.docNamesOf());
                log.info("导出PDF完成: 请求数据 {}ms → 渲染 {}ms → 转Excel {}ms → 转PDF {}ms → 合计 {}ms",
                        p.result().getFetchElapsed(), p.result().getExpandElapsed(),
                        excelMs, System.currentTimeMillis() - pdfStart,
                        System.currentTimeMillis() - start);
                return pdf;
            } catch (IOException e) {
                throw new BizException("PDF 导出失败: " + e.getMessage());
            }
        });
    }

    @Override
    public byte[] exportWord(String reportId, Map<String, Object> params, String versionId) {
        return withExportPermit(() -> {
            long start = System.currentTimeMillis();
            Prepared p = prepareExport(reportId, params, versionId, null);
            // 逐格搬成一张真正的 Word 表格（可继续编辑，横向只取到打印区域）。
            // 纸张/方向/页边距按 sheet 各自生效（docx 一节一套，每张 sheet 一节），
            // 但**水印整份文档只有一套**（Word 的水印是页眉里的图形），按第一张 sheet 的取
            long excelStart = System.currentTimeMillis();
            try (XSSFWorkbook wb = excelExporter.exportWorkbook(p.sheets(), p.pageConfigOf())) {
                long excelMs = System.currentTimeMillis() - excelStart;
                long wordStart = System.currentTimeMillis();
                byte[] word = wordExporter.convert(wb, p.pageConfigOf().apply(0), p.docBreaksOf());
                log.info("导出Word完成: 请求数据 {}ms → 渲染 {}ms → 转Excel {}ms → 转Word {}ms → 合计 {}ms",
                        p.result().getFetchElapsed(), p.result().getExpandElapsed(),
                        excelMs, System.currentTimeMillis() - wordStart,
                        System.currentTimeMillis() - start);
                return word;
            } catch (IOException e) {
                throw new BizException("Word 导出失败: " + e.getMessage());
            }
        });
    }

    /**
     * 一次「选版本 + 渲染」后交给导出器的那套东西：结果 sheet + 与**这份 sheet 序**对齐的
     * 几个按 sheet 取值的函数。
     *
     * <p>几样必须一起传：下游的 PDF/Word 转换器要照着同一份打印设置分页、画水印，也要知道
     * 页码在哪里重编（{@code mzDocBreaks}，见 {@code engine/ReportRenderEngine#markDocBreaks}）、
     * 每份单据叫什么（{@code mzDocNames}，页头页尾里的 {@code ${sheet}}），
     * 而只导一张 sheet 时导出下标 0 未必是渲染结果里的下标 0。
     */
    private record Prepared(List<Map<String, Object>> sheets, IntFunction<PageConfigDTO> pageConfigOf,
                            IntFunction<List<Integer>> docBreaksOf,
                            IntFunction<List<String>> docNamesOf,
                            RenderResultDTO result) {
    }

    /** 一次「选版本 + 渲染」的产物：结果，以及**基准**那份 content（取打印设置时兜底要用）。 */
    private record Rendered(RenderResultDTO result, ReportContentDTO content) {
    }

    private Prepared prepareExport(String reportId, Map<String, Object> params, String versionId,
                                   Integer sheetIndex) {
        Rendered rendered = renderSaved(reportId, params, versionId);
        RenderResultDTO result = rendered.result();
        List<Map<String, Object>> sheets = result.getSheets();
        // 越界当没选：预览页切到第 3 张后又改了查询条件，这一次渲染出来可能就没那么多 sheet 了，
        // 与其报错不如整本出，用户拿到的仍是一份能打的 PDF
        boolean pick = sheetIndex != null && sheets != null && sheetIndex >= 0 && sheetIndex < sheets.size();

        // 导出的 xlsx 直接带上打印设置（纸张/方向/页边距/缩放/打印区域）；
        // 打印设置可以按 sheet 单独设，所以按下标逐个取，而不是整本用同一份
        IntFunction<PageConfigDTO> pageConfigOf = pick
                // 裁完只剩一张，xlsx 里的下标恒为 0，得换回它在渲染结果里的那张去取设置
                ? i -> pageConfigAt(rendered, sheetIndex)
                : i -> pageConfigAt(rendered, i);
        List<Map<String, Object>> exported = pick ? List.of(sheets.get(sheetIndex)) : sheets;
        // 「页码从这一行起重编」存不进 xlsx（Excel 只有 sheet 级的起始页号），和水印一样
        // 只能顺着方法签名递给 PDF / Word 那两条路
        IntFunction<List<Integer>> docBreaksOf = i -> docBreaksAt(exported, i);
        // 每份单据叫什么同样存不进 xlsx（Excel 的 &A 只有工作表名一个值），一起递下去
        IntFunction<List<String>> docNamesOf = i -> docNamesAt(exported, i);
        return new Prepared(exported, pageConfigOf, docBreaksOf, docNamesOf, result);
    }

    /** 第 {@code i} 张导出 sheet 上的 {@code mzDocBreaks}（每份单据的起始行）；普通报表没有这一项。 */
    @SuppressWarnings("unchecked")
    private List<Integer> docBreaksAt(List<Map<String, Object>> sheets, int i) {
        if (sheets == null || i < 0 || i >= sheets.size()) {
            return List.of();
        }
        Object breaks = sheets.get(i).get("mzDocBreaks");
        return breaks instanceof List<?> list ? (List<Integer>) list : List.of();
    }

    /**
     * 第 {@code i} 张导出 sheet 上的 {@code mzDocNames}（每份单据的名字，与 {@code mzDocBreaks}
     * 一一对应）；只有「每条数据一页」拼出来的 sheet 有，其余为空。
     */
    @SuppressWarnings("unchecked")
    private List<String> docNamesAt(List<Map<String, Object>> sheets, int i) {
        if (sheets == null || i < 0 || i >= sheets.size()) {
            return List.of();
        }
        Object names = sheets.get(i).get("mzDocNames");
        return names instanceof List<?> list ? (List<String>) list : List.of();
    }

    /**
     * 第 {@code i} 张**结果** sheet 生效的打印设置。
     *
     * <p>优先认引擎随结果一起给出的那份（{@code RenderResult.sheetPageConfigs}）—— 结果 sheet 与模板
     * 不是一一对应的（拆分 N 倍、拼接又合并），逐行选版本时更是各出自不同版本的模板，只有引擎自己
     * 说得清。老结果没有这一项时才退回 {@code ReportContentDTO#pageConfigOfSheet} 那条按
     * {@code mzTemplateIndex} 推算的路。
     */
    private PageConfigDTO pageConfigAt(Rendered rendered, int i) {
        List<PageConfigDTO> configs = rendered.result().getSheetPageConfigs();
        if (configs != null && i >= 0 && i < configs.size() && configs.get(i) != null) {
            return configs.get(i);
        }
        List<Map<String, Object>> sheets = rendered.result().getSheets();
        Map<String, Object> sheet = sheets != null && i >= 0 && i < sheets.size() ? sheets.get(i) : null;
        return rendered.content().pageConfigOfSheet(sheet, i);
    }

    /**
     * 渲染一张**已保存的**报表：选版本 -> 渲染 -> 补总条数与版本信息。
     *
     * <p>总数是取数时顺手记下来的（见 {@link #dataFetcher}）：引擎的取数函数只还行数据，
     * 总数带不回来，所以用一个只活在这一次渲染里的 map 接着，渲染完再按
     * {@code content.primaryDataset} 挑出那一个。
     */
    private Rendered renderSaved(String reportId, Map<String, Object> params, String versionId) {
        // getDetail 会顺手做懒迁移，并把**默认版本**的 content 装进 report.content。
        // 最后那个 false = 报表不存在时不要凭空建一张：渲染/导出一张不存在的报表应当报错，
        // 建出来只会让「报表 id 写错了」悄无声息地过去，还顺手往库里留一行垃圾
        MzReport report = reportService.getDetail(reportId, null, false);
        ReportContentDTO base = parseContent(report.getContent());
        VersionConfigDTO config = parseVersionConfig(report.getVersionConfig());
        List<ReportVersionResolver.Candidate> candidates = versionService.candidates(reportId);

        Map<String, Long> totals = new ConcurrentHashMap<>();
        // 探测用它、引擎也用它：同一个 (code, params) 第二次直接命中缓存，主接口只被打一次。
        // **只记主接口这一个数据集** —— 别的数据集上游本来就去过重，而父子关联的子表每行参数都不同、
        // 永远命中不了第二次，全记只会把它们留在内存里到渲染结束（见 CachingDataFetcher 的类注释）
        BiFunction<String, Map<String, Object>, List<Map<String, Object>>> fetcher =
                CachingDataFetcher.wrap(dataFetcher(reportId, totals), primaryCode(base));

        RenderResultDTO result;
        ReportContentDTO content;
        if (perRowVersioning(base, config, candidates, versionId)) {
            // 逐行选版本：整份报表不定版，每条数据按自己的判定值挑一份版式
            content = base;
            Function<Map<String, Object>, ReportContentDTO> picker =
                    perRowPicker(reportId, report.getVersionId(), base, config, candidates);
            result = renderEngine.render(content, params, fetcher, picker);
            result.setVersionMatch("按每条数据的[" + config.getField() + "]逐行选版式");
        } else {
            // 整份报表选一版。探测取数用的参数要和引擎随后 mergeParams 出来的那份**一致**，
            // 否则缓存 key 对不上，主接口会被打第二遍
            Map<String, Object> probeParams = renderEngine.mergeParams(base.getParams(), params);
            ReportVersionResolver.Resolution resolution = ReportVersionResolver.resolve(
                    candidates, versionId, config, base.getPrimaryDataset(), probeParams, fetcher);
            content = contentOf(reportId, resolution.version().id(), report.getVersionId(), base);
            result = renderEngine.render(content, params, fetcher);
            result.setVersionId(resolution.version().id());
            result.setVersionNo(resolution.version().versionNo());
            result.setVersionName(resolution.version().name());
            result.setVersionMatch(resolution.reason());
        }
        fillTotal(result, content, totals);
        return new Rendered(result, content);
    }

    /**
     * 要不要**逐行**选版本：「打印 3 月到 9 月的一批单据，每张按自己的下单日期用对应版式」。
     *
     * <p>四个条件缺一不可：按条拆分（一条数据一张单据，整份定一版就没意义了）、判定依据是
     * 主接口字段（参数/当日对每一行都一样，逐行选是徒劳）、确实有得选（多个启用版本）、
     * 没有显式指定版本（设计器点开哪一版就是哪一版，不许再逐行换）。
     */
    private boolean perRowVersioning(ReportContentDTO base, VersionConfigDTO config,
                                     List<ReportVersionResolver.Candidate> candidates, String versionId) {
        if (StringUtils.hasText(versionId) || !base.splitByRow()) {
            return false;
        }
        if (!VersionConfigDTO.SOURCE_FIELD.equals(config.getSource())
                || !StringUtils.hasText(config.getField())) {
            return false;
        }
        return candidates.stream().filter(ReportVersionResolver.Candidate::enabled).count() > 1;
    }

    /**
     * 逐行选版本用的那个 picker：拿这一行的判定字段值去选版本，再把那一版的 content 给引擎。
     *
     * <p>按版本 id 缓存已解析的 content —— 200 条单据只会解析出用到的那两三份。
     */
    private Function<Map<String, Object>, ReportContentDTO> perRowPicker(
            String reportId, String defaultVersionId, ReportContentDTO base,
            VersionConfigDTO config, List<ReportVersionResolver.Candidate> candidates) {
        Map<String, ReportContentDTO> cache = new HashMap<>();
        // 默认版本那份已经解析过了（就是 base），别再解析一遍
        if (StringUtils.hasText(defaultVersionId)) {
            cache.put(defaultVersionId, base);
        }
        String field = config.getField();
        return row -> {
            // 行数据的 key 统一是小写（见 DatasetServiceImpl），但字段名可能是用户按原样填的
            Object raw = row == null ? null
                    : row.containsKey(field) ? row.get(field) : row.get(field.toLowerCase());
            ReportVersionResolver.Resolution r =
                    ReportVersionResolver.resolveByValue(candidates, raw, config);
            return cache.computeIfAbsent(r.version().id(),
                    id -> parseContent(versionService.detail(reportId, id).getContent()));
        };
    }

    /** 选中的那一版的 content：正好是默认版本时直接用已经解析好的那份，不再多查一次库。 */
    private ReportContentDTO contentOf(String reportId, String versionId, String defaultVersionId,
                                       ReportContentDTO base) {
        if (versionId != null && versionId.equals(defaultVersionId)) {
            return base;
        }
        return parseContent(versionService.detail(reportId, versionId).getContent());
    }

    /** 要记忆的那个 code：主接口。没设主接口就不会有探测取数，返回空串表示「什么都不必记」。 */
    private String primaryCode(ReportContentDTO base) {
        return StringUtils.hasText(base.getPrimaryDataset()) ? base.getPrimaryDataset() : "";
    }

    private void fillTotal(RenderResultDTO result, ReportContentDTO content, Map<String, Long> totals) {
        if (StringUtils.hasText(content.getPrimaryDataset())) {
            result.setTotal(totals.get(content.getPrimaryDataset()));
        }
    }

    /**
     * 引擎要的取数函数：{@code (datasetCode, params) -> rows}，把报表 id 绑进去。
     *
     * <p>引擎刻意只认这个函数签名（不依赖 Spring 与数据库），所以「内部数据集按报表隔离」
     * 与「分页型数据集的总条数」这两件事都落在这一层里，引擎本身两件都不必知道。
     *
     * @param totals 取数过程中收集各数据集总条数的口袋（集合型数据集不写入）
     */
    private BiFunction<String, Map<String, Object>, List<Map<String, Object>>> dataFetcher(
            String reportId, Map<String, Long> totals) {
        // 数据集定义与它的参数定义在一次渲染里恒定不变，解析一遍就够 ——
        // 不记的话每取一行数据都要再打 2~3 条元数据查询（getByCode 最多 2 条 + listParams 1 条），
        // perRow 200 条数据 × 3 个数据集就是一千多条。只活这一次渲染，跟着闭包一起回收；
        // 引擎并行取数时多个线程同时进来，所以是并发容器
        Map<String, DatasetService.ResolvedDataset> defs = new ConcurrentHashMap<>();
        return (code, params) -> {
            DatasetService.ResolvedDataset resolved =
                    defs.computeIfAbsent(code, c -> datasetService.resolve(reportId, c));
            DatasetRowsDTO fetched = datasetService.fetchRows(resolved, params);
            if (fetched.getTotal() != null) {
                totals.put(code, fetched.getTotal());
            }
            return fetched.getRows();
        };
    }

    /** 把版本内容的 JSON 字符串解析成 {@link ReportContentDTO}。 */
    private ReportContentDTO parseContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            throw new BizException("报表内容为空，请先在设计器中保存");
        }
        ReportContentDTO content;
        try {
            content = objectMapper.readValue(contentJson, ReportContentDTO.class);
        } catch (Exception e) {
            log.error("解析报表 content 失败", e);
            throw new BizException("报表内容格式非法: " + e.getMessage());
        }
        // 并全局参数放在 try 外面：它要查库，出错跟「content 格式非法」是两回事，别混成一句话
        return withGlobalParams(content);
    }

    /**
     * 把全局参数并进这份 content 的参数定义里。见 CONTRACT §5「参数值从哪来」。
     *
     * <p><b>合并只在这一层做，渲染引擎不知道有全局参数这回事</b> —— 它照旧只看
     * {@code content.params}，于是默认值填充、必填校验、参数表单、`${}` 取值全都不必各写一遍。
     *
     * <p>挂在 {@link #parseContent} 上是因为它是「库里存的 content」进本类的唯一入口
     * （渲染、逐行选版本的换版、{@link #listParams} 都经过它）；免保存预览的 content 来自请求体，
     * 不走这里，所以 {@link #preview} 里另外调了一次。**两处，别漏。**
     *
     * <p>停用的全局参数不会出现在这里（{@link ParamService#listDefinitions} 只取启用的）。
     */
    private ReportContentDTO withGlobalParams(ReportContentDTO content) {
        if (content == null) {
            return null;
        }
        content.setParams(ReportParamDTO.merge(paramService.listDefinitions(), content.getParams()));
        return content;
    }

    /** 版本切换规则；没配过（老报表）或写坏了都退回默认规则，不让它拦住渲染。 */
    private VersionConfigDTO parseVersionConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return VersionConfigDTO.defaults();
        }
        try {
            VersionConfigDTO cfg = objectMapper.readValue(json, VersionConfigDTO.class);
            return cfg == null ? VersionConfigDTO.defaults() : cfg;
        } catch (Exception e) {
            log.warn("版本切换规则格式非法，按默认规则处理: {}", json, e);
            return VersionConfigDTO.defaults();
        }
    }
}
