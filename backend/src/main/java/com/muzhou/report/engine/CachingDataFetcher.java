package com.muzhou.report.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * 按 {@code (datasetCode, params)} 记忆的取数装饰器 —— 同一次渲染里，同一个数据集用同一份参数
 * 只会真的取一次。
 *
 * <p>存在的理由是**版本选择**：用哪一版由主接口的字段值决定，而模板又由版本决定，
 * 于是渲染前要先探一次主接口。这一次取数不能白取 —— 包一层 memo，随后引擎再要同一份数据时
 * 直接命中缓存（见 {@code RenderServiceImpl#render}）。
 *
 * <p><b>缓存 key 必须含 params</b>：父子关联里「主表每行查一次子表」用的是同一个 code、不同的参数
 * （{@code LinkedDataFetcher}），只按 code 缓存的话所有明细都会串成第一行的那一份。
 * 参数一律做**不可变拷贝**再当 key，调用方后续改自己那份 map 也影响不到已经缓存的条目。
 * 值里如果装着数组之类「不按内容比 equals」的东西，最坏结果只是没命中缓存、多取一次，不会取错。
 *
 * <p><b>默认只记住一个数据集</b>（{@link #wrap(BiFunction, String)} 的 {@code onlyCode}，
 * 也就是探测用的那个主接口）。记全部反而是有害的：普通渲染那头
 * {@code ReportRenderEngine#fetchDatasets} 本来就按 code 去过重、{@code perRowFetcher} 也有自己的
 * {@code shared} 缓存，而父子关联的子表每行参数都不一样、**永远不会命中第二次** ——
 * 全记的结果只是把主表 N 行拉出来的 N 份子表数据全部**留在内存里直到渲染结束**，白涨一倍内存，
 * 一次命中都换不来。
 *
 * <p>只活一次渲染，但**线程安全**：并行取数（{@code ReportRenderEngine#fetchDatasets}）会从
 * 多个线程同时进来。缓存是 {@link ConcurrentHashMap}，极端竞态下同一个 key 可能真取两次
 * （get 与 putIfAbsent 之间撞上），代价只是多一次查询、结果一致 —— 比为它上锁便宜。
 * {@link LinkedDataFetcher} / {@code perRowFetcher} 照旧包在它**外面**，行为不变。
 */
public final class CachingDataFetcher
        implements BiFunction<String, Map<String, Object>, List<Map<String, Object>>> {

    private final BiFunction<String, Map<String, Object>, List<Map<String, Object>>> inner;

    /** 只记这个 code 的取数结果；null = 全记（通用形态，测试与将来别的场景用） */
    private final String onlyCode;

    private final Map<Key, List<Map<String, Object>>> cache = new ConcurrentHashMap<>();

    private CachingDataFetcher(BiFunction<String, Map<String, Object>, List<Map<String, Object>>> inner,
                               String onlyCode) {
        this.inner = inner;
        this.onlyCode = onlyCode;
    }

    /** 包一层记忆，记住所有数据集；已经包过的原样返回（多包一层只是白占内存）。 */
    public static BiFunction<String, Map<String, Object>, List<Map<String, Object>>> wrap(
            BiFunction<String, Map<String, Object>, List<Map<String, Object>>> inner) {
        return wrap(inner, null);
    }

    /**
     * 包一层记忆，**只记 {@code onlyCode} 这一个数据集**（版本探测用的主接口）。
     *
     * @param onlyCode 只记这个 code；null 表示全记，空串表示什么都不记（没有主接口时就不必探测）
     */
    public static BiFunction<String, Map<String, Object>, List<Map<String, Object>>> wrap(
            BiFunction<String, Map<String, Object>, List<Map<String, Object>>> inner, String onlyCode) {
        if (inner == null || inner instanceof CachingDataFetcher) {
            return inner;
        }
        return new CachingDataFetcher(inner, onlyCode);
    }

    @Override
    public List<Map<String, Object>> apply(String code, Map<String, Object> params) {
        if (onlyCode != null && !onlyCode.equals(code)) {
            return inner.apply(code, params);
        }
        Key key = new Key(code, snapshot(params));
        List<Map<String, Object>> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        // null 归一成空集合再缓存：ConcurrentHashMap 存不了 null，而空集合本身就是一次有效结果，
        // 照样要缓存（不该被反复重取）
        List<Map<String, Object>> rows = inner.apply(code, params);
        List<Map<String, Object>> value = rows == null ? List.of() : rows;
        List<Map<String, Object>> prev = cache.putIfAbsent(key, value);
        return prev != null ? prev : value;
    }

    /** HashMap 而不是 Map.copyOf：参数值允许为 null（没给值的报表参数），copyOf 会直接 NPE。 */
    private static Map<String, Object> snapshot(Map<String, Object> params) {
        return params == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(params));
    }

    private record Key(String code, Map<String, Object> params) {
    }
}
