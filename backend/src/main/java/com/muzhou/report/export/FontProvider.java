package com.muzhou.report.export;

/**
 * 「字体名 -> 本节点上可用的字体文件」的来源。{@link PdfExporter} 靠它把单元格上那个字体名
 * 变成一款真字体。
 *
 * <p>之所以是个接口而不是直接注入 {@code FontService}：导出这一层至今是**纯 Java、不认识
 * Spring 与数据库**的（{@code ExcelExporter} / {@code PdfExporter} 在测试里都是 {@code new}
 * 出来的，见 {@code PdfExporterTest}）。给它一个只有一个方法的接口，测试里传个 lambda 就能
 * 造出「这台机器上有某某字体」的场景，不必起 Spring 上下文。
 */
public interface FontProvider {

    /**
     * 按字体名要一个能直接交给 PDF 引擎的路径（.ttc 是「路径,序号」），没有这款字体返回 null。
     *
     * <p><b>实现方要保证返回的路径在本节点上真的存在</b>：字体文件的正本存在库里，
     * 多节点部署时各节点各缓存一份到本地磁盘（见 {@code MzFont#fileData}），
     * 「还没缓存过就先落一份」这件事只能由实现方来做 —— 导出这一层不认识数据库。
     *
     * <p>调用是**惰性**的：{@link PdfFonts} 只为模板里真出现过的那几个字体名问一次并记住结果，
     * 所以一次导出通常只有两三次调用，落不到「逐格查库」上去。
     */
    String pathOf(String fontName);
}
