package com.muzhou.report.export;

import com.muzhou.report.dto.HeaderFooterDTO;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 页头 / 页尾文字与 xlsx 页眉页脚串之间的互转。
 *
 * <p><b>为什么要有这一层</b>：报表里存的是给人看的占位符（{@code ${page}}），xlsx 里存的是
 * Excel 的页眉代码（{@code &P}）。而 PDF / Word 两条导出路都是「先出 xlsx 再转」——
 * 它们只拿得到 xlsx，读回来的就是 Excel 代码，得在这里解回文字。页头页尾因此和纸张、
 * 页边距一样是「存进 xlsx 让下游读回来」，不必给转换器再传一份配置。
 *
 * <p>支持的占位符（大小写敏感）：
 * <table border="1">
 *   <caption>占位符</caption>
 *   <tr><th>占位符</th><th>Excel 代码</th><th>含义</th></tr>
 *   <tr><td>{@code ${page}}</td><td>{@code &P}</td><td>当前页码</td></tr>
 *   <tr><td>{@code ${pages}}</td><td>{@code &N}</td><td>总页数</td></tr>
 *   <tr><td>{@code ${date}}</td><td>{@code &D}</td><td>日期</td></tr>
 *   <tr><td>{@code ${time}}</td><td>{@code &T}</td><td>时间</td></tr>
 *   <tr><td>{@code ${sheet}}</td><td>{@code &A}</td><td>工作表名</td></tr>
 * </table>
 */
public final class HeaderFooterText {

    /** 占位符 -> Excel 页眉代码。 */
    private static final Map<String, String> PLACEHOLDERS = new LinkedHashMap<>();

    static {
        PLACEHOLDERS.put("${page}", "&P");
        PLACEHOLDERS.put("${pages}", "&N");
        PLACEHOLDERS.put("${date}", "&D");
        PLACEHOLDERS.put("${time}", "&T");
        PLACEHOLDERS.put("${sheet}", "&A");
    }

