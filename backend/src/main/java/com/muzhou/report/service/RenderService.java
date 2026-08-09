package com.muzhou.report.service;

import com.muzhou.report.dto.ReportContentDTO;
import com.muzhou.report.dto.ReportParamDTO;
import com.muzhou.report.dto.RenderResultDTO;

import java.util.List;
import java.util.Map;

/**
 * 报表渲染服务。见 docs/CONTRACT.md §3.4。
 *
 * <p>渲染入口一律多带一个 {@code versionId}：一张报表可以有好几份版式，省略表示
 * 「按报表的版本切换规则自动选」（见 CONTRACT §4「版本」）。**它只能走请求体**，
 * 挂在地址栏 query 上会被当成报表参数透传给数据集（CONTRACT §5）。
 */
public interface RenderService {

    /**
     * 渲染已保存的报表。
     *
     * @param reportId  报表 id
     * @param params    用户传入的参数值
     * @param versionId 用哪一版版式，空 = 按版本切换规则自动选
     */
    RenderResultDTO renderReport(String reportId, Map<String, Object> params, String versionId);

    /**
     * 免保存预览：直接用设计器里的 content 渲染，不落库。
     *
     * <p>**不走版本选择** —— 设计器画布上是哪一版，预览出来就是哪一版；
     * {@code versionId} 只是原样回显，让预览页说得出「现在看的是 v2」。
     *
     * @param reportId 正在设计的报表 id，用于解析它的内部数据集（content 本身不带 id）
     */
    RenderResultDTO preview(String reportId, ReportContentDTO content, Map<String, Object> params,
                            String versionId);

    /**
     * 查询报表的参数列表（供前端渲染参数表单）：**全局参数**（{@code mz_param}）与**默认版本**的
     * 报表参数合并后的那一份，同名时报表那条整条覆盖全局那条（见 CONTRACT §5）。
     */
    List<ReportParamDTO> listParams(String reportId);

    /**
     * 渲染并导出为 Excel 字节流。
     */
    byte[] exportExcel(String reportId, Map<String, Object> params, String versionId);

    /**
     * 渲染并导出为 PDF 字节流。
     *
     * <p>实现是「先导出 xlsx，再把 xlsx 转成 pdf」，页面设置沿用渲染时定下的那一份，
     * 与 Excel 导出出纸一致。
     *
     * @param sheetIndex 只出**渲染结果**里的第几张 sheet，null / 越界 = 整本。
     *                   预览页的「打印」按钮打的就是这份 PDF，而它一次只显示一张工作表
     */
    byte[] exportPdf(String reportId, Map<String, Object> params, Integer sheetIndex, String versionId);

    /**
     * 渲染并导出为 Word(.docx) 字节流。
     *
     * <p>同样是「先导出 xlsx 再转」，按 xlsx 的页面设置分页，版式与 PDF 一致。
     */
    byte[] exportWord(String reportId, Map<String, Object> params, String versionId);
}
