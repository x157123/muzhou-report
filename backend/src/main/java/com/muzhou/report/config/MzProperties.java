package com.muzhou.report.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * muzhou.report.* 配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "muzhou.report")
public class MzProperties {

    /**
     * 单个数据集最大返回行数。
     *
     * <p>三种类型都受它约束：sql 走 {@code PreparedStatement#setMaxRows}，
     * api / json 在解析行数组时截断（都记一条 warn）。超出部分静默丢弃 ——
     * 与 JDBC 的行为一致，报错反而会让「数据比预期多」直接变成一次失败的渲染。
     */
    private int maxRows = 20000;

    /**
     * api 型数据集单次响应的字节上限，超过就报错。
     *
     * <p>没有这一条的话，一个还几百 MB 的接口就是一次 OOM —— 行数上限是**解析之后**才生效的，
     * 那时整份响应已经在内存里了。所以要在读流的时候就掐断，做法同
     * {@code export/ImageLoader#download}：只多读一个字节就能判断是不是超了。
     */
    private long apiMaxBytes = 32L * 1024 * 1024;

    /** 数据集预览默认条数。 */
    private int previewRows = 100;

    /** SQL 执行超时（秒）。 */
    private int queryTimeout = 30;

    /** 渲染结果最大单元格数。 */
    private int maxCells = 500000;

    /**
     * 父子关联（子接口查询）里，主表最多驱动多少行子查询。
     *
     * <p>主表每一行都要打一次子表的 SQL / 接口，成本随主表行数线性涨 —— 一个没筛过的主表
     * 能把子接口打上万次。宁可报错让人先加参数筛小，也不要让请求卡死在那儿。
     */
    private int maxLinkRows = 500;

    /**
     * 一次渲染里并行取数的并发度，<=1 为串行。
     *
     * <p>模板用到多个数据集时，各自的 SQL / 接口互相独立，逐个取是纯串行的 IO 等待，
     * 并行取省的就是这段等待。线程池整个应用共用一个、有界、空闲即回收
     * （见 {@code ReportRenderEngine#fetchPool}）；并发压力落在业务库连接池与下游接口上，
     * 调大之前先确认它们扛得住。配了父子关联的报表不受此项影响 ——
     * 关联取数有先后依赖（先主后子、主表逐行查子表），并行不了。
     */
    private int fetchParallelism = 4;

    /**
     * 打开一张不存在的报表时，是否当场建一张空白的（{@code ReportServiceImpl#autoCreate}）。
     *
     * <p>这是为**嵌入**准备的：外部系统拿自己的业务 KEY 直接开设计器，不必先来一次
     * {@code POST /api/report}。代价是 {@code GET /api/report/{id}} 带上了写副作用 ——
     * 谁扫一遍地址就往库里灌多少行，且没有上限。不靠这个能力的部署应当关掉。
     *
     * <p>注意它只对**设计器打开报表**那条路生效；渲染 / 导出一律不建
     * （渲染一张不存在的报表该报错，而不是出一份空模板，见
     * {@code ReportService#getDetail(String, String, boolean)}）。
     */
    private boolean autoCreate = true;

    /**
     * 同时进行的导出任务数上限（Excel / PDF / Word 共用一个闸）。
     *
     * <p>导出全程在内存里：PDF 那条路的峰值是「xlsx 字节 + POI 对象树 + PDF 字节」三份
     * 同时占着堆。{@code maxCells} 拦的是**单次**规模，拦不住并发 —— 十个人同时导一份大报表
     * 照样能把堆打满，而 OOM 之后倒下的是整个服务，不只是那几次导出。
     * 排队比崩掉强，超时了也只是那一个人重试。
     */
    private int exportConcurrency = 4;

    /** 排队等导出名额的最长时间（秒），等不到就报错让用户稍后重试。 */
    private int exportWaitSeconds = 60;

    /** 请求日志（调试用）。 */
    private Log log = new Log();

