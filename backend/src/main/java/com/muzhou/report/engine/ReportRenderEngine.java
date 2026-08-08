package com.muzhou.report.engine;

import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.dto.DatasetLinkDTO;
import com.muzhou.report.dto.PageConfigDTO;
import com.muzhou.report.dto.RenderResultDTO;
import com.muzhou.report.dto.ReportContentDTO;
import com.muzhou.report.dto.ReportParamDTO;
import com.muzhou.report.engine.model.GridCell;
import com.muzhou.report.engine.model.RenderGrid;
import com.muzhou.report.engine.model.SheetTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 渲染引擎入口：编排「解析模板 → 取数 → 扩展 → 输出」。
 *
 * <p>取数刻意抽象成 {@link BiFunction}，而不是直接依赖 DatasetService，
 * 这样引擎本身可以脱离 Spring 与数据库做纯单元测试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportRenderEngine {

    /**
     * 按行拆分的条数上限（{@code perRow} / {@code perRowPage} 共用）。
     *
     * <p>拆分是整份模板渲染 N 遍，成本随行数线性涨，一个没筛过的数据集能拆出几千份
     * 直接把内存和 Excel 都撑爆。宁可报错让人先加参数筛数据，也不要让请求卡死在那儿。
     */
    private static final int MAX_SPLIT_ROWS = 200;

    private final TemplateParser templateParser;
    private final ExpandProcessor expandProcessor;
    private final MzProperties props;

    /** 并行取数共用的线程池，懒建（见 {@link #fetchPool()}）。 */
    private volatile ExecutorService fetchPool;

    /**
     * 渲染。
     *
     * @param content     报表内容
     * @param inputParams 用户传入的参数值
     * @param dataFetcher 取数函数：(datasetCode, params) -> 行数据
     */
    public RenderResultDTO render(ReportContentDTO content,
                                  Map<String, Object> inputParams,
                                  BiFunction<String, Map<String, Object>, List<Map<String, Object>>> dataFetcher) {
        return render(content, inputParams, dataFetcher, null);
    }

    /**
     * 渲染，并允许 {@code perRow} / {@code perRowPage} **逐行换版式**
     * （「打印 3 月到 9 月的一批单据，每张按自己的下单日期用对应版式」）。
     *
     * <p>做法照旧是**换函数**：引擎不知道「版本」是什么，只知道有人能按数据行给它一份 content。
     * 基准 content（决定 {@code splitMode} / {@code primaryDataset} / {@code datasetLinks} / 参数）
     * 恒取传进来的这一份，逐行换掉的只有**模板与打印设置**。
     *
     * @param versionPicker 按主接口的某一行给出该用哪份 content，null = 全都用同一份（默认）
     */
    public RenderResultDTO render(ReportContentDTO content,
                                  Map<String, Object> inputParams,
                                  BiFunction<String, Map<String, Object>, List<Map<String, Object>>> dataFetcher,
                                  Function<Map<String, Object>, ReportContentDTO> versionPicker) {
        long start = System.currentTimeMillis();
        if (content == null) {
            throw new BizException("报表内容为空，请先在设计器中保存");
        }

        Map<String, Object> params = mergeParams(content.getParams(), inputParams);
        List<SheetTemplate> templates = templateParser.parse(content);
        if (templates.isEmpty()) {
            throw new BizException("报表模板为空，请先在设计器中设计报表");
        }

        if (content.splitByRow()) {
            return renderPerRow(content, templates, params, dataFetcher, versionPicker, start);
        }

        // 父子关联只是给取数函数套一层（子表 -> 先查主表，主表每行查一次子表再拼起来），
        // 下面的扩展/公式/格式化一步不知道有这回事
        long fetchStart = System.currentTimeMillis();
        Map<String, List<Map<String, Object>>> datasets = fetchDatasets(templates, params,
                LinkedDataFetcher.wrap(content.getDatasetLinks(), dataFetcher, props.getMaxLinkRows()));
        long fetched = System.currentTimeMillis();

        RenderResultDTO result = new RenderResultDTO();
        List<Map<String, Object>> outSheets = new ArrayList<>();
        List<PageConfigDTO> pageConfigs = new ArrayList<>();
        for (SheetTemplate st : templates) {
            RenderGrid grid = expandProcessor.process(st, datasets, params);
            outSheets.add(toSheet(st, grid));
            pageConfigs.add(content.pageConfigOf(st.getSheetIndex()));
        }
        result.setSheets(outSheets);
        result.setSheetPageConfigs(pageConfigs);
        result.setElapsed(System.currentTimeMillis() - start);
        // 分段计时：慢在取数（IO，看并行度/数据集本身）还是扩展（CPU，看数据量），一眼分得开。
        // 不在这里打日志 —— RenderServiceImpl 把它和转 Excel/PDF/Word 连成一条耗时链一起打
        result.setFetchElapsed(fetched - fetchStart);
        result.setExpandElapsed(System.currentTimeMillis() - fetched);
        if (datasets.values().stream().allMatch(List::isEmpty) && !datasets.isEmpty()) {
            result.setMessage("数据集未返回任何数据");
        }
        return result;
    }

    /**
     * 「每条数据一个 sheet」：主接口有几行，整份模板就渲染几遍。
     *
     * <p>做法是**换掉取数函数**而不是改扩展逻辑 —— 第 i 遍渲染时，主接口只返回第 i 行，
     * 其它数据集照常返回全量（且只取一次，缓存起来复用，否则 100 条数据会把从表接口打 100 遍）。
     * 于是「一行数据一张单据」在模板里就是普通的取数，不用为它写第二套扩展规则。
     *
     * <p>模板本身有多张 sheet 时，每一行都会把这几张都渲染一遍（N 行 × M 张），
     * 输出顺序是**行优先**（r0t0, r0t1, r1t0, r1t1…），即一条数据的几张挨在一起 ——
     * {@link SheetConcat} 原样照抄这个顺序摞。
     *
     * <p>{@code perRowPage} 走的也是这段代码，只是出口处多一道拼接（{@link #concatIfNeeded}）。
     *
     * <p>配了父子关联时，关联要套在换掉的这个取数函数**外面**：子表向里问主表要数据，
     * 拿到的正好是当前这一行，于是「这张单据的明细」自然只查这一行的。也因此**子表不能进
     * 上面那个跨行复用的缓存** —— 它每张 sheet 都不一样，缓存下来就全串成第一条的明细了。
     *
     * <p>{@code versionPicker} 不为空时**每一行还会各自换一份模板**（逐行选版本）：这一批单据
     * 跨了改版时点，3 月的那几张用旧版式、9 月的用新版式。换的只有模板与打印设置，
     * 上面那套取数逻辑一个字没动 —— 模板按 content 实例缓存，同一版只 parse 一次。
     */
    private RenderResultDTO renderPerRow(ReportContentDTO content, List<SheetTemplate> templates,
                                         Map<String, Object> params,
                                         BiFunction<String, Map<String, Object>, List<Map<String, Object>>> dataFetcher,
                                         Function<Map<String, Object>, ReportContentDTO> versionPicker,
                                         long start) {
        String primary = content.getPrimaryDataset();
        List<DatasetLinkDTO> links = content.getDatasetLinks();
        // 主接口自己也可能是别人的子表，所以顶层这次取数照样走带父子关联的那份
        long fetchStart = System.currentTimeMillis();
        List<Map<String, Object>> rows = fetchOne(primary, params,
                LinkedDataFetcher.wrap(links, dataFetcher, props.getMaxLinkRows()));
        long primaryFetched = System.currentTimeMillis();

        // 主接口一条数据都没有：退回普通渲染出一份空模板，比给个没有 sheet 的工作簿友好
        if (rows.isEmpty()) {
            Map<String, List<Map<String, Object>>> empty = fetchDatasets(templates, params,
                    LinkedDataFetcher.wrap(links, dataFetcher, props.getMaxLinkRows()));
            RenderResultDTO result = new RenderResultDTO();
            List<Map<String, Object>> outSheets = new ArrayList<>();
            List<SheetTemplate> sheetTemplates = new ArrayList<>();
            List<PageConfigDTO> pageConfigs = new ArrayList<>();
            List<Integer> docIndexes = new ArrayList<>();
            for (SheetTemplate st : templates) {
                outSheets.add(toSheet(st, expandProcessor.process(st, empty, params)));
                sheetTemplates.add(st);
                pageConfigs.add(content.pageConfigOf(st.getSheetIndex()));
                // 一条数据都没有，那份空模板整个算作一份单据
                docIndexes.add(0);
            }
            // 每个模板正好一份，拼接是恒等变换，不必为这条早返回路径写特例
            concatIfNeeded(content, result, outSheets, sheetTemplates, pageConfigs, docIndexes, null);
            result.setElapsed(System.currentTimeMillis() - start);
            result.setFetchElapsed(primaryFetched - fetchStart);
            result.setExpandElapsed(System.currentTimeMillis() - primaryFetched);
            result.setMessage("主接口[" + primary + "]未返回数据，只输出了一份空模板");
            return result;
        }
        if (rows.size() > MAX_SPLIT_ROWS) {
            throw new BizException("按条拆分最多 " + MAX_SPLIT_ROWS + " 条，主接口["
                    + primary + "]返回了 " + rows.size() + " 条，请先用报表参数把数据筛小");
        }

        // 非主接口的数据集每份 sheet 都一样，取一次就够。并行取数会从多个线程同时进来，
        // 所以要用并发容器 + computeIfAbsent（同一个 code 只真取一次）
        Map<String, List<Map<String, Object>>> shared = new ConcurrentHashMap<>();
        // 但子表不行：它是被主表这一行的字段值查出来的，每份 sheet 各不相同，缓存下来就全串了
        Set<String> childCodes = LinkedDataFetcher.childCodes(links);
        List<Map<String, Object>> outSheets = new ArrayList<>();
        // 与 outSheets 一一对应：这一份出自哪张模板、该用哪份打印设置。
        // 逐行换版式之后「结果 sheet 数 = 模板张数 × N」这个前提没了（各版模板张数可以不同），
        // 靠下标取模再也算不回去，只能一路记着
        List<SheetTemplate> sheetTemplates = new ArrayList<>();
        List<PageConfigDTO> pageConfigs = new ArrayList<>();
        // 这一份属于第几条数据（= 第几份单据）。页码按单据重编就靠它，见 markDocBreaks
        List<Integer> docIndexes = new ArrayList<>();
        // 这一份所属单据叫什么（= sheet 名去掉模板后缀与去重后缀那一层）。拼成一张之后
        // 页头页尾里的 ${sheet} 认它，见 SheetConcat 的 mzDocNames
        List<String> docNames = new ArrayList<>();
        Set<String> usedNames = new LinkedHashSet<>();
        // 同一版只 parse 一次：按 content 实例认（picker 每次给的是同一个缓存实例）
        Map<ReportContentDTO, List<SheetTemplate>> templateCache = new IdentityHashMap<>();
        templateCache.put(content, templates);

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            // 这一行该用哪一版版式。基准 content 仍是传进来的那份（splitMode / primaryDataset /
            // datasetLinks / 参数都以它为准），换掉的只有模板与打印设置
            ReportContentDTO rowContent = pick(versionPicker, row, content);
            List<SheetTemplate> rowTemplates = templateCache.computeIfAbsent(rowContent, templateParser::parse);
            if (rowTemplates.isEmpty()) {
                throw new BizException("第 " + (i + 1) + " 条数据选中的版式是空模板");
            }
            BiFunction<String, Map<String, Object>, List<Map<String, Object>>> perRowFetcher = (code, p) -> {
                if (primary.equals(code)) {
                    return List.of(row);
                }
                if (childCodes.contains(code)) {
                    return fetchOne(code, p, dataFetcher);
                }
                return shared.computeIfAbsent(code, c -> fetchOne(c, p, dataFetcher));
            };
            // 关联套在 perRowFetcher **外面**：子表向里问主表要数据时，拿到的是当前这一行，
            // 于是「这张单据的明细」自然就只查这一行的
            Map<String, List<Map<String, Object>>> datasets = fetchDatasets(templates, params,
                    LinkedDataFetcher.wrap(links, perRowFetcher, props.getMaxLinkRows()));

            // 这一条数据（= 这份单据）叫什么：sheet 名与页头页尾里的 ${sheet} 共用这一个值
            String docName = docName(row, content.getSheetNameField(), i);
            for (SheetTemplate st : rowTemplates) {
                Map<String, Object> sheet = toSheet(st, expandProcessor.process(st, datasets, params));
                // id / order / status 必须重新编：模板的那份在 N 个副本之间会撞车
                sheet.put("id", st.getId() + "_" + (i + 1));
                sheet.put("order", outSheets.size());
                sheet.put("status", outSheets.isEmpty() ? 1 : 0);
                sheet.put("name", splitSheetName(docName, st, rowTemplates.size(), usedNames));
                outSheets.add(sheet);
                sheetTemplates.add(st);
                // 打印设置取**这一行选中的那一版**的（模板下标是那一版里的下标）
                pageConfigs.add(rowContent.pageConfigOf(st.getSheetIndex()));
                docIndexes.add(i);
                docNames.add(docName);
            }
        }

        RenderResultDTO result = new RenderResultDTO();
        concatIfNeeded(content, result, outSheets, sheetTemplates, pageConfigs, docIndexes, docNames);
        result.setElapsed(System.currentTimeMillis() - start);
        // 分段计时：主接口那一口 vs 逐条渲染那一段（含每条自己的其它取数与扩展）。
        // 日志由 RenderServiceImpl 连成一条耗时链统一打
        result.setFetchElapsed(primaryFetched - fetchStart);
        result.setExpandElapsed(System.currentTimeMillis() - primaryFetched);
        return result;
    }

    /** 这一行用哪份 content：没有 picker（或它给了个空）就还用基准那份。 */
    private ReportContentDTO pick(Function<Map<String, Object>, ReportContentDTO> versionPicker,
                                  Map<String, Object> row, ReportContentDTO fallback) {
        if (versionPicker == null) {
            return fallback;
        }
        ReportContentDTO picked = versionPicker.apply(row);
        return picked == null ? fallback : picked;
    }

    /**
     * {@code perRowPage}：把拆出来的那一摞 sheet 首尾相接拼起来（顺序即行优先顺序：
     * 一条数据的几张模板挨着，然后才是下一条数据）。**打印设置不同的两份不拼在一起**
     * —— 一张 sheet 只放得下一套页面设置，硬拼的话第 2 张模板设的横向会被第 1 张的纵向盖掉。
     *
     * <p>拆分逻辑一行没改 —— 这里只在**出口**上做一道坐标搬移，见 {@link SheetConcat}。
     * 拼完的单元格数照样要过一次上限：拆成多张 sheet 时是每张各自受
     * {@code ExpandProcessor} 那道限制约束的，拼成一张之后总量翻了 N 倍，那道限制就管不到了。
     */
    private void concatIfNeeded(ReportContentDTO content, RenderResultDTO result,
                                List<Map<String, Object>> outSheets,
                                List<SheetTemplate> sheetTemplates,
                                List<PageConfigDTO> pageConfigs,
                                List<Integer> docIndexes,
                                List<String> docNames) {
        if (!content.concatPerRow()) {
            markDocBreaks(outSheets, docIndexes);
            result.setSheets(outSheets);
            result.setSheetPageConfigs(pageConfigs);
            return;
        }
        int cells = 0;
        for (Map<String, Object> sheet : outSheets) {
            if (sheet.get("celldata") instanceof List<?> cd) {
                cells += cd.size();
            }
        }
        if (cells > props.getMaxCells()) {
            throw new BizException("拼接结果过大（超过 " + props.getMaxCells()
                    + " 个单元格），请先用报表参数把数据筛小");
        }
        // 打印设置一并传进去：一张 sheet 只放得下一套，设置不同的两份不能拼在一起。
        // 每一份属于第几条数据也要传：拼成一张之后，「页码从这里重编」只能靠行号表达（mzDocBreaks）。
        // 名字同理 —— 拼完整张 sheet 只剩一个名字，页头页尾里的 ${sheet} 得另有出处（mzDocNames）
        SheetConcat.Concated concated = SheetConcat.concat(outSheets, sheetTemplates, pageConfigs,
                docIndexes, docNames);
        result.setSheets(concated.sheets());
        // 每组的打印设置 = 该组第一份的那份（组里各份的设置本来就相同，不同才会另起一组）
        List<PageConfigDTO> merged = new ArrayList<>(concated.firstIndexes().size());
        for (int idx : concated.firstIndexes()) {
            merged.add(pageConfigs.get(idx));
        }
        result.setSheetPageConfigs(merged);
    }

    /**
     * 给拆出来的每张 sheet 挂上 {@code mzDocBreaks}：该 sheet 内**第几行起是一份新单据**
     * （页码从那里重编）。{@code perRow} 一份单据就是一张（或连着的几张）sheet，所以值只可能是
     * {@code [0]}（这张起一份新单据）或 {@code []}（延续上一张，比如同一条数据的第 2 张模板）。
     *
     * <p>为什么要这个：页头页尾里的 {@code ${page}} / {@code ${pages}} 在「一条数据一张单据」时
     * 说的是**这份单据的第几页 / 共几页**，而不是整个工作簿的。整份连续编号的话，第 7 张单据上
     * 印的是「第 7 页 共 20 页」—— 单据是一份份发出去的，那个数字对收到的人没有意义。
     *
     * <p>普通输出（{@code single}）不挂这一项，下游据此走「整份连续编号」的老路。
     */
    private void markDocBreaks(List<Map<String, Object>> sheets, List<Integer> docIndexes) {
        if (docIndexes == null || docIndexes.size() != sheets.size()) {
            return;
        }
        for (int i = 0; i < sheets.size(); i++) {
            boolean starts = i == 0 || !docIndexes.get(i).equals(docIndexes.get(i - 1));
            sheets.get(i).put("mzDocBreaks", starts ? List.of(0) : List.of());
        }
    }

    /**
     * 这一条数据（= 这份单据）叫什么：取该行指定字段的值，取不到就用「第 n 条」。
     *
     * <p>两个模式共用这一个值，也就共用 {@code sheetNameField} 这一项配置：{@code perRow} 拿它
     * 当 sheet 名（再补模板名与去重后缀，见 {@link #splitSheetName}），{@code perRowPage} 拼成
     * 一张之后 sheet 名说不了话，拿它当页头页尾里 {@code ${sheet}} 的值（{@code mzDocNames}）。
     */
    private String docName(Map<String, Object> row, String field, int rowIndex) {
        String base = fieldText(row, field);
        return base.isEmpty() ? "第" + (rowIndex + 1) + "条" : base;
    }

    /**
     * 拆出来的 sheet 叫什么：单据名 + 必要的后缀。
     *
     * <p>模板有多张 sheet 时补上模板名（`订单A-明细` / `订单A-汇总`），否则一行拆出来的几张
     * 会同名。重名再挂 (2)(3) —— 字段值本来就可能重复，而 Excel 不允许同名工作表。
     */
    private String splitSheetName(String docName, SheetTemplate st,
                                  int templateCount, Set<String> used) {
        String base = docName;
        if (templateCount > 1) {
            base = base + "-" + st.getName();
        }
        String unique = base;
        for (int n = 2; used.contains(unique); n++) {
            unique = base + "(" + n + ")";
        }
        used.add(unique);
        return unique;
    }

    /** 行数据的 key 统一是小写（见 DatasetServiceImpl），但字段名可能是用户按原样填的，两种都试。 */
    private String fieldText(Map<String, Object> row, String field) {
        if (row == null || field == null || field.isBlank()) {
            return "";
        }
        Object v = row.containsKey(field) ? row.get(field) : row.get(field.toLowerCase());
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 单个数据集取数，异常包装成能看出是哪个数据集出的问题。 */
    private List<Map<String, Object>> fetchOne(
            String code, Map<String, Object> params,
            BiFunction<String, Map<String, Object>, List<Map<String, Object>>> dataFetcher) {
        try {
            List<Map<String, Object>> rows = dataFetcher.apply(code, params);
            return rows == null ? List.of() : rows;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("数据集取数失败: {}", code, e);
            throw new BizException("数据集[" + code + "]取数失败: " + e.getMessage());
        }
    }

    /**
     * 合并参数：默认值兜底 + 必填校验。
     */
    public Map<String, Object> mergeParams(List<ReportParamDTO> defs, Map<String, Object> input) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (input != null) {
            params.putAll(input);
        }
        if (defs == null) {
            return params;
        }
        for (ReportParamDTO def : defs) {
            if (def.getName() == null || def.getName().isBlank()) {
                continue;
            }
            Object v = params.get(def.getName());
            if (isEmpty(v)) {
                v = def.getDefaultValue();
                params.put(def.getName(), v);
            }
            if (Boolean.TRUE.equals(def.getRequired()) && isEmpty(v)) {
                String label = def.getText() == null || def.getText().isBlank() ? def.getName() : def.getText();
                throw new BizException("缺少必填参数: " + label);
            }
        }
        return params;
    }

    private boolean isEmpty(Object v) {
        return v == null || (v instanceof CharSequence cs && cs.toString().isBlank());
    }

    /**
     * 收集模板中用到的数据集并逐个取数（同一 code 只取一次）。
     *
     * <p>多个数据集的 SQL / 接口互相独立，逐个取是纯串行的 IO 等待，所以多于一个时**并行取**
     * （{@code muzhou.report.fetch-parallelism}，<=1 退回串行）。配了父子关联时不并行：
     * {@link LinkedDataFetcher} 的关联取数有先后依赖（先主后子、主表逐行查子表）、整个在锁里，
     * 提交到线程池也只是排队，白占线程。
     */
    private Map<String, List<Map<String, Object>>> fetchDatasets(
            List<SheetTemplate> templates, Map<String, Object> params,
            BiFunction<String, Map<String, Object>, List<Map<String, Object>>> dataFetcher) {

        Set<String> codes = new LinkedHashSet<>();
        for (SheetTemplate st : templates) {
            st.getRows().values().forEach(list -> list.forEach(tc -> {
                CellConfigDTO cfg = tc.getConfig();
                if (cfg != null && cfg.getDatasetCode() != null && !cfg.getDatasetCode().isBlank()) {
                    codes.add(cfg.getDatasetCode());
                }
            }));
        }

        boolean parallel = codes.size() > 1 && props.getFetchParallelism() > 1
                && !(dataFetcher instanceof LinkedDataFetcher);
        return parallel ? fetchParallel(codes, params, dataFetcher)
                : fetchSequential(codes, params, dataFetcher);
    }

    private Map<String, List<Map<String, Object>>> fetchSequential(
            Set<String> codes, Map<String, Object> params,
            BiFunction<String, Map<String, Object>, List<Map<String, Object>>> dataFetcher) {
        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        for (String code : codes) {
            try {
                List<Map<String, Object>> rows = dataFetcher.apply(code, params);
                data.put(code, rows == null ? List.of() : rows);
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                log.error("数据集取数失败: {}", code, e);
                throw new BizException("数据集[" + code + "]取数失败: " + e.getMessage());
            }
        }
        return data;
    }

    /**
     * 并行取数。结果仍按 {@code codes} 的声明顺序装回，与串行**一字不差**（谁先取完不影响顺序）；
     * 任一数据集失败整体报错，报的是声明顺序里**最靠前**的那个失败（确定性，不看谁先炸），
     * 其余任务顺手取消。
     */
    private Map<String, List<Map<String, Object>>> fetchParallel(
            Set<String> codes, Map<String, Object> params,
            BiFunction<String, Map<String, Object>, List<Map<String, Object>>> dataFetcher) {
        Map<String, Future<List<Map<String, Object>>>> futures = new LinkedHashMap<>();
        for (String code : codes) {
            futures.put(code, fetchPool().submit(() -> dataFetcher.apply(code, params)));
        }
        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Future<List<Map<String, Object>>>> e : futures.entrySet()) {
                try {
                    List<Map<String, Object>> rows = e.getValue().get();
                    data.put(e.getKey(), rows == null ? List.of() : rows);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BizException("数据集取数被中断");
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                    if (cause instanceof BizException biz) {
                        throw biz;
                    }
                    log.error("数据集取数失败: {}", e.getKey(), cause);
                    throw new BizException("数据集[" + e.getKey() + "]取数失败: " + cause.getMessage());
                }
            }
        } finally {
            // 有一个失败就不等剩下的了；正常走完时对已完成的任务 cancel 是空操作
            futures.values().forEach(f -> f.cancel(true));
        }
        return data;
    }

    /**
     * 并行取数共用的线程池：整个引擎（= 整个应用，引擎是单例）一个，有界、守护线程、
     * 空闲 60 秒即回收。
     *
     * <p>懒建在引擎自己手里而不是注入 Spring bean：引擎的约定是脱离 Spring 可用（纯 POJO 测试
     * 直接 new），守护线程 + 空闲回收让它不需要谁来管生命周期。**必须全局共用** ——
     * 渲染请求本身就是并发的，一次渲染建一个池的话线程数 = 请求数 × 并行度，没有上界。
     */
    private ExecutorService fetchPool() {
        ExecutorService pool = fetchPool;
        if (pool != null) {
            return pool;
        }
        synchronized (this) {
            if (fetchPool == null) {
                int n = Math.max(2, props.getFetchParallelism());
                AtomicInteger seq = new AtomicInteger();
                ThreadPoolExecutor tpe = new ThreadPoolExecutor(n, n, 60, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(), r -> {
                            Thread t = new Thread(r, "mz-fetch-" + seq.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        });
                tpe.allowCoreThreadTimeOut(true);
                fetchPool = tpe;
            }
            return fetchPool;
        }
    }

    /** 把渲染网格组装回 FortuneSheet 的 Sheet 结构。 */
    private Map<String, Object> toSheet(SheetTemplate st, RenderGrid grid) {
        Map<String, Object> sheet = new LinkedHashMap<>(st.getRaw());
        sheet.put("name", st.getName());
        sheet.put("id", st.getId());
        sheet.put("order", st.getOrder());
        sheet.put("status", st.getSheetIndex() == 0 ? 1 : 0);
        // 这张结果 sheet 是从第几张模板出来的。拆分/拼接之后结果下标与模板下标对不上，
        // 取打印设置一律认这个值（见 ReportContentDTO#pageConfigOfSheet），别再靠下标推算
        sheet.put("mzTemplateIndex", st.getSheetIndex());

        List<Map<String, Object>> celldata = new ArrayList<>();
        for (GridCell cell : grid.getCells().values()) {
            Map<String, Object> cd = new LinkedHashMap<>();
            cd.put("r", cell.getR());
            cd.put("c", cell.getC());
            cd.put("v", cell.toCellValue());
            celldata.add(cd);
        }
        sheet.put("celldata", celldata);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("merge", grid.getMerge());
        config.put("rowlen", grid.getRowlen());
        config.put("columnlen", grid.getColumnlen());
        config.put("borderInfo", grid.getBorderInfo());
        sheet.put("config", config);

        // 留出余量，避免内容贴边显示
        sheet.put("row", Math.max(60, grid.getMaxRow() + 10));
        sheet.put("column", Math.max(20, grid.getMaxCol() + 10));
        return sheet;
    }
}
