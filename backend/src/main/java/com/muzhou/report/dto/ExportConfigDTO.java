package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导出设置：Excel / PDF / Word 下载下来叫什么名字。见 docs/CONTRACT.md §4。
 *
 * <p>文件名 = <b>报表名</b>（可关掉）+ <b>{@link #fields} 里的若干段</b>，按 {@link #separator} 拼接
 * —— 一批单据导出来才分得清哪份是哪份（`销售出库单_SO-2026-001_华东仓`）。
 *
 * <p>每一段有三种写法，同一份 fields 里可以混着来、**按数组顺序**拼：
 * <ul>
 *   <li>{@code orderNo} —— <b>主接口字段</b>，取那一行的值；</li>
 *   <li>{@code ${now}} —— <b>当前时间</b>，恒为 {@code yyyyMMddHHmmss}
 *       （紧凑时间戳，冒号斜杠这些文件名里不能用的字符一个都不出现）；</li>
 *   <li>{@code ${paramName}} —— <b>参数值</b>，取这次渲染实际用的那份参数
 *       （报表参数、全局参数、地址栏透传的都在里面，见 §5）。{@code now} 是保留名。</li>
 * </ul>
 *
 * <p><b>字段那几段只在主接口恰好一行时才有效</b>（{@link #nameRow}）：一份文件只有一个名字，
 * 主接口返回 200 条时第一行代表不了整份 —— 拼出来的 `销售出库单_SO-001` 里其实装着 200 张单，
 * 名字反而是误导。多条时那几段整段跳过，只剩报表名与 {@code ${now}} / {@code ${参数}}
 * 这些不看数据的段（后两种正是为此而设：多条数据的那一批也得分得清是哪次导的）。
 *
 * <p>这是一份**纯 POJO**：拼名字、洗掉文件名里不能用的字符都在这里做，
 * {@code RenderServiceImpl} 只负责把报表名、那一行数据和参数递进来。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExportConfigDTO implements Serializable {

    /** 拿不到任何一段时的最终兜底名（报表名也是空的时候） */
    private static final String FALLBACK = "report";

    /**
     * 文件名长度上限（字符）。Windows 单个文件名上限 255，留出扩展名与下载器可能追加的
     * 「(1)」还绰绰有余；主要是拦住「字段里塞了一整段备注」把名字撑成一屏。
     */
    private static final int MAX_LENGTH = 120;

    /** 文件名里不能出现的字符（Windows 比 Linux 严，按严的来），控制字符另外过滤 */
    private static final String ILLEGAL = "\\/:*?\"<>|";

    /** {@code ${now}} / {@code ${paramName}}：花括号里那个名字，写法同单元格里的参数占位符 */
    private static final Pattern TOKEN = Pattern.compile("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}");

    /** {@code ${now}} 里的保留名 —— 同名参数让位给它 */
    private static final String NOW = "now";

    /** 当前时间那一段的写法：紧凑时间戳，本身就不含要洗掉的字符 */
    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 文件名以报表名开头，默认开。关掉就是纯字段拼出来的名字 */
    private boolean withReportName = true;

    /** 拼进文件名的那几段（字段名 / {@code ${now}} / {@code ${参数}}），**按这里的先后顺序**拼；空 = 只用报表名 */
    private List<String> fields = new ArrayList<>();

    /** 各段之间的连接符，默认下划线；本身也会被洗一遍（用户填 `/` 的话文件名就废了） */
    private String separator = "_";

    /**
     * 主接口那一行数据里，哪一行拿来拼名字：**恰好一行时才是它，多于一行一律为 null**
     * （见类注释「字段那几段只在主接口恰好一行时才有效」）。null 时字段段整段跳过，
     * {@code ${now}} / {@code ${参数}} 照旧。
     */
    public static Map<String, Object> nameRow(List<Map<String, Object>> rows) {
        return rows != null && rows.size() == 1 ? rows.get(0) : null;
    }

    /**
     * 要不要为了拼名字去要主接口那一行数据 —— 没配字段就一次都不必问，
     * 只配了 {@code ${now}} / {@code ${参数}} 的同样不必问，那两种压根不看数据。
     */
    public boolean needsRow() {
        return fields != null && fields.stream()
                .anyMatch(f -> f != null && !f.isBlank() && !TOKEN.matcher(f.trim()).matches());
    }

    /**
     * 拼出文件名（<b>不含扩展名</b>，由 controller 补）。
     *
     * @param reportName 报表名
     * @param row        主接口那一行（{@link #nameRow}），可以为 null
     *                   —— 没有主接口 / 没取到数据 / <b>主接口不止一行</b>
     * @param params     这次渲染实际用的参数，{@code ${参数名}} 那几段从这里取；可以为 null
     */
    public String resolve(String reportName, Map<String, Object> row, Map<String, Object> params) {
        List<String> parts = new ArrayList<>();
        if (withReportName) {
            append(parts, reportName);
        }
        if (fields != null) {
            for (String field : fields) {
                append(parts, segment(field, row, params));
            }
        }
        String name = String.join(sanitize(separator == null ? "_" : separator), parts);
        // 一段都没拼出来（字段全空、又关了报表名）时退回报表名，再不行才是 report ——
        // 空文件名下载下来是一个没有主名的 `.xlsx`，多数浏览器直接存成 download.xlsx
        if (name.isBlank()) {
            name = sanitize(reportName);
        }
        return name.isBlank() ? FALLBACK : truncate(name);
    }

    private void append(List<String> parts, String raw) {
        String clean = sanitize(raw);
        if (!clean.isBlank()) {
            parts.add(clean);
        }
    }

    /** 一段拼出来是什么：`${now}` 是当前时间，`${xxx}` 取参数，其余当主接口字段名。 */
    private String segment(String field, Map<String, Object> row, Map<String, Object> params) {
        if (field == null || field.isBlank()) {
            return "";
        }
        Matcher m = TOKEN.matcher(field.trim());
        if (m.matches()) {
            String name = m.group(1);
            return NOW.equals(name) ? LocalDateTime.now().format(NOW_FORMAT) : valueText(params, name);
        }
        return valueText(row, field);
    }

    /**
     * 行数据的 key 大小写敏感（引擎按字段名原样取值），但字段名可能是用户手写的 ——
     * 两种都试一次，同 {@code ReportRenderEngine#fieldText}。参数名同理。
     */
    private String valueText(Map<String, Object> source, String key) {
        if (source == null || key == null || key.isBlank()) {
            return "";
        }
        Object v = source.containsKey(key) ? source.get(key) : source.get(key.toLowerCase());
        return v == null ? "" : String.valueOf(v);
    }

    /** 洗掉文件名里不能用的字符与换行/制表符，顺手把首尾空白与点去掉（Windows 不收结尾的点）。 */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c < 0x20 || c == 0x7F || ILLEGAL.indexOf(c) >= 0) {
                continue;
            }
            sb.append(c);
        }
        String s = sb.toString().trim();
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static String truncate(String name) {
        return name.length() <= MAX_LENGTH ? name : name.substring(0, MAX_LENGTH).trim();
    }
}
