package com.muzhou.report.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 上传字体：一份系统级的字体，所有报表共用。见 docs/CONTRACT.md §2 mz_font、§3.6。
 *
 * <p><b>字体文件存在库里</b>（{@link #fileData}）：多节点部署时，数据库是各节点唯一共享的东西 ——
 * 存本地磁盘的话，在 A 节点传的字体，B 节点导出时找不到文件、**静默退回默认字体**，
 * 于是「同一张报表导两次字体不一样」，还是最难查的那种。各节点要用它时再落成一份本地缓存文件
 * （{@code muzhou.report.font.dir}）—— PDF 引擎要的是一个路径，见 {@code FontServiceImpl#pathOf}。
 *
 * <p>{@link #fontName} 就是单元格样式里 {@code ff} 存的那个字符串 —— 三条渲染路认的都是它：
 * 画布靠 {@code @font-face} 的 {@code font-family}、xlsx 靠 {@code font.setFontName}、
 * PDF 靠 {@code export/PdfFonts} 拿它去查这张表。所以它一改，老报表里绑着旧名字的格子就找不着了，
 * 改名前想清楚（界面上有提示）。
 */
@Data
@TableName("mz_font")
public class MzFont implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 字体名，等于单元格样式里的 {@code ff}，全局唯一。 */
    private String fontName;

    /** 上传时的原始文件名，只用于界面显示。 */
    private String fileName;

    /**
     * 字体文件内容。
     *
     * <p><b>{@code select = false}</b>：一款中文字体十几 MB，列表页一页十行就是上百 MB ——
     * 而列表、{@code /font/list}（字体清单）、渲染时按名字找路径，全都只要元信息。
     * 真要字节时走 {@code MzFontMapper#selectData} 单独取一次。
     */
    @TableField(value = "file_data", select = false)
    private byte[] fileData;

    /** ttf / otf / ttc。 */
    private String fontFormat;

    /**
     * .ttc 字体集里用第几款，非 ttc 恒为 0。
     *
     * <p>OpenPDF 认的是「路径,序号」这种写法（见 {@code PdfExporter#FONT_CANDIDATES}），
     * 拼串的活由 {@code FontServiceImpl#pathOf} 一处做。
     */
    private Integer ttcIndex;

    /** 字节数，界面上显示用。 */
    private Long fileSize;

    private String remark;

    /** 1 启用 0 停用。**停用 = 字体清单里不再出现**，已经用了它的格子退回默认字体。 */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
