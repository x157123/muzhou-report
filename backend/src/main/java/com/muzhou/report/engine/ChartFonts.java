package com.muzhou.report.engine;

import lombok.extern.slf4j.Slf4j;

import java.awt.Font;
import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * 图表里那些字用哪款字体。
 *
 * <p><b>为什么单独有这么一个类</b>：图表是 AWT 画出来的，字也画在图里，服务器上没有中文字体
 * 就是**一堆方框** —— 这是这条路上最容易踩的坑，而且只有出纸那一刻才看得见。
 *
 * <p>三级往下找、先找到的赢：
 * <ol>
 *   <li>{@link #configure} 指定的那一款（复用 {@code muzhou.report.pdf.font-path}，
 *       由 {@code ExpandProcessor} 在启动时传进来 —— 报表里的字体已经配过一次了，不该再配一遍）；</li>
 *   <li>{@link #CANDIDATES} 里探测得到的第一款；</li>
 *   <li>都没有就退回 {@link Font#SANS_SERIF} 并记一条 warn。</li>
 * </ol>
 *
 * <p><b>候选清单与 {@code PdfExporter#FONT_CANDIDATES} 是两份，故意的</b>：AWT 的
 * {@link Font#createFont} <b>读不了 {@code .ttc} 字体集</b>（OpenPDF 读得了，还能用 {@code 路径,序号}
 * 指定其中一款），所以这里必须优先挑 {@code .ttf} / {@code .otf}。两份清单指的是同一批系统字体，
 * 加字体路径时最好一起加。
 *
 * <p>解析出来的字体**常驻**（一次读盘就够）：一份 500 行的报表可能画上百张图，
 * 逐张 {@code createFont} 就是上百次读盘 + 解析。
 */
@Slf4j
public final class ChartFonts {

    private ChartFonts() {
    }

    /**
     * 自动探测的中文字体候选，**只列 AWT 读得了的**（.ttf / .otf，不含 .ttc），按「好看优先」排序。
     */
    private static final List<String> CANDIDATES = List.of(
            // Windows：雅黑与宋体都是 ttc，AWT 读不了，能用的是黑体/楷体
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/simkai.ttf",
            "C:/Windows/Fonts/simfang.ttf",
            // Linux
            "/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
            "/usr/share/fonts/opentype/noto/NotoSansSC-Regular.otf",
            "/usr/share/fonts/truetype/noto/NotoSansCJKsc-Regular.otf",
            "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
            "/usr/share/fonts/truetype/arphic/ukai.ttf",
            "/usr/share/fonts/wqy-microhei/wqy-microhei.ttf",
            // macOS
            "/Library/Fonts/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
    );

    /** 配置指定的字体文件，null/空 = 没配。 */
    private static volatile String configured;

    /** 解析好的基准字体（字号 10），null 表示还没解析过。 */
    private static volatile Font base;

    /** 解析过一次就不再重试（探测不到时也别每张图都刷一遍日志）。 */
    private static volatile boolean resolved;

    /**
     * 指定字体文件。由 Spring 侧在启动时调用一次，值取自 {@code muzhou.report.pdf.font-path}。
     *
     * <p>支持 PDF 那边 {@code 路径,序号} 的写法（{@code .ttc} 字体集用的），但**序号会被忽略**
     * 且 {@code .ttc} 在这里读不了 —— 那种情况下退回探测/兜底，并记一条说清原因的 warn。
     */
    public static synchronized void configure(String path) {
        configured = path;
        // 换了配置就重新解析，免得测试里配了字体却仍用着上一次的兜底
        base = null;
        resolved = false;
    }

    /**
     * 取一款字体。
     *
     * @param size 字号（已含超采样倍数，见 {@code ChartRenderer#SUPERSAMPLE}）
     * @param bold 是否加粗（图表标题、坐标轴标题用）
     */
    public static Font of(float size, boolean bold) {
        Font f = baseFont();
        return f.deriveFont(bold ? Font.BOLD : Font.PLAIN, size);
    }

    private static Font baseFont() {
        Font f = base;
        if (f != null) {
            return f;
        }
        synchronized (ChartFonts.class) {
            if (base == null) {
                base = load();
            }
            return base;
        }
    }

    private static Font load() {
        String path = filePart(configured);
        if (path != null && !path.isBlank()) {
            Font f = tryLoad(path, true);
            if (f != null) {
                return f;
            }
        }
        for (String candidate : CANDIDATES) {
            Font f = tryLoad(candidate, false);
            if (f != null) {
                log.info("图表字体：已探测到 {}", candidate);
                return f;
            }
        }
        if (!resolved) {
            resolved = true;
            log.warn("图表字体：没有找到可用的中文字体文件，已退回逻辑字体 SansSerif。"
                    + "系统本身装了中文字体时它照样显示得出中文；如果图上是一片方框，"
                    + "请安装中文字体(如 fonts-wqy-zenhei)或用 muzhou.report.pdf.font-path 指一个 .ttf/.otf"
                    + "（注意 AWT 读不了 .ttc，那种要另找一份 .ttf/.otf）");
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    }

    /**
     * @param explicit 是不是用户显式配的 —— 显式配的解析失败要说清楚（他配了却没生效），
     *                 探测清单里的失败是常态（那台机器上本来就没这个文件），不必刷日志
     */
    private static Font tryLoad(String path, boolean explicit) {
        File file = new File(path);
        if (!file.isFile()) {
            if (explicit) {
                log.warn("图表字体：muzhou.report.pdf.font-path 指的文件不存在，已退回探测: {}", path);
            }
            return null;
        }
        if (path.toLowerCase(Locale.ROOT).endsWith(".ttc")) {
            if (explicit) {
                log.warn("图表字体：AWT 读不了字体集(.ttc)，图表会退回兜底字体（PDF 那边不受影响）: {}", path);
            }
            return null;
        }
        try {
            // **不往 GraphicsEnvironment 里注册**：注册是为了让 `new Font("字体名", ...)` 按名字找得到，
            // 而这里全程用的是这个 Font 实例本身，注册纯属多余 —— 还得在无显示器的服务器上
            // 碰一次 GraphicsEnvironment，能不碰就不碰
            return Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(10f);
        } catch (Exception e) {
            if (explicit) {
                log.warn("图表字体：读不了 {}，已退回探测。原因={}", path, e.toString());
            }
            return null;
        }
    }

    /** 剥掉 PDF 那边 {@code 路径,序号} 写法里的序号（.ttc 字体集用的，这里读不了但要认得出）。 */
    private static String filePart(String path) {
        if (path == null) {
            return null;
        }
        int comma = path.lastIndexOf(',');
        if (comma <= 0) {
            return path;
        }
        String tail = path.substring(comma + 1).trim();
        return tail.matches("\\d+") ? path.substring(0, comma) : path;
    }
}
