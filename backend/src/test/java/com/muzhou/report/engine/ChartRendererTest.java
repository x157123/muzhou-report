package com.muzhou.report.engine;

import com.muzhou.report.dto.ChartConfigDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图表出图测试：{@link ChartRenderer}。
 *
 * <p>纯 POJO，不启动 Spring —— 手工构造 {@code List<Map>} 直接调，同 {@code RenderEngineTest}。
 * 画出来的图长得好不好看断言不了，能断言的是：**数据聚合对不对**、**尺寸对不对**、
 * **坏数据不会把整份渲染带崩**（这三样才是会出故障的地方）。
 */
class ChartRendererTest {

    private Map<String, Object> row(String month, Object amount, Object qty) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("month", month);
        m.put("amount", amount);
        m.put("qty", qty);
        return m;
    }

    private List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("1月", 100, 2));
        rows.add(row("1月", 200, 3));
        rows.add(row("2月", 300, 1));
        return rows;
    }

    private ChartConfigDTO config(String type, String aggregate) {
        ChartConfigDTO cfg = new ChartConfigDTO();
        cfg.setChartType(type);
        cfg.setCategoryField("month");
        ChartConfigDTO.Series s = new ChartConfigDTO.Series();
        s.setField("amount");
        s.setName("销售额");
        s.setAggregate(aggregate);
        cfg.setSeries(new ArrayList<>(List.of(s)));
        return cfg;
    }

    @Test
    @DisplayName("按类目分组求和，类目顺序按首次出现")
    void aggregateSum() {
        ChartRenderer.ChartData data = ChartRenderer.aggregate(config("bar", "sum"), rows());
        assertEquals(List.of("1月", "2月"), data.categories());
        assertEquals(1, data.series().size());
        assertEquals("销售额", data.series().get(0).name());
        assertEquals(300.0, data.series().get(0).values()[0], 1e-9);
        assertEquals(300.0, data.series().get(0).values()[1], 1e-9);
    }

    @Test
    @DisplayName("avg / max / min / count / none 各自的语义")
    void aggregateOthers() {
        assertEquals(150.0, ChartRenderer.aggregate(config("bar", "avg"), rows())
                .series().get(0).values()[0], 1e-9);
        assertEquals(200.0, ChartRenderer.aggregate(config("bar", "max"), rows())
                .series().get(0).values()[0], 1e-9);
        assertEquals(100.0, ChartRenderer.aggregate(config("bar", "min"), rows())
                .series().get(0).values()[0], 1e-9);
        // count 数的是「有值的行数」，不要求那一格是数字
        assertEquals(2.0, ChartRenderer.aggregate(config("bar", "count"), rows())
                .series().get(0).values()[0], 1e-9);
        // none = 不聚合，同类目取第一条
        assertEquals(100.0, ChartRenderer.aggregate(config("bar", "none"), rows())
                .series().get(0).values()[0], 1e-9);
    }

    @Test
    @DisplayName("类目值为空的行归到 (空)，不丢掉 —— 丢了图上的合计与表里对不上")
    void blankCategory() {
        List<Map<String, Object>> rows = new ArrayList<>(rows());
        rows.add(row(null, 50, 1));
        rows.add(row("", 60, 1));
        ChartRenderer.ChartData data = ChartRenderer.aggregate(config("bar", "sum"), rows);
        assertEquals(3, data.categories().size());
        assertTrue(data.categories().contains(ChartRenderer.EMPTY_CATEGORY));
        assertEquals(110.0, data.series().get(0).values()[2], 1e-9);
    }

    @Test
    @DisplayName("类目数超过上限时截断，并标记 truncated")
    void truncate() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(row("类目" + i, i, 1));
        }
        ChartConfigDTO cfg = config("bar", "sum");
        cfg.setMaxCategories(10);
        ChartRenderer.ChartData data = ChartRenderer.aggregate(cfg, rows);
        assertEquals(10, data.categories().size());
        assertTrue(data.truncated());
    }

    @Test
    @DisplayName("字段名大小写：先按原样取，取不到退回小写（同 fieldText 的兜底）")
    void fieldCase() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("suppliesname", "甲");
        assertEquals("甲", ChartRenderer.value(row, "suppliesName"));
        row.put("suppliesName", "乙");
        assertEquals("乙", ChartRenderer.value(row, "suppliesName"));
    }

    @Test
    @DisplayName("数值解析：数字/字符串/千分位/货币符号/百分号")
    void numbers() {
        assertEquals(12.5, ChartRenderer.toDouble(12.5), 1e-9);
        assertEquals(1234.0, ChartRenderer.toDouble("1,234"), 1e-9);
        assertEquals(1234.56, ChartRenderer.toDouble("¥1,234.56"), 1e-9);
        assertEquals(0.15, ChartRenderer.toDouble("15%"), 1e-9);
        assertEquals(99.0, ChartRenderer.toDouble(new BigDecimal("99")), 1e-9);
        assertNull(ChartRenderer.toDouble("待定"));
        assertNull(ChartRenderer.toDouble(null));
        assertNull(ChartRenderer.toDouble(""));
    }

    @Test
    @DisplayName("刻度间隔取 1/2/5×10^n，轴上不出现 37、74 这种读不出来的数")
    void ticks() {
        assertEquals(2.0, ChartRenderer.niceStep(10), 1e-9);
        assertEquals(20.0, ChartRenderer.niceStep(100), 1e-9);
        assertEquals(200.0, ChartRenderer.niceStep(1000), 1e-9);
        // 退化输入不许抛异常（数据全 0 时 range 就是 0）
        assertEquals(1.0, ChartRenderer.niceStep(0), 1e-9);
        assertEquals(1.0, ChartRenderer.niceStep(Double.NaN), 1e-9);
    }

    @Test
    @DisplayName("刻度文字：大数带千分位，小数去掉末尾的 0")
    void format() {
        assertEquals("1,234", ChartRenderer.fmt(1234));
        assertEquals("0.5", ChartRenderer.fmt(0.5));
        assertEquals("12", ChartRenderer.fmt(12));
        assertEquals("-3.25", ChartRenderer.fmt(-3.25));
    }

    @Test
    @DisplayName("五种图都出得来一张 PNG，尺寸 = 目标框 × 超采样倍数")
    void drawAllTypes() throws Exception {
        for (String type : List.of("bar", "hbar", "line", "area", "pie")) {
            ChartConfigDTO cfg = config(type, "sum");
            cfg.setTitle("月度销售");
            cfg.setShowValueLabels(true);
            String uri = ChartRenderer.dataUri(cfg, rows(), 400, 240);
            assertNotNull(uri, type + " 应该画得出来");
            assertTrue(uri.startsWith("data:image/png;base64,"), type + " 应该是 PNG data URI");
            BufferedImage img = decode(uri);
            assertEquals(400 * ChartRenderer.SUPERSAMPLE, img.getWidth(), type + " 宽");
            assertEquals(240 * ChartRenderer.SUPERSAMPLE, img.getHeight(), type + " 高");
        }
    }

    @Test
    @DisplayName("出图尺寸钳在上下限之间，别为一个巨大的合并区画出几十 MB")
    void clampSize() throws Exception {
        BufferedImage tiny = decode(ChartRenderer.dataUri(config("bar", "sum"), rows(), 10, 5));
        assertEquals(ChartRenderer.MIN_PX, tiny.getWidth());
        assertEquals(ChartRenderer.MIN_PX, tiny.getHeight());

        BufferedImage huge = decode(ChartRenderer.dataUri(config("bar", "sum"), rows(), 9000, 9000));
        assertEquals(ChartRenderer.MAX_PX, huge.getWidth());
        assertEquals(ChartRenderer.MAX_PX, huge.getHeight());
    }

    @Test
    @DisplayName("多系列并排画得出来")
    void multiSeries() {
        ChartConfigDTO cfg = config("bar", "sum");
        ChartConfigDTO.Series qty = new ChartConfigDTO.Series();
        qty.setField("qty");
        qty.setName("单量");
        qty.setAggregate("count");
        cfg.getSeries().add(qty);
        ChartRenderer.ChartData data = ChartRenderer.aggregate(cfg, rows());
        assertEquals(2, data.series().size());
        assertNotNull(ChartRenderer.dataUri(cfg, rows(), 400, 240));
    }

    @Test
    @DisplayName("坏数据一律出空格（返回 null），不许把整份渲染带崩")
    void badInput() {
        assertNull(ChartRenderer.dataUri(null, rows(), 400, 240), "没有配置");
        assertNull(ChartRenderer.dataUri(config("bar", "sum"), null, 400, 240), "没有数据");
        assertNull(ChartRenderer.dataUri(config("bar", "sum"), List.of(), 400, 240), "空数据集");

        ChartConfigDTO noCategory = config("bar", "sum");
        noCategory.setCategoryField("");
        assertNull(ChartRenderer.dataUri(noCategory, rows(), 400, 240), "没配类目字段");

        ChartConfigDTO noSeries = config("bar", "sum");
        noSeries.setSeries(new ArrayList<>());
        assertNull(ChartRenderer.dataUri(noSeries, rows(), 400, 240), "没配系列");

        ChartConfigDTO badField = config("bar", "sum");
        badField.getSeries().get(0).setField("不存在的字段");
        // 字段取不到值 = 全 0，图还是要画出来（轴上是 0~1 的空轴），不是崩掉
        assertNotNull(ChartRenderer.dataUri(badField, rows(), 400, 240));
    }

    @Test
    @DisplayName("负数：柱状图的 0 线不在底边上，也不许抛异常")
    void negatives() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("盈", 100, 1));
        rows.add(row("亏", -80, 1));
        assertNotNull(ChartRenderer.dataUri(config("bar", "sum"), rows, 400, 240));
        assertNotNull(ChartRenderer.dataUri(config("line", "sum"), rows, 400, 240));
        assertNotNull(ChartRenderer.dataUri(config("area", "sum"), rows, 400, 240));
    }

    @Test
    @DisplayName("饼图数值合计为 0 时出空格 —— 画不出一个 0 度的饼")
    void pieAllZero() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("甲", 0, 1));
        rows.add(row("乙", 0, 1));
        // 画布本身出得来（标题/图例还在），扇区画不出来只是留白；这里断言不抛异常
        assertNotNull(ChartRenderer.dataUri(config("pie", "sum"), rows, 400, 240));
    }

    private BufferedImage decode(String dataUri) throws Exception {
        assertNotNull(dataUri);
        byte[] bytes = Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(",") + 1));
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
}
