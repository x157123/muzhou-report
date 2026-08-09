package com.muzhou.report.engine;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.common.HybridBinarizer;
import com.muzhou.report.dto.CellConfigDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条码出图测试：{@link BarcodeGenerator}。纯 POJO，不启动 Spring。
 *
 * <p>断言的方式是**把出来的图再扫回来**（ZXing 自己的 reader）——「画出来了」不等于「扫得出来」，
 * 而报表上的条码是要给扫码枪读的。白边留多少、文字带盖没盖住条、中文编成了什么字符集，
 * 只有解码这一步查得出来。
 */
class BarcodeGeneratorTest {

    private CellConfigDTO cfg(String type) {
        CellConfigDTO cfg = new CellConfigDTO();
        cfg.setType(type);
        return cfg;
    }

    /** 出图 -> data URI -> 解码回原文。 */
    private String decode(String dataUri) throws Exception {
        return read(dataUri).getText();
    }

    /** 同 {@link #decode}，但把整个解码结果给出来（要看纠错级别这类元信息时用）。 */
    private Result read(String dataUri) throws Exception {
        assertNotNull(dataUri, "没有出图");
        assertTrue(dataUri.startsWith("data:image/png;base64,"), "条码必须归一化成 PNG 的 data URI：" + dataUri);
        byte[] png = Base64.getDecoder().decode(dataUri.substring("data:image/png;base64,".length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "出来的不是一张能解开的 PNG");
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new ImageLuminance(image)));
        return new MultiFormatReader().decode(bitmap, hints);
    }

    @Test
    @DisplayName("type=barcode：默认按 Code128 出图，扫回来就是原文")
    void barcodeDefaultsToCode128() throws Exception {
        assertEquals("MZ-2026-0001", decode(BarcodeGenerator.dataUri(cfg("barcode"), "MZ-2026-0001")));
    }

    @Test
    @DisplayName("条码下方那行文字不影响识读：印与不印扫出来是同一串")
    void humanReadableTextDoesNotBreakScanning() throws Exception {
        CellConfigDTO with = cfg("barcode");
        CellConfigDTO without = cfg("barcode");
        without.setBarcodeText(false);

        assertEquals("A123456789", decode(BarcodeGenerator.dataUri(with, "A123456789")));
        assertEquals("A123456789", decode(BarcodeGenerator.dataUri(without, "A123456789")));
        // 印文字那张要高出一条文字带来，不然就是把条压扁了去挤文字
        assertTrue(height(BarcodeGenerator.dataUri(with, "A123456789"))
                > height(BarcodeGenerator.dataUri(without, "A123456789")));
    }

    @Test
    @DisplayName("type=qrcode：默认 QR_CODE，中文按 UTF-8 编（不指定字符集会扫出乱码）")
    void qrCodeKeepsChinese() throws Exception {
        assertEquals("木舟报表 2026 年度", decode(BarcodeGenerator.dataUri(cfg("qrcode"), "木舟报表 2026 年度")));
    }

    @Test
    @DisplayName("码制照配置来：EAN_13 收 13 位数字，扫回来带着校验位")
    void formatIsHonoured() throws Exception {
        CellConfigDTO cfg = cfg("barcode");
        cfg.setBarcodeFormat("EAN_13");
        assertEquals("6901234567892", decode(BarcodeGenerator.dataUri(cfg, "6901234567892")));
    }

    @Test
    @DisplayName("纠错级别照配置来：扫回来的元信息里就写着是哪一级")
    void qrLevelIsHonoured() throws Exception {
        CellConfigDTO low = cfg("qrcode");
        low.setQrLevel("L");
        CellConfigDTO high = cfg("qrcode");
        high.setQrLevel("H");
        String text = "MZ-2026-0001-ORDER";

        assertEquals("L", ecLevel(BarcodeGenerator.dataUri(low, text)));
        assertEquals("H", ecLevel(BarcodeGenerator.dataUri(high, text)));
        assertEquals(text, decode(BarcodeGenerator.dataUri(high, text)));
    }

    @Test
    @DisplayName("编不出来的那一格出空白：位数不合码制、值为空都不许把整份渲染带崩")
    void badContentYieldsBlankCell() {
        CellConfigDTO ean = cfg("barcode");
        ean.setBarcodeFormat("EAN_13");
        // EAN_13 只收 13 位数字，这是最常见的「配错码制」
        assertNull(BarcodeGenerator.dataUri(ean, "不是数字"));
        assertNull(BarcodeGenerator.dataUri(ean, "123"));

        assertNull(BarcodeGenerator.dataUri(cfg("barcode"), null));
        assertNull(BarcodeGenerator.dataUri(cfg("barcode"), ""));
        assertNull(BarcodeGenerator.dataUri(cfg("qrcode"), "   "));
    }

    @Test
    @DisplayName("码制/纠错级别写错了退回默认，不抛异常 —— 手写 content 时拼错名字是常事")
    void unknownNamesFallBack() throws Exception {
        CellConfigDTO cfg = cfg("qrcode");
        cfg.setBarcodeFormat("QRCODE");  // 正确的名字是 QR_CODE
        cfg.setQrLevel("X");
        assertEquals("退回默认", decode(BarcodeGenerator.dataUri(cfg, "退回默认")));
    }

    @Test
    @DisplayName("Aztec / PDF417 的纠错参数与 QR 不是一套，塞错了会编不出码")
    void twoDVariantsStillEncode() throws Exception {
        CellConfigDTO aztec = cfg("qrcode");
        aztec.setBarcodeFormat("AZTEC");
        aztec.setQrLevel("H");
        assertEquals("AZ-001", decode(BarcodeGenerator.dataUri(aztec, "AZ-001")));

        CellConfigDTO pdf417 = cfg("qrcode");
        pdf417.setBarcodeFormat("PDF_417");
        assertEquals("PDF-001", decode(BarcodeGenerator.dataUri(pdf417, "PDF-001")));
    }

    /* ------------------------------- 工具 ------------------------------- */

    private BufferedImage imageOf(String dataUri) throws Exception {
        byte[] png = Base64.getDecoder().decode(dataUri.substring("data:image/png;base64,".length()));
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private int height(String dataUri) throws Exception {
        return imageOf(dataUri).getHeight();
    }

    /** 扫回来的那张 QR 用的是哪一级纠错（"L"/"M"/"Q"/"H"）。 */
    private String ecLevel(String dataUri) throws Exception {
        return String.valueOf(read(dataUri).getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL));
    }

    /**
     * {@code BufferedImage} 的亮度源。
     *
     * <p>ZXing 官方那份在 {@code javase} 模块里，而本项目只依赖 {@code core}（见 pom 里的注释）——
     * 出图这一侧自己画，解码只有测试用得到，为它多拖一个模块进来不值当。
     */
    private static final class ImageLuminance extends LuminanceSource {

        private final BufferedImage image;

        ImageLuminance(BufferedImage image) {
            super(image.getWidth(), image.getHeight());
            this.image = image;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            int width = getWidth();
            if (row == null || row.length < width) {
                row = new byte[width];
            }
            for (int x = 0; x < width; x++) {
                row[x] = (byte) luminance(x, y);
            }
            return row;
        }

        @Override
        public byte[] getMatrix() {
            int width = getWidth();
            int height = getHeight();
            byte[] matrix = new byte[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    matrix[y * width + x] = (byte) luminance(x, y);
                }
            }
            return matrix;
        }

        /** 出的图本来就是灰度/二值的，取任意一个通道即可。 */
        private int luminance(int x, int y) {
            return image.getRGB(x, y) & 0xff;
        }
    }
}
