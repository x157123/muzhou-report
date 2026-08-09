package com.muzhou.report.engine;

import com.muzhou.report.dto.CellConfigDTO;

/**
 * 图片单元格（{@code img} / {@code base64} / {@code barcode} / {@code qrcode}）的取值归一化。
 *
 * <p>几种类型的差别只在数据长什么样：{@code img} 拿到的是一个地址，{@code base64} 拿到的是
 * 图片本身的 base64 编码，两种条码拿到的是要编成码的那串字（现画一张 PNG，见
 * {@link BarcodeGenerator}）。归一化之后都变成「一个能直接当 {@code <img src>} 用的串」——
 * 前端预览直接挂上去，导出那头（{@code export/ImageLoader}）再按它取字节。
 *
 * <p>base64 一律补成 data URI：光有一串 base64 浏览器认不出来，而 MIME 类型只能从头几个字节
 * 反推。反推错了也不致命——浏览器会自己嗅探，导出那头是拿解码后的字节重新判的。
 */
public final class CellImage {

    private CellImage() {
    }

    /** base64 前缀 -> MIME。base64 是每 3 字节编 4 字符，所以文件头的前几个字节对应固定的前缀串。 */
    private static final String[][] BASE64_MAGIC = {
            {"/9j/", "image/jpeg"},
            {"iVBORw0KGgo", "image/png"},
            {"R0lGOD", "image/gif"},
            {"Qk", "image/bmp"},
            {"UklGR", "image/webp"},
            {"PHN2Zw", "image/svg+xml"},
            {"PD94bWw", "image/svg+xml"},
    };

    /**
     * 把单元格取到的值变成图片地址。
     *
     * <p>条码要按配置出图（码制/纠错级别/印不印文字都在 cfg 里），所以走这个入口而不是下面
     * 那个只认类型的重载。
     *
     * @param cfg   单元格配置
     * @param value 取到的字段值
     * @return 图片地址；值为空、或不像图片/编不出码时返回 null（该格就当普通空格子处理）
     */
    public static String src(CellConfigDTO cfg, Object value) {
        if (cfg == null) {
            return null;
        }
        return cfg.isBarcode() ? BarcodeGenerator.dataUri(cfg, value) : src(cfg.getType(), value);
    }

    /**
     * 把单元格取到的值变成图片地址。
     *
     * @param type  单元格类型（{@code img} / {@code base64}）
     * @param value 取到的字段值
     * @return 图片地址；值为空、或不像图片时返回 null（该格就当普通空格子处理）
     */
    public static String src(String type, Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        if (!"base64".equals(type)) {
            return s;
        }
        if (s.startsWith("data:")) {
            return s;
        }
        // 有的接口会把整个 data URI 的后半截存进来（`;base64,xxxx`），或者干脆带着前缀又缺了 data:
        int comma = s.indexOf("base64,");
        if (comma >= 0) {
            s = s.substring(comma + "base64,".length());
        }
        // 换行是 base64 里唯一合法的「杂质」（MIME 编码每 76 字符折一行），去掉即可
        String payload = s.replaceAll("\\s", "");
        if (payload.isEmpty()) {
            return null;
        }
        return "data:" + mimeOf(payload) + ";base64," + payload;
    }

    private static String mimeOf(String base64) {
        for (String[] magic : BASE64_MAGIC) {
            if (base64.startsWith(magic[0])) {
                return magic[1];
            }
        }
        return "image/png";
    }
}
