package com.muzhou.report.engine;

import com.muzhou.report.dto.CellConfigDTO;
import com.muzhou.report.dto.ChartConfigDTO;
import com.muzhou.report.engine.model.GridCell;
import com.muzhou.report.engine.model.RenderGrid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图表出图 pass 测试：{@link ChartProcessor}。
 *
 * <p>这里断言的是**几何与副作用**：目标框按合并区算得对不对、设计器那段占位文本有没有被清掉、
 * 图有没有挂到 {@code GridCell#image} 上（下游三条导出路认的就是它）。
 */
class ChartProcessorTest {

    private ChartConfigDTO chartConfig() {
        ChartConfigDTO chart = new ChartConfigDTO();
        chart.setChartType("bar");
        chart.setCategoryField("month");
        ChartConfigDTO.Series s = new ChartConfigDTO.Series();
        s.setField("amount");
        s.setAggregate("sum");
        chart.setSeries(new ArrayList<>(List.of(s)));
        return chart;
    }

    private CellConfigDTO cellConfig() {
        CellConfigDTO cfg = new CellConfigDTO();
        cfg.setType("chart");
        cfg.setDatasetCode("orders");
        cfg.setChart(chartConfig());
        return cfg;
    }

    private Map<String, List<Map<String, Object>>> datasets() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("month", "1月");
        row.put("amount", 100);
        Map<String, List<Map<String, Object>>> datasets = new LinkedHashMap<>();
        datasets.put("orders", List.of(row));
        return datasets;
    }

    /** 放一个带占位文本的图表格进网格（模拟 ExpandProcessor 出来的样子）。 */
    private GridCell putChartCell(RenderGrid grid, int r, int c) {
        GridCell cell = new GridCell(r, c);
        cell.setValue("[图表] 月度销售");
        cell.setDisplay("[图表] 月度销售");
        grid.put(cell);
        return cell;
    }

    @Test
    @DisplayName("单格：没设过行高列宽时用设计器的默认值 73×19")
    void boxDefault() {
        RenderGrid grid = new RenderGrid();
        double[] box = ChartProcessor.boxOf(grid, 3, 2);
        assertEquals(73, box[0], 1e-9);
        assertEquals(19, box[1], 1e-9);
    }

    @Test
    @DisplayName("合并区：整块的宽高是被跨掉那几行几列的和")
    void boxMerged() {
        RenderGrid grid = new RenderGrid();
        grid.addMerge(2, 1, 4, 3);
        grid.getColumnlen().put("1", 100);
        grid.getColumnlen().put("2", 120);
        // 第 3 列没设过 -> 默认 73
        grid.getRowlen().put("2", 30);
        grid.getRowlen().put("3", 30);
        // 第 4、5 行没设过 -> 各 19
        double[] box = ChartProcessor.boxOf(grid, 2, 1);
        assertEquals(100 + 120 + 73, box[0], 1e-9);
        assertEquals(30 + 30 + 19 + 19, box[1], 1e-9);
    }

    @Test
    @DisplayName("行高列宽是字符串数字时也认（content 里两种都出现过）")
    void boxStringLen() {
        RenderGrid grid = new RenderGrid();
        grid.getColumnlen().put("0", "150");
        grid.getRowlen().put("0", "40.5");
        double[] box = ChartProcessor.boxOf(grid, 0, 0);
        assertEquals(150, box[0], 1e-9);
        assertEquals(40.5, box[1], 1e-9);
    }

    @Test
    @DisplayName("出图挂到 image 上，设计器那段占位文本被清掉")
    void drawsIntoCell() {
        RenderGrid grid = new RenderGrid();
        GridCell cell = putChartCell(grid, 0, 0);
        grid.addMerge(0, 0, 6, 5);

        ChartProcessor.process(grid, List.of(new ChartProcessor.ChartCell(0, 0, cellConfig())), datasets());

        assertNotNull(cell.getImage(), "图应该挂在 GridCell#image 上（下游三条路认的就是它）");
        assertTrue(cell.getImage().startsWith("data:image/png;base64,"));
        assertEquals("", cell.getValue(), "占位文本不该印到纸上");
        assertEquals("", cell.getDisplay());
    }

    @Test
    @DisplayName("画不出来时：图为空，但占位文本照样要清掉")
    void clearsTextEvenWhenBlank() {
        RenderGrid grid = new RenderGrid();
        GridCell cell = putChartCell(grid, 0, 0);

        CellConfigDTO cfg = cellConfig();
        cfg.getChart().setCategoryField("");   // 配不全 -> 画不出来

        ChartProcessor.process(grid, List.of(new ChartProcessor.ChartCell(0, 0, cfg)), datasets());

        assertNull(cell.getImage());
        assertEquals("", cell.getDisplay(), "画不出来也不该在纸上留下 [图表] xxx");
    }

    @Test
    @DisplayName("数据集没取到数时同样是空格，不抛异常")
    void missingDataset() {
        RenderGrid grid = new RenderGrid();
        GridCell cell = putChartCell(grid, 0, 0);
        CellConfigDTO cfg = cellConfig();
        cfg.setDatasetCode("不存在的数据集");

        ChartProcessor.process(grid, List.of(new ChartProcessor.ChartCell(0, 0, cfg)), datasets());

        assertNull(cell.getImage());
        assertEquals("", cell.getDisplay());
    }

    @Test
    @DisplayName("超过一次渲染的图表上限后留空，但不报错（报表还是出得来）")
    void maxCharts() {
        RenderGrid grid = new RenderGrid();
        List<ChartProcessor.ChartCell> charts = new ArrayList<>();
        int total = ChartProcessor.MAX_CHARTS + 5;
        for (int i = 0; i < total; i++) {
            putChartCell(grid, i, 0);
            charts.add(new ChartProcessor.ChartCell(i, 0, cellConfig()));
        }

        ChartProcessor.process(grid, charts, datasets());

        assertNotNull(grid.get(0, 0).getImage(), "上限之内的照常出图");
        assertNull(grid.get(total - 1, 0).getImage(), "超出的留空");
        assertEquals("", grid.get(total - 1, 0).getDisplay(), "留空的那些也不该留下占位文本");
    }

    @Test
    @DisplayName("同一张 sheet 里两张一模一样的图只画一次（复用同一个 data URI）")
    void reusesIdenticalChart() {
        RenderGrid grid = new RenderGrid();
        GridCell a = putChartCell(grid, 0, 0);
        GridCell b = putChartCell(grid, 10, 0);

        ChartProcessor.process(grid, List.of(
                new ChartProcessor.ChartCell(0, 0, cellConfig()),
                new ChartProcessor.ChartCell(10, 0, cellConfig())), datasets());

        assertNotNull(a.getImage());
        assertEquals(a.getImage(), b.getImage());
    }

    @Test
    @DisplayName("没有图表格时什么都不做")
    void noCharts() {
        RenderGrid grid = new RenderGrid();
        ChartProcessor.process(grid, List.of(), datasets());
        ChartProcessor.process(grid, null, datasets());
    }
}
