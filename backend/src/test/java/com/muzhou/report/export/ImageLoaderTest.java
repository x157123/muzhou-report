package com.muzhou.report.export;

import com.muzhou.report.config.MzProperties;
import com.muzhou.report.dto.PageConfigDTO;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageLoader} 的取图测试：起一个本地 HTTP 服务当图床，不依赖外网。
 *
 * <p>盯的是「预览里看得到、导出的文件里没有」这一类问题 —— 预览是浏览器去拉图，
 * 导出是**服务端**去拉，两条路的网络环境、登录态、地址形态都可能不一样。
 */
class ImageLoaderTest {

    private static final byte[] PNG = Base64.getDecoder().decode(TestImages.PNG_1X1);

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastUserAgent = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a.png", exchange -> {
            lastUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, PNG.length);
            exchange.getResponseBody().write(PNG);
            exchange.close();
        });
        server.createContext("/missing.png", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        // 需要登录态的地址：浏览器带着 cookie 拿得到图，服务端这一趟拿回来的是登录页
        server.createContext("/login.png", exchange -> {
            byte[] html = "<!DOCTYPE html><html>login</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ImageLoader loader(String baseUrl) {
        MzProperties.Image cfg = new MzProperties.Image();
        cfg.setBaseUrl(baseUrl);
        return new ImageLoader(cfg);
    }

    @Test
    @DisplayName("http 地址的图片下载得回来，并且带上了 User-Agent（空 UA 会被不少图床挡成 403）")
    void downloadsHttpImage() {
        byte[] bytes = loader(null).load(base + "/a.png");

        assertNotNull(bytes, "该下载到图片");
        assertEquals(PNG.length, bytes.length);
        assertEquals(XSSFWorkbook.PICTURE_TYPE_PNG, ImageLoader.pictureType(bytes));
        assertTrue(lastUserAgent.get() != null && !lastUserAgent.get().isBlank(),
                "请求该带 User-Agent，实际: " + lastUserAgent.get());
    }

    @Test
    @DisplayName("下载不到（404）时返回空，让导出跳过这一张而不是整份失败")
    void missingImageReturnsNull() {
        assertNull(loader(null).load(base + "/missing.png"));
    }

    @Test
    @DisplayName("拿回来的是登录页而不是图片时认得出来（格式判不了），不会被当成图片写进 xlsx")
    void loginPageIsNotAnImage() {
        byte[] bytes = loader(null).load(base + "/login.png");
        assertTrue(ImageLoader.pictureType(bytes) < 0, "HTML 不该被当成图片");
    }

    @Test
    @DisplayName("相对路径：配了 base-url 就能取回，没配则跳过（服务端没有「当前页面」这个上下文）")
    void relativePathNeedsBaseUrl() {
        assertNull(loader(null).load("/a.png"), "没配 base-url 时取不到");
        assertNotNull(loader(base).load("/a.png"));
        assertNotNull(loader(base + "/").load("a.png"), "base-url 末尾的斜杠不该影响结果");
    }

    @Test
    @DisplayName("Excel 放不进的格式只要 ImageIO 解得开就转成 PNG（浏览器认的格式比 Excel 多得多）")
    void unsupportedFormatIsConvertedToPng() throws Exception {
        // PNG 原样通过
        ImageLoader.Picture png = ImageLoader.toPicture(PNG);
        assertNotNull(png);
        assertEquals(XSSFWorkbook.PICTURE_TYPE_PNG, png.type());
        assertEquals(PNG.length, png.data().length, "本来就能用的格式不该被重新编码");

        // TIFF：xlsx 收不了，但 ImageIO 解得开 -> 转成 PNG
        BufferedImage img = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "tiff", tiff), "JDK 应该带 TIFF 编码器");
        ImageLoader.Picture converted = ImageLoader.toPicture(tiff.toByteArray());
        assertNotNull(converted, "TIFF 该被转成 PNG 而不是丢掉");
        assertEquals(XSSFWorkbook.PICTURE_TYPE_PNG, converted.type());
    }

    @Test
    @DisplayName("既不是认得的格式、也解不开（ico / 登录页）时跳过这一张，不写进 xlsx")
    void undecodableIsSkipped() {
        // ICO 的文件头，JDK 没有 ico 解码器
        byte[] ico = new byte[]{0, 0, 1, 0, 1, 0, 64, 64, 0, 0, 1, 0, 32, 0};
        assertNull(ImageLoader.toPicture(ico));
        assertNull(ImageLoader.toPicture("<!DOCTYPE html><html>login</html>".getBytes(StandardCharsets.UTF_8)));
        assertNull(ImageLoader.toPicture(null));
    }

    @Test
    @DisplayName("整条导出链路：URL 图片被下载回来并写进 xlsx")
    void urlImageReachesXlsx() throws Exception {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("v", "");
        v.put("m", "");
        v.put("mzImg", Map.of("src", base + "/a.png"));

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("name", "网络图");
        sheet.put("celldata", List.of(Map.of("r", 0, "c", 0, "v", v)));
        sheet.put("config", Map.of("columnlen", Map.of("0", 100), "rowlen", Map.of("0", 100)));

        byte[] xlsx = new ExcelExporter(new MzProperties()).export(List.of(sheet), new PageConfigDTO());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals(1, wb.getAllPictures().size(), "URL 图片该被下载回来写进 xlsx");
            assertEquals(1, wb.getSheetAt(0).getDrawingPatriarch().getShapes().size());
        }
    }
}
