package com.muzhou.report.export;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.openpdf.text.pdf.BaseFont;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 一次 PDF 导出期间的「字体名 -> {@link BaseFont}」解析器。
 *
 * <p>早先 PDF 那条路整份只用一款字体（探测到的那个中文字体），单元格上设的字体名一律丢掉 ——
 * 而**预览页默认看到的就是后端出的这份 PDF**，于是设计器画布上是楷体、点开预览变成雅黑，
 * 同一张报表两个样子。这个类就是把那一层补上。
 *
 * <p>按三级往下找，**先找到的赢**：
 * <ol>
 *   <li>用户上传的字体（{@link FontProvider}）—— 服务器上没有的字体只能靠这条路，也是它的主要用途；</li>
 *   <li>{@link #SYSTEM_ALIASES} 里那几款常见中文字体在系统字体目录下的位置 ——
 *       宋体/黑体/楷体这些用户压根不会想到「还要上传一遍」，机器上有就该用上；</li>
 *   <li>都没有就退回 {@code PdfExporter#font()} 探测到的那款，与改动之前的行为一致。</li>
 * </ol>
 *
 * <p><b>解析结果按名字缓存在实例里，字体文件按路径缓存在 {@code PdfExporter} 里</b>：
 * 前者一次导出一份 —— 于是模板里出现过的每个字体名只问一次 {@link FontProvider}
 * （那一问背后是查库 + 可能要把文件落到本地，见它的注释），逐格调用落不到查库上去；
 * 后者跨导出常驻 —— 解析一个字体文件要几十到几百毫秒，一张报表用到三款字体就是三次，
 * 每导一次都重来一遍的话，耗时全花在这儿了。
 *
 * <p><b>粗体/斜体仍然是模拟的</b>（{@code PdfExporter} 里的描边与倾斜）：一个字体名对应的是
 * 一个字体**文件**，不是一整个字族，手上没有它的 Bold 那一款。这一条没跟着改 ——
 * 改了就得让用户按「常规/粗体/斜体」各传一份，而报表里的粗体绝大多数是表头那一行。
 */
@Slf4j
public final class PdfFonts {

    /**
     * 几款常见中文字体在系统字体目录下的位置。值是候选清单，逐个试，第一个存在的算数。
     *
     * <p>名字取的是 {@code frontend/src/utils/fontList.js} 里那份清单 —— 那是用户在工具栏
     * 字体下拉里能选到的全部，两边对不上就等于「选得到但印不出」。
     *
     * <p>Linux 服务器上这些多半一个都没有（列在这儿也不亏，探测不到就往下走兜底），
     * 真要在 Linux 上印楷体，正路是把 simkai.ttf 传上来。
     */
    private static final Map<String, List<String>> SYSTEM_ALIASES = Map.ofEntries(
            Map.entry("宋体", List.of("C:/Windows/Fonts/simsun.ttc,0", "/usr/share/fonts/truetype/arphic/uming.ttc,0")),
            Map.entry("新宋体", List.of("C:/Windows/Fonts/simsun.ttc,1")),
            Map.entry("黑体", List.of("C:/Windows/Fonts/simhei.ttf", "/usr/share/fonts/truetype/arphic/ukai.ttc,0")),
            Map.entry("微软雅黑", List.of("C:/Windows/Fonts/msyh.ttc,0",
                    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc,0",
                    "/usr/share/fonts/wqy-microhei/wqy-microhei.ttc,0")),
            Map.entry("楷体", List.of("C:/Windows/Fonts/simkai.ttf")),
            Map.entry("仿宋", List.of("C:/Windows/Fonts/simfang.ttf")),
            Map.entry("华文新魏", List.of("C:/Windows/Fonts/STXINWEI.TTF")),
            Map.entry("华文行楷", List.of("C:/Windows/Fonts/STXINGKA.TTF")),
            Map.entry("华文隶书", List.of("C:/Windows/Fonts/STLITI.TTF")),
            Map.entry("Times New Roman", List.of("C:/Windows/Fonts/times.ttf",
                    "/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf")),
            Map.entry("Arial", List.of("C:/Windows/Fonts/arial.ttf",
                    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf")),
            Map.entry("Tahoma", List.of("C:/Windows/Fonts/tahoma.ttf")),
            Map.entry("Verdana", List.of("C:/Windows/Fonts/verdana.ttf",
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
    );

    /** 上传字体：字体名 -> 本节点上可用的路径，没有返回 null。 */
    private final FontProvider provider;

    /** 路径 -> BaseFont，加载失败返回 null。缓存在 {@code PdfExporter} 那头，跨导出常驻。 */
    private final Function<String, BaseFont> loader;

    /** 三级都找不到时用的那款，见类注释。 */
    private final BaseFont fallback;

    /** 本次导出内的解析结果，字体名 -> BaseFont（恒非 null，兜底也是一款字体）。 */
    private final Map<String, BaseFont> resolved = new HashMap<>();

    public PdfFonts(FontProvider provider, Function<String, BaseFont> loader, BaseFont fallback) {
        this.provider = provider;
        this.loader = loader;
        this.fallback = fallback;
    }

    /** 兜底字体：页头页尾、水印用的就是它 —— 那些地方的字体不来自单元格，没有「这一格是什么字体」可问。 */
    public BaseFont base() {
        return fallback;
    }

    /**
     * 这一格该用哪款字体。
     *
     * <p>没设过字体的格子在 xlsx 里是默认字体名（POI 写的是 Calibri），查不到就走兜底 ——
     * 与改动之前的行为一模一样，所以老报表出纸不变。
     */
    public BaseFont of(XSSFCellStyle style) {
        XSSFFont f = style == null ? null : style.getFont();
        return of(f == null ? null : f.getFontName());
    }

    /** 按字体名解析，找不到返回兜底字体（不会返回 null）。 */
    public BaseFont of(String name) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        BaseFont cached = resolved.get(name);
        if (cached != null) {
            return cached;
        }
        BaseFont font = lookup(name);
        resolved.put(name, font);
        return font;
    }

    private BaseFont lookup(String name) {
        String path = provider == null ? null : provider.pathOf(name);
        if (path != null) {
            BaseFont font = loader.apply(path);
            if (font != null) {
                return font;
            }
            // 传的时候是能加载的（上传接口用同一条路校验过），到这儿加载不了多半是本地缓存那份坏了
            log.warn("上传字体[{}]的文件加载不了，这一格退回默认字体: {}", name, path);
        }
        for (String candidate : SYSTEM_ALIASES.getOrDefault(name, List.of())) {
            if (!exists(candidate)) {
                continue;
            }
            BaseFont font = loader.apply(candidate);
            if (font != null) {
                return font;
            }
        }
        return fallback;
    }

    /** 路径存不存在。「路径,序号」里逗号后面是 .ttc 字体集的序号，不是路径的一部分。 */
    static boolean exists(String path) {
        return new File(filePart(path)).isFile();
    }

    /** 从「路径,序号」里取出路径那一段。 */
    static String filePart(String path) {
        int comma = path.lastIndexOf(',');
        // > 1 是为了躲开 Windows 盘符那个冒号后面……其实是躲开路径本身以逗号开头的怪情况，
        // 与 PdfExporter#font() 里那句保持一致
        return comma > 1 ? path.substring(0, comma) : path;
    }

    /**
     * 真去加载一款字体，加载不了就抛。
     *
     * <p><b>上传接口拿它当校验用</b>（{@code FontServiceImpl#upload}）：能不能印出来这件事，
     * 只有真正要用它的那个引擎说了算 —— 后缀名是 .otf 不代表 OpenPDF 读得了它（CFF 轮廓的
     * OpenType 就未必），而校验放在上传这一刻，用户当场就知道；放过去的话，要等到某天
     * 有人导出那张报表才发现字体没生效，那时早已没人记得是哪次上传的。
     *
     * <p>IDENTITY_H + 内嵌：中文必须内嵌字体子集才能在别人机器上显示，同 {@code PdfExporter#font()}。
     */
    public static BaseFont load(String path) throws Exception {
        return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
    }

    /**
     * 直接拿字节校验一款字体能不能用，**不落盘**。
     *
     * <p>上传时的校验走这条而不是 {@link #load(String)}：那条要先把文件写到磁盘上，而
     * {@code BaseFont.createFont} 失败时**文件句柄不一定收得回来**（它是内存映射读的，
     * JDK 9 之后 iText 那套 {@code sun.misc.Cleaner} 反射清理早失效了），
     * Windows 下随后的删除就会撞上「另一个程序正在使用此文件」——
     * 于是每传一次坏字体，磁盘上就留一个再也没人引用的孤儿文件。从字节校验没有这回事。
     *
     * <p>{@code cached=false}：名字是我们编的（OpenPDF 靠后缀挑解析器），不该拿它去污染
     * OpenPDF 那个按名字索引的静态字体缓存。
     *
     * <p><b>.ttc 用不了这条</b>：字体集要按「路径,序号」从文件里取第几款，只能落盘之后走
     * {@link #load(String)}。
     */
    public static void probe(byte[] data, String format) throws Exception {
        BaseFont.createFont("mzfont." + format, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, data, null);
    }

    /**
     * 这个异常是不是「字体自己声明了不许嵌入」。
     *
     * <p>TTF 的 {@code OS/2} 表里有个 {@code fsType} 位，是**字体厂商写在文件里的嵌入许可声明**；
     * 取值 2（Restricted License embedding）时 OpenPDF 拒绝内嵌并抛出这句话。商业中文字体
     * （方正的多数收费字体就是）常常这么设。
     *
     * <p>而 PDF 里的中文走 Identity-H 编码，**必须内嵌**才显示得出来，绕不过去 ——
     * 所以这种字体只能拒收。单独认出来是为了给一句说得通的提示：「换一份 .ttf」对它毫无意义，
     * 文件本身好好的。
     *
     * <p>只能靠消息文本认（OpenPDF 抛的是笼统的 {@code DocumentException}，没有错误码）。
     * 升级 OpenPDF 时留意这句话有没有变 —— 变了也只是退回通用提示，不影响拦截本身。
     */
    public static boolean embeddingRestricted(Throwable e) {
        String msg = e == null ? null : e.getMessage();
        return msg != null && msg.contains("cannot be embedded due to licensing restrictions");
    }
}