    /** 字号写成两位十进制，最大 99（设计器里的上限是 72）。见 {@link #append} 与 {@link #parseParts}。 */
    private static final int MAX_FONT_SIZE = 99;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** 左中右三段的下标，与 {@link #split} 返回的数组对应。 */
    public static final int LEFT = 0;
    public static final int CENTER = 1;
    public static final int RIGHT = 2;

    private HeaderFooterText() {
    }

    /** 展开占位码时要用到的上下文。 */
    public record Ctx(int page, int pages, String sheetName, LocalDateTime now) {

        public static Ctx of(int page, int pages, String sheetName) {
            return new Ctx(page, pages, sheetName, LocalDateTime.now());
        }
    }

    /** 一段的解析结果：{@code text} 是展开后的文字，{@code fontSize} 是段内写着的字号（没写为 null）。 */
    public record Section(String text, Integer fontSize) {
    }

    /**
     * 一段拆出来的片段类型。
     *
     * <p>页码和总页数单独成一类，是为了 Word 那条路 —— docx 里它们得写成 {@code PAGE} /
     * {@code NUMPAGES} 域，由 Word 自己算，直接把数字写死的话文档一编辑就不对了。
     * PDF 那条路是逐页画的，页码在画的时候就知道，展开成文字即可。
     */
    public enum Kind {
        TEXT, PAGE, PAGES
    }

    /** {@code kind=TEXT} 时 {@code text} 是文字，其余情况 {@code text} 为空。 */
    public record Part(Kind kind, String text) {
    }

    /** 一段拆成片段后的结果。 */
    public record Parsed(List<Part> parts, Integer fontSize) {
    }

    /**
     * 把页头/页尾编成 xlsx 的页眉串，形如 {@code &L&9左段&C&9中段}。
     *
     * <p>字号写进每一段：读回来时按段取，不必额外传。空段不写 —— 写了 Excel 也会当成空串，
     * 但串越短越不容易在别的工具里解错。
     *
     * @return 没有任何内容时返回空串（调用方据此决定不设置页眉）
     */
    public static String toExcelCode(HeaderFooterDTO hf) {
        if (hf == null || hf.isBlank()) {
            return "";
        }
        int size = hf.getFontSize() == null || hf.getFontSize() <= 0 ? 9 : hf.getFontSize();
        StringBuilder sb = new StringBuilder();
        append(sb, "&L", hf.getLeft(), size);
        append(sb, "&C", hf.getCenter(), size);
        append(sb, "&R", hf.getRight(), size);
        return sb.toString();
    }

    /**
     * 追加一段。
     *
     * <p>字号**恒写成两位**（{@code &09} 而不是 {@code &9}）：Excel 的字号码是
     * 「{@code &} + 一串数字」，紧跟其后的文字要是也以数字开头就分不清哪几位是字号了
     * —— 页头写「2026 年度经营分析」时 {@code &92026...} 会被解成 92026 磅，
     * 页头字大到把正文挤成一行一页，「2026」还跟着一起没了。写死两位、解析也只吃两位，
     * 这个歧义就不存在（设计器里字号限制在 6~72，两位够用；越界的按边界钳住）。
     */
    private static void append(StringBuilder sb, String marker, String text, int fontSize) {
        if (text == null || text.isBlank()) {
            return;
        }
        int size = Math.min(Math.max(fontSize, 1), MAX_FONT_SIZE);
        sb.append(marker).append('&').append(String.format("%02d", size)).append(encode(text));
    }

    /**
     * 用户写的文字 -> Excel 页眉代码。
     *
     * <p>先把 {@code &} 转义成 {@code &&}（否则用户文字里的 {@code &C} 会被 Excel 当成
     * 「居中段开始」），再把占位符换成代码 —— 占位符里没有 {@code &}，两步不会互相干扰。
     */
    static String encode(String text) {
        String s = text.replace("&", "&&");
        for (Map.Entry<String, String> e : PLACEHOLDERS.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        return s;
    }

    /**
     * 这几段页头/页尾里有没有页码占位符（{@code &P} / {@code &N}）。
     *
     * <p>用来判断一张 sheet **参不参与页码统计**：封面、总结这类没写页码的 sheet 不该占页数，
     * 否则正文第一页印出来是「第 2 页 共 5 页」。判定看的是「印不印页码」而不是「有没有页头页尾」
     * —— 页头只写了个标题的封面照样不占页数，而**印了页码的 sheet 必定被算进总数**，
     * 不会出现「印了却没算」这种自相矛盾。
     *
     * @param sections xlsx 里的页眉串（{@link #split} 拆出来的三段，或整串）
     */
    public static boolean hasPageNumber(String... sections) {
        if (sections == null) {
            return false;
        }
        for (String s : sections) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            for (Part part : parseParts(s, Ctx.of(1, 1, "")).parts()) {
                if (part.kind() == Kind.PAGE || part.kind() == Kind.PAGES) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 按 {@code &L / &C / &R} 把 xlsx 的页眉串拆成左中右三段（段内的代码原样保留）。
     *
     * <p>不用 POI 的 {@code HeaderFooter#getLeft} 那套：它不认 {@code &&} 转义，
     * 用户文字里写个 {@code &C} 就会被当成分段标记，整段错位。
     *
     * @return 长度恒为 3 的数组，见 {@link #LEFT} / {@link #CENTER} / {@link #RIGHT}
     */
    public static String[] split(String raw) {
        String[] parts = {"", "", ""};
        if (raw == null || raw.isEmpty()) {
            return parts;
        }
        StringBuilder[] buf = {new StringBuilder(), new StringBuilder(), new StringBuilder()};
        // 没写分段标记时 Excel 视为居中段
        int cur = CENTER;
        int i = 0;
        while (i < raw.length()) {
            char ch = raw.charAt(i);
            if (ch != '&' || i + 1 >= raw.length()) {
                buf[cur].append(ch);
                i++;
                continue;
            }
            char next = raw.charAt(i + 1);
            int section = switch (next) {
                case 'L' -> LEFT;
                case 'C' -> CENTER;
                case 'R' -> RIGHT;
                default -> -1;
            };
            if (section >= 0) {
                cur = section;
            } else {
                // 转义的 && 与其它代码都留给 parse 处理，这里只负责不把它们看成分段标记
                buf[cur].append(ch).append(next);
            }
            i += 2;
        }
        for (int k = 0; k < 3; k++) {
            parts[k] = buf[k].toString();
        }
        return parts;
    }

    /**
     * 解析一段：去掉格式码、把 {@code &&} 还原成 {@code &}、把 {@code &P} 之类展开成实际值，
     * 同时把段内写着的字号带出来。页码/总页数按 {@code ctx} 里的数字展开成文字。
     */
    public static Section parse(String section, Ctx ctx) {
        Parsed parsed = parseParts(section, ctx);
        StringBuilder sb = new StringBuilder();
        for (Part part : parsed.parts()) {
            sb.append(switch (part.kind()) {
                case TEXT -> part.text();
                case PAGE -> String.valueOf(ctx.page());
                case PAGES -> String.valueOf(ctx.pages());
            });
        }
        return new Section(sb.toString(), parsed.fontSize());
    }

    /**
     * 解析一段，把页码 / 总页数留成单独的片段（其余全部展开成文字）。
     *
     * <p>不认识的代码一律丢掉而不是原样留下 —— 页头上出现一个 {@code &Z} 比少一点格式更难看。
     *
     * @param ctx 只用来展开日期/时间/工作表名；页码不在这里展开
     */
    public static Parsed parseParts(String section, Ctx ctx) {
        List<Part> parts = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        Integer fontSize = null;
        int i = 0;
        while (i < section.length()) {
            char ch = section.charAt(i);
            if (ch != '&') {
                out.append(ch);
                i++;
                continue;
            }
            if (i + 1 >= section.length()) {
                // 末尾孤零零一个 &，丢掉
                break;
            }
            char code = section.charAt(i + 1);
            i += 2;
            switch (code) {
                case '&' -> out.append('&');
                case 'P' -> flushTo(parts, out, Kind.PAGE);
                case 'N' -> flushTo(parts, out, Kind.PAGES);
                case 'D' -> out.append(DATE.format(ctx.now()));
                case 'T' -> out.append(TIME.format(ctx.now()));
                case 'A' -> out.append(ctx.sheetName() == null ? "" : ctx.sheetName());
                case '"' -> i = skipQuoted(section, i);
                case 'K' -> i = Math.min(i + 6, section.length());   // &K 后面跟 6 位颜色
                default -> {
                    if (Character.isDigit(code)) {
                        // &12 这样的字号：**最多吃两位**。贪心地吃下去的话，紧跟其后以数字开头的
                        // 文字会被卷进字号里（「&09」+「2026 年度」-> 92026 磅），见 append
                        int start = i - 1;
                        if (i < section.length() && Character.isDigit(section.charAt(i))) {
                            i++;
                        }
                        int size = Integer.parseInt(section.substring(start, i));
                        if (fontSize == null && size > 0) {
                            fontSize = size;
                        }
                    }
                    // 其余（&B 粗体、&F 文件名、&G 图片……）本项目不产出，忽略
                }
            }
        }
        if (out.length() > 0) {
            parts.add(new Part(Kind.TEXT, out.toString()));
        }
        return new Parsed(parts, fontSize);
    }

    /** 把攒着的文字收成一个 TEXT 片段，再追加一个 {@code kind} 片段。 */
    private static void flushTo(List<Part> parts, StringBuilder out, Kind kind) {
        if (out.length() > 0) {
            parts.add(new Part(Kind.TEXT, out.toString()));
            out.setLength(0);
        }
        parts.add(new Part(kind, ""));
    }

    /** 跳过 {@code &"字体名,字型"} 里引号包着的那一段，返回闭引号之后的位置。 */
    private static int skipQuoted(String section, int from) {
        int end = section.indexOf('"', from);
        return end < 0 ? section.length() : end + 1;
    }
}
