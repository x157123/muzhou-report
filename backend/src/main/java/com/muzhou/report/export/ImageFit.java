package com.muzhou.report.export;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

/**
 * 图片在单元格里怎么摆：**等比例缩放到装得下，居中，不拉伸**。
 *
 * <p>格子的宽高比几乎不可能正好等于图片的宽高比，铺满整格就等于把图片拉变形（人像会被压扁、
 * 商品图会被拉宽）。所以按「宽、高哪个先顶到边就以哪个为准」缩放，另一个方向留白。
 *
 * <p>三条输出路都用这一份规则：Excel（锚点直接就是算出来的这个框）、PDF、Word。
 * 别在某一条路上单独改，三处就对不上了。
 */
@Slf4j
final class ImageFit {

    private ImageFit() {
    }

    /**
     * 图片在格子里占的那一块（相对格子左上角）。
     *
     * @param width  缩放后的宽
     * @param height 缩放后的高
     * @param left   居中后距格子左边的距离
     * @param top    居中后距格子上边的距离
     */
    record Box(double width, double height, double left, double top) {
    }

    /**
     * 把图片等比例装进 {@code boxW × boxH} 的格子并居中。
     *
     * <p>读不出图片尺寸时退回「铺满整格」——总比不画强，而且这种图 POI 多半也认不出来，
     * 走不到这一步。
     */
    static Box contain(double boxW, double boxH, byte[] data) {
        int[] size = intrinsicSize(data);
        if (size == null || boxW <= 0 || boxH <= 0) {
            return new Box(boxW, boxH, 0, 0);
        }
        // 宽、高哪个先顶到边就以哪个为准；小图也放大到贴边（「按表格高或宽展示」），
        // 否则一张 40px 的缩略图摆在拖高的行里只有一丁点
        double scale = Math.min(boxW / size[0], boxH / size[1]);
        double w = size[0] * scale;
        double h = size[1] * scale;
        return new Box(w, h, (boxW - w) / 2, (boxH - h) / 2);
    }

    /**
     * 图片自己的像素尺寸，认不出返回 null。
     *
     * <p>只读文件头（{@code ImageReader#getWidth}），不解码整张图 —— 一份报表里可能有几百张图，
     * 全解出来内存就没了。
     */
    static int[] intrinsicSize(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                return w > 0 && h > 0 ? new int[]{w, h} : null;
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.warn("读取图片尺寸失败，按铺满格子处理: {}", e.getMessage());
            return null;
        }
    }
}
