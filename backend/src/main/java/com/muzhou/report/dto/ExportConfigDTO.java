package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导出设置：Excel / PDF / Word 下载下来叫什么名字。见 docs/CONTRACT.md §4。
 *
 * <p>文件名 = <b>报表名</b>（可关掉）+ <b>主接口的若干字段值</b>，按 {@link #separator} 拼接
 * —— 一批单据导出来才分得清哪份是哪份（`销售出库单_SO-2026-001_华东仓`）。
 *
 * <p><b>取的是主接口第一行</b>：一份文件只有一个名字，而按条拆单据时整批仍是一份文件，
 * 所以拿第一条的值代表整份（同「非拆分报表的版本判定取第一行」那条规则）。
 *
 * <p>这是一份**纯 POJO**：拼名字、洗掉文件名里不能用的字符都在这里做，
 * {@code RenderServiceImpl} 只负责把报表名和那一行数据递进来。
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

    /** 文件名以报表名开头，默认开。关掉就是纯字段拼出来的名字 */
    private boolean withReportName = true;

    /** 拼进文件名的主接口字段，**按这里的先后顺序**拼；空 = 只用报表名 */
    private List<String> fields = new ArrayList<>();

    /** 各段之间的连接符，默认下划线；本身也会被洗一遍（用户填 `/` 的话文件名就废了） */
    private String separator = "_";

    /** 要不要为了拼名字去要主接口那一行数据 —— 没配字段就一次都不必问。 */
    public boolean needsRow() {
        return fields != null && fields.stream().anyMatch(f -> f != null && !f.isBlank());
    }

    /**
     * 拼出文件名（<b>不含扩展名</b>，由 controller 补）。
     *
     * @param reportName 报表名
     * @param row        主接口第一行，可以为 null（没有主接口 / 没取到数据）
     */
    public String resolve(String reportName, Map<String, Object> row) {
        List<String> parts = new ArrayList<>();
        if (withReportName) {
            append(parts, reportName);
        }
        if (fields != null) {
            for (String field : fields) {
                append(parts, fieldText(row, field));
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

    /**
     * 行数据的 key 大小写敏感（引擎按字段名原样取值），但字段名可能是用户手写的 ——
     * 两种都试一次，同 {@code ReportRenderEngine#fieldText}。
     */
    private String fieldText(Map<String, Object> row, String field) {
        if (row == null || field == null || field.isBlank()) {
            return "";
        }
        Object v = row.containsKey(field) ? row.get(field) : row.get(field.toLowerCase());
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
