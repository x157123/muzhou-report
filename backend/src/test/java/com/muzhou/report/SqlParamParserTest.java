package com.muzhou.report;

import com.muzhou.report.common.BizException;
import com.muzhou.report.datasource.SqlParamParser;
import com.muzhou.report.entity.MzDatasetParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlParamParser 单元测试（纯 POJO，不启动 Spring）。
 */
class SqlParamParserTest {

    private final SqlParamParser parser = new SqlParamParser();

    private MzDatasetParam param(String name, String type, String def, int required) {
        MzDatasetParam p = new MzDatasetParam();
        p.setParamName(name);
        p.setParamText(name);
        p.setParamType(type);
        p.setDefaultValue(def);
        p.setRequired(required);
        return p;
    }

    @Test
    @DisplayName("多参数按出现顺序替换为 ? 并绑定")
    void bindMultipleParams() {
        String sql = "SELECT * FROM t WHERE region = ${region} AND amount > ${minAmount}";
        SqlParamParser.ParsedSql r = parser.parse(sql, null, Map.of("region", "华东", "minAmount", 100));

        assertEquals("SELECT * FROM t WHERE region = ? AND amount > ?", r.getSql());
        assertEquals(List.of("华东", 100), r.getArgs());
        assertEquals(List.of("region", "minAmount"), r.getParamNames());
    }

    @Test
    @DisplayName("同名参数出现两次则绑定两次")
    void bindRepeatedParam() {
        String sql = "SELECT * FROM t WHERE (${region} = '' OR region = ${region})";
        SqlParamParser.ParsedSql r = parser.parse(sql, null, Map.of("region", "华北"));

        assertEquals("SELECT * FROM t WHERE (? = '' OR region = ?)", r.getSql());
        assertEquals(2, r.getArgs().size());
        assertEquals(List.of("华北", "华北"), r.getArgs());
    }

    @Test
    @DisplayName("未提供的参数绑定 null，不抛异常")
    void missingParamBindsNull() {
        SqlParamParser.ParsedSql r = parser.parse("SELECT * FROM t WHERE a = ${nope}", null, Map.of());
        assertEquals(1, r.getArgs().size());
        assertNull(r.getArgs().get(0));
    }

    @Test
    @DisplayName("$!{} 合法值直接拼接进 SQL")
    void concatSafeValue() {
        String sql = "SELECT * FROM t ORDER BY $!{sortField}";
        SqlParamParser.ParsedSql r = parser.parse(sql, null, Map.of("sortField", "order_date desc"));

        assertEquals("SELECT * FROM t ORDER BY order_date desc", r.getSql());
        assertTrue(r.getArgs().isEmpty());
    }

    @Test
    @DisplayName("$!{} 非法值被白名单拒绝")
    void concatRejectsInjection() {
        String sql = "SELECT * FROM t ORDER BY $!{sortField}";
        BizException e = assertThrows(BizException.class,
                () -> parser.parse(sql, null, Map.of("sortField", "1; DROP TABLE t")));
        assertTrue(e.getMessage().contains("非法的拼接参数值"));
    }

    @Test
    @DisplayName("$!{} 与 ${} 混用时互不干扰")
    void concatAndBindTogether() {
        String sql = "SELECT * FROM t WHERE region = ${region} ORDER BY $!{sortField}";
        SqlParamParser.ParsedSql r = parser.parse(sql, null,
                Map.of("region", "华南", "sortField", "amount"));

        assertEquals("SELECT * FROM t WHERE region = ? ORDER BY amount", r.getSql());
        assertEquals(List.of("华南"), r.getArgs());
    }

    @Test
    @DisplayName("extractParamNames 同时抓到 ${} 与 $!{}")
    void extractNames() {
        Set<String> names = parser.extractParamNames(
                "SELECT * FROM t WHERE a = ${a} AND b = ${b} ORDER BY $!{sortField}");
        assertEquals(Set.of("a", "b", "sortField"), names);
    }

    @Test
    @DisplayName("resolveValues 用默认值补齐缺失参数")
    void resolveFillsDefault() {
        List<MzDatasetParam> defs = List.of(param("region", "string", "华东", 0));
        Map<String, Object> values = parser.resolveValues(defs, Map.of());
        assertEquals("华东", values.get("region"));
    }

    @Test
    @DisplayName("resolveValues 对必填缺值抛异常")
    void resolveRequiredMissing() {
        List<MzDatasetParam> defs = List.of(param("region", "string", "", 1));
        BizException e = assertThrows(BizException.class, () -> parser.resolveValues(defs, Map.of()));
        assertTrue(e.getMessage().contains("缺少必填参数"));
    }

    @Test
    @DisplayName("convertValue 按类型转换")
    void convertValues() {
        assertEquals(new BigDecimal("12.5"), parser.convertValue("number", "12.5"));
        assertEquals(BigDecimal.ZERO, parser.convertValue("number", ""));
        assertEquals(LocalDate.of(2025, 1, 8), parser.convertValue("date", "2025-01-08"));
        assertEquals(LocalDate.of(2025, 1, 8), parser.convertValue("date", "2025-01-08 13:20:00"));
        assertEquals(Boolean.TRUE, parser.convertValue("boolean", "1"));
        assertEquals(Boolean.FALSE, parser.convertValue("boolean", "no"));
        assertEquals("abc", parser.convertValue("string", "abc"));
        assertEquals("", parser.convertValue("string", null));
    }

    @Test
    @DisplayName("number 类型非法值抛异常")
    void invalidNumber() {
        assertThrows(BizException.class, () -> parser.convertValue("number", "abc"));
    }
}
