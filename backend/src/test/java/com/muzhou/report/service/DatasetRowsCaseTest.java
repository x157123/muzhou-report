package com.muzhou.report.service;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.DatasetParseDTO;
import com.muzhou.report.dto.DatasetParseResultDTO;
import com.muzhou.report.dto.DatasetPreviewResultDTO;
import com.muzhou.report.entity.MzDatasetField;
import com.muzhou.report.service.impl.DatasetServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * api / json 数据集的行 key 大小写：<b>原样保留</b>。
 *
 * <p>渲染引擎按字段名取值是大小写敏感的（{@code ExpandProcessor} 的 {@code row.get(field)}），
 * 而字段名要么由「解析字段」按这里的 key 生成、要么由外部导入原样带着接口的 camelCase。
 * 这里一转小写，{@code #{code.suppliesName}} 就永远取不到数，而恰好全小写的字段又是好的 ——
 * 一张表一半列有数一半没有，最难查的那种。
 *
 * <p>纯 POJO 测试：json 类型的解析/预览不碰数据库与 JDBC，依赖传 null 即可 ——
 * 但 ObjectMapper 是要真用的（解析 jsonText 就靠它），所以那一个不能传 null。
 */
class DatasetRowsCaseTest {

    private final DatasetServiceImpl service =
            new DatasetServiceImpl(null, null, null, null, null, new MzProperties(),
                    JsonMapper.builder().findAndAddModules().build());

    private DatasetParseDTO json(String jsonText) {
        DatasetParseDTO dto = new DatasetParseDTO();
        dto.setType("json");
        dto.setResultType("list");
        dto.setJsonText(jsonText);
        return dto;
    }

    @Test
    @DisplayName("接口返回的 camelCase key 原样进入行数据，不被转成小写")
    void camelCaseKeysArePreserved() {
        DatasetPreviewResultDTO r = service.preview(json(
                "{\"code\":200,\"data\":[{\"seq\":1,\"suppliesName\":\"供电电力电缆\",\"brand\":\"/\"}]}"));

        assertEquals(List.of("seq", "suppliesName", "brand"), r.getColumns());
        Map<String, Object> row = r.getRecords().get(0);
        assertEquals("供电电力电缆", row.get("suppliesName"));
        assertFalse(row.containsKey("suppliesname"), "转小写的 key 不该出现，引擎按原名取值");
    }

    @Test
    @DisplayName("响应是单个对象（没有数组）时同样原样保留 key")
    void singleObjectKeepsKeyCase() {
        DatasetPreviewResultDTO r = service.preview(json("{\"totalPrice\":2400,\"unit\":\"m\"}"));

        assertEquals(List.of("totalPrice", "unit"), r.getColumns());
        assertTrue(r.getRecords().get(0).containsKey("totalPrice"));
    }

    @Test
    @DisplayName("「解析字段」按行 key 生成，于是字段名与取数时的 key 恒是同一个写法")
    void parsedFieldNamesMatchRowKeys() {
        DatasetParseResultDTO parsed = service.parse(json(
                "{\"data\":[{\"suppliesName\":\"电缆\",\"businessTotalPrice\":2400}]}"));

        List<String> names = parsed.getFields().stream().map(MzDatasetField::getFieldName).toList();
        assertEquals(List.of("suppliesName", "businessTotalPrice"), names);
    }
}
