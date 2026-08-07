package com.muzhou.report.engine;

import com.muzhou.report.common.BizException;
import com.muzhou.report.dto.DatasetLinkDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 父子关联（子接口查询）的取数装饰器，见 docs/CONTRACT.md §4 `datasetLinks`。
 *
 * <p>纯 POJO 测试：取数函数直接手写，不启动 Spring、不连库 —— 引擎的取数抽象本来就是为此存在的。
 */
class LinkedDataFetcherTest {

    /** 主表：两张订单。 */
    private static final List<Map<String, Object>> ORDERS = List.of(
            row("id", "1", "order_no", "SO-001"),
            row("id", "2", "order_no", "SO-002"));

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private DatasetLinkDTO link(String master, String child, String param, String field) {
        DatasetLinkDTO l = new DatasetLinkDTO();
        l.setName("订单明细");
        l.setMaster(master);
        l.setChild(child);
        DatasetLinkDTO.Mapping m = new DatasetLinkDTO.Mapping();
        m.setParam(param);
        m.setField(field);
        l.setMappings(List.of(m));
        return l;
    }

    /** 记录每次取数的 (code, 参数快照)，用来断言「子表被打了几次、每次传的什么」。 */
    private static class Calls {
        final List<String> log = new ArrayList<>();

        BiFunction<String, Map<String, Object>, List<Map<String, Object>>> fetcher() {
            return (code, params) -> {
                log.add(code + params.get("orderId"));
                if ("orders".equals(code)) {
                    return ORDERS;
                }
                if ("items".equals(code)) {
                    // 子表按 orderId 出不同条数的明细：1 号两条、2 号一条
                    return "1".equals(params.get("orderId"))
                            ? List.of(row("name", "螺丝"), row("name", "螺母"))
                            : List.of(row("name", "垫片"));
                }
                return List.of();
            };
        }
    }

    @Test
    @DisplayName("主表每行查一次子表，结果拼成一份，并把主表字段合进子行")
    void fetchesChildPerMasterRow() {
        Calls calls = new Calls();
        var fetcher = LinkedDataFetcher.wrap(List.of(link("orders", "items", "orderId", "id")),
                calls.fetcher(), 500);

        List<Map<String, Object>> rows = fetcher.apply("items", new LinkedHashMap<>());

        assertEquals(3, rows.size());
        assertEquals(List.of("螺丝", "螺母", "垫片"), rows.stream().map(r -> r.get("name")).toList());
        // 主表这一行的字段合进了它的每条子行 —— 主子拼一张清单时主表列才排得到同一行上
        assertEquals(List.of("SO-001", "SO-001", "SO-002"), rows.stream().map(r -> r.get("order_no")).toList());
        // 主表取一次、子表按主表行数取两次，参数是各自那一行的 id
        assertEquals(List.of("ordersnull", "items1", "items2"), calls.log);
    }

    @Test
    @DisplayName("主表也被模板用到时只取一次（缓存），不会为了驱动子表再打一遍")
    void masterFetchedOnce() {
        Calls calls = new Calls();
        var fetcher = LinkedDataFetcher.wrap(List.of(link("orders", "items", "orderId", "id")),
                calls.fetcher(), 500);

        fetcher.apply("orders", new LinkedHashMap<>());
        fetcher.apply("items", new LinkedHashMap<>());

        assertEquals(1, calls.log.stream().filter("ordersnull"::equals).count());
    }

    @Test
    @DisplayName("子表参数覆盖同名报表参数，其余参数照旧透传")
    void masterValueOverridesReportParam() {
        List<Map<String, Object>> seen = new ArrayList<>();
        BiFunction<String, Map<String, Object>, List<Map<String, Object>>> base = (code, params) -> {
            if ("orders".equals(code)) {
                return List.of(row("id", "1"));
            }
            seen.add(params);
            return List.of(row("name", "螺丝"));
        };
        var fetcher = LinkedDataFetcher.wrap(List.of(link("orders", "items", "orderId", "id")), base, 500);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", "999");
        params.put("dept", "采购部");
        fetcher.apply("items", params);

        assertEquals("1", seen.get(0).get("orderId"));
        assertEquals("采购部", seen.get(0).get("dept"));
    }

    @Test
    @DisplayName("主表没有数据时子表一次都不查")
    void emptyMasterSkipsChild() {
        List<String> log = new ArrayList<>();
        BiFunction<String, Map<String, Object>, List<Map<String, Object>>> base = (code, params) -> {
            log.add(code);
            return List.of();
        };
        var fetcher = LinkedDataFetcher.wrap(List.of(link("orders", "items", "orderId", "id")), base, 500);

        assertTrue(fetcher.apply("items", new LinkedHashMap<>()).isEmpty());
        assertEquals(List.of("orders"), log);
    }

    @Test
    @DisplayName("主表行数超上限直接报错，不去打成百上千次子接口")
    void masterRowsCapped() {
        Calls calls = new Calls();
        var fetcher = LinkedDataFetcher.wrap(List.of(link("orders", "items", "orderId", "id")),
                calls.fetcher(), 1);

        BizException e = assertThrows(BizException.class, () -> fetcher.apply("items", new LinkedHashMap<>()));
        assertTrue(e.getMessage().contains("最多 1 行"));
    }

    @Test
    @DisplayName("互为主子的环要报错，不能栈溢出")
    void cycleDetected() {
        List<DatasetLinkDTO> links = List.of(
                link("a", "b", "p", "f"),
                link("b", "a", "p", "f"));
        var fetcher = LinkedDataFetcher.wrap(links, (code, params) -> List.of(row("f", "1")), 500);

        BizException e = assertThrows(BizException.class, () -> fetcher.apply("b", new LinkedHashMap<>()));
        assertTrue(e.getMessage().contains("循环"));
    }

    @Test
    @DisplayName("同一个子表挂两个主表：取数规则有歧义，建索引时就报错")
    void duplicateChildRejected() {
        List<DatasetLinkDTO> links = List.of(
                link("a", "c", "p", "f"),
                link("b", "c", "p", "f"));

        assertThrows(BizException.class, () -> LinkedDataFetcher.childCodes(links));
    }

    @Test
    @DisplayName("没配关联时原样返回原取数函数，不多包一层")
    void noLinksNoWrapper() {
        BiFunction<String, Map<String, Object>, List<Map<String, Object>>> base = (code, params) -> List.of();
        assertEquals(base, LinkedDataFetcher.wrap(List.of(), base, 500));
        assertEquals(base, LinkedDataFetcher.wrap(null, base, 500));
    }
}
