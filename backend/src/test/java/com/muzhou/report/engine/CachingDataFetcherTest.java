package com.muzhou.report.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CachingDataFetcher} 的纯 POJO 测试。
 *
 * <p>两条不能破的规矩：
 * ① 同一个 {@code (code, params)} 只真取一次 —— 版本选择要先探一次主接口，那一次不能白取；
 * ② <b>缓存 key 必须含 params</b> —— 父子关联里「主表每行查一次子表」用的是同一个 code、
 * 不同的参数，只按 code 缓存的话所有明细都会串成第一行的那一份。
 */
class CachingDataFetcherTest {

    /** 每次调用记一笔 `code:参数`，用来数真正打到底层的次数。 */
    private final List<String> calls = new ArrayList<>();

    private BiFunction<String, Map<String, Object>, List<Map<String, Object>>> counting() {
        return (code, params) -> {
            calls.add(code + ":" + params.get("orderId"));
            return List.of(Map.of("code", code, "orderId", String.valueOf(params.get("orderId"))));
        };
    }

    private Map<String, Object> params(Object orderId) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", orderId);
        return m;
    }

    @Test
    @DisplayName("同一个 (code, params) 只真取一次")
    void sameCodeAndParamsHitsTheCache() {
        var fetcher = CachingDataFetcher.wrap(counting());
        List<Map<String, Object>> first = fetcher.apply("orders", params("1"));
        List<Map<String, Object>> second = fetcher.apply("orders", params("1"));
        assertEquals(List.of("orders:1"), calls);
        assertSame(first, second, "命中缓存该还同一份数据");
    }

    @Test
    @DisplayName("同一个 code、不同 params 各取各的（父子关联的明细不能串）")
    void differentParamsAreDifferentEntries() {
        var fetcher = CachingDataFetcher.wrap(counting());
        assertEquals("1", fetcher.apply("items", params("1")).get(0).get("orderId"));
        assertEquals("2", fetcher.apply("items", params("2")).get(0).get("orderId"));
        assertEquals("3", fetcher.apply("items", params("3")).get(0).get("orderId"));
        // 再来一遍，全部命中缓存
        assertEquals("2", fetcher.apply("items", params("2")).get(0).get("orderId"));
        assertEquals(List.of("items:1", "items:2", "items:3"), calls);
    }

    @Test
    @DisplayName("参数做的是不可变拷贝：调用方事后改自己那份 map，已缓存的条目不受影响")
    void paramsAreSnapshotted() {
        var fetcher = CachingDataFetcher.wrap(counting());
        Map<String, Object> p = params("1");
        fetcher.apply("items", p);
        // 复用同一个 map 对象继续查下一行（LinkedDataFetcher 就有可能这么干）
        p.put("orderId", "2");
        assertEquals("2", fetcher.apply("items", p).get(0).get("orderId"));
        assertEquals(List.of("items:1", "items:2"), calls);
        // 改回去仍然命中第一次那条缓存
        p.put("orderId", "1");
        fetcher.apply("items", p);
        assertEquals(2, calls.size());
    }

    @Test
    @DisplayName("参数值为 null 也能当 key（没给值的报表参数），空结果也照样缓存")
    void nullValuesAndEmptyResultsAreCacheable() {
        List<String> hits = new ArrayList<>();
        var fetcher = CachingDataFetcher.wrap((code, params) -> {
            hits.add(code);
            return List.of();
        });
        fetcher.apply("orders", params(null));
        fetcher.apply("orders", params(null));
        assertEquals(1, hits.size(), "取回空集合也是一次有效结果，不该被反复重取");
    }

    @Test
    @DisplayName("只记指定的那个 code：主接口进缓存，子表原样穿过去（不留在内存里）")
    void onlyTheNamedCodeIsMemoized() {
        var fetcher = CachingDataFetcher.wrap(counting(), "orders");
        fetcher.apply("orders", params("1"));
        fetcher.apply("orders", params("1"));
        // 子表每行参数都不同，本来就命中不了缓存 —— 全记下来只是白留一份内存
        fetcher.apply("items", params("1"));
        fetcher.apply("items", params("1"));
        assertEquals(List.of("orders:1", "items:1", "items:1"), calls);
    }

    @Test
    @DisplayName("onlyCode 传空串 = 什么都不记（报表没设主接口时就不必探测）")
    void emptyOnlyCodeMemoizesNothing() {
        var fetcher = CachingDataFetcher.wrap(counting(), "");
        fetcher.apply("orders", params("1"));
        fetcher.apply("orders", params("1"));
        assertEquals(List.of("orders:1", "orders:1"), calls);
    }

    @Test
    @DisplayName("包过的不再包第二层")
    void wrapIsIdempotent() {
        var once = CachingDataFetcher.wrap(counting());
        assertSame(once, CachingDataFetcher.wrap(once));
        assertTrue(once instanceof CachingDataFetcher);
    }
}
