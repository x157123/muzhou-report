package com.muzhou.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.muzhou.report.entity.MzParam;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MzParamMapper extends BaseMapper<MzParam> {

    /**
     * 把同名的**已逻辑删除**的全局参数真删掉。
     *
     * <p>唯一索引 {@code uk_param_name(param_name)} 不带 deleted 条件，管的是全部行 ——
     * 删掉 {@code deptId} 之后再建一个同名的就会撞唯一键，而用户看到的列表里明明没有它。
     * 与 {@link MzDatasetMapper#purgeDeletedByReport} 是同一个坑。
     *
     * <p>必须手写 SQL —— 走 BaseMapper 的话 {@code @TableLogic} 会把 delete 改写成 update，
     * 那一行永远删不掉。
     */
    @Delete("DELETE FROM mz_param WHERE param_name = #{paramName} AND deleted = 1")
    int purgeDeletedByName(@Param("paramName") String paramName);
}
