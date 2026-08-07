package com.muzhou.report.engine;

import com.muzhou.report.common.BizException;
import com.muzhou.report.dto.DatasetLinkDTO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 父子关联（子接口查询）的取数装饰器，见 docs/CONTRACT.md §4 `datasetLinks` 与 §7 第 2 步。
 *
 * <p>它把「先查主表、再拿主表的返回值去查子表」这件事**完全做在取数这一层**：对外仍是
 * 引擎认的那个 {@code (datasetCode, params) -> rows} 函数，扩展/公式/格式化一步都不必知道
 * 有父子关联这回事 —— 和「每条数据一个 sheet」是同一个套路（换取数函数，不改扩展逻辑）。
 *
 * <p>要子表数据时：
 * <ol>
 *   <li>先取主表（主表自己也可能是别人的子表，所以是递归的）；</li>
 *   <li>主表每一行调一次子表，把 {@code mappings} 里配的**主表字段值**填进子表参数；</li>
 *   <li>把 N 次的结果**首尾拼成一份**返回，并把主表这一行的字段合进它的每条子行
 *       （同名字段子表优先）。</li>
 * </ol>
 *
 * <p>第 3 步的拼接与合并是有意的：拼起来之后子表在模板里就是一条普通的扩展行带，
 * 主表的列跟着子行走（配 {@code groupType=group} 就是跨行合并的主表列），
 * 「主表 + 明细」一张清单直接铺出来。而「一行主表一张单据」那种排版走的是
 * {@code splitMode=perRow}：那边主表每次只有一行，这里自然也就只查那一行的子表。
 *
 * <p>纯 POJO，不依赖 Spring：引擎的取数抽象是可测的，这一层也要保持可测。
 */
public class LinkedDataFetcher implements BiFunction<String, Map<String, Object>, List<Map<String, Object>>> {

    /** 子表 code -> 关联配置。 */
    private final Map<String, DatasetLinkDTO> byChild;

    /** 真正干活的取数函数（数据集 -> 行数据）。 */
    private final BiFunction<String, Map<String, Object>, List<Map<String, Object>>> base;

    /** 主表最多驱动多少行子查询。 */
    private final int maxMasterRows;

    /**
     * 本次渲染的取数结果，key = 数据集 code。
     *
     * <p>一次渲染里参数是不变的，同一个 code 取一次就够：主表既要自己出数据、又要驱动子表，
     * 不缓存的话它会被打两遍。**实例只活一次渲染** —— {@code perRow} 是一行一个实例，
     * 每行的子表结果本来就不同，跨行复用会全串。
     */
    private final Map<String, List<Map<String, Object>>> cache = new LinkedHashMap<>();

    /** 正在解析中的 code，用来发现 A 的主表是 B、B 的主表又是 A 这种环。 */
    private final Deque<String> resolving = new ArrayDeque<>();

    private LinkedDataFetcher(Map<String, DatasetLinkDTO> byChild,
                              BiFunction<String, Map<String, Object>, List<Map<String, Object>>> base,
                              int maxMasterRows) {
        this.byChild = byChild;
        this.base = base;
        this.maxMasterRows = maxMasterRows;
    }

    /**
     * 给取数函数套上父子关联；没有配关联时原样返回，不多包一层。
     *
     * @param links         报表里配的父子关联（{@code content.datasetLinks}）
     * @param base          原始取数函数
     * @param maxMasterRows 主表最多驱动多少行子查询（{@code muzhou.report.max-link-rows}）
     */
    public static BiFunction<String, Map<String, Object>, List<Map<String, Object>>> wrap(
            List<DatasetLinkDTO> links,
            BiFunction<String, Map<String, Object>, List<Map<String, Object>>> base,
            int maxMasterRows) {
        Map<String, DatasetLinkDTO> byChild = indexByChild(links);
        return byChild.isEmpty() ? base : new LinkedDataFetcher(byChild, base, maxMasterRows);
    }

