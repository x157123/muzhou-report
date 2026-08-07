package com.muzhou.report.controller;

import com.muzhou.report.common.PageResult;
import com.muzhou.report.common.Result;
import com.muzhou.report.dto.DatasetDetailDTO;
import com.muzhou.report.dto.DatasetParseDTO;
import com.muzhou.report.dto.DatasetParseResultDTO;
import com.muzhou.report.dto.DatasetPreviewResultDTO;
import com.muzhou.report.dto.DatasetSaveDTO;
import com.muzhou.report.entity.MzDataset;
import com.muzhou.report.service.DatasetService;
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
import java.util.Map;

/**
 * 数据集接口，见 docs/CONTRACT.md §3.2。
 */
@RestController
@RequestMapping("/api/dataset")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;

    /** 数据集管理页：只列公共数据集。 */
    @GetMapping("/page")
    public Result<PageResult<MzDataset>> page(@RequestParam(defaultValue = "1") long pageNo,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) String name) {
        return Result.ok(datasetService.page(pageNo, pageSize, name));
    }

    /**
     * 含字段与参数，供设计器左侧字段树使用。
     *
     * @param reportId 当前报表 id：公共数据集 + 该报表的内部数据集；不传则只有公共数据集
     */
    @GetMapping("/list")
    public Result<List<DatasetDetailDTO>> list(@RequestParam(required = false) String reportId) {
        return Result.ok(datasetService.listForReport(reportId));
    }

    @GetMapping("/{id}")
    public Result<DatasetDetailDTO> detail(@PathVariable String id) {
        return Result.ok(datasetService.detail(id));
    }

    @PostMapping
    public Result<String> create(@RequestBody DatasetSaveDTO dto) {
        return Result.ok(datasetService.create(dto));
    }

    @PutMapping
    public Result<Boolean> update(@RequestBody DatasetSaveDTO dto) {
        return Result.ok(datasetService.update(dto));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> remove(@PathVariable String id) {
        return Result.ok(datasetService.remove(id));
    }

    /** 解析字段与参数。 */
    @PostMapping("/parse")
    public Result<DatasetParseResultDTO> parse(@RequestBody DatasetParseDTO dto) {
        return Result.ok(datasetService.parse(dto));
    }

    /** 数据预览。 */
    @PostMapping("/preview")
    public Result<DatasetPreviewResultDTO> preview(@RequestBody DatasetParseDTO dto) {
        return Result.ok(datasetService.preview(dto));
    }

    /** 按已保存的数据集取数。 */
    @PostMapping("/{id}/data")
    @SuppressWarnings("unchecked")
    public Result<List<Map<String, Object>>> data(@PathVariable String id,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        Object params = body == null ? null : body.get("params");
        Map<String, Object> paramMap = params instanceof Map ? (Map<String, Object>) params : Map.of();
        return Result.ok(datasetService.fetchDataById(id, paramMap));
    }
}
