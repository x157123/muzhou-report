package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

/**
 * 版本切换规则，对应 {@code mz_report.version_config}，见 docs/CONTRACT.md §4。
 *
 * <p>规则是**报表级**的，不在 content 里：content 本身就是被版本化的那个东西，
 * 规则放进去就成了「每个版本各有一套怎么选自己」，逻辑成环。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VersionConfigDTO implements Serializable {

    /** 主接口字段（默认）：单据类报表用这条 */
    public static final String SOURCE_FIELD = "field";

    /** 报表参数：汇总类报表建议用这条（第一行的日期未必代表整张表） */
    public static final String SOURCE_PARAM = "param";

    /** 渲染当日 */
    public static final String SOURCE_NOW = "now";

    /** 判定值取不到时用默认版本（默认） */
    public static final String FALLBACK_DEFAULT = "default";

    /** 判定值取不到时直接报错 */
    public static final String FALLBACK_ERROR = "error";

    /** 判定值从哪来：{@code field} 主接口字段（默认）/ {@code param} 报表参数 / {@code now} 渲染当日 */
    private String source = SOURCE_FIELD;

    /** 字段名或参数名；{@code source=now} 时无意义 */
    private String field;

    /** 判定值取不到时：{@code default} 用默认版本（默认）/ {@code error} 直接报错 */
    private String fallback = FALLBACK_DEFAULT;

    /** 缺省规则：按主接口字段判定、取不到就用默认版本。老报表没有这一列，读出来是 null */
    public static VersionConfigDTO defaults() {
        return new VersionConfigDTO();
    }

    public boolean isFallbackError() {
        return FALLBACK_ERROR.equals(fallback);
    }

    /** 判定值来自数据（需要探一次主接口）还是来自参数/当日（不必取数）。 */
    public boolean needsProbe() {
        return !SOURCE_PARAM.equals(source) && !SOURCE_NOW.equals(source);
    }
}
