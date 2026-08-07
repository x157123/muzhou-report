package com.muzhou.report.datasource;

import com.muzhou.report.common.BizException;
import com.muzhou.report.entity.MzDatasetParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据集 SQL 的参数解析器，见 docs/CONTRACT.md §5。
 *
 * <p>两种语法：
 * <ul>
 *   <li>{@code ${name}}  —— 替换为 JDBC 占位符 {@code ?} 并顺序绑定，天然防注入，推荐使用。</li>
 *   <li>{@code $!{name}} —— 直接字符串拼接，用于动态表名/排序字段这类无法用占位符的场景，
 *       因此必须过白名单校验。</li>
 * </ul>
 *
 * <p><b>处理顺序很关键</b>：必须先替换 {@code $!{}} 再替换 {@code ${}}，
 * 否则 {@code ${}} 的正则会把 {@code $!{name}} 里的 {@code {name}} 部分误当成普通参数。
 */
@Component
public class SqlParamParser {

    /** ${name} */
    private static final Pattern BIND_PATTERN = Pattern.compile("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}");

    /** $!{name} */
    private static final Pattern CONCAT_PATTERN = Pattern.compile("\\$!\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}");

    /** 拼接参数的白名单：仅允许标识符、逗号、点、空格（可组合出 "a.b desc, c asc"） */
    private static final Pattern CONCAT_WHITELIST = Pattern.compile("^[A-Za-z0-9_,. ]*$");

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
    };

    /** 解析结果。 */
    @Data
    @AllArgsConstructor
    public static class ParsedSql {
        /** ${x} 已替换为 ? 的可执行 SQL */
        private String sql;
        /** 与 ? 顺序一一对应的绑定值 */
        private List<Object> args;
        /** 与 args 顺序一一对应的参数名（便于日志排查） */
        private List<String> paramNames;
    }

    /**
     * 把带 {@code ${}} / {@code $!{}} 的 SQL 解析成可执行 SQL + 绑定参数。
     *
     * @param sql       原始 SQL
     * @param paramDefs 数据集的参数定义（可为 null）
     * @param values    调用方传入的参数值（可为 null）
     */
    public ParsedSql parse(String sql, List<MzDatasetParam> paramDefs, Map<String, Object> values) {
        if (sql == null || sql.isBlank()) {
            throw new BizException("SQL 不能为空");
        }
        Map<String, Object> resolved = resolveValues(paramDefs, values);

        // 第一步：$!{} 直接拼接（先做，避免被 ${} 的正则吃掉）
        String concatSql = replaceConcat(sql, resolved);

        // 第二步：${} 替换为 ?
        List<Object> args = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher m = BIND_PATTERN.matcher(concatSql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            names.add(name);
            // 参数未提供时绑定 null，交给 SQL 自身处理（如 IS NULL / COALESCE）
            args.add(resolved.get(name));
            m.appendReplacement(sb, Matcher.quoteReplacement("?"));
        }
        m.appendTail(sb);

        return new ParsedSql(sb.toString(), args, names);
    }

    /** 处理 $!{name}：白名单校验后直接拼接。 */
    private String replaceConcat(String sql, Map<String, Object> values) {
        Matcher m = CONCAT_PATTERN.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            Object v = values.get(name);
            String text = v == null ? "" : String.valueOf(v);
            if (!isSafeForConcat(text)) {
                throw new BizException("非法的拼接参数值: " + text);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(text));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 拼接值安全性校验：只允许标识符字符与排序关键字，杜绝注入。 */
    private boolean isSafeForConcat(String text) {
        if (text.isEmpty()) {
            return true;
        }
        return CONCAT_WHITELIST.matcher(text).matches();
    }

    /**
     * 提取 SQL 中出现的所有参数名（同时覆盖 {@code ${}} 与 {@code $!{}}），保持出现顺序。
     */
    public Set<String> extractParamNames(String sql) {
        Set<String> names = new LinkedHashSet<>();
        if (sql == null || sql.isBlank()) {
            return names;
        }
        Matcher cm = CONCAT_PATTERN.matcher(sql);
        while (cm.find()) {
            names.add(cm.group(1));
        }
        Matcher bm = BIND_PATTERN.matcher(sql);
        while (bm.find()) {
            names.add(bm.group(1));
        }
        return names;
    }

    /**
     * 按参数定义补默认值、做必填校验与类型转换。
     *
     * @return key=paramName 的最终值 Map
     */
    public Map<String, Object> resolveValues(List<MzDatasetParam> paramDefs, Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (input != null) {
            result.putAll(input);
        }
        if (paramDefs == null || paramDefs.isEmpty()) {
            return result;
        }
        for (MzDatasetParam def : paramDefs) {
            String name = def.getParamName();
            if (name == null || name.isBlank()) {
                continue;
            }
            Object raw = result.get(name);
            if (isEmpty(raw)) {
                raw = def.getDefaultValue();
            }
            boolean required = def.getRequired() != null && def.getRequired() == 1;
            if (required && isEmpty(raw)) {
                String label = def.getParamText() == null || def.getParamText().isBlank()
                        ? name : def.getParamText();
                throw new BizException("缺少必填参数: " + label);
            }
            result.put(name, convertValue(def.getParamType(), raw));
        }
        return result;
    }

    private boolean isEmpty(Object v) {
        return v == null || (v instanceof CharSequence cs && cs.toString().isBlank());
    }

    /**
     * 单值类型转换：string / number / date / boolean。
     */
    public Object convertValue(String paramType, Object raw) {
        String type = paramType == null ? "string" : paramType.toLowerCase();
        return switch (type) {
            case "number" -> toNumber(raw);
            case "date" -> toDate(raw);
            case "boolean" -> toBoolean(raw);
            default -> raw == null ? "" : String.valueOf(raw);
        };
    }

    private BigDecimal toNumber(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        if (raw instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new BizException("参数不是合法数字: " + s);
        }
    }

    private LocalDate toDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate d) {
            return d;
        }
        if (raw instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        if (raw instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        if (raw instanceof Date d) {
            return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        // 带时间的先截掉时间部分
        String datePart = s.length() > 10 ? s.substring(0, 10) : s;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(datePart, f);
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        throw new BizException("参数不是合法日期: " + s);
    }

    private Boolean toBoolean(Object raw) {
        if (raw == null) {
            return Boolean.FALSE;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s);
    }
}
