package com.muzhou.report.controller;

import com.muzhou.report.common.BizException;
import com.muzhou.report.common.PageResult;
import com.muzhou.report.common.Result;
import com.muzhou.report.dto.ReportImportResultDTO;
import com.muzhou.report.dto.ReportVersionSaveDTO;
import com.muzhou.report.entity.MzReport;
import com.muzhou.report.entity.MzReportVersion;
import com.muzhou.report.service.ReportPackageService;
import com.muzhou.report.service.ReportService;
import com.muzhou.report.service.ReportVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 报表管理接口。见 docs/CONTRACT.md §3.3。
 * 异常统一由 {@link com.muzhou.report.common.GlobalExceptionHandler} 处理，此处不做 try/catch。
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    /** 导出包的扩展名。仍以 {@code .json} 结尾 —— 它就是一份 JSON，随手能看能改。 */
    private static final String FILE_EXT = ".mzreport.json";

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ReportService reportService;

    private final ReportVersionService versionService;

    private final ReportPackageService packageService;

    /** 分页查询报表（不含 content）。 */
    @GetMapping("/page")
    public Result<PageResult<MzReport>> page(@RequestParam(defaultValue = "1") long pageNo,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) String name) {
        return Result.ok(reportService.page(pageNo, pageSize, name));
    }

    /**
     * 查询报表详情（含 content）。
     *
     * @param versionId 要哪一版的版式，省略 = 默认版本。响应里的 {@code versionId} 说明这是哪一版
     */
    @GetMapping("/{id}")
    public Result<MzReport> get(@PathVariable String id,
                                @RequestParam(required = false) String versionId) {
        return Result.ok(reportService.getDetail(id, versionId));
    }

    /** 新建报表。 */
    @PostMapping
    public Result<String> create(@RequestBody MzReport report) {
        return Result.ok(reportService.create(report));
    }

    /** 更新报表。 */
    @PutMapping
    public Result<Boolean> update(@RequestBody MzReport report) {
        return Result.ok(reportService.update(report));
    }

    /** 删除报表。 */
    @DeleteMapping("/{id}")
    public Result<Boolean> remove(@PathVariable String id) {
        return Result.ok(reportService.remove(id));
    }

    /** 复制报表。 */
    @PostMapping("/{id}/copy")
    public Result<String> copy(@PathVariable String id) {
        return Result.ok(reportService.copy(id));
    }

    /* ------------------------------ 导入导出 ------------------------------ */

    /**
     * 导出若干张报表为一个 JSON 包（测试环境调好，整包带进正式环境）。
     *
     * <p>走 POST 而不是 GET：批量导出时 id 是一串，挂在 query 上迟早撞地址长度上限。
     * 返回的是**文件字节流**，失败时同导出 Excel 那几个接口 —— 仍是 200 + Result(JSON)，
     * 前端按 blob 收流时要认出这种情况（见 CONTRACT §3.4 的说明）。
     *
     * <p>内容是 JSON，但 Content-Type 报的是 {@code octet-stream}：前端那道「200 里装着错误结构」
     * 的识别只把 {@code json} 类型的 blob 读成文本（{@code api/request.js#readErrorMsg}），
     * 报成 json 的话每导出一次就要把整个包解码一遍。
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody List<String> ids) {
        byte[] bytes = packageService.exportPackage(ids);
        String name = ids.size() == 1 ? exportName(ids.get(0)) : "报表导出_" + LocalDateTime.now().format(TS_FORMAT);
        String encoded = URLEncoder.encode(name + FILE_EXT, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    /**
     * 导入报表包。
     *
     * @param mode 目标环境已有同编码报表时怎么办：{@code skip} 跳过 / {@code overwrite} 覆盖 /
     *             {@code copy} 另建一张（编码加后缀）
     * @return 每张报表一条明细 —— 一张失败不影响别的（一张报表一个事务）
     */
    @PostMapping("/import")
    public Result<ReportImportResultDTO> importPackage(@RequestPart("file") MultipartFile file,
                                                       @RequestParam(defaultValue = "skip") String mode)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的文件");
        }
        return Result.ok(packageService.importPackage(file.getBytes(), mode));
    }

    /** 单张导出时用报表名当文件名，报表没了就退回 id（导出那头会抛，这里只是取个名字）。 */
    private String exportName(String id) {
        MzReport report = reportService.getById(id);
        return report == null || !StringUtils.hasText(report.getName()) ? id : report.getName();
    }

    /* ------------------------------ 版本 ------------------------------ */

    /** 版本列表（**不含 content**，同 {@code /page} 不传大字段的规矩），按 versionNo 升序。 */
    @GetMapping("/{id}/version")
    public Result<List<MzReportVersion>> versions(@PathVariable String id) {
        versionService.ensureMigrated(id);
        return Result.ok(versionService.list(id));
    }

    /** 从某一版复制出新版本（content 原样，停用、无生效时间），返回新版本 id。 */
    @PostMapping("/{id}/version/{versionId}/copy")
    public Result<String> copyVersion(@PathVariable String id, @PathVariable String versionId) {
        return Result.ok(versionService.copyVersion(id, versionId));
    }

    /** 改版本元信息（name / effectiveFrom / status / remark）。 */
    @PutMapping("/version")
    public Result<Boolean> updateVersion(@RequestBody ReportVersionSaveDTO dto) {
        return Result.ok(versionService.updateMeta(dto));
    }

    /** 设为默认（基准）版本。 */
    @PostMapping("/{id}/version/{versionId}/default")
    public Result<Boolean> setDefaultVersion(@PathVariable String id, @PathVariable String versionId) {
        return Result.ok(versionService.setDefault(id, versionId));
    }

    /** 删除版本。默认版本、最后一个启用版本不许删。 */
    @DeleteMapping("/version/{versionId}")
    public Result<Boolean> removeVersion(@PathVariable String versionId) {
        return Result.ok(versionService.removeVersion(versionId));
    }

    /**
     * 体检某一版：引用到的数据集/字段还在不在，以及与默认版本之间那几项
     * 「以默认版本为准」的设置是否一致。返回人话写的问题列表，空 = 没查出问题。
     */
    @GetMapping("/{id}/version/{versionId}/check")
    public Result<List<String>> checkVersion(@PathVariable String id, @PathVariable String versionId) {
        return Result.ok(versionService.check(id, versionId));
    }
}
