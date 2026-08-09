package com.muzhou.report.controller;

import com.muzhou.report.common.PageResult;
import com.muzhou.report.common.Result;
import com.muzhou.report.entity.MzParam;
import com.muzhou.report.service.ParamService;
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
 * 全局参数管理接口。见 docs/CONTRACT.md §3.5。
 * 异常统一由 {@link com.muzhou.report.common.GlobalExceptionHandler} 处理，此处不做 try/catch。
 */
@RestController
@RequestMapping("/api/param")
@RequiredArgsConstructor
public class ParamController {

    private final ParamService service;

    /** 分页查询全局参数（name 按参数名/显示名模糊匹配）。 */
    @GetMapping("/page")
    public Result<PageResult<MzParam>> page(@RequestParam(defaultValue = "1") long pageNo,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String name) {
        return Result.ok(service.page(pageNo, pageSize, name));
    }

    /** 查询全部启用的全局参数。 */
    @GetMapping("/list")
    public Result<List<MzParam>> list() {
        return Result.ok(service.listAll());
    }

    /** 查询单个全局参数。 */
    @GetMapping("/{id}")
    public Result<MzParam> get(@PathVariable String id) {
        return Result.ok(service.get(id));
    }

    /** 新建全局参数。 */
    @PostMapping
    public Result<String> create(@RequestBody MzParam param) {
        return Result.ok(service.create(param));
    }

    /** 更新全局参数。 */
    @PutMapping
    public Result<Boolean> update(@RequestBody MzParam param) {
        return Result.ok(service.update(param));
    }

    /** 删除全局参数。 */
    @DeleteMapping("/{id}")
    public Result<Boolean> remove(@PathVariable String id) {
        return Result.ok(service.remove(id));
    }
}
