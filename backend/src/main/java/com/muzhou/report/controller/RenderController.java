package com.muzhou.report.controller;

import com.muzhou.report.common.Result;
import com.muzhou.report.dto.PreviewRequestDTO;
import com.muzhou.report.dto.RenderRequestDTO;
import com.muzhou.report.dto.RenderResultDTO;
import com.muzhou.report.dto.ReportParamDTO;
import com.muzhou.report.service.RenderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 报表渲染接口。见 docs/CONTRACT.md §3.4。
 * 异常统一由 {@link com.muzhou.report.common.GlobalExceptionHandler} 处理，此处不做 try/catch。
 */
@RestController
@RequestMapping("/api/render")
@RequiredArgsConstructor
public class RenderController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final RenderService renderService;

    /**
     * 渲染已保存的报表。
     *
     * <p>请求体里可以带 {@code versionId} 指定用哪一版版式，省略 = 按报表的版本切换规则自动选。
     * <b>它必须走请求体</b>：地址上的 query 会被前端原样透传成报表参数（CONTRACT §5），
     * {@code ?versionId=} 会跑进 SQL 的 {@code ${versionId}} 里去。
     */
    @PostMapping("/report/{id}")
    public Result<RenderResultDTO> renderReport(@PathVariable String id,
                                                 @RequestBody(required = false) RenderRequestDTO body) {
        Map<String, Object> params = body == null ? Map.of() : body.getParams();
        String versionId = body == null ? null : body.getVersionId();
        return Result.ok(renderService.renderReport(id, params, versionId));
    }

    /** 设计器免保存预览。 */
    @PostMapping("/preview")
    public Result<RenderResultDTO> preview(@RequestBody PreviewRequestDTO body) {
        Map<String, Object> params = body.getParams();
        return Result.ok(renderService.preview(body.getReportId(), body.getContent(), params,
                body.getVersionId()));
    }

    /** 查询报表定义的参数列表。 */
    @GetMapping("/report/{id}/params")
    public Result<List<ReportParamDTO>> params(@PathVariable String id) {
        return Result.ok(renderService.listParams(id));
    }

    /** 渲染并导出 Excel。 */
    @PostMapping("/report/{id}/export/excel")
    public ResponseEntity<byte[]> exportExcel(@PathVariable String id,
                                              @RequestBody(required = false) RenderRequestDTO body) {
        Map<String, Object> params = body == null ? Map.of() : body.getParams();
        String versionId = body == null ? null : body.getVersionId();
        return download(renderService.exportExcel(id, params, versionId), ".xlsx", XLSX);
    }

    /**
     * 渲染并导出 PDF。
     *
     * <p>由后端先出 xlsx 再转 PDF，所以出纸效果与导出 Excel 完全一致。纯 Java 实现，
     * 服务器不需要装 LibreOffice / MS Office。
     *
     * <p>请求体里可以带 {@code sheetIndex} 只出某一张 sheet —— 预览页的「打印」按钮就打这份 PDF
     * （而不是把网页截下来打），页面上显示的是哪张工作表就打哪张。
     */
    @PostMapping("/report/{id}/export/pdf")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String id,
                                            @RequestBody(required = false) RenderRequestDTO body) {
        Map<String, Object> params = body == null ? Map.of() : body.getParams();
        Integer sheetIndex = body == null ? null : body.getSheetIndex();
        String versionId = body == null ? null : body.getVersionId();
        return download(renderService.exportPdf(id, params, sheetIndex, versionId), ".pdf",
                MediaType.APPLICATION_PDF);
    }

    /**
     * 渲染并导出 Word(.docx)。
     *
     * <p>与 PDF 同一条路（先出 xlsx 再转），按 xlsx 的页面设置分页，版式与 PDF 一致 ——
     * 是「排好版的文档」而不是可继续编辑的数据表格。
     */
    @PostMapping("/report/{id}/export/word")
    public ResponseEntity<byte[]> exportWord(@PathVariable String id,
                                             @RequestBody(required = false) RenderRequestDTO body) {
        Map<String, Object> params = body == null ? Map.of() : body.getParams();
        String versionId = body == null ? null : body.getVersionId();
        return download(renderService.exportWord(id, params, versionId), ".docx", DOCX);
    }

    /**
     * 下载响应：文件名 + 扩展名（RFC 5987 编码，兼容中文名）。
     *
     * <p>名字由渲染那一层拼好（{@code content.exportConfig}：报表名 + 主接口若干字段值，
     * 见 CONTRACT §4）—— 拼它要用主接口那一行数据，只有那边拿得到。
     *
     * <p>额外放出 {@code Content-Disposition}：前端按 blob 收流，要从这个头上读文件名，
     * 而它不是 CORS 的「简单响应头」，跨域部署时不显式放出来 JS 一个字都读不到。
     */
    private ResponseEntity<byte[]> download(RenderService.ExportFile file, String ext, MediaType type) {
        String baseName = file.baseName() == null || file.baseName().isBlank() ? "report" : file.baseName();
        String encoded = java.net.URLEncoder.encode(baseName + ext, StandardCharsets.UTF_8)
                .replace("+", "%20");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
        return ResponseEntity.ok().headers(headers).body(file.bytes());
    }
}
