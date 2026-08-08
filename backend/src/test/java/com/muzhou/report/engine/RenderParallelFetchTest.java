package com.muzhou.report.engine;

import com.muzhou.report.common.BizException;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.dto.DatasetLinkDTO;
import com.muzhou.report.dto.RenderResultDTO;
import com.muzhou.report.dto.ReportContentDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行取数（{@code ReportRenderEngine#fetchDatasets}）的行为锁定。
 *
 * <p>四条规矩：
 * ① <b>并行结果与串行一字不差</b> —— 结果按数据集声明顺序装回，谁先取完不影响顺序；
 * ② 任一数据集失败整体报错，报的是声明顺序里最靠前的那个（确定性，不看谁先炸），
 *    {@code BizException} 原样透传、其它异常包装成带数据集 code 的报错；
 * ③ 配了父子关联时不并行 —— 关联取数有先后依赖（先主后子），提交线程池只是白排队；
 * ④ {@code perRow} 逐条渲染里的共享缓存（非主接口数据集）在并行下也只真取一次。
 *
 * <p>纯 POJO：引擎手工 new，取数函数手写，不启动 Spring。
 */
class RenderParallelFetchTest {

    private ReportRenderEngine engine(int parallelism) {
        MzProperties props = new MzProperties();
        props.setFetchParallelism(parallelism);
        FormulaEvaluator evaluator = new FormulaEvaluator();
        evaluator.init();
        return new ReportRenderEngine(new TemplateParser(),
                new ExpandProcessor(new CellFormatter(), evaluator, new MzProperties()), props);
    }

    /* ------------------------------ 造数据 ------------------------------ */

    private Map<String, Object> cd(int r, int c, String text) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("v", text);
        v.put("m", text);
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("r", r);
        cell.put("c", c);
        cell.put("v", v);
        return cell;
    }

    private CellConfigDTO cfg(String code) {
        CellConfigDTO cfg = new CellConfigDTO();
        cfg.setType("data");
        cfg.setDatasetCode(code);
        cfg.setField("no");
        cfg.setExpandType("down");
        cfg.setGroupType("list");
        cfg.setAggregate("none");
        cfg.setFormatType("text");
        return cfg;
    }

    /** 一张 sheet，三个行带分别绑数据集 a / b / c（声明顺序即 a, b, c）。 */
    private ReportContentDTO content() {
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "表1");
        sheet.put("id", "s0");
        sheet.put("order", 0);
        sheet.put("status", 1);
        sheet.put("row", 20);
        sheet.put("column", 5);
        sheet.put("celldata", List.of(cd(0, 0, "#{a.no}"), cd(2, 0, "#{b.no}"), cd(4, 0, "#{c.no}")));
        sheet.put("config", new LinkedHashMap<>());

        Map<String, CellConfigDTO> cellConfigs = new LinkedHashMap<>();
        cellConfigs.put("0_0_0", cfg("a"));
        cellConfigs.put("0_2_0", cfg("b"));
        cellConfigs.put("0_4_0", cfg("c"));

        ReportContentDTO content = new ReportContentDTO();
        content.setSheets(new ArrayList<>(List.of(sheet)));
        content.setCellConfigs(cellConfigs);
        return content;
    }

    /**
     * 手写取数：a 两行、b 三行、c 一行。{@code threads} 不为 null 时记下每次取数跑在哪个线程上，
     * {@code slowCode} 那个数据集慢一拍 —— 用来把完成顺序搅得和声明顺序不一样。
     */
    private BiFunction<String, Map<String, Object>, List<Map<String, Object>>> fetcher(
            Set<String> threads, String slowCode) {
        return (code, params) -> {
            if (threads != null) {
                threads.add(Thread.currentThread().getName());
            }
            if (code.equals(slowCode)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return switch (code) {
                case "a" -> List.of(Map.of("no", "A-1"), Map.of("no", "A-2"));
                case "b" -> List.of(Map.of("no", "B-1"), Map.of("no", "B-2"), Map.of("no", "B-3"));
                case "c" -> List.of(Map.of("no", "C-1"));
                default -> List.of();
            };
        };
    }

    /* ------------------------------ 用例 ------------------------------ */

    @Test
    @DisplayName("并行结果与串行一字不差：声明顺序装回，谁先取完不影响顺序")
    void parallelEqualsSequential() {
        // a 声明在最前但最慢，让完成顺序与声明顺序刚好相反
        RenderResultDTO seq = engine(1).render(content(), Map.of(), fetcher(null, "a"));
        RenderResultDTO par = engine(4).render(content(), Map.of(), fetcher(null, "a"));
        assertEquals(seq.getSheets(), par.getSheets());
        // 分段耗时随结果带出（服务层拿它拼「请求数据 → 渲染 → 转… → 合计」那条链）
        assertTrue(seq.getFetchElapsed() >= 50, "取数耗时该被记下: " + seq.getFetchElapsed());
    }

    @Test
    @DisplayName("多个数据集确实分到了多个线程上")
    void fetchesRunOnMultipleThreads() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        engine(4).render(content(), Map.of(), fetcher(threads, null));
        assertTrue(threads.size() >= 2, "3 个数据集该并行取，实际线程: " + threads);
    }

    @Test
    @DisplayName("并行度 <=1 退回串行：全部取数都在调用线程上")
    void parallelismOneStaysOnCallerThread() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        engine(1).render(content(), Map.of(), fetcher(threads, null));
        assertEquals(Set.of(Thread.currentThread().getName()), threads);
    }

    @Test
    @DisplayName("取数失败整体报错，报声明顺序里最靠前的那个，其它异常带上数据集 code")
    void failureNamesTheEarliestDeclaredDataset() {
        BiFunction<String, Map<String, Object>, List<Map<String, Object>>> failing =
                (code, params) -> {
                    if ("b".equals(code) || "c".equals(code)) {
                        throw new RuntimeException(code + " 挂了");
                    }
                    return List.of(Map.of("no", "A-1"));
                };
        BizException ex = assertThrows(BizException.class,
                () -> engine(4).render(content(), Map.of(), failing));
        assertTrue(ex.getMessage().contains("数据集[b]"), "b 声明在 c 前面，该报 b: " + ex.getMessage());
    }

    @Test
    @DisplayName("取数抛出的 BizException 原样透传，不再裹一层")
    void bizExceptionPassesThrough() {
        BiFunction<String, Map<String, Object>, List<Map<String, Object>>> failing =
                (code, params) -> {
                    if ("b".equals(code)) {
                        throw new BizException("业务库连不上");
                    }
                    return List.of(Map.of("no", "X-1"));
                };
        BizException ex = assertThrows(BizException.class,
                () -> engine(4).render(content(), Map.of(), failing));
        assertEquals("业务库连不上", ex.getMessage());
    }

    @Test
    @DisplayName("配了父子关联时不并行：关联取数有先后依赖，全部落在调用线程上")
    void linkedFetchStaysSequential() {
        ReportContentDTO content = content();
        DatasetLinkDTO link = new DatasetLinkDTO();
        link.setMaster("b");
        link.setChild("a");
        DatasetLinkDTO.Mapping mapping = new DatasetLinkDTO.Mapping();
        mapping.setParam("no");
        mapping.setField("no");
        link.setMappings(List.of(mapping));
        content.setDatasetLinks(List.of(link));

        Set<String> threads = ConcurrentHashMap.newKeySet();
        engine(4).render(content, Map.of(), fetcher(threads, null));
        assertEquals(Set.of(Thread.currentThread().getName()), threads);
    }

    @Test
    @DisplayName("perRow 并行下结果照旧，非主接口的共享数据集只真取一次")
    void perRowParallelEqualsSequentialAndSharesNonPrimary() {
        List<String> seqCalls = new ArrayList<>();
        RenderResultDTO seq;
        {
            ReportContentDTO content = content();
            content.setPrimaryDataset("a");
            content.setSplitMode("perRow");
            seq = engine(1).render(content, Map.of(), counting(seqCalls, null));
        }
        Set<String> calls = ConcurrentHashMap.newKeySet();
        RenderResultDTO par;
        {
            ReportContentDTO content = content();
            content.setPrimaryDataset("a");
            content.setSplitMode("perRow");
            par = engine(4).render(content, Map.of(), counting(null, calls));
        }
        assertEquals(seq.getSheets(), par.getSheets());
        // 主接口 a 取一次（拆分前），b / c 进共享缓存也各只取一次
        assertEquals(Set.of("a", "b", "c"), calls);
    }

    /** 记下每个 code 被真取了几次（并发安全的那份用 Set 判「只取一次」）。 */
    private BiFunction<String, Map<String, Object>, List<Map<String, Object>>> counting(
            List<String> seqCalls, Set<String> concurrentCalls) {
        BiFunction<String, Map<String, Object>, List<Map<String, Object>>> base = fetcher(null, null);
        return (code, params) -> {
            if (seqCalls != null) {
                seqCalls.add(code);
            }
            if (concurrentCalls != null && !concurrentCalls.add(code)) {
                throw new IllegalStateException("数据集[" + code + "]被重复取了");
            }
            return base.apply(code, params);
        };
    }
}
