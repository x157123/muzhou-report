package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 版本的一条**匹配条件**，对应 {@code mz_report_version.match_rules} 里的一项，见 docs/CONTRACT.md §4.1。
 *
 * <p>时间那一维（{@code effective_from} 推出来的生效区间）只说得清「什么时候起换版式」，
 * 说不清「单据类型 A 走这版、类型 B 走那版」。所以每个版本可以再自带一组条件，
 * 同一版内**多条是 AND**；条件全满足的版本才参与时间区间推导。
 *
 * <p>规则挂在**版本行**上而不是报表级：报表级只放得下一套判定依据，放不下「每一版各自适用于什么」。
 * 老版本没有条件 = 无条件匹配（特异度 0），所以这一维加上来对老报表是零影响的。
 *
 * <p><b>值比较一律按文本、忽略大小写与首尾空格</b>（两边都是数字时按数值比，免得 {@code 1} 与
 * {@code 1.0} 判成不等）—— 判定值来自数据库/接口，同一个类型码大小写不一致的比比皆是。
 * 注意这跟「按字段名取值大小写敏感」不是一回事（见 CLAUDE.md「数据集与动态数据源」）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionMatchRuleDTO implements Serializable {

    /** 值取自主接口字段（默认）：拆分报表时就是**这一条数据**的该字段 */
    public static final String SOURCE_FIELD = "field";

    /** 值取自报表参数 */
    public static final String SOURCE_PARAM = "param";

    public static final String OP_EQ = "eq";
    public static final String OP_NE = "ne";
    public static final String OP_IN = "in";
    public static final String OP_NOT_IN = "notIn";
    public static final String OP_CONTAINS = "contains";
    public static final String OP_EMPTY = "empty";
    public static final String OP_NOT_EMPTY = "notEmpty";

    /** 多值运算符（{@code in} / {@code notIn}）的分隔符 */
    private static final String SEPARATOR = ",";

    /** 值从哪来：{@code field} 主接口字段（默认）/ {@code param} 报表参数 */
    private String source = SOURCE_FIELD;

    /** 字段名或参数名 */
    private String field;

    /** 比较方式：{@code eq}(默认) / {@code ne} / {@code in} / {@code notIn} / {@code contains} / {@code empty} / {@code notEmpty} */
    private String op = OP_EQ;

    /** 比较用的那个值；{@code in} / {@code notIn} 写成逗号分隔的一串；{@code empty} / {@code notEmpty} 时无意义 */
    private String value;

    /** 字段名空的条目是界面上那一行还没填完，一律忽略（不参与匹配、也不算进特异度）。 */
    public boolean isValid() {
        return field != null && !field.isBlank();
    }

    /** 值要不要去主接口那一行取 —— 有一条这样的条件就得探一次主接口。 */
    public boolean isFieldSourced() {
        return isValid() && !SOURCE_PARAM.equals(source);
    }

    /** 这条条件成不成立。{@code raw} 是按 {@link #field} 取回来的原始值，取不到就是 null。 */
    public boolean test(Object raw) {
        String actual = text(raw);
        String expect = text(value);
        return switch (op == null || op.isBlank() ? OP_EQ : op) {
            case OP_NE -> !eq(actual, expect);
            case OP_IN -> in(actual);
            case OP_NOT_IN -> !in(actual);
            // 空值不算「包含」：actual 为空时 contains("") 会恒真，那不是用户想要的
            case OP_CONTAINS -> actual != null && !actual.isEmpty() && expect != null
                    && lower(actual).contains(lower(expect));
            case OP_EMPTY -> actual == null || actual.isEmpty();
            case OP_NOT_EMPTY -> actual != null && !actual.isEmpty();
            default -> eq(actual, expect);
        };
    }

    /** 给人看的一句话：{@code type1=A}、{@code type2∈X,Y}，进 {@code versionMatch} 与体检结果。 */
    public String describe() {
        String f = field == null ? "" : field;
        return switch (op == null || op.isBlank() ? OP_EQ : op) {
            case OP_NE -> f + "≠" + show();
            case OP_IN -> f + "∈" + show();
            case OP_NOT_IN -> f + "∉" + show();
            case OP_CONTAINS -> f + "包含" + show();
            case OP_EMPTY -> f + "为空";
            case OP_NOT_EMPTY -> f + "不为空";
            default -> f + "=" + show();
        };
    }

    /** 一组条件连成一句：{@code type1=A 且 type2∈X,Y}。 */
    public static String describe(List<VersionMatchRuleDTO> rules) {
        if (rules == null || rules.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (VersionMatchRuleDTO r : rules) {
            if (r == null || !r.isValid()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" 且 ");
            }
            sb.append(r.describe());
        }
        return sb.toString();
    }

    /* ------------------------------ 内部 ------------------------------ */

    private String show() {
        return value == null ? "" : value.trim();
    }

    private boolean in(String actual) {
        if (actual == null || value == null) {
            return false;
        }
        for (String one : split()) {
            if (eq(actual, one)) {
                return true;
            }
        }
        return false;
    }

    private List<String> split() {
        List<String> out = new ArrayList<>();
        for (String s : Arrays.asList(value.split(SEPARATOR))) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 两边都是数字时按数值比（{@code 1} 与 {@code 1.0} 相等），否则按文本比、忽略大小写。 */
    private static boolean eq(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        BigDecimal na = number(a);
        BigDecimal nb = number(b);
        if (na != null && nb != null) {
            return na.compareTo(nb) == 0;
        }
        return a.equalsIgnoreCase(b);
    }

    private static BigDecimal number(String s) {
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
