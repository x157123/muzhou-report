package com.muzhou.report.version;

import com.muzhou.report.common.BizException;
import com.muzhou.report.dto.VersionConfigDTO;
import com.muzhou.report.version.ReportVersionResolver.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本选择算法的纯 POJO 测试（照 {@code RenderEngineTest} 的路子，不启动 Spring）。
 *
 * <p>用的是设计文档里那张表：
 * <pre>
 * v1  effectiveFrom = null        (-∞, 2026-05-01)
 * v2  effectiveFrom = 2026-05-01  [2026-05-01, 2026-08-01)
 * v3  effectiveFrom = 2026-08-01  [2026-08-01, +∞)
 * </pre>
 */
class ReportVersionResolverTest {

    private static final LocalDateTime MAY = LocalDateTime.of(2026, 5, 1, 0, 0);
    private static final LocalDateTime AUG = LocalDateTime.of(2026, 8, 1, 0, 0);

    private Candidate v(String id, int no, LocalDateTime from, boolean isDefault, boolean enabled) {
        return new Candidate(id, no, null, from, isDefault, enabled);
    }

    private List<Candidate> three() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        list.add(v("v2", 2, MAY, false, true));
        list.add(v("v3", 3, AUG, false, true));
        return list;
    }

    /** 判定值直接给（不经过取数），走 resolveByValue —— 逐行选版本用的也是这条。 */
    private String pick(List<Candidate> versions, Object value) {
        return ReportVersionResolver.resolveByValue(versions, value, null).version().id();
    }

    @Test
    @DisplayName("区间左闭右开：5/1 00:00:00 归 v2，4/30 23:59:59 归 v1")
    void boundaryIsInclusiveOnTheLeft() {
        assertEquals("v2", pick(three(), LocalDateTime.of(2026, 5, 1, 0, 0, 0)));
        assertEquals("v1", pick(three(), LocalDateTime.of(2026, 4, 30, 23, 59, 59)));
        assertEquals("v2", pick(three(), LocalDateTime.of(2026, 7, 31, 23, 59, 59)));
        assertEquals("v3", pick(three(), AUG));
        assertEquals("v3", pick(three(), LocalDateTime.of(2030, 1, 1, 0, 0)));
    }

    @Test
    @DisplayName("早于所有起点 -> effectiveFrom 为 null 的那一版")
    void earlierThanEverythingFallsToTheNullVersion() {
        assertEquals("v1", pick(three(), LocalDate.of(2020, 1, 1)));
    }

    @Test
    @DisplayName("早于所有起点、又没有 null 版 -> 默认版本")
    void earlierThanEverythingWithoutNullVersionUsesDefault() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v2", 2, MAY, false, true));
        // 默认版本是 v3，尽管它的起点更晚
        list.add(v("v3", 3, AUG, true, true));
        assertEquals("v3", pick(list, LocalDate.of(2020, 1, 1)));
    }

    @Test
    @DisplayName("停用 v2 后，5/1 ~ 8/1 这一段落回 v1（区间被前一版吞掉）")
    void disabledVersionIsSwallowedByThePreviousOne() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        list.add(v("v2", 2, MAY, false, false));
        list.add(v("v3", 3, AUG, false, true));
        assertEquals("v1", pick(list, LocalDate.of(2026, 6, 1)));
        // 8/1 之后仍是 v3，停用的那一版只影响它自己那一段
        assertEquals("v3", pick(list, LocalDate.of(2026, 9, 1)));
    }

    @Test
    @DisplayName("判定值取不到：fallback=default 走默认版本")
    void missingValueFallsBackToDefault() {
        assertEquals("v1", pick(three(), null));
        assertEquals("v1", pick(three(), "不是日期"));
    }

    @Test
    @DisplayName("判定值取不到：fallback=error 抛 BizException，消息里带字段名")
    void missingValueCanBeAnError() {
        VersionConfigDTO cfg = new VersionConfigDTO();
        cfg.setField("order_date");
        cfg.setFallback(VersionConfigDTO.FALLBACK_ERROR);
        BizException e = assertThrows(BizException.class,
                () -> ReportVersionResolver.resolveByValue(three(), null, cfg));
        assertTrue(e.getMessage().contains("order_date"), e.getMessage());
    }

    @Test
    @DisplayName("日期归一化：sql.Date / Timestamp / LocalDate / 字符串 / epoch 毫秒")
    void valueNormalization() {
        assertEquals("v2", pick(three(), java.sql.Date.valueOf(LocalDate.of(2026, 6, 1))));
        assertEquals("v2", pick(three(), java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 6, 1, 12, 0))));
        assertEquals("v2", pick(three(), LocalDate.of(2026, 6, 1)));
        assertEquals("v2", pick(three(), "2026-06-01"));
        assertEquals("v2", pick(three(), "2026-06-01 12:30"));
        assertEquals("v2", pick(three(), "2026-06-01 12:30:45"));
        // 接口常见的两种：ISO 的 T、带毫秒尾巴
        assertEquals("v2", pick(three(), "2026-06-01T12:30:45"));
        assertEquals("v2", pick(three(), "2026-06-01 12:30:45.123"));
        long epoch = LocalDateTime.of(2026, 6, 1, 0, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals("v2", pick(three(), epoch));
        assertEquals("v2", pick(three(), String.valueOf(epoch)));
    }

    @Test
    @DisplayName("显式 versionId 直接用它，停用的版本也照开，且不探测取数")
    void explicitVersionWins() {
        List<Candidate> list = three();
        list.set(1, v("v2", 2, MAY, false, false));
        AtomicInteger calls = new AtomicInteger();
        ReportVersionResolver.Resolution r = ReportVersionResolver.resolve(
                list, "v2", null, "orders", Map.of(), counting(calls, "2026-09-01"));
        assertEquals("v2", r.version().id());
        assertEquals(0, calls.get(), "指定了版本还去探主接口就是白打一次 SQL");
    }

    @Test
    @DisplayName("显式 versionId 不属于这张报表 -> 报错")
    void explicitVersionMustBelongToTheReport() {
        assertThrows(BizException.class, () -> ReportVersionResolver.resolve(
                three(), "别的报表的版本", null, "orders", Map.of(), counting(new AtomicInteger(), null)));
    }

    @Test
    @DisplayName("只有一个启用版本时不探测取数（绝大多数报表就是这种）")
    void singleEnabledVersionSkipsTheProbe() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        list.add(v("v2", 2, MAY, false, false));
        AtomicInteger calls = new AtomicInteger();
        ReportVersionResolver.Resolution r = ReportVersionResolver.resolve(
                list, null, null, "orders", Map.of(), counting(calls, "2026-09-01"));
        assertEquals("v1", r.version().id());
        assertEquals(0, calls.get());
        assertNull(r.value());
    }

    @Test
    @DisplayName("source=field：探一次主接口，取第一行的字段值（大小写两种 key 都认）")
    void probesPrimaryDatasetOnce() {
        AtomicInteger calls = new AtomicInteger();
        VersionConfigDTO cfg = new VersionConfigDTO();
        cfg.setField("ORDER_DATE");
        ReportVersionResolver.Resolution r = ReportVersionResolver.resolve(
                three(), null, cfg, "orders", Map.of(), counting(calls, "2026-09-01"));
        assertEquals("v3", r.version().id());
        assertEquals(1, calls.get(), "主接口只许探一次");
        assertTrue(r.reason().contains("ORDER_DATE"), r.reason());
    }

    @Test
    @DisplayName("source=param 与 source=now 不探测取数")
    void paramAndNowNeedNoProbe() {
        AtomicInteger calls = new AtomicInteger();
        VersionConfigDTO byParam = new VersionConfigDTO();
        byParam.setSource(VersionConfigDTO.SOURCE_PARAM);
        byParam.setField("bizDate");
        assertEquals("v2", ReportVersionResolver.resolve(three(), null, byParam, "orders",
                Map.of("bizDate", "2026-06-01"), counting(calls, "2026-09-01")).version().id());

        VersionConfigDTO byNow = new VersionConfigDTO();
        byNow.setSource(VersionConfigDTO.SOURCE_NOW);
        // 今天必定晚于 2026-08-01 以外的两段？不一定 —— 只断言它没去探数据集
        ReportVersionResolver.resolve(three(), null, byNow, "orders", Map.of(),
                counting(calls, "2026-09-01"));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("主接口没设 / 取回空数据 -> 判定值取不到，走 fallback")
    void noPrimaryDatasetMeansNoValue() {
        AtomicInteger calls = new AtomicInteger();
        VersionConfigDTO cfg = new VersionConfigDTO();
        cfg.setField("order_date");
        assertEquals("v1", ReportVersionResolver.resolve(three(), null, cfg, "", Map.of(),
                counting(calls, "2026-09-01")).version().id());
        assertEquals(0, calls.get());
        // 主接口有、但一行数据都没有
        assertEquals("v1", ReportVersionResolver.resolve(three(), null, cfg, "orders", Map.of(),
                (code, p) -> List.of()).version().id());
    }

    /** 记一笔调用次数的假取数函数，只有 orders 会还一行数据。 */
    private BiFunction<String, Map<String, Object>, List<Map<String, Object>>> counting(
            AtomicInteger calls, String orderDate) {
        return (code, params) -> {
            calls.incrementAndGet();
            return List.of(Map.of("order_date", orderDate == null ? "" : orderDate));
        };
    }
}
