package com.muzhou.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.muzhou.report.entity.MzReportVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MzReportVersionMapper extends BaseMapper<MzReportVersion> {

    /**
     * 报表内已用到的最大 version_no，**含已逻辑删除的行**。
     *
     * <p>必须手写 SQL：走 BaseMapper 的话 {@code @TableLogic} 会自动加上 {@code deleted = 0}，
     * 而唯一索引 {@code uk_version_report_no} 管的是全部行 —— 删掉 v3 再发一次号就撞车了。
     */
    @Select("SELECT COALESCE(MAX(version_no), 0) FROM mz_report_version WHERE report_id = #{reportId}")
    int maxVersionNo(@Param("reportId") String reportId);
}
