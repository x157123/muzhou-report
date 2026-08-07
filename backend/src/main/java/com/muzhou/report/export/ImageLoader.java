package com.muzhou.report.export;

import com.muzhou.report.config.MzProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * 把图片单元格的地址（{@code GridCell#image}）变成字节，供 {@link ExcelExporter} 写进 xlsx。
 *
 * <p>只有**导出**这条路需要它：预览时图片是浏览器自己按 src 去拉的，服务端一个字节都不碰。
 * 而 xlsx 里图片必须是内嵌的二进制，所以 URL 那种得由服务端下载回来。
 *
 * <p>取不到图（地址打不开、超时、太大、不是图片）一律**跳过这一张并记一条 warn**，不抛异常 ——
 * 一份 500 行的报表里有一个坏图链就整份导出失败，是很难交代的。
 */
@Slf4j
public class ImageLoader {

    /** 认得出的图片格式：文件头魔数 -> POI 的图片类型常量。 */
    private static final int[] PICTURE_TYPES = {
            XSSFWorkbook.PICTURE_TYPE_PNG,
            XSSFWorkbook.PICTURE_TYPE_JPEG,
            XSSFWorkbook.PICTURE_TYPE_GIF,
            XSSFWorkbook.PICTURE_TYPE_BMP,
    };

    /**
     * 浏览器都会带的 UA。不少图床/CDN 见到空 UA 直接回 403 —— 于是「浏览器里看得到、
     * 导出的文件里没有」，而这正是最难猜的一种失败。
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; MzReport/1.0; +https://github.com/muzhou-report)";

    private final MzProperties.Image cfg;

    /** HttpClient 是线程安全且带连接池的，一个实例复用（每张图新建一个会把连接数打满）。 */
    private final HttpClient http;

    public ImageLoader(MzProperties.Image cfg) {
        this.cfg = cfg == null ? new MzProperties.Image() : cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.cfg.getTimeout()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                // JDK 的 HttpClient **默认不走系统代理**（URLConnection 会走）。公司网里
                // 浏览器能拉到图、服务端却连不上外网，十有八九是这一条
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /**
     * 取图片字节。
     *
     * @param src {@code data:} URI 或 http(s) 地址
     * @return 图片字节；取不到返回 null
     */
    public byte[] load(String src) {
        if (src == null || src.isBlank()) {
            return null;
        }
        String s = src.trim();
        try {
            if (s.startsWith("data:")) {
                return decodeDataUri(s);
            }
            String url = absolute(s);
            if (url == null) {
                return null;
            }
            return download(url);
        } catch (Exception e) {
            log.warn("图片[{}]取回失败，导出时已跳过: {}", brief(s), e.toString());
            return null;
        }
    }

    /**
     * 补成服务端能请求的绝对地址。
     *
     * <p>数据库里存相对路径（`/upload/a.png`）是很常见的：前端拼上当前站点就能显示，
     * 而服务端没有「当前页面」这个上下文，只能靠 {@code muzhou.report.image.base-url} 告诉它
     * 图片站在哪儿。协议相对地址（`//host/a.png`）按 https 补。
     *
     * @return 绝对地址；补不出来返回 null（已记 warn）
     */
    private String absolute(String src) {
        String lower = src.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return src;
        }
        if (src.startsWith("//")) {
            return "https:" + src;
        }
        String base = cfg.getBaseUrl();
        if (base == null || base.isBlank()) {
            log.warn("图片地址[{}]是相对路径，服务端不知道它在哪个站点上，导出时已跳过 —— "
                    + "请把 muzhou.report.image.base-url 配成图片所在站点（如 http://10.0.0.9:8080）", brief(src));
            return null;
        }
        return base.replaceAll("/+$", "") + (src.startsWith("/") ? src : "/" + src);
    }

    private byte[] decodeDataUri(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 0) {
            return null;
        }
        String meta = uri.substring(0, comma);
        String payload = uri.substring(comma + 1);
        if (!meta.contains(";base64")) {
            // 非 base64 的 data URI（百分号编码的 svg 之类）不是报表里会出现的形态，不猜
            log.warn("不支持非 base64 的 data URI 图片，导出时已跳过");
            return null;
        }
        byte[] bytes = Base64.getMimeDecoder().decode(payload);
        return bytes.length > cfg.getMaxBytes() ? tooLarge(bytes.length) : bytes;
    }

    private byte[] download(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(cfg.getTimeout()))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/*,*/*")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() / 100 != 2) {
                // 401/403 基本都是「这个图片地址要登录态」：浏览器带着 cookie 拿得到，
                // 服务端这一趟是干净的请求，拿不到
                log.warn("图片[{}]下载失败: HTTP {}{}", brief(url), response.statusCode(),
                        response.statusCode() == 401 || response.statusCode() == 403
                                ? "（该地址需要登录态，服务端取不到，考虑改用 base64 返回图片）" : "");
                return null;
            }
            // 多读一个字节就能判断「是不是超了」，而不必先把整个流读进内存
            byte[] bytes = in.readNBytes((int) Math.min(cfg.getMaxBytes() + 1, Integer.MAX_VALUE));
            if (bytes.length > cfg.getMaxBytes()) {
                return tooLarge(bytes.length);
            }
            if (pictureType(bytes) < 0) {
                // 拿回来的不是图片：多半是被网关挡下来的登录页/错误页，光说「格式不认识」很难猜
                log.warn("图片[{}]取回的不是图片: Content-Type={}, 前 16 字节={}", brief(url),
                        response.headers().firstValue("content-type").orElse("?"), head(bytes));
            }
            return bytes;
        }
    }

    /** 出错时打头几个字节，一眼能看出拿回来的是不是 HTML。 */
    private String head(byte[] bytes) {
        int n = Math.min(bytes.length, 16);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            char ch = (char) (bytes[i] & 0xff);
            sb.append(ch >= 32 && ch < 127 ? ch : '.');
        }
        return sb.toString();
    }

    private byte[] tooLarge(int size) {
        log.warn("图片超过 {} 字节上限（实际 {}），导出时已跳过", cfg.getMaxBytes(), size);
        return null;
    }

    /** 日志里只留个头，base64 图片一整串打出来能刷屏几千行。 */
    private String brief(String src) {
        return src.length() <= 80 ? src : src.substring(0, 80) + "...";
    }

    /** 能写进 xlsx 的一张图：字节 + POI 的图片类型。 */
    public record Picture(byte[] data, int type) {
    }

    /**
     * 把取回来的字节变成「xlsx 放得进去的图片」。
     *
     * <p>xlsx 只收 PNG / JPEG / GIF / BMP 这几种。别的格式（TIFF 之类）只要 ImageIO 解得开就
     * **转成 PNG** —— 「预览里看得到、导出的文件里没有」十有八九就是格式这一关：浏览器认的
     * 图片格式比 Excel 多得多（ico / webp / svg 都能显示）。
     *
     * @return 可用的图片；实在处理不了返回 null（已记 warn 写明是什么格式）
     */
    public static Picture toPicture(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        int type = pictureType(bytes);
        if (type >= 0) {
            return new Picture(bytes, type);
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null) {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                if (ImageIO.write(img, "png", png)) {
                    log.debug("图片格式 {} 已转成 PNG 后写入", sniff(bytes));
                    return new Picture(png.toByteArray(), XSSFWorkbook.PICTURE_TYPE_PNG);
                }
            }
        } catch (Exception e) {
            log.debug("图片转 PNG 失败: {}", e.getMessage());
        }
        log.warn("图片格式 {} 放不进 Excel（只支持 PNG/JPEG/GIF/BMP，浏览器认得的格式比 Excel 多），"
                + "导出时已跳过 —— 换成这几种格式，或改用 base64 返回", sniff(bytes));
        return null;
    }

    /** 认个格式名，只为日志说人话。 */
    private static String sniff(byte[] b) {
        if (b.length >= 4 && b[0] == 0 && b[1] == 0 && b[2] == 1 && b[3] == 0) {
            return "ICO(网站图标)";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "WEBP";
        }
        String head = new String(b, 0, Math.min(b.length, 64), StandardCharsets.US_ASCII).trim();
        if (head.startsWith("<svg") || head.startsWith("<?xml")) {
            return "SVG";
        }
        if (head.toLowerCase(Locale.ROOT).startsWith("<!doctype html") || head.startsWith("<html")) {
            return "HTML(不是图片，多半是登录页或错误页)";
        }
        if (b.length >= 2 && ((b[0] == 'I' && b[1] == 'I') || (b[0] == 'M' && b[1] == 'M'))) {
            return "TIFF";
        }
        return "未知";
    }

    /**
     * 按文件头认图片格式。
     *
     * <p>不信地址后缀、也不信 data URI 里写的 MIME —— 两者都是人填的，填错了 POI 会照着错的
     * 类型写进 xlsx，Excel 打开就是一个红叉。
     *
     * @return POI 的图片类型常量；认不出返回 -1
     */
    public static int pictureType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return -1;
        }
        int b0 = bytes[0] & 0xff;
        int b1 = bytes[1] & 0xff;
        if (b0 == 0x89 && b1 == 0x50) {
            return XSSFWorkbook.PICTURE_TYPE_PNG;
        }
        if (b0 == 0xff && b1 == 0xd8) {
            return XSSFWorkbook.PICTURE_TYPE_JPEG;
        }
        if (b0 == 'G' && b1 == 'I' && (bytes[2] & 0xff) == 'F') {
            return XSSFWorkbook.PICTURE_TYPE_GIF;
        }
        if (b0 == 'B' && b1 == 'M') {
            return XSSFWorkbook.PICTURE_TYPE_BMP;
        }
        // webp / svg 等 Excel 自己也放不进去，退回「不认识」比写错类型强
        return -1;
    }

    /** POI 的图片类型常量是不是本项目支持的那几种（PDF / Word 那两条路读回来时用）。 */
    public static boolean supported(int pictureType) {
        for (int t : PICTURE_TYPES) {
            if (t == pictureType) {
                return true;
            }
        }
        return false;
    }
}
