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

    /** 业务库连接池（运行时注册的那些数据源）。 */
    private Pool pool = new Pool();

    /** 请求日志（调试用）。 */
    private Log log = new Log();

    /** PDF 导出（xlsx -> pdf）配置。 */
    private Pdf pdf = new Pdf();

    /** 图片单元格（type=img / base64）导出时的取图配置。 */
    private Image image = new Image();

    /** 上传字体的存放配置。 */
    private Font font = new Font();

    /**
     * 业务库连接池参数。见 {@link com.muzhou.report.datasource.DynamicDatasourceRegistry}。
     *
     * <p><b>这一份只管在「数据源管理」里加出来的业务库</b>，不含 master（本系统自己的元数据库，
     * 它的池在 {@code spring.datasource.dynamic.hikari}）。两者分开是有意的：元数据库是本地
     * 小库、连接就那么几条，业务库是别人家的生产库，能给多少连接由对方 DBA 说了算。
     *
     * <p><b>所有业务库共用这一份配置</b>——这里的每一项都会逐条写进 {@code DataSourceProperty#hikari}，
     * 一项不留 null。留 null 的项会被 dynamic-datasource 拿 {@code spring.datasource.dynamic.hikari}
     * （那是 master 的）补上，于是「改了 master 的池，业务库跟着变」，两边说不清谁管谁。
     */
    @Data
    public static class Pool {

        /**
         * 单个业务库的最大连接数（HikariCP {@code maximumPoolSize}）。
         *
         * <p><b>池是定长的，超出上限的请求排队等 {@link #connectionTimeout} 而不是再开新连接</b> ——
         * 报表场景一次渲染可能同时打好几个数据集（{@code fetchParallelism}）、还有导出并发
         * （{@code exportConcurrency}），不封顶的话业务库那边看到的就是连接数一路涨。
         * 调大之前先确认对方的 {@code max_connections} 扛得住：**每个数据源各占一份这个上限**，
         * 10 个数据源就是最多 10×这个数。
         */
        private int maximumPoolSize = 10;

        /**
         * 常驻的空闲连接数（{@code minimumIdle}）。
         *
         * <p>默认 1：报表是低频高峰型的负载，常驻一条免掉「每次渲染都重新握手 + 登录」这一下，
         * 又不至于让十来个数据源白占几十条连接。设成 0 = 完全空闲时一条不留（连接数最省，
         * 代价是每次冷启动多一次建连）；设成与 {@link #maximumPoolSize} 相等 = 真正的定长池，
         * 此时 {@link #idleTimeout} 不再起作用。
         */
        private int minimumIdle = 1;

        /** 等一条空闲连接的最长时间（毫秒），等不到就报错。池满时排队等的就是它。 */
        private long connectionTimeout = 30000;

        /** 借出连接前做存活校验的超时（毫秒），必须小于 {@link #connectionTimeout}。 */
        private long validationTimeout = 5000;

        /**
         * 空闲连接多久后被回收（毫秒），0 = 不回收。
         *
         * <p>只在 {@link #minimumIdle} 小于 {@link #maximumPoolSize} 时有意义 ——
         * 高峰期涨上去的那些连接靠它退回去，否则报表跑完一次高峰，连接就一直占着。
         */
        private long idleTimeout = 600000;

        /**
         * 一条连接的最长寿命（毫秒），0 = 不限。
         *
         * <p>务必**小于**数据库/中间件那边的空闲断连时间（MySQL 的 {@code wait_timeout} 默认
         * 28800s，但云上的负载均衡常砍到几分钟）：被对方悄悄掐掉的连接，池子自己不换就要等到
         * 借出去用的时候才发现，表现是「偶发一次 Communications link failure」。
         */
        private long maxLifetime = 1800000;

        /**
         * 空闲连接的保活间隔（毫秒），0 = 关闭；开启时不得小于 30000。
         *
         * <p>连接被防火墙按「空闲多久」掐的场合才需要它 —— 隔一会儿探一下，让连接不算空闲。
         */
        private long keepaliveTime = 0;

        /**
         * 连接借出超过多少毫秒未归还就打一条疑似泄漏的日志，0 = 关闭；开启时不得小于 2000。
         *
         * <p>排查「连接池被占满」时打开它，日志里会带上借走没还的那处调用栈。
         */
        private long leakDetectionThreshold = 0;

        /**
         * 存活校验 SQL，**留空 = 走 JDBC4 的 {@code Connection.isValid()}**（推荐）。
         *
         * <p>别图省事统一填 {@code SELECT 1}：Oracle 上它是语法错误（要 {@code SELECT 1 FROM DUAL}），
         * 而这一份配置是所有业务库共用的，填死一句就等于挑数据库类型。只有老到不支持
         * {@code isValid()} 的驱动才需要填，而且那时你的库类型多半只有一种。
         */
        private String connectionTestQuery;
    }

    /**
     * 上传字体配置。见 {@link com.muzhou.report.service.FontService}。
     *
     * <p>字体文件的正本在库里（见 {@code MzFont#fileData}），这里配的是各节点落缓存的地方。
     */
    @Data
    public static class Font {

        /**
         * 字体文件的**本地缓存目录**，相对路径按进程工作目录解析。
         *
         * <p>PDF 引擎要的是一个路径而不是一段字节，所以各节点第一次用到某款字体时会把它从库里
         * 落一份到这里。**删了不会丢东西**（正本在库里，下次用到自动重新落），多节点部署时
         * 各节点各配各的、不需要共享存储。
         */
        private String dir = "./data/fonts";

        /**
         * 单个字体文件的字节上限。
         *
         * <p>中文字体动辄十几 MB（思源黑体全字集 20MB+），所以不能照图片那个 5MB 收；
         * 但也得有个上限，这个目录是无限往里堆的。
         */
        private long maxBytes = 30L * 1024 * 1024;
    }

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
