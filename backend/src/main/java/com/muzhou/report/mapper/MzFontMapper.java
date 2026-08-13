package com.muzhou.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.muzhou.report.entity.MzFont;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MzFontMapper extends BaseMapper<MzFont> {

    /**
     * 单独取字体文件的字节。
     *
     * <p>{@code MzFont#fileData} 上挂着 {@code select = false}（十几 MB 的东西不该跟着每次
     * 列表查询走），要字节时走这一条。**只在本节点还没缓存过这个文件时才会被调到**。
     */
    @Select("SELECT file_data FROM mz_font WHERE id = #{id}")
    byte[] selectData(@Param("id") String id);

    /**
     * 把同名的**已逻辑删除**的字体真删掉。
     *
     * <p>唯一索引 {@code uk_font_name(font_name)} 不带 deleted 条件，管的是全部行 ——
     * 删掉「楷体」之后再传一份同名的就会撞唯一键，而用户看到的列表里明明没有它。
     * 与 {@link MzParamMapper#purgeDeletedByName} 是同一个坑。
     *
     * <p>必须手写 SQL —— 走 BaseMapper 的话 {@code @TableLogic} 会把 delete 改写成 update，
     * 那一行永远删不掉。
     */
    @Delete("DELETE FROM mz_font WHERE font_name = #{fontName} AND deleted = 1")
    int purgeDeletedByName(@Param("fontName") String fontName);
}
