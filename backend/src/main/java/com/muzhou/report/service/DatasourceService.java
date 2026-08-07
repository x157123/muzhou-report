package com.muzhou.report.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.muzhou.report.common.PageResult;
import com.muzhou.report.dto.ColumnMetaDTO;
import com.muzhou.report.entity.MzDatasource;

import java.util.List;

/**
 * 数据源管理服务。见 docs/CONTRACT.md §3.1。
 */
public interface DatasourceService extends IService<MzDatasource> {

    /**
     * 分页查询数据源。
     *
     * @param pageNo   页码，从 1 开始
     * @param pageSize 每页条数
     * @param name     名称模糊查询，可为空
     */
    PageResult<MzDatasource> page(long pageNo, long pageSize, String name);

    /**
     * 查询全部启用状态的数据源（用于下拉选择等场景），按名称升序。
     */
    List<MzDatasource> listAll();

    /**
     * 新建数据源，返回主键 id。
     */
    String create(MzDatasource ds);

    /**
     * 更新数据源。
     */
    boolean update(MzDatasource ds);

    /**
     * 删除数据源（若已被数据集引用则拒绝）。
     */
    boolean remove(String id);

    /**
     * 测试数据源连通性，失败抛出 BizException 携带原因。
     */
    boolean test(MzDatasource ds);

    /**
     * 查询指定数据源下的所有表名。
     */
    List<String> tables(String id);

    /**
     * 查询指定数据源下某张表的列元数据。
     */
    List<ColumnMetaDTO> columns(String id, String table);

    /**
     * 按 code 查询数据源，供 {@link com.muzhou.report.datasource.DynamicDatasourceRegistry} 惰性注册时使用。
     */
    MzDatasource getByCode(String code);
}
