package com.muzhou.report.engine;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.muzhou.report.dto.CellConfigDTO;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 条形码 / 二维码单元格（{@code type=barcode} / {@code type=qrcode}）的出图。
 *
 * <p>它是 {@code base64} 图片格的**延长线**：取数、扩展、分组一律与 {@code data} 相同，
 * 区别只在取到的那个值是「图片本身」还是「要编成图的那串字」—— 这里把字编成一张 PNG，
 * 归一化成同一个 {@code data:} URI 交回 {@link CellImage}。于是下游一处都不用改：
 * 预览的浮动图片、{@code ExcelExporter#applyImages} 锚进 xlsx、PDF / Word 从 xlsx 读回来，
 * 全都只认 {@code v.mzImg.src}，认不出这张图是画的还是取回来的。
 *
 * <p><b>为什么在服务端出图</b>：预览与三条导出路要看到同一张码。放到前端画的话，导出那头
 * （Excel / PDF / Word 都在服务端）还得再实现一遍，两份实现迟早对不齐 —— 这正是本项目
 * 「一处写、多处读」的老规矩。
 *
 * <p><b>编不出来就当空格子</b>（记一条 warn 说清是哪一种失败）：EAN_13 只收 13 位数字、
 * CODE_39 不认小写字母，一份 500 行的报表里有一条数据不合码制就整份渲染失败，没法交代。
 *
 * <p>出图尺寸是固定的（见 {@link #ONE_D_WIDTH} 等），最终多大由
 * {@code export/ImageFit#contain} 按格子等比例装 —— 与别的图片单元格同一条规则。
 * 所以条码是**等比例居中、不铺满**：要它更宽就把这一行调矮，或者横向合并几格。
 */
@Slf4j
public final class BarcodeGenerator {

    private BarcodeGenerator() {
    }

    /** 一维码出图宽度（像素）。真正的模块宽由 ZXing 取整数倍后居中，这里只是给够分辨率。 */
    private static final int ONE_D_WIDTH = 640;

    /** 一维码的条高。宽高比约 4:1，跟常见的物流/商品标签接近。 */
    private static final int ONE_D_HEIGHT = 160;

    /** 二维码出图边长。 */
    private static final int TWO_D_SIZE = 320;

    /** 条码下方那行文字占的高度。 */
    private static final int TEXT_BAND = 40;

    /** 一维码默认码制：字母数字都能编，国内报表上用得最多。 */
    private static final BarcodeFormat DEFAULT_ONE_D = BarcodeFormat.CODE_128;

    /**
     * 把单元格取到的值编成一张码，返回可直接当 {@code <img src>} 用的 data URI。
     *
     * @param cfg   单元格配置（码制、纠错级别、是否印文字都在里面）
     * @param value 取到的字段值 —— 要编进码里的那串字
     * @return data URI；值为空或编不出来返回 null（该格当普通空格子处理）
     */
    public static String dataUri(CellConfigDTO cfg, Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        BarcodeFormat format = format(cfg);
        try {
            BitMatrix matrix = encode(cfg, text, format);
            BufferedImage image = draw(matrix, text, showText(cfg, format));
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png(image));
        } catch (Exception e) {
            // 绝大多数是「这串字不合这个码制」：EAN_13 要 13 位数字、ITF 要偶数位数字、
            // CODE_39 不认小写。把码制和原文一起打出来，一眼能看出是配错了码制还是数据脏
            log.warn("单元格条码生成失败，该格已当空格处理: 码制={}, 内容={}, 原因={}",
                    format, brief(text), e.toString());
            return null;
        }
    }

    private static BitMatrix encode(CellConfigDTO cfg, String text, BarcodeFormat format) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // ZXing 编 QR 时默认按 ISO-8859-1 走，中文会编成乱码（扫出来是「???」），必须显式指定
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        if (twoD(format)) {
            // ZXing 默认按规范留 4 个模块的白边。这里收到 2：图是**等比例居中装进格子**的，
            // 格子本身又是白的，实际留出的白边比图里这一圈多 —— 白边全画在图里的话，
            // 同样大的格子只能印出更小的码
            hints.put(EncodeHintType.MARGIN, 2);
        }
        // ERROR_CORRECTION 这个 hint 各家写法**不通用**：只有 QRCodeWriter 收
        // ErrorCorrectionLevel，Aztec / PDF417 收的是整数（前者是百分比、后者是 0-8 的档位），
        // 一律塞进去的话它们会在 Integer.parseInt("M") 上抛出来，那一格就白白编不出码
        if (format == BarcodeFormat.QR_CODE) {
            hints.put(EncodeHintType.ERROR_CORRECTION, level(cfg));
        }
        int w = twoD(format) ? TWO_D_SIZE : ONE_D_WIDTH;
        int h = twoD(format) ? TWO_D_SIZE : ONE_D_HEIGHT;
        return new MultiFormatWriter().encode(text, format, w, h, hints);
    }

    /**
     * 把点阵画成图，一维码按需在下方印出原文。
     *
     * <p><b>用 1 位二值图</b>：这张图是要 base64 进渲染结果 JSON、再原样进 xlsx / PDF / Word 的，
     * 几百行数据就是几百张。同一张 Code128 二值 PNG 只有 ~500 字节，换成灰度图是 ~9KB
     * （条码那种细密竖条在灰度下压不动），一份 500 行的报表就差出 4MB。
     * 文字照旧开抗锯齿 —— 画到二值面上会被抖动成点阵，缩到格子里看不出与灰度的差别。
     */
    private static BufferedImage draw(BitMatrix matrix, String text, boolean showText) {
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        int total = h + (showText ? TEXT_BAND : 0);
        BufferedImage image = new BufferedImage(w, total, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, total);
            g.setColor(Color.BLACK);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (matrix.get(x, y)) {
                        g.fillRect(x, y, 1, 1);
                    }
                }
            }
            if (showText) {
                drawText(g, w, h, text);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 条码下方居中印一行原文（大家习惯的那串数字），太长就按格宽缩字号。 */
    private static void drawText(Graphics2D g, int w, int top, String text) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int size = 28;
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, size);
        FontRenderContext frc = g.getFontRenderContext();
        Rectangle2D box = font.getStringBounds(text, frc);
        if (box.getWidth() > w) {
            size = Math.max(10, (int) Math.floor(size * w / box.getWidth()));
            font = font.deriveFont((float) size);
            box = font.getStringBounds(text, frc);
        }
        g.setFont(font);
        // 基线 = 文字带顶端 + 上升部；文字带比字号高一截，居中摆下去
        int baseline = top + (TEXT_BAND + size) / 2 - 2;
        g.drawString(text, (int) ((w - box.getWidth()) / 2), baseline);
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * 码制：配了就照配的来，没配（老报表、或者用户没动过）按单元格类型给个默认。
     *
     * <p>认不出的名字退回默认并记 warn —— 手写 content 时把码制拼错了，比起 500 更该看到一张
     * 「码是出来了但不是想要的那种」。
     */
    private static BarcodeFormat format(CellConfigDTO cfg) {
        String name = cfg == null ? null : cfg.getBarcodeFormat();
        boolean qr = cfg != null && "qrcode".equals(cfg.getType());
        BarcodeFormat fallback = qr ? BarcodeFormat.QR_CODE : DEFAULT_ONE_D;
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return BarcodeFormat.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("不认识的条码码制[{}]，已按 {} 出图", name, fallback);
            return fallback;
        }
    }

    /** 二维码纠错级别，越高越抗污损、能编的内容也越少；没配按 M（约 15%）。 */
    private static ErrorCorrectionLevel level(CellConfigDTO cfg) {
        String s = cfg == null ? null : cfg.getQrLevel();
        if (s == null || s.isBlank()) {
            return ErrorCorrectionLevel.M;
        }
        try {
            return ErrorCorrectionLevel.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("不认识的二维码纠错级别[{}]，已按 M 出图", s);
            return ErrorCorrectionLevel.M;
        }
    }

    /** 二维码那一类（方的），与一维码的差别在出图尺寸、白边和纠错级别。 */
    private static boolean twoD(BarcodeFormat format) {
        return switch (format) {
            case QR_CODE, DATA_MATRIX, AZTEC -> true;
            default -> false;
        };
    }

    /** 二维码不印文字（本来就是给机器扫的），一维码默认印 —— 没有那串数字的条码没法人工核对。 */
    private static boolean showText(CellConfigDTO cfg, BarcodeFormat format) {
        if (twoD(format) || format == BarcodeFormat.PDF_417) {
            return false;
        }
        return cfg == null || cfg.isBarcodeText();
    }

    /** 日志里只留个头，免得一条长文本刷屏。 */
    private static String brief(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "...";
    }
}
