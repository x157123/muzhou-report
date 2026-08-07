package com.muzhou.report.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页结果，见 docs/CONTRACT.md §1。
 */
@Data
public class PageResult<T> implements Serializable {

    private List<T> records = new ArrayList<>();
    private long total;
    private long pageNo = 1;
    private long pageSize = 10;

    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> pr = new PageResult<>();
        pr.setRecords(page.getRecords());
        pr.setTotal(page.getTotal());
        pr.setPageNo(page.getCurrent());
        pr.setPageSize(page.getSize());
        return pr;
    }

    public static <T> PageResult<T> of(List<T> records, long total, long pageNo, long pageSize) {
        PageResult<T> pr = new PageResult<>();
        pr.setRecords(records);
        pr.setTotal(total);
        pr.setPageNo(pageNo);
        pr.setPageSize(pageSize);
        return pr;
    }
}
