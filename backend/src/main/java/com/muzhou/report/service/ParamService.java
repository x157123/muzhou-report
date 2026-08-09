package com.muzhou.report.service;

import com.muzhou.report.common.PageResult;
import com.muzhou.report.dto.ReportParamDTO;
import com.muzhou.report.entity.MzParam;

import java.util.List;

/**
 * 全局参数管理服务。见 docs/CONTRACT.md §3.5。
 *
 * <p>全局参数是**系统级**的一份参数定义，所有报表共用，不属于任何一张报表 ——
 * 所以这里是纯 CRUD，没有 {@code reportId} 那一维（对比 {@link DatasetService}，
 * 数据集是分公共/内部两种作用范围的）。
 */
public interface ParamService {

    PageResult<MzParam> page(long pageNo, long pageSize, String name);

    /** 全部**启用**的全局参数，按参数名排序。 */
    List<MzParam> listAll();

    MzParam get(String id);

    String create(MzParam param);

    boolean update(MzParam param);

    boolean remove(String id);

    /**
     * 渲染侧要的那一份：**启用**的全局参数，转成引擎认的 {@link ReportParamDTO}。
     *
     * <p>与报表参数怎么合并、谁覆盖谁，见 {@link ReportParamDTO#merge} 与 CONTRACT §5。
     */
    List<ReportParamDTO> listDefinitions();
}
