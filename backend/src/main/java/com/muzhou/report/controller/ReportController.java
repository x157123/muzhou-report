package com.muzhou.report.controller;

import com.muzhou.report.common.PageResult;
import com.muzhou.report.common.Result;
import com.muzhou.report.dto.ReportVersionSaveDTO;
import com.muzhou.report.entity.MzReport;
import com.muzhou.report.entity.MzReportVersion;
import com.muzhou.report.service.ReportService;
import com.muzhou.report.service.ReportVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 报表管理接口。见 docs/CONTRACT.md §3.3。
 * 异常统一由 {@link com.muzhou.report.common.GlobalExceptionHandler} 处理，此处不做 try/catch。
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    private final ReportVersionService versionService;

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