    /**
     * 子表 code -> 关联。一个子表挂两个主表直接报错：那意味着同一份子表数据有两种取法，
     * 谁也说不清该按哪个来，与其挑一个不如让人回去把配置改对。
     */
    public static Map<String, DatasetLinkDTO> indexByChild(List<DatasetLinkDTO> links) {
        Map<String, DatasetLinkDTO> byChild = new LinkedHashMap<>();
        if (links == null) {
            return byChild;
        }
        for (DatasetLinkDTO link : links) {
            if (link == null || !link.usable()) {
                continue;
            }
            DatasetLinkDTO exists = byChild.put(link.getChild(), link);
            if (exists != null) {
                throw new BizException("数据集[" + link.getChild() + "]同时挂在两个主表下（"
                        + exists.getMaster() + " / " + link.getMaster() + "），请在父子关联里只保留一条");
            }
        }
        return byChild;
    }

    /** 所有被当成子表的数据集 code。 */
    public static Set<String> childCodes(List<DatasetLinkDTO> links) {
        return new LinkedHashSet<>(indexByChild(links).keySet());
    }

    @Override
    public List<Map<String, Object>> apply(String code, Map<String, Object> params) {
        List<Map<String, Object>> cached = cache.get(code);
        if (cached != null) {
            return cached;
        }
        DatasetLinkDTO link = byChild.get(code);
        List<Map<String, Object>> rows = link == null
                ? nullToEmpty(base.apply(code, params))
                : fetchChild(link, params);
        cache.put(code, rows);
        return rows;
    }

    /** 主表每一行查一次子表，结果拼成一份。 */
    private List<Map<String, Object>> fetchChild(DatasetLinkDTO link, Map<String, Object> params) {
        String child = link.getChild();
        if (resolving.contains(child)) {
            throw new BizException("父子关联存在循环: " + String.join(" -> ", resolving) + " -> " + child);
        }
        resolving.push(child);
        try {
            // 主表也可能是别人的子表，所以走 apply 而不是 base：链式关联（A -> B -> C）照样成立
            List<Map<String, Object>> masterRows = apply(link.getMaster(), params);
            if (masterRows.size() > maxMasterRows) {
                throw new BizException("父子关联[" + link.getMaster() + " -> " + child + "]的主表返回了 "
                        + masterRows.size() + " 行，每行都要查一次子表，最多 " + maxMasterRows
                        + " 行，请先用参数把主表数据筛小");
            }

            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> masterRow : masterRows) {
                for (Map<String, Object> childRow : nullToEmpty(base.apply(child, childParams(link, params, masterRow)))) {
                    out.add(mergeMaster(childRow, masterRow));
                }
            }
            return out;
        } finally {
            resolving.pop();
        }
    }

    /**
     * 子表这一次的参数：报表参数打底，再按 {@code mappings} 覆盖上主表这一行的值。
     *
     * <p>覆盖而不是「没值才填」—— 关联配了就以主表的值为准，否则报表参数里同名的那个
     * 会把每一行的子查询都变成同一次查询。
     */
    private Map<String, Object> childParams(DatasetLinkDTO link, Map<String, Object> params,
                                            Map<String, Object> masterRow) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (params != null) {
            p.putAll(params);
        }
        if (link.getMappings() == null) {
            return p;
        }
        for (DatasetLinkDTO.Mapping m : link.getMappings()) {
            if (m == null || m.getParam() == null || m.getParam().isBlank()) {
                continue;
            }
            p.put(m.getParam().trim(), fieldValue(masterRow, m.getField()));
        }
        return p;
    }

    /**
     * 把主表这一行的字段合进子行，子表已有的同名字段优先。
     *
     * <p>合了之后模板里 {@code #{子表.主表字段}} 也取得到值 —— 主子拼成一张清单时，
     * 主表那几列和明细在同一条行带上，否则两边行数对不齐，根本排不到一行去。
     */
    private Map<String, Object> mergeMaster(Map<String, Object> childRow, Map<String, Object> masterRow) {
        Map<String, Object> merged = new LinkedHashMap<>(childRow);
        if (masterRow != null) {
            masterRow.forEach(merged::putIfAbsent);
        }
        return merged;
    }

    /** 行数据的 key 统一是小写（见 DatasetServiceImpl），字段名可能是按原样填的，两种都试。 */
    private Object fieldValue(Map<String, Object> row, String field) {
        if (row == null || field == null || field.isBlank()) {
            return null;
        }
        String name = field.trim();
        return row.containsKey(name) ? row.get(name) : row.get(name.toLowerCase());
    }

    private List<Map<String, Object>> nullToEmpty(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }
}
