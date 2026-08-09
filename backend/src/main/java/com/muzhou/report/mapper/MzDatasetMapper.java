package com.muzhou.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.muzhou.report.entity.MzDataset;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MzDatasetMapper extends BaseMapper<MzDataset> {

    /**
     * 把该报表下**已逻辑删除**的内部数据集真删掉。
     *
     * <p>唯一索引 {@code uk_dataset_code_scope(code, report_id)} 不带 deleted 条件，管的是全部行——
     * 逻辑删除留下的那条会挡住同 code 的重建。导入覆盖一张报表时必然撞上：上一次导入删掉的
     * 数据集，这一次又回来了。作用范围只到「正在被覆盖的这张报表的内部数据集」，公共集不碰。
     *
     * <p>必须手写 SQL —— 走 BaseMapper 的话 {@code @TableLogic} 会把 delete 改写成 update，
     * 那一行永远删不掉（同 {@link MzReportVersionMapper#maxVersionNo} 的道理）。
     */
    @Delete("DELETE FROM mz_dataset WHERE report_id = #{reportId} AND deleted = 1")
    int purgeDeletedByReport(@Param("reportId") String reportId);
}
