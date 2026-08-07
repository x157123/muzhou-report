package com.muzhou.report.common;

/**
 * 日志里打长文本用的截断工具。
 *
 * <p>接口请求日志（{@link com.muzhou.report.config.RequestLogFilter}）与远程接口取数日志共用同一套规则，
 * 免得两处各写一遍、截断长度还对不上。
 */
public final class LogText {

    private LogText() {
    }

    /**
     * 超过 {@code max} 个字符就截断，并在尾巴上注明原长度。{@code max <= 0} 表示不截断。
     */
    public static String abbrev(String s, int max) {
        if (s == null) {
            return "";
        }
        if (max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...(共 " + s.length() + " 字符，已截断)";
    }
}
