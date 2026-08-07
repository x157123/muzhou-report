package com.muzhou.report.controller;

import com.muzhou.report.common.PageResult;
import com.muzhou.report.common.Result;
import com.muzhou.report.dto.ColumnMetaDTO;
import com.muzhou.report.entity.MzDatasource;
import com.muzhou.report.service.DatasourceService;
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
 * 数据源管理接口。见 docs/CONTRACT.md §3.1。
 * 异常统一由 {@link com.muzhou.report.common.GlobalExceptionHandler} 处理，此处不做 try/catch。
 */
@RestController
@RequestMapping("/api/datasource")
@RequiredArgsConstructor
public class DatasourceController {

    private final DatasourceService service;

    /** 分页查询数据源。 */
    @GetMapping("/page")
    public Result<PageResult<MzDatasource>> page(@RequestParam(defaultValue = "1") long pageNo,
                                                  @RequestParam(defaultValue = "10") long pageSize,
                                                  @RequestParam(required = false) String name) {
        return Result.ok(service.page(pageNo, pageSize, name));
    }

    /** 查询全部启用的数据源。 */
    @GetMapping("/list")
    public Result<List<MzDatasource>> list() {
        return Result.ok(service.listAll());
    }

    /** 查询单个数据源详情。 */
    @GetMapping("/{id}")
    public Result<MzDatasource> get(@PathVariable String id) {
        return Result.ok(service.getById(id));
    }

    /** 新建数据源。 */
    @PostMapping
    public Result<String> create(@RequestBody MzDatasource ds) {
        return Result.ok(service.create(ds));
    }

    /** 更新数据源。 */
    @PutMapping
    public Result<Boolean> update(@RequestBody MzDatasource ds) {
        return Result.ok(service.update(ds));
    }

    /** 删除数据源。 */
    @DeleteMapping("/{id}")
    public Result<Boolean> remove(@PathVariable String id) {
        return Result.ok(service.remove(id));
    }

    /** 测试数据源连通性。 */
    @PostMapping("/test")
    public Result<Boolean> test(@RequestBody MzDatasource ds) {
        return Result.ok(service.test(ds));
    }

    /** 查询数据源下的所有表名。 */
    @GetMapping("/{id}/tables")
    public Result<List<String>> tables(@PathVariable String id) {
        return Result.ok(service.tables(id));
    }

    /** 查询数据源下某张表的列元数据。 */
    @GetMapping("/{id}/columns")
    public Result<List<ColumnMetaDTO>> columns(@PathVariable String id, @RequestParam String table) {
        return Result.ok(service.columns(id, table));
    }
}
