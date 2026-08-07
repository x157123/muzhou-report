package com.muzhou.report.export;

/** 导出测试共用的图片素材。 */
final class TestImages {

    private TestImages() {
    }

    /** 1×1 的透明 PNG，够用来验证「图片被写进去了、格式判对了」。 */
    static final String PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    /** 渲染引擎给 base64 图片单元格补出来的形态（见 engine/CellImage）。 */
    static final String PNG_DATA_URI = "data:image/png;base64," + PNG_1X1;
}
