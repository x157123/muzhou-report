package com.muzhou.report.engine;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.Options;
import com.muzhou.report.engine.function.GridFunctions;
import com.muzhou.report.engine.model.RenderGrid;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aviator 表达式求值器。
 *
 * <p>使用独立的 {@link AviatorEvaluatorInstance} 而不是全局单例，避免自定义函数
 * 污染同一 JVM 内其它使用 Aviator 的组件。表达式编译结果做缓存——报表里同一个
 * 表达式往往要在成百上千个扩展行里重复求值。
 */
@Slf4j
@Component
public class FormulaEvaluator {

    private AviatorEvaluatorInstance engine;

    /**
     * 表达式原文 -> 编译结果。
     *
     * <p>报表里同一个表达式往往要在成百上千个扩展行里重复求值，编译一次很值。
     * 但 key 来自用户 content，**永不淘汰就是慢性泄漏** —— 每保存一版新版式就可能多出几条。
     * 满了整个清掉（见 {@link #cacheExpression}）：缓存丢了只是下次多编译一遍，不影响正确性，
     * 为这点收益引一个 LRU 依赖不值当。
     */
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    /** 缓存条数上限。一张报表里不同的表达式撑死几十条，1000 已经是很宽的余量。 */
    private static final int CACHE_LIMIT = 1000;

    @PostConstruct
    public void init() {
        engine = AviatorEvaluator.newInstance();
        // 关闭反射式的 new/静态方法调用，报表表达式没有这类需求，避免成为攻击面
        engine.setOption(Options.FEATURE_SET, com.googlecode.aviator.Feature.asSet(
                com.googlecode.aviator.Feature.Assignment,
                com.googlecode.aviator.Feature.If,
                com.googlecode.aviator.Feature.Let,
                com.googlecode.aviator.Feature.Return,
                com.googlecode.aviator.Feature.Lambda,
                com.googlecode.aviator.Feature.StringInterpolation));
        engine.setOption(Options.TRACE_EVAL, false);
        GridFunctions.registerAll(engine);
    }

    /**
     * 求值单个表达式。
     *
     * @param expression Aviator 表达式（不含外层 !{}）
     * @param params     报表参数
     * @param row        当前扩展行数据（可为 null）
     * @param datasets   全量数据集数据
     * @param grid       当前渲染网格（供区间函数使用）
     * @return 求值结果；出错返回 "#ERR: 原因"
     */
    public Object eval(String expression, Map<String, Object> params, Map<String, Object> row,
                       Map<String, List<Map<String, Object>>> datasets, RenderGrid grid) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        try {
            Expression exp = cacheExpression(expression);
            Map<String, Object> env = new HashMap<>();
            env.put("params", params == null ? Map.of() : params);
            env.put("row", row == null ? Map.of() : row);
            env.put("ds", datasets == null ? Map.of() : datasets);
            env.put(GridFunctions.GRID_KEY, grid);
            // 把当前行字段直接暴露成变量，便于写 !{amount * 0.1}
            if (row != null) {
                row.forEach((k, v) -> {
                    if (k != null && k.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        env.putIfAbsent(k, v);
                    }
                });
            }
            return exp.execute(env);
        } catch (Throwable e) {
            // 接 Throwable 而不是 Exception：特性集里虽然没开 While / ForLoop，但 Lambda + Let
            // 写得出无限递归，抛的是 StackOverflowError（Error 不是 Exception）。
            // 让它穿上去就是整个渲染挂掉，而一格算不出来本来只该显示 #ERR
            log.warn("表达式求值失败: {} -> {}", expression, e.toString());
            return "#ERR: " + e;
        }
    }

    /** 取编译结果，顺带守住缓存容量。 */
    private Expression cacheExpression(String expression) {
        Expression hit = cache.get(expression);
        if (hit != null) {
            return hit;
        }
        if (cache.size() >= CACHE_LIMIT) {
            log.info("表达式缓存超过 {} 条，整体清空", CACHE_LIMIT);
            cache.clear();
        }
        return cache.computeIfAbsent(expression, e -> engine.compile(e, true));
    }
}
