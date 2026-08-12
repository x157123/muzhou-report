package com.muzhou.report.version;

import com.muzhou.report.common.BizException;
import com.muzhou.report.dto.VersionConfigDTO;
import com.muzhou.report.dto.VersionMatchRuleDTO;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** 带匹配条件的候选版本 */
    private Candidate v(String id, int no, LocalDateTime from, boolean isDefault, boolean enabled,
                        VersionMatchRuleDTO... rules) {
        return new Candidate(id, no, null, from, isDefault, enabled, List.of(rules));
    }

    private VersionMatchRuleDTO rule(String field, String op, String value) {
        VersionMatchRuleDTO r = new VersionMatchRuleDTO();
        r.setField(field);
        r.setOp(op);
        r.setValue(value);
        return r;
    }

    private VersionMatchRuleDTO paramRule(String name, String value) {
        VersionMatchRuleDTO r = rule(name, VersionMatchRuleDTO.OP_EQ, value);
        r.setSource(VersionMatchRuleDTO.SOURCE_PARAM);
        return r;
    }

    /** 按一行数据选版本（逐行选版本走的就是这条），判定字段是 order_date */
    private String pickRow(List<Candidate> versions, Map<String, Object> row) {
        VersionConfigDTO cfg = new VersionConfigDTO();
        cfg.setField("order_date");
        return ReportVersionResolver.resolveByRow(versions, cfg, row, Map.of()).version().id();
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

    /* ---------------------- 匹配条件（时间之外的那几维） ---------------------- */

    /** v1 无条件兜底；v2 认类型 A；v3 认类型 B 且区域在华东/华南 */
    private List<Candidate> byType() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        list.add(v("v2", 2, null, false, true, rule("order_type", "eq", "A")));
        list.add(v("v3", 3, null, false, true,
                rule("order_type", "eq", "B"), rule("area", "in", "华东, 华南")));
        return list;
    }

    @Test
    @DisplayName("按条件选：类型 A 走 v2，类型 B + 华东走 v3，都不沾边落回无条件的 v1")
    void picksByMatchRules() {
        assertEquals("v2", pickRow(byType(), Map.of("order_type", "A", "area", "华东")));
        assertEquals("v3", pickRow(byType(), Map.of("order_type", "B", "area", "华南")));
        // 类型对上了、区域没对上 —— v3 的两条是 AND，只能落回 v1
        assertEquals("v1", pickRow(byType(), Map.of("order_type", "B", "area", "华北")));
        assertEquals("v1", pickRow(byType(), Map.of("order_type", "C")));
        // 字段整个缺失同样不算满足
        assertEquals("v1", pickRow(byType(), Map.of()));
    }

    @Test
    @DisplayName("特异度优先：条件多的压过条件少的，两者都压过无条件的那一版")
    void moreSpecificWins() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        list.add(v("v2", 2, null, false, true, rule("order_type", "eq", "A")));
        list.add(v("v3", 3, null, false, true,
                rule("order_type", "eq", "A"), rule("area", "eq", "华东")));
        // 两条的那版赢 —— 没有这一条的话「谁赢」要看生效时间，配条件就白配了
        assertEquals("v3", pickRow(list, Map.of("order_type", "A", "area", "华东")));
        assertEquals("v2", pickRow(list, Map.of("order_type", "A", "area", "华北")));
        assertEquals("v1", pickRow(list, Map.of("order_type", "B", "area", "华东")));
    }

    @Test
    @DisplayName("条件 + 时间叠加：先按条件筛，再在筛出来的那一批里推时间区间")
    void rulesThenTime() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        // 同一个类型的两版：5/1 起换版式
        list.add(v("A旧", 2, null, false, true, rule("order_type", "eq", "A")));
        list.add(v("A新", 3, MAY, false, true, rule("order_type", "eq", "A")));
        list.add(v("B版", 4, AUG, false, true, rule("order_type", "eq", "B")));

        assertEquals("A旧", pickRow(list, Map.of("order_type", "A", "order_date", "2026-04-01")));
        assertEquals("A新", pickRow(list, Map.of("order_type", "A", "order_date", "2026-06-01")));
        // 类型 B 那一批只有一版，8/1 之前的数据也归它 —— 条件已经命中，退回默认版本会把条件推翻
        assertEquals("B版", pickRow(list, Map.of("order_type", "B", "order_date", "2026-06-01")));
        assertEquals("B版", pickRow(list, Map.of("order_type", "B", "order_date", "2026-09-01")));
        // 条件谁也不满足 -> 无条件的 v1（它是这一批里唯一一个）
        assertEquals("v1", pickRow(list, Map.of("order_type", "C", "order_date", "2026-06-01")));
    }

    @Test
    @DisplayName("只按条件选（没配判定字段）：时间这一维不参与，取匹配那批里生效最晚的一版")
    void rulesOnlyIgnoresTime() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        list.add(v("A旧", 2, null, false, true, rule("order_type", "eq", "A")));
        list.add(v("A新", 3, MAY, false, true, rule("order_type", "eq", "A")));
        // 判定字段留空 = 用户压根不想按时间选
        VersionConfigDTO cfg = new VersionConfigDTO();
        assertEquals("A新", ReportVersionResolver
                .resolveByRow(list, cfg, Map.of("order_type", "A"), Map.of()).version().id());
        // 一条条件都没匹配上时，行为不变：还是那条老路（判定值取不到 -> 默认版本）
        assertEquals("v1", ReportVersionResolver
                .resolveByRow(list, cfg, Map.of("order_type", "C"), Map.of()).version().id());
    }

    @Test
    @DisplayName("条件已把那批筛成唯一一版时，判定值取不到也照用它")
    void uniqueByRulesSurvivesMissingValue() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        list.add(v("v2", 2, MAY, false, true, rule("order_type", "eq", "A")));
        // order_date 整个没有 —— 但条件已经定案了，不该再被时间这一维推翻
        assertEquals("v2", pickRow(list, Map.of("order_type", "A")));
    }

    @Test
    @DisplayName("每一版都带条件、一条也不满足：fallback=default 用默认版本，error 抛 BizException")
    void nothingMatchesGoesToFallback() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true, rule("order_type", "eq", "A")));
        list.add(v("v2", 2, MAY, false, true, rule("order_type", "eq", "B")));
        assertEquals("v1", pickRow(list, Map.of("order_type", "C")));

        VersionConfigDTO cfg = new VersionConfigDTO();
        cfg.setFallback(VersionConfigDTO.FALLBACK_ERROR);
        assertThrows(BizException.class, () -> ReportVersionResolver
                .resolveByRow(list, cfg, Map.of("order_type", "C"), Map.of()));
    }

    @Test
    @DisplayName("运算符：ne / in / notIn / contains / empty / notEmpty，数字按数值比、文本忽略大小写")
    void operators() {
        assertTrue(rule("f", "ne", "A").test("B"));
        assertFalse(rule("f", "ne", "A").test("a"), "文本比较忽略大小写");
        assertTrue(rule("f", "in", "X, Y ,Z").test("Y"));
        assertFalse(rule("f", "in", "X,Y").test("Z"));
        assertTrue(rule("f", "notIn", "X,Y").test("Z"));
        assertTrue(rule("f", "contains", "XS").test("XS-2026-001"));
        assertFalse(rule("f", "contains", "XS").test(""));
        assertTrue(rule("f", "empty", null).test(null));
        assertTrue(rule("f", "empty", null).test("  "));
        assertFalse(rule("f", "notEmpty", null).test(null));
        // 1 与 1.0 是同一个值：接口还回来的类型码常是数字
        assertTrue(rule("f", "eq", "1").test(1.0));
        assertTrue(rule("f", "eq", "1.0").test(1));
        // 值两头的空格不算数（数据库里的 char 列常带尾空格）
        assertTrue(rule("f", "eq", "A").test(" A "));
    }

    @Test
    @DisplayName("条件的值也可以取自报表参数")
    void ruleCanReadParams() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        list.add(v("v2", 2, null, false, true, paramRule("printMode", "简版")));
        VersionConfigDTO cfg = new VersionConfigDTO();
        assertEquals("v2", ReportVersionResolver
                .resolveByRow(list, cfg, Map.of(), Map.of("printMode", "简版")).version().id());
        assertEquals("v1", ReportVersionResolver
                .resolveByRow(list, cfg, Map.of(), Map.of("printMode", "全版")).version().id());
    }

    @Test
    @DisplayName("条件取主接口字段时照样只探一次主接口；全是参数条件则一次都不探")
    void probesOnlyWhenRulesNeedTheRow() {
        List<Candidate> byField = new ArrayList<>();
        byField.add(v("v1", 1, null, true, true));
        byField.add(v("v2", 2, null, false, true, rule("order_date", "contains", "2026-09")));
        AtomicInteger calls = new AtomicInteger();
        // 判定依据是「渲染当日」（本来不必取数），但有一版的条件要看主接口字段
        VersionConfigDTO now = new VersionConfigDTO();
        now.setSource(VersionConfigDTO.SOURCE_NOW);
        assertEquals("v2", ReportVersionResolver.resolve(byField, null, now, "orders", Map.of(),
                counting(calls, "2026-09-01")).version().id());
        assertEquals(1, calls.get(), "主接口只许探一次");

        List<Candidate> byParam = new ArrayList<>();
        byParam.add(v("v1", 1, null, true, true));
        byParam.add(v("v2", 2, null, false, true, paramRule("printMode", "简版")));
        calls.set(0);
        assertEquals("v2", ReportVersionResolver.resolve(byParam, null, now, "orders",
                Map.of("printMode", "简版"), counting(calls, "2026-09-01")).version().id());
        assertEquals(0, calls.get(), "条件全取自参数，不该去探主接口");
    }

    @Test
    @DisplayName("条件里的字段名大小写两种 key 都认（同判定字段）")
    void ruleFieldIsCaseTolerant() {
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        list.add(v("v2", 2, null, false, true, rule("ORDER_TYPE", "eq", "A")));
        assertEquals("v2", pickRow(list, Map.of("order_type", "A")));
    }

    @Test
    @DisplayName("versionMatch 那句话里带上命中的条件，出问题时看一眼就知道走了哪一版")
    void reasonMentionsRules() {
        VersionConfigDTO cfg = new VersionConfigDTO();
        cfg.setField("order_date");
        String reason = ReportVersionResolver.resolveByRow(byType(), cfg,
                Map.of("order_type", "B", "area", "华东", "order_date", "2026-06-01"), Map.of()).reason();
        assertTrue(reason.contains("order_type=B"), reason);
        assertTrue(reason.contains("area∈华东, 华南"), reason);
        assertTrue(reason.contains("order_date=2026-06-01"), reason);
    }

    @Test
    @DisplayName("字段名还空着的条件不算数（界面上没填完的那一行）")
    void blankFieldRulesAreIgnored() {
        VersionMatchRuleDTO half = rule(null, "eq", "A");
        List<Candidate> list = new ArrayList<>();
        list.add(v("v1", 1, null, true, true));
        list.add(v("v2", 2, MAY, false, true, half));
        // v2 等价于「无条件」，于是仍是纯时间那一套
        assertEquals("v2", pickRow(list, Map.of("order_date", "2026-06-01")));
        assertEquals("v1", pickRow(list, Map.of("order_date", "2026-01-01")));
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
