package com.muzhou.report.service;

import com.muzhou.report.common.PageResult;
import com.muzhou.report.entity.MzFont;
import com.muzhou.report.export.FontProvider;

import java.util.List;

/**
 * 上传字体管理。见 docs/CONTRACT.md §3.6。
 *
 * <p>解决的是这么一件事：报表设计器里能选的字体是一份写死的清单（{@code utils/fontList.js}），
 * 而三条出纸的路各自要的东西不一样 —— 画布要浏览器装了这款字体，xlsx / docx 里只写得进
 * **字体名**（打开文件的那台机器得有），**PDF 必须把字体文件本身内嵌进去**。所以服务器上
 * 没有的字体，从前在 PDF 里是印不出来的；传一份上来，这三条路就都齐了。
 *
 * <p>本接口同时是 {@link FontProvider} —— 导出那一层只认这一个方法，见它的类注释。
 *
 * <p><b>多节点部署</b>：字体文件的正本存在库里（{@code mz_font.file_data}），各节点第一次用到
 * 时才把它落成一份本地缓存文件。所以在哪个节点上传都一样，不需要共享存储、也不需要节点间同步
 * —— 前提当然是各节点连的是**同一个库**（默认那个内嵌 H2 是单节点的，多节点得切 MySQL）。
 */
public interface FontService extends FontProvider {

    /** 分页查询（name 按字体名模糊匹配）。 */
    PageResult<MzFont> page(long pageNo, long pageSize, String name);

    /** 全部**启用**的字体，按字体名升序。设计器的字体清单与 {@code @font-face} 都照这份来。 */
    List<MzFont> listAll();

    MzFont get(String id);

    /**
     * 传一款字体。
     *
     * @param bytes            字体文件内容
     * @param originalFilename 原始文件名，后缀决定格式（ttf / otf / ttc）
     * @param fontName         字体名，就是单元格样式里 {@code ff} 存的那个字符串，全局唯一
     * @param ttcIndex         .ttc 字体集里用第几款，其余格式忽略
     * @return 新字体的 id
     */
    String upload(byte[] bytes, String originalFilename, String fontName, Integer ttcIndex, String remark);

    /**
     * 改字体的元信息（名字 / 备注 / 状态）。
     *
     * <p><b>换不了文件</b>：要换成另一份字体文件就删掉再传。文件路径一旦定下来就不再变，
     * 于是 {@code PdfExporter} 那份「路径 -> 已解析字体」的常驻缓存永远不会过期 ——
     * 允许原地换文件的话，那份缓存就得跟着做失效，为一个「删了重传」两步就能办的事不值当。
     */
    boolean update(MzFont font);

    /**
     * 删字体：库里的行逻辑删除（正本连同字节一起失效），本节点的缓存文件真删掉。
     *
     * <p>别的节点上那份缓存删不着、也不必删：解析一律先查库，行没了就再也不会去碰那个文件。
     */
    boolean remove(String id);

    /** 读字体文件，供 {@code @font-face} 下载。本节点还没缓存过就先从库里落一份。 */
    FontFile readFile(String id);

    /** 一份可以直接往响应体里写的字体文件。 */
    record FontFile(String fileName, String contentType, byte[] bytes) {
    }
}