    /** PDF 导出（xlsx -> pdf）配置。 */
    private Pdf pdf = new Pdf();

    /** 图片单元格（type=img / base64）导出时的取图配置。 */
    private Image image = new Image();

    /**
     * 图片单元格取图配置。见 {@link com.muzhou.report.export.ImageLoader}。
     *
     * <p>只影响**导出**：预览时图片是浏览器自己去拉的，服务端不碰。
     */
    @Data
    public static class Image {

        /** 下载一张图片的连接 / 读取超时（毫秒）。 */
        private int timeout = 5000;

        /**
         * 相对路径图片（`/upload/a.png`）的站点地址，例如 {@code http://10.0.0.9:8080}。
         *
         * <p>数据库里存相对路径很常见：前端拼上当前站点就显示出来了，而**导出时是服务端去取图**
         * ，它没有「当前页面」这个上下文，不配这一项就只能跳过。留空 = 只接受绝对地址。
         */
        private String baseUrl;

        /**
         * 单张图片的字节数上限，超过就跳过这张（导出不中断）。
         *
         * <p>图片是逐行扩展出来的，一张 500 行的报表就是 500 张图 —— 不设上限的话，
         * 一个指错的地址（比如指到了一份视频）就能把导出的内存吃光。
         */
        private long maxBytes = 5 * 1024 * 1024;
    }

    /**
     * 请求日志配置。见 {@link RequestLogFilter}。
     */
    @Data
    public static class Log {

        /** 是否打印接口请求/返回。默认开，生产环境可关。 */
        private boolean enabled = true;

        /** 是否打印请求体与响应体。关掉则只打「方法 + 路径 + 状态码 + 耗时」一行。 */
        private boolean body = true;

        /**
         * 请求体/响应体单边最多打印多少字符，超出截断。
         *
         * <p>渲染结果动辄几百 KB（整张 FortuneSheet 的 celldata），全打出来控制台就没法看了。
         */
        private int maxBody = 2000;

        /** 只记录匹配这些 Ant 路径的请求，其余放过（静态资源、健康检查等不记）。 */
        private List<String> include = new ArrayList<>(List.of("/api/**"));

        /**
         * 匹配这些路径时不缓存<b>响应体</b>（请求体照打）。
         *
         * <p>导出接口的响应是几 MB 的 xlsx / pdf 字节流，缓存下来纯属白白多占一份内存，
         * 而且它也不是文本、打不出有用的东西；请求体那几行参数 JSON 反倒是要看的。
         */
        private List<String> exclude = new ArrayList<>(List.of("/api/render/**/export/**"));

        /**
         * 匹配这些路径时<b>两个方向的报文都不打</b>，只留「方法 + 路径 + 状态码 + 耗时」那一行。
         *
         * <p>与 {@link #exclude} 的区别是它管的是**请求体**：数据源接口的请求体里带着业务库口令
         * （`PUT /api/datasource`），照打就等于把所有业务库的明文口令写进日志文件 ——
         * 而日志的流转范围往往比数据库宽得多（发给同事排查、进日志采集平台）。
         * 口令本身已经不出参了（见 {@code MzDatasource#password}），这里补上入参这一侧。
         */
        private List<String> mask = new ArrayList<>(List.of("/api/datasource/**"));
    }

    /**
     * PDF 导出配置。见 {@link com.muzhou.report.export.PdfExporter}。
     */
    @Data
    public static class Pdf {

        /**
         * 正文字体文件路径（.ttf / .otf / .ttc），留空则在系统字体目录里自动探测中文字体。
         *
         * <p>PDF 必须内嵌字体才能显示中文，而 Java 侧没有内置中文字体。.ttc 字体集要用
         * {@code 路径,序号} 指定其中一款，例如 {@code C:/Windows/Fonts/msyh.ttc,0}。
         */
        private String fontPath;
    }
}
