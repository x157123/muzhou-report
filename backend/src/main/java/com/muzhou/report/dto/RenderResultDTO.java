package com.muzhou.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 渲染结果，见 docs/CONTRACT.md §4。 */
@Data
public class RenderResultDTO implements Serializable {

    /** 填充后的 FortuneSheet Sheet[] */
    private List<Map<String, Object>> sheets = new ArrayList<>();

    /** 渲染耗时（毫秒） */
    private long elapsed;

    /**
     * 请求数据（取数）耗时（毫秒）。{@code @JsonIgnore}：它只是服务端耗时链日志的原料
     * （{@code RenderServiceImpl} 里「请求数据 → 渲染 → 转Excel → 转PDF → 合计」那一行），
     * 不出参 —— 出了参就成了契约（CONTRACT §4）的一部分。
     */
    @JsonIgnore
    private long fetchElapsed;

    /**
     * 渲染（扩展）耗时（毫秒），同上不出参。{@code perRow} / {@code perRowPage} 时是
     * 「逐条渲染」那一整段（含每条自己的非主接口取数）。
     */
    @JsonIgnore
    private long expandElapsed;

    /** 附加提示（如数据为空） */
    private String message;

    /**
     * 主接口（{@code content.primaryDataset}）自报的总条数，前端据此画分页条。
     *
     * <p>为 null 的三种情况：没设主接口、主接口是集合型（整份取回，没有第二个总数）、
     * 主接口这次没被用到（模板里没有引用它的单元格，压根没取数）。
     */
    private Long total;

    /** 这一次用的是哪一版版式（免保存预览没有版本，为 null）。 */
    private String versionId;

    /** 版本号，展示成 v1/v2/v3 */
    private Integer versionNo;

    /** 版本名，空则前端显示 v{versionNo} */
    private String versionName;

    /** 为什么是这一版，给人看的一句话：「order_date=2026-06-01 命中 v2」 */
    private String versionMatch;

    /**
     * **每张结果 sheet 生效的打印设置**，与 {@link #sheets} 一一对应。
     *
     * <p>打印设置存在 content 里、按**模板**下标寻址，而结果 sheet 与模板不是一一对应的
     * （{@code perRow} 拆成 N 倍、{@code perRowPage} 又把若干份拼成一张），更要命的是
     * <b>逐行选版本时不同结果 sheet 可能出自不同版本的模板</b> —— 这时候光有
     * {@code mzTemplateIndex} 也说不清是哪一版的第几张。所以由引擎在渲染时直接把解析好的那份
     * 摆出来，导出与预览按下标取即可（见 {@code RenderServiceImpl#renderToXlsx}）。
     *
     * <p>老结果（手工构造的、Phase 1 之前的）没有这一项，此时才退回
     * {@code ReportContentDTO#pageConfigOfSheet} 那条按 {@code mzTemplateIndex} 推算的路。
     */
    private List<PageConfigDTO> sheetPageConfigs;
}
