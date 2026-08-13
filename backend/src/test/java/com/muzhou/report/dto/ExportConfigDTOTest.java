package com.muzhou.report.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出文件名的拼法，见 docs/CONTRACT.md §4「导出设置」。
 *
 * <p>这块的边界都在「拼不出来怎么办」上：字段没值、名字里带着 Windows 不收的字符、
 * 一段都拼不出来 —— 每一种都不能拼出一个下载下来打不开或者没有主名的文件。
 */
class ExportConfigDTOTest {

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            row.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return row;
    }

    @Test
    @DisplayName("报表名 + 字段值按顺序拼，分隔符可换")
    void joinsReportNameAndFields() {
        ExportConfigDTO cfg = new ExportConfigDTO();
        cfg.setFields(List.of("orderNo", "warehouse"));

        assertEquals("销售出库单_SO-001_华东仓",
                cfg.resolve("销售出库单", row("orderNo", "SO-001", "warehouse", "华东仓")));

        cfg.setSeparator("-");
        assertEquals("销售出库单-SO-001-华东仓",
                cfg.resolve("销售出库单", row("orderNo", "SO-001", "warehouse", "华东仓")));
    }

    @Test
    @DisplayName("关掉报表名就是纯字段拼出来的名字")
    void withoutReportName() {
        ExportConfigDTO cfg = new ExportConfigDTO();
        cfg.setWithReportName(false);
        cfg.setFields(List.of("orderNo"));

        assertEquals("SO-001", cfg.resolve("销售出库单", row("orderNo", "SO-001")));
    }

    @Test
    @DisplayName("字段没值整段跳过，不留下空的连接符")
    void skipsBlankFields() {
        ExportConfigDTO cfg = new ExportConfigDTO();
        cfg.setFields(List.of("orderNo", "warehouse"));

        assertEquals("销售出库单_SO-001",
                cfg.resolve("销售出库单", row("orderNo", "SO-001", "warehouse", "  ")));
        assertEquals("销售出库单_SO-001",
                cfg.resolve("销售出库单", row("orderNo", "SO-001")));
    }

    @Test
    @DisplayName("字段名大小写对不上时退回小写再试一次")
    void fallsBackToLowerCaseKey() {
        ExportConfigDTO cfg = new ExportConfigDTO();
        cfg.setWithReportName(false);
        cfg.setFields(List.of("OrderNo"));

        assertEquals("SO-001", cfg.resolve("单据", row("orderno", "SO-001")));
    }

    @Test
    @DisplayName("洗掉文件名里不能用的字符（连接符本身也洗）")
    void sanitizesIllegalChars() {
        ExportConfigDTO cfg = new ExportConfigDTO();
        cfg.setFields(List.of("period"));
        cfg.setSeparator("/");

        // `2026/01` 里的斜杠、报表名里的冒号都要没掉，否则下载下来是个非法文件名
        assertEquals("经营分析202601", cfg.resolve("经营:分析", row("period", "2026/01")));
    }

    @Test
    @DisplayName("一段都拼不出来时退回报表名，报表名也空才是 report")
    void fallsBackToReportName() {
        ExportConfigDTO cfg = new ExportConfigDTO();
        cfg.setWithReportName(false);
        cfg.setFields(List.of("orderNo"));

        assertEquals("销售出库单", cfg.resolve("销售出库单", Map.of()));
        assertEquals("report", cfg.resolve("  ", null));
    }

    @Test
    @DisplayName("没配字段就不必去要主接口那一行数据")
    void needsRowOnlyWhenFieldsConfigured() {
        ExportConfigDTO cfg = new ExportConfigDTO();
        assertFalse(cfg.needsRow());

        cfg.setFields(List.of("  "));
        assertFalse(cfg.needsRow());

        cfg.setFields(List.of("orderNo"));
        assertTrue(cfg.needsRow());
    }

    @Test
    @DisplayName("名字过长时截断，别拼出一屏那么长的文件名")
    void truncatesOverlongName() {
        ExportConfigDTO cfg = new ExportConfigDTO();
        cfg.setWithReportName(false);
        cfg.setFields(List.of("remark"));

        String name = cfg.resolve("单据", row("remark", "备".repeat(300)));
        assertEquals(120, name.length());
    }

    @Test
    @DisplayName("老报表没有 exportConfig 时文件名就是报表名")
    void contentWithoutConfigUsesReportName() {
        ReportContentDTO content = new ReportContentDTO();
        content.setExportConfig(null);

        assertEquals("销售出库单", content.exportFileName("销售出库单", row("orderNo", "SO-001")));
    }
}
