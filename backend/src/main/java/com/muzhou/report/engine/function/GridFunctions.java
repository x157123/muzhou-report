package com.muzhou.report.engine.function;

import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.runtime.function.AbstractFunction;
import com.googlecode.aviator.runtime.function.AbstractVariadicFunction;
import com.googlecode.aviator.runtime.function.FunctionUtils;
import com.googlecode.aviator.runtime.type.AviatorObject;
import com.googlecode.aviator.runtime.type.AviatorRuntimeJavaType;
import com.muzhou.report.engine.A1RefUtils;
import com.muzhou.report.engine.CellFormatter;
import com.muzhou.report.engine.model.GridCell;
import com.muzhou.report.engine.model.RenderGrid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 注册 Aviator 自定义函数，见 docs/CONTRACT.md §6。
 *
 * <p>区间函数（SUM/AVG/...）需要访问「渲染后的网格」，网格通过 env 的
 * {@value #GRID_KEY} 传入，避免使用 ThreadLocal 带来的泄漏风险。
 */
public final class GridFunctions {

    public static final String GRID_KEY = "__grid__";

    private GridFunctions() {
    }

    public static void registerAll(AviatorEvaluatorInstance engine) {
        engine.addFunction(new RangeAggFunction("SUM"));
        engine.addFunction(new RangeAggFunction("AVG"));
        engine.addFunction(new RangeAggFunction("MAX"));
        engine.addFunction(new RangeAggFunction("MIN"));
        engine.addFunction(new RangeAggFunction("COUNT"));
        engine.addFunction(new IfFunction());
        engine.addFunction(new RoundFunction());
        engine.addFunction(new ConcatFunction());
        engine.addFunction(new DateFmtFunction());
        engine.addFunction(new NumFmtFunction());
        engine.addFunction(new NvlFunction());
        engine.addFunction(new AbsFunction());
        engine.addFunction(new PercentFunction());
    }

    /** 从 env 取出当前网格。 */
    static RenderGrid grid(Map<String, Object> env) {
        Object g = env == null ? null : env.get(GRID_KEY);
        return g instanceof RenderGrid rg ? rg : null;
    }

    /** 收集区间内所有可解析为数字的值。 */
    static List<BigDecimal> numbersIn(RenderGrid grid, String range) {
        List<BigDecimal> nums = new ArrayList<>();
        if (grid == null) {
            return nums;
        }
        int[] rg = A1RefUtils.parseRange(range);
        if (rg == null) {
            return nums;
        }
        for (int[] pos : A1RefUtils.cellsInRange(rg)) {
            GridCell cell = grid.get(pos[0], pos[1]);
            if (cell == null) {
                continue;
            }
            BigDecimal d = CellFormatter.toDecimal(cell.getValue());
            if (d != null) {
                nums.add(d);
            }
        }
        return nums;
    }

    /* ------------------------- 区间聚合 ------------------------- */

    /** SUM / AVG / MAX / MIN / COUNT，参数为 "A2:A10" 形式的区间字符串。 */
    static class RangeAggFunction extends AbstractFunction {
        private final String name;

        RangeAggFunction(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject arg1) {
            String range = FunctionUtils.getStringValue(arg1, env);
            List<BigDecimal> nums = numbersIn(grid(env), range);
            BigDecimal result = switch (name) {
                case "SUM" -> nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                case "COUNT" -> BigDecimal.valueOf(nums.size());
                case "AVG" -> nums.isEmpty() ? BigDecimal.ZERO
                        : nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(nums.size()), 6, RoundingMode.HALF_UP);
                case "MAX" -> nums.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                case "MIN" -> nums.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
                default -> BigDecimal.ZERO;
            };
            return AviatorRuntimeJavaType.valueOf(result);
        }
    }

    /* ------------------------- 通用函数 ------------------------- */

    static class IfFunction extends AbstractFunction {
        @Override
        public String getName() {
            return "IF";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject cond,
                                  AviatorObject a, AviatorObject b) {
            Object v = cond.getValue(env);
            boolean truthy = v instanceof Boolean bo ? bo : v != null && !"".equals(v);
            return AviatorRuntimeJavaType.valueOf(truthy ? a.getValue(env) : b.getValue(env));
        }
    }

    static class RoundFunction extends AbstractFunction {
        @Override
        public String getName() {
            return "ROUND";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject num, AviatorObject scale) {
            BigDecimal d = CellFormatter.toDecimal(num.getValue(env));
            int s = (int) FunctionUtils.getNumberValue(scale, env).doubleValue();
            return AviatorRuntimeJavaType.valueOf(d == null ? null : d.setScale(s, RoundingMode.HALF_UP));
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject num) {
            BigDecimal d = CellFormatter.toDecimal(num.getValue(env));
            return AviatorRuntimeJavaType.valueOf(d == null ? null : d.setScale(0, RoundingMode.HALF_UP));
        }
    }

    static class ConcatFunction extends AbstractVariadicFunction {
        @Override
        public String getName() {
            return "CONCAT";
        }

        @Override
        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {
            StringBuilder sb = new StringBuilder();
            if (args != null) {
                for (AviatorObject a : args) {
                    Object v = a.getValue(env);
                    if (v != null) {
                        sb.append(v);
                    }
                }
            }
            return AviatorRuntimeJavaType.valueOf(sb.toString());
        }
    }

    static class DateFmtFunction extends AbstractFunction {
        @Override
        public String getName() {
            return "DATEFMT";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject date, AviatorObject pattern) {
            LocalDateTime dt = CellFormatter.toDateTime(date.getValue(env));
            String p = FunctionUtils.getStringValue(pattern, env);
            if (dt == null) {
                return AviatorRuntimeJavaType.valueOf("");
            }
            return AviatorRuntimeJavaType.valueOf(DateTimeFormatter.ofPattern(p).format(dt));
        }
    }

    static class NumFmtFunction extends AbstractFunction {
        @Override
        public String getName() {
            return "NUMFMT";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject num, AviatorObject pattern) {
            BigDecimal d = CellFormatter.toDecimal(num.getValue(env));
            String p = FunctionUtils.getStringValue(pattern, env);
            if (d == null) {
                return AviatorRuntimeJavaType.valueOf("");
            }
            return AviatorRuntimeJavaType.valueOf(new java.text.DecimalFormat(p).format(d));
        }
    }

    static class NvlFunction extends AbstractFunction {
        @Override
        public String getName() {
            return "NVL";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject a, AviatorObject b) {
            Object v = a.getValue(env);
            boolean empty = v == null || (v instanceof CharSequence cs && cs.isEmpty());
            return AviatorRuntimeJavaType.valueOf(empty ? b.getValue(env) : v);
        }
    }

    static class AbsFunction extends AbstractFunction {
        @Override
        public String getName() {
            return "ABS";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject num) {
            BigDecimal d = CellFormatter.toDecimal(num.getValue(env));
            return AviatorRuntimeJavaType.valueOf(d == null ? null : d.abs());
        }
    }

    /** PERCENT(a, b) = a / b，b 为 0 时返回 0。 */
    static class PercentFunction extends AbstractFunction {
        @Override
        public String getName() {
            return "PERCENT";
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject a, AviatorObject b) {
            BigDecimal x = CellFormatter.toDecimal(a.getValue(env));
            BigDecimal y = CellFormatter.toDecimal(b.getValue(env));
            if (x == null || y == null || y.compareTo(BigDecimal.ZERO) == 0) {
                return AviatorRuntimeJavaType.valueOf(BigDecimal.ZERO);
            }
            return AviatorRuntimeJavaType.valueOf(x.divide(y, 6, RoundingMode.HALF_UP));
        }
    }
}
