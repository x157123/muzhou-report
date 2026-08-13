package com.muzhou.report.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.BaseFont;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PdfFonts 单元测试（纯 POJO，不启动 Spring、不碰磁盘）。
 *
 * <p>「加载一个路径」这一步由构造器收的那个函数负责，所以这里传个假的就能把三级回退
 * （上传字体 → 系统别名 → 兜底）全测到，不必真去准备字体文件。
 */
class PdfFontsTest {

    /** 拿两款内置的 Type1 字体当「不同的字体」用：它们不需要字体文件，断言身份最省事。 */
    private static BaseFont helvetica() throws Exception {
        return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    }

    private static BaseFont courier() throws Exception {
        return BaseFont.createFont(BaseFont.COURIER, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    }

    /** 只认识一款字体的 FontProvider。真实实现那头一次调用是一次查库 + 可能落一次本地缓存。 */
    private static FontProvider named(String name, String path) {
        return asked -> name.equals(asked) ? path : null;
    }

    @Test
    @DisplayName("没设字体名 / 名字不认识时退回兜底字体")
    void fallsBackWhenUnknown() throws Exception {
        BaseFont fallback = helvetica();
        PdfFonts fonts = new PdfFonts(name -> null, path -> null, fallback);

        assertSame(fallback, fonts.of((String) null));
        assertSame(fallback, fonts.of(""));
        assertSame(fallback, fonts.of("   "));
        // Calibri 是 POI 给没设过字体的格子写的默认字体名 —— 老报表走的就是这条路
        assertSame(fallback, fonts.of("Calibri"));
        assertSame(fallback, fonts.base());
    }

    @Test
    @DisplayName("上传字体按名字命中，加载用的是登记的那个路径")
    void resolvesUploadedFont() throws Exception {
        BaseFont fallback = helvetica();
        BaseFont uploaded = courier();
        List<String> asked = new ArrayList<>();
        Function<String, BaseFont> loader = path -> {
            asked.add(path);
            return "/fonts/kaiti.ttf".equals(path) ? uploaded : null;
        };
        PdfFonts fonts = new PdfFonts(named("楷体", "/fonts/kaiti.ttf"), loader, fallback);

        assertSame(uploaded, fonts.of("楷体"));
        assertEquals(List.of("/fonts/kaiti.ttf"), asked);
    }

    @Test
    @DisplayName("上传字体的文件加载不了时退回兜底，不是整份导出失败")
    void fallsBackWhenUploadedFileBroken() throws Exception {
        BaseFont fallback = helvetica();
        // 文件被删了 / 内容坏了：登记着这个名字，但加载不出来
        PdfFonts fonts = new PdfFonts(named("楷体", "/fonts/gone.ttf"), path -> null, fallback);

        assertSame(fallback, fonts.of("楷体"));
    }

    @Test
    @DisplayName("同一个名字只解析一次：逐格调用不该反复去加载字体文件")
    void cachesPerName() throws Exception {
        BaseFont fallback = helvetica();
        BaseFont uploaded = courier();
        Map<String, Integer> calls = new HashMap<>();
        Function<String, BaseFont> loader = path -> {
            calls.merge(path, 1, Integer::sum);
            return uploaded;
        };
        PdfFonts fonts = new PdfFonts(named("楷体", "/fonts/kaiti.ttf"), loader, fallback);

        for (int i = 0; i < 100; i++) {
            assertSame(uploaded, fonts.of("楷体"));
        }
        assertEquals(1, calls.get("/fonts/kaiti.ttf"));

        // 认不出的名字同样只走一遍，不然一张长报表会把「查不到」这件事重做几千次
        for (int i = 0; i < 100; i++) {
            assertSame(fallback, fonts.of("查无此体"));
        }
        assertEquals(1, calls.size());
    }

    @Test
    @DisplayName("上传字体压过系统别名：传上来就是因为不想用机器上那款")
    void uploadedBeatsSystemAlias() throws Exception {
        BaseFont fallback = helvetica();
        BaseFont uploaded = courier();
        // 「宋体」在 SYSTEM_ALIASES 里是有的，但登记过上传字体时不该再去看系统那份
        Function<String, BaseFont> loader = path -> "/fonts/song.ttf".equals(path) ? uploaded : null;
        PdfFonts fonts = new PdfFonts(named("宋体", "/fonts/song.ttf"), loader, fallback);

        assertSame(uploaded, fonts.of("宋体"));
    }

    @Test
    @DisplayName("每个字体名只问一次 FontProvider —— 那一问背后是查库，逐格问会把库打爆")
    void asksProviderOncePerName() throws Exception {
        BaseFont fallback = helvetica();
        BaseFont uploaded = courier();
        List<String> asked = new ArrayList<>();
        FontProvider provider = name -> {
            asked.add(name);
            return "楷体".equals(name) ? "/fonts/kaiti.ttf" : null;
        };
        PdfFonts fonts = new PdfFonts(provider, path -> uploaded, fallback);

        for (int i = 0; i < 100; i++) {
            fonts.of("楷体");
            fonts.of("查无此体");
        }
        // 命中的和没命中的都只问一遍；空名字压根不问
        fonts.of("");
        fonts.of((String) null);
        assertEquals(List.of("楷体", "查无此体"), asked);
    }

    @Test
    @DisplayName("「路径,序号」里逗号后面是 .ttc 的序号，不是路径的一部分")
    void splitsTtcIndexFromPath() {
        assertEquals("C:/Windows/Fonts/simsun.ttc", PdfFonts.filePart("C:/Windows/Fonts/simsun.ttc,1"));
        assertEquals("/fonts/kaiti.ttf", PdfFonts.filePart("/fonts/kaiti.ttf"));
    }

    @Test
    @DisplayName("load 读不了就抛 —— 上传接口靠这条把坏字体挡在导出之前")
    void loadThrowsOnBadFont() {
        // FontServiceImpl#upload 拿它当校验：能不能印出来只有真要用它的那个引擎说了算，
        // 所以「读不了就抛」这条不能改成静默返回 null
        assertThrows(Exception.class, () -> PdfFonts.load("/no/such/font.ttf"));
    }

    @Test
    @DisplayName("probe 从字节校验、一个文件都不落 —— 落盘校验失败时文件在 Windows 上删不掉")
    void probeReadsBytesOnly() {
        assertThrows(Exception.class, () -> PdfFonts.probe(new byte[]{1, 2, 3}, "ttf"));
        assertThrows(Exception.class, () -> PdfFonts.probe(new byte[0], "ttf"));
    }

    @Test
    @DisplayName("认得出「字体自己声明了不许嵌入」：这种要给的是另一句提示，不是「换个格式」")
    void detectsEmbeddingRestriction() {
        // OpenPDF 抛的是笼统的 DocumentException，只能认这句话（见 PdfFonts#embeddingRestricted）
        assertTrue(PdfFonts.embeddingRestricted(
                new Exception("x.ttf cannot be embedded due to licensing restrictions.")));
        assertFalse(PdfFonts.embeddingRestricted(new Exception("not a valid TTF or OTF file")));
        assertFalse(PdfFonts.embeddingRestricted(new Exception((String) null)));
        assertFalse(PdfFonts.embeddingRestricted(null));
    }
}
