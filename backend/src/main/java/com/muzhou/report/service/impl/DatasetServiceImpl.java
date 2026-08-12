package com.muzhou.report.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muzhou.report.common.BizException;
import com.muzhou.report.common.LogText;
import com.muzhou.report.common.PageResult;
import com.muzhou.report.config.MzProperties;
import com.muzhou.report.datasource.JdbcExecutor;
import com.muzhou.report.datasource.SqlParamParser;
import com.muzhou.report.dto.ColumnMetaDTO;
import com.muzhou.report.dto.DatasetDetailDTO;
import com.muzhou.report.dto.DatasetParseDTO;
import com.muzhou.report.dto.DatasetParseResultDTO;
import com.muzhou.report.dto.DatasetPreviewResultDTO;
import com.muzhou.report.dto.DatasetRowsDTO;
import com.muzhou.report.dto.DatasetSaveDTO;
import com.muzhou.report.entity.MzDataset;
import com.muzhou.report.entity.MzDatasetField;
import com.muzhou.report.entity.MzDatasetParam;
import com.muzhou.report.entity.MzDatasource;
import com.muzhou.report.mapper.MzDatasetFieldMapper;
import com.muzhou.report.mapper.MzDatasetMapper;
import com.muzhou.report.mapper.MzDatasetParamMapper;
import com.muzhou.report.service.DatasetService;
import com.muzhou.report.service.DatasourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据集服务实现，见 docs/CONTRACT.md §3.2。
 *
 * <p>三种数据集类型（sql / api / json）统一收敛到 {@link #execute} 里，
 * 对外只暴露「给我 code 和参数，还我行数据」这一个语义，渲染引擎因此不必关心取数细节。
 */
@Slf4j
@Service
public class DatasetServiceImpl extends ServiceImpl<MzDatasetMapper, MzDataset> implements DatasetService {

    /** 数据集编码规则：字母开头，仅含字母、数字、下划线。 */
    private static final String CODE_PATTERN = "^[a-zA-Z][a-zA-Z0-9_]*$";

    /** 公共数据集的 report_id：空串而不是 NULL，唯一索引才拦得住重名。 */
    private static final String PUBLIC = "";

    /** 不限版本（公共集，以及报表下全版本共用的那些）的 version_id，同上，空串不是 NULL。 */
    private static final String ALL_VERSIONS = "";

    /** api/json 类型里，从对象响应中依次尝试的数组字段名（顺序即优先级，别随手调）。 */
    private static final List<String> ARRAY_KEYS = List.of("data", "records", "rows", "result", "list");

    /** 分页响应里依次尝试的总数字段名。 */
    private static final List<String> TOTAL_KEYS = List.of("total", "totalCount", "totalRow", "totalRows", "count");

    /** 接口地址里的 ${param}，与 {@link SqlParamParser} 的绑定语法保持一致。 */
    private static final Pattern URL_PLACEHOLDER = Pattern.compile("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}");

    private final MzDatasetFieldMapper fieldMapper;
    private final MzDatasetParamMapper paramMapper;
    private final DatasourceService datasourceService;
    private final SqlParamParser sqlParamParser;
    private final JdbcExecutor jdbcExecutor;
    private final MzProperties props;

    /**
     * 解析接口响应、序列化 POST 请求体都用它。
     *
     * <p><b>不能用裸的 {@code new ObjectMapper()}</b>：{@link SqlParamParser#convertValue} 会把
     * {@code paramType=date} 的参数转成 {@link java.time.LocalDate}，而裸 ObjectMapper 没注册
     * jsr310 模块，序列化时直接抛「Java 8 date/time type not supported by default」——
     * 现象是「api 型数据集只要走 POST 且带日期参数就报『接口请求失败』」，报错里看不出原因。
     * 用容器里那个配置完整的实例（{@code WebConfig#mzObjectMapper}），顺带让日期的写法与
     * Web 出参保持同一套规则。
     */
    private final ObjectMapper mapper;

    /**
     * 接口型数据集共用的 HttpClient。
     *
     * <p><b>必须复用一个实例</b>：JDK 的 HttpClient 每建一个就带一条 SelectorManager 线程和
     * 独立连接池，而这里从不 close。以前是每次取数 new 一个 —— {@code perRowPage} 200 条单据
     * 叠上父子关联「主表每行查一次子表」，一次渲染就能建出几百个，全靠 GC 回收。
     * 同样的道理见 {@code export/ImageLoader}。
     *
     * <p>连接超时是 client 级的，读超时仍逐个请求设（见 {@link #executeApi}）。
     */
    private final HttpClient httpClient;

    /**
     * 手写构造器而非 {@code @RequiredArgsConstructor}：ObjectMapper 要按名字精确指定
     * （Lombok 不会把字段上的 {@code @Qualifier} 搬到生成的构造器参数上，同
     * {@code RenderServiceImpl}），HttpClient 也需要在这里按配置建出来。
     */
    public DatasetServiceImpl(MzDatasetFieldMapper fieldMapper,
                              MzDatasetParamMapper paramMapper,
                              DatasourceService datasourceService,
                              SqlParamParser sqlParamParser,
                              JdbcExecutor jdbcExecutor,
                              MzProperties props,
                              @Qualifier("mzObjectMapper") ObjectMapper mapper) {
        this.fieldMapper = fieldMapper;
        this.paramMapper = paramMapper;
        this.datasourceService = datasourceService;
        this.sqlParamParser = sqlParamParser;
        this.jdbcExecutor = jdbcExecutor;
        this.props = props;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getQueryTimeout()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /* ------------------------------ 取数 ------------------------------ */

    @Override
    public List<Map<String, Object>> fetchDataByCode(String reportId, String versionId, String datasetCode,
                                                     Map<String, Object> params) {
        return fetchRowsByCode(reportId, versionId, datasetCode, params).getRows();
    }

    @Override
    public DatasetRowsDTO fetchRowsByCode(String reportId, String versionId, String datasetCode,
                                          Map<String, Object> params) {
        return fetchRows(resolve(reportId, versionId, datasetCode), params);
    }

    @Override
    public ResolvedDataset resolve(String reportId, String versionId, String datasetCode) {
        MzDataset ds = getByCode(reportId, versionId, datasetCode);
        if (ds == null) {
            throw new BizException("数据集不存在: " + datasetCode);
        }
        return new ResolvedDataset(ds, ds.getId() == null ? List.of() : listParams(ds.getId()));
    }

    @Override
    public DatasetRowsDTO fetchRows(ResolvedDataset resolved, Map<String, Object> params) {
        return execute(resolved.dataset(), resolved.params(), params);
    }

    @Override
    public List<Map<String, Object>> fetchDataById(String datasetId, Map<String, Object> params) {
        MzDataset ds = getById(datasetId);
        if (ds == null) {
            throw new BizException("数据集不存在: " + datasetId);
        }
        return execute(ds, params).getRows();
    }

    /** 自己去查一次参数定义再取数（预览、按 id 取数这类一次性调用走这条）。 */
    private DatasetRowsDTO execute(MzDataset ds, Map<String, Object> params) {
        return execute(ds, ds.getId() == null ? List.of() : listParams(ds.getId()), params);
    }

    /**
     * 按类型执行取数。
     *
     * @param defs 参数定义，由调用方给 —— 渲染那条路上它是**一次渲染里解析一遍**的
     *             （见 {@link #resolve}），不该每取一行数据就再查一次库
     */
    private DatasetRowsDTO execute(MzDataset ds, List<MzDatasetParam> defs, Map<String, Object> params) {
        String type = StringUtils.hasText(ds.getType()) ? ds.getType() : "sql";
        return switch (type) {
            case "sql" -> DatasetRowsDTO.of(executeSql(ds, defs, params));
            case "api" -> executeApi(ds, defs, params);
            case "json" -> extractRows(ds.getJsonText(), ds.getResultType());
            default -> throw new BizException("不支持的数据集类型: " + type);
        };
    }

    /** SQL 恒为集合型：分页要靠数据库端 limit/offset 与另一条 count，不在本项目范围内。 */
    private List<Map<String, Object>> executeSql(MzDataset ds, List<MzDatasetParam> defs,
                                                 Map<String, Object> params) {
        if (!StringUtils.hasText(ds.getSqlText())) {
            throw new BizException("数据集[" + ds.getCode() + "]未配置 SQL");
        }
        SqlParamParser.ParsedSql parsed = sqlParamParser.parse(ds.getSqlText(), defs, params);
        String dsCode = resolveDatasourceCode(ds.getDatasourceId());
        return jdbcExecutor.query(dsCode, parsed.getSql(), parsed.getArgs(), props.getMaxRows());
    }

    /** 通过数据源 id 拿到动态数据源的 code。 */
    private String resolveDatasourceCode(String datasourceId) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new BizException("数据集未绑定数据源");
        }
        MzDatasource datasource = datasourceService.getById(datasourceId);
        if (datasource == null) {
            throw new BizException("数据源不存在: " + datasourceId);
        }
        return datasource.getCode();
    }

    /**
     * HTTP 接口型数据集。URL 中的 {@code ${param}} 做 URL 编码后替换；
     * POST 时把参数序列化成 JSON 作为请求体。
     */
    private DatasetRowsDTO executeApi(MzDataset ds, List<MzDatasetParam> defs,
                                      Map<String, Object> params) {
        if (!StringUtils.hasText(ds.getApiUrl())) {
            throw new BizException("数据集[" + ds.getCode() + "]未配置接口地址");
        }
        Map<String, Object> values = sqlParamParser.resolveValues(defs, params);
        String url = substituteUrl(ds.getApiUrl(), values);
        String method = StringUtils.hasText(ds.getApiMethod()) ? ds.getApiMethod().toUpperCase() : "GET";

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getQueryTimeout()));

            applyHeaders(builder, ds.getApiHeaders());

            String reqBody = null;
            if ("POST".equals(method)) {
                reqBody = mapper.writeValueAsString(values);
                builder.header("Content-Type", "application/json;charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }

            long start = System.currentTimeMillis();
            // 收成流而不是 ofString：整份读进 String 的话，一个还几百 MB 的接口
            // 在这一行就把内存吃光了，后面的行数上限根本轮不到生效
            HttpResponse<InputStream> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            String body = readBody(response, url);
            // 状态码判断之前就打 —— 接口挂了才最需要看到「到底打的哪个地址、对面回了什么」
            logApi(ds, method, url, reqBody, response.statusCode(), body,
                    System.currentTimeMillis() - start);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException("接口[" + url + "]返回状态码 " + response.statusCode());
            }
            return extractRows(body, ds.getResultType());
        } catch (BizException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("接口请求被中断");
        } catch (Exception e) {
            log.error("接口数据集取数失败 url={}", url, e);
            throw new BizException("接口请求失败: " + e.getMessage());
        }
    }

    /**
     * 读响应体，超过 {@code muzhou.report.api-max-bytes} 直接报错。
     *
     * <p>做法同 {@code export/ImageLoader#download}：**只多读一个字节**就能判断是不是超了，
     * 不必先把整个流读进内存再来后悔。这里选择报错而不是截断 —— 截出来的必定是半截 JSON，
     * 解析报错的信息量还不如直接说「响应太大」。
     */
    private String readBody(HttpResponse<InputStream> response, String url) throws IOException {
        long limit = props.getApiMaxBytes();
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE));
            if (bytes.length > limit) {
                throw new BizException("接口[" + url + "]的响应超过 " + limit
                        + " 字节上限，请让接口分页返回（数据集设成分页型）或调大 muzhou.report.api-max-bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * 打印一次远程接口取数：地址是 {@code ${param}} 替换后的**实际请求地址**，不是数据集里存的模板。
     *
     * <p>格式与 {@link com.muzhou.report.config.RequestLogFilter} 保持一致（{@code >>>} 是收到的请求，
     * {@code <<<} 是打出去的请求），共用 {@code muzhou.report.log.*} 那几个开关。
     */
    private void logApi(MzDataset ds, String method, String url, String reqBody,
                        int status, String respBody, long cost) {
        MzProperties.Log cfg = props.getLog();
        if (!cfg.isEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("\n<<< 远程接口[").append(ds.getCode()).append("] ").append(method).append(' ').append(url)
                .append("  [").append(status).append("]  ").append(cost).append("ms");
        if (cfg.isBody()) {
            if (StringUtils.hasText(reqBody)) {
                sb.append("\n    请求: ").append(LogText.abbrev(reqBody, cfg.getMaxBody()));
            }
            sb.append("\n    响应: ").append(StringUtils.hasText(respBody)
                    ? LogText.abbrev(respBody, cfg.getMaxBody()) : "<空>");
        }
        log.info("{}", sb);
    }

    private void applyHeaders(HttpRequest.Builder builder, String headersJson) {
        if (!StringUtils.hasText(headersJson)) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(headersJson);
            node.fields().forEachRemaining(en -> {
                if (en.getValue() != null && !en.getValue().isNull()) {
                    builder.header(en.getKey(), en.getValue().asText());
                }
            });
        } catch (Exception e) {
            throw new BizException("请求头不是合法 JSON: " + e.getMessage());
        }
    }

    /**
     * URL 里的 {@code ${param}} 替换（值做 URL 编码）。
     *
     * <p>值的来源有两处：数据集自己的参数定义（含默认值），以及**渲染时传进来的报表参数** ——
     * 后者包含预览地址上的 query（`/preview/{id}?id=11233`），所以接口地址里写
     * {@code ...?id=${id}} 就能拿到外部传来的 id，不必先在报表里声明一遍参数。
     */
    private String substituteUrl(String url, Map<String, Object> values) {
        String out = url;
        for (Map.Entry<String, Object> en : values.entrySet()) {
            String token = "${" + en.getKey() + "}";
            if (out.contains(token)) {
                String v = en.getValue() == null ? "" : String.valueOf(en.getValue());
                out = out.replace(token, URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        }
        // 没人给值的占位符要当场说清楚是哪个 —— 留在 URL 里的 ${} 会让 URI.create
        // 抛「Illegal character」，那个报错完全看不出少的是哪个参数
        Matcher left = URL_PLACEHOLDER.matcher(out);
        if (left.find()) {
            Set<String> missing = new LinkedHashSet<>();
            do {
                missing.add(left.group(1));
            } while (left.find());
            throw new BizException("接口地址缺少参数值: " + String.join(", ", missing));
        }
        return out;
    }

    /**
     * JSON 文本 -> 行数据（+ 分页型的总条数）。key 原样保留，见 {@link #toRows}。
     *
     * <p>两种形态区别只在「认不认得出来算不算错」：
     * <ul>
     *   <li>{@code list} 集合：数组直接用；对象依次尝试常见的数据字段名；都不是就当成一行单对象。
     *       这一层兜底是有意的 —— 集合型不关心总数，实在认不出来也能出数据。</li>
     *   <li>{@code page} 分页：**必须**同时找得到数据数组与总数，找不到就报错。
     *       标了分页却认不出结构，翻页和总页数都是错的，静默降级只会让人以为「数据不全」。</li>
     * </ul>
     */
    private DatasetRowsDTO extractRows(String json, String resultType) {
        if (!StringUtils.hasText(json)) {
            return DatasetRowsDTO.of(List.of());
        }
        boolean paged = isPaged(resultType);
        try {
            JsonNode root = mapper.readTree(json);
            ArrayHit hit = findArray(root);
            JsonNode array = hit == null ? null : hit.array();

            if (paged) {
                if (array == null) {
                    throw new BizException("分页响应里找不到数据数组（依次找 "
                            + String.join(" / ", ARRAY_KEYS) + "，支持嵌一层）");
                }
                Long total = findTotal(root, hit.sibling());
                if (total == null) {
                    throw new BizException("分页响应里找不到总数字段（依次找 "
                            + String.join(" / ", TOTAL_KEYS) + "）");
                }
                return new DatasetRowsDTO(toRows(array), total);
            }

            if (array == null) {
                // 单对象也当成一行，字段名即列名
                List<Map<String, Object>> rows = new ArrayList<>();
                rows.add(new LinkedHashMap<>(mapper.convertValue(root, Map.class)));
                return DatasetRowsDTO.of(rows);
            }
            return DatasetRowsDTO.of(toRows(array));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("JSON 解析失败: " + e.getMessage());
        }
    }

    private boolean isPaged(String resultType) {
        return "page".equalsIgnoreCase(resultType);
    }

    /**
     * 命中的数据数组，以及**包着它的那个对象** —— 总数字段一般跟数组是同级的
     * （{@code {records:[...], total:n}}），根就是数组时没有同级对象。
     */
    private record ArrayHit(JsonNode array, JsonNode sibling) {
    }

    /** 依次按常见字段名找数据数组，支持嵌一层（{@code {data:{list:[...],total:n}}}）。 */
    private ArrayHit findArray(JsonNode root) {
        if (root.isArray()) {
            return new ArrayHit(root, null);
        }
        if (!root.isObject()) {
            return null;
        }
        for (String key : ARRAY_KEYS) {
            JsonNode candidate = root.get(key);
            if (candidate != null && candidate.isArray()) {
                return new ArrayHit(candidate, root);
            }
        }
        for (String outer : ARRAY_KEYS) {
            JsonNode nested = root.get(outer);
            if (nested == null || !nested.isObject()) {
                continue;
            }
            for (String key : ARRAY_KEYS) {
                JsonNode candidate = nested.get(key);
                if (candidate != null && candidate.isArray()) {
                    return new ArrayHit(candidate, nested);
                }
            }
        }
        return null;
    }

    /** 总数：先在数组的同级对象上找，再退回根对象（有的接口把 total 放在最外层）。 */
    private Long findTotal(JsonNode root, JsonNode sibling) {
        Long fromSibling = readTotal(sibling);
        return fromSibling != null ? fromSibling : readTotal(root);
    }

    private Long readTotal(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String key : TOTAL_KEYS) {
            JsonNode v = node.get(key);
            if (v != null && v.isNumber()) {
                return v.asLong();
            }
            // "total":"1280" 这种字符串数字也认
            if (v != null && v.isTextual() && v.asText().matches("\\d+")) {
                return Long.parseLong(v.asText());
            }
        }
        return null;
    }

    /**
     * JSON 数组 -> 行数据。<b>key 原样保留，不做大小写转换。</b>
     *
     * <p>渲染引擎按字段名取值是大小写敏感的（{@code ExpandProcessor} 的 {@code row.get(field)}），
     * 而字段名有两个来源，两边必须是同一个写法：一是「解析字段」按这里的 key 生成
     * （{@link #fieldsFromRows}），二是外部导入进来的报表原样带着接口的 camelCase
     * （{@code SpreadsheetImportTool}）。曾经在这里统一转小写，接口还的是 {@code suppliesName}、
     * 落到行里成了 {@code suppliesname}，模板里 {@code #{code.suppliesName}} 就取不到数 ——
     * 现象是「数据集预览里明明有值，报表上那一列空着」，而恰好全小写的字段（seq / brand / unit）
     * 又是好的，一张表一半列有数一半没有。
     *
     * <p>SQL 分支不同：列标签由数据库给，各家大小写规则不一（{@code JdbcExecutor#label} 统一小写），
     * 那边的字段名同样是按同一个 label 生成的，自成一套、也是对齐的。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toRows(JsonNode array) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int limit = props.getMaxRows();
        if (array.size() > limit) {
            // 与 sql 那条路一致：JdbcExecutor 用 setMaxRows 也是静默截断的。
            // 这里报错的话，「接口这次多还了几条」就会直接变成一次失败的渲染
            log.warn("接口/JSON 数据集返回 {} 行，超过 muzhou.report.max-rows={}，只取前 {} 行",
                    array.size(), limit, limit);
        }
        for (JsonNode item : array) {
            if (rows.size() >= limit) {
                break;
            }
            if (item.isObject()) {
                rows.add(new LinkedHashMap<>(mapper.convertValue(item, Map.class)));
            } else {
                Map<String, Object> single = new LinkedHashMap<>();
                single.put("value", item.isNumber() ? item.numberValue() : item.asText());
                rows.add(single);
            }
        }
        return rows;
    }

    /* ------------------------------ 查询 ------------------------------ */

    /**
     * 按作用范围**由窄到宽**找同 code 的那一个：版本级 → 报表级（全版本共用）→ 公共。
     *
     * <p>「某一版换个接口」就是靠这条顺序实现的：在那一版下建一个同 code 的数据集，
     * 它把报表级/公共的那份盖住，模板里的 {@code #{code.字段}} 一个字都不用改。
     *
     * <p>报表级压过公共这一层原本是兜底（新建内部集时会拦掉与公共重名），现在多了一层
     * 同样的道理：万一三级都有同 code 的，本版本用自己的那份，语义不会飘。
     */
    @Override
    public MzDataset getByCode(String reportId, String versionId, String code) {
        if (StringUtils.hasText(reportId)) {
            if (StringUtils.hasText(versionId)) {
                MzDataset own = lambdaQuery()
                        .eq(MzDataset::getCode, code)
                        .eq(MzDataset::getReportId, reportId)
                        .eq(MzDataset::getVersionId, versionId)
                        .one();
                if (own != null) {
                    return own;
                }
            }
            MzDataset shared = lambdaQuery()
                    .eq(MzDataset::getCode, code)
                    .eq(MzDataset::getReportId, reportId)
                    .eq(MzDataset::getVersionId, ALL_VERSIONS)
                    .one();
            if (shared != null) {
                return shared;
            }
        }
        return lambdaQuery().eq(MzDataset::getCode, code).eq(MzDataset::getReportId, PUBLIC).one();
    }

    @Override
    public List<MzDatasetField> listFields(String datasetId) {
        return fieldMapper.selectList(new LambdaQueryWrapper<MzDatasetField>()
                .eq(MzDatasetField::getDatasetId, datasetId)
                .orderByAsc(MzDatasetField::getSortNo));
    }

    @Override
    public List<MzDatasetParam> listParams(String datasetId) {
        return paramMapper.selectList(new LambdaQueryWrapper<MzDatasetParam>()
                .eq(MzDatasetParam::getDatasetId, datasetId)
                .orderByAsc(MzDatasetParam::getSortNo));
    }

    /** 数据集管理页只管公共数据集：内部数据集属于某张报表，在它自己的设计器里增删改。 */
    @Override
    public PageResult<MzDataset> page(long pageNo, long pageSize, String name) {
        LambdaQueryWrapper<MzDataset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MzDataset::getReportId, PUBLIC);
        if (StringUtils.hasText(name)) {
            wrapper.like(MzDataset::getName, name);
        }
        wrapper.orderByDesc(MzDataset::getCreateTime);
        Page<MzDataset> pageParam = new Page<>(pageNo, pageSize);
        return PageResult.of(page(pageParam, wrapper));
    }

    /**
     * 一次性查出所有子表记录后在内存分组，避免 N+1 查询。
     *
     * <p>返回**这一版用得上的那些**：公共数据集 + 该报表全版本共用的 + 这一版自己的，
     * 三类由 {@code dataset.reportId} / {@code dataset.versionId} 区分，前端自己分组。
     * 别的版本自己的数据集不在其中 —— 设计 v2 时看得见 v3 的接口只会让人绑错。
     */
    @Override
    public List<DatasetDetailDTO> listForReport(String reportId, String versionId) {
        List<String> scopes = StringUtils.hasText(reportId) ? List.of(PUBLIC, reportId) : List.of(PUBLIC);
        List<MzDataset> datasets = lambdaQuery()
                .eq(MzDataset::getStatus, 1)
                .in(MzDataset::getReportId, scopes)
                // 版本级的只放行当前这一版；null 是老库刚加列时可能出现的形态，按「不限版本」算
                .and(w -> w.eq(MzDataset::getVersionId, ALL_VERSIONS).or().isNull(MzDataset::getVersionId)
                        .or(StringUtils.hasText(versionId), q -> q.eq(MzDataset::getVersionId, versionId)))
                .orderByAsc(MzDataset::getName)
                .list();
        if (datasets.isEmpty()) {
            return List.of();
        }
        List<String> ids = datasets.stream().map(MzDataset::getId).toList();

        Map<String, List<MzDatasetField>> fieldMap = fieldMapper.selectList(
                        new LambdaQueryWrapper<MzDatasetField>()
                                .in(MzDatasetField::getDatasetId, ids)
                                .orderByAsc(MzDatasetField::getSortNo))
                .stream().collect(Collectors.groupingBy(MzDatasetField::getDatasetId));

        Map<String, List<MzDatasetParam>> paramMap = paramMapper.selectList(
                        new LambdaQueryWrapper<MzDatasetParam>()
                                .in(MzDatasetParam::getDatasetId, ids)
                                .orderByAsc(MzDatasetParam::getSortNo))
                .stream().collect(Collectors.groupingBy(MzDatasetParam::getDatasetId));

        List<DatasetDetailDTO> list = new ArrayList<>();
        for (MzDataset ds : datasets) {
            list.add(new DatasetDetailDTO(ds,
                    fieldMap.getOrDefault(ds.getId(), List.of()),
                    paramMap.getOrDefault(ds.getId(), List.of())));
        }
        return list;
    }

    @Override
    public DatasetDetailDTO detail(String id) {
        MzDataset ds = getById(id);
        if (ds == null) {
            throw new BizException("数据集不存在");
        }
        return new DatasetDetailDTO(ds, listFields(id), listParams(id));
    }

    /* ------------------------------ 增删改 ------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(DatasetSaveDTO dto) {
        validate(dto, null);
        return insert(dto);
    }

    /** 落库（主表 + 子表），不校验。 */
    private String insert(DatasetSaveDTO dto) {
        MzDataset ds = toEntity(dto);
        ds.setId(null);
        save(ds);
        saveChildren(ds.getId(), dto);
        return ds.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(DatasetSaveDTO dto) {
        if (!StringUtils.hasText(dto.getId())) {
            throw new BizException("缺少数据集 id");
        }
        MzDataset old = getById(dto.getId());
        if (old == null) {
            throw new BizException("数据集不存在");
        }
        // 归属报表以库里存的为准：公共集不会被某次编辑悄悄变成某报表的内部集，反之亦然。
        // **版本这一维反过来，允许改** —— 「这个接口其实几版都要用」是常事，
        // 不许改就只能删了重建（还得把字段/参数再配一遍）。公共集没有版本可言，强制清掉
        dto.setReportId(old.getReportId());
        if (!StringUtils.hasText(old.getReportId())) {
            dto.setVersionId(null);
        }
        validate(dto, dto.getId());
        MzDataset ds = toEntity(dto);
        boolean ok = updateById(ds);
        saveChildren(dto.getId(), dto);
        return ok;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(String id) {
        deleteChildren(id);
        return removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeByReport(String reportId) {
        if (!StringUtils.hasText(reportId)) {
            return;
        }
        for (MzDataset ds : listByReport(reportId)) {
            remove(ds.getId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copyToReport(String fromReportId, String toReportId, Map<String, String> versionIdMap) {
        if (!StringUtils.hasText(fromReportId) || !StringUtils.hasText(toReportId)) {
            return;
        }
        Map<String, String> versions = versionIdMap == null ? Map.of() : versionIdMap;
        for (MzDataset src : listByReport(fromReportId)) {
            DatasetSaveDTO dto = new DatasetSaveDTO();
            dto.setName(src.getName());
            dto.setCode(src.getCode());
            dto.setReportId(toReportId);
            // 版本级的那些要落到副本里**对应的那一版**上。映射里找不到（不该发生）就退回
            // 「全版本共用」—— 挂着源报表的版本 id 等于挂空，那一版在副本里根本不存在
            dto.setVersionId(StringUtils.hasText(src.getVersionId())
                    ? versions.get(src.getVersionId()) : null);
            dto.setDatasourceId(src.getDatasourceId());
            dto.setType(src.getType());
            // 结果形态照搬：漏了它分页型数据集会被 toEntity 归一成集合型，副本上的分页条就没了
            dto.setResultType(src.getResultType());
            dto.setSqlText(src.getSqlText());
            dto.setApiUrl(src.getApiUrl());
            dto.setApiMethod(src.getApiMethod());
            dto.setApiHeaders(src.getApiHeaders());
            dto.setJsonText(src.getJsonText());
            dto.setRemark(src.getRemark());
            dto.setStatus(src.getStatus());
            dto.setFields(listFields(src.getId()));
            dto.setParams(listParams(src.getId()));
            // 走 insert 不走 create：源数据集本来就存在，复制它不该因为「编码已被公共数据集占用」
            // （公共集是后建的那种情况）而让整个复制报表失败；code 换了报表也仍然唯一
            insert(dto);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceReportDatasets(String reportId, List<DatasetSaveDTO> datasets) {
        if (!StringUtils.hasText(reportId)) {
            throw new BizException("缺少报表 id");
        }
        List<DatasetSaveDTO> incoming = datasets == null ? List.of() : datasets;
        // 对齐的键是**作用范围 + code**：同一张报表里 v1 与 v2 可以各有一个 code=orders，
        // 只按 code 对齐会把两个当成同一个，第二个直接覆盖掉第一个
        Map<String, MzDataset> exist = new LinkedHashMap<>();
        for (MzDataset ds : listByReport(reportId)) {
            exist.put(scopeKey(ds.getVersionId(), ds.getCode()), ds);
        }
        Set<String> keys = incoming.stream()
                .map(d -> scopeKey(d.getVersionId(), d.getCode())).collect(Collectors.toSet());
        // 包里没有的那些：这张报表已经不用它们了，跟着删
        for (Map.Entry<String, MzDataset> e : exist.entrySet()) {
            if (!keys.contains(e.getKey())) {
                remove(e.getValue().getId());
            }
        }
        // 逻辑删除的行仍占着 uk_dataset_code_scope_v，不清掉的话同 code 的新行插不进来
        baseMapper.purgeDeletedByReport(reportId);
        for (DatasetSaveDTO dto : incoming) {
            dto.setReportId(reportId);
            MzDataset old = exist.get(scopeKey(dto.getVersionId(), dto.getCode()));
            if (old == null) {
                insert(dto);
                continue;
            }
            // 原地更新：id 保持不变。模板里的绑定认的是 code 不是 id，但内部数据集的 id
            // 还挂在别处（数据集管理面板的选中态等），换一个没有好处
            dto.setId(old.getId());
            updateById(toEntity(dto));
            saveChildren(old.getId(), dto);
        }
    }

    @Override
    public List<MzDataset> listByReport(String reportId) {
        return lambdaQuery().eq(MzDataset::getReportId, reportId).list();
    }

    @Override
    public List<MzDataset> listByVersion(String reportId, String versionId) {
        if (!StringUtils.hasText(reportId) || !StringUtils.hasText(versionId)) {
            return List.of();
        }
        return lambdaQuery()
                .eq(MzDataset::getReportId, reportId)
                .eq(MzDataset::getVersionId, versionId)
                .list();
    }

    @Override
    public boolean hasVersionDatasets(String reportId, String versionId) {
        if (!StringUtils.hasText(reportId) || !StringUtils.hasText(versionId)) {
            return false;
        }
        return count(new LambdaQueryWrapper<MzDataset>()
                .eq(MzDataset::getReportId, reportId)
                .eq(MzDataset::getVersionId, versionId)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copyToVersion(String reportId, String fromVersionId, String toVersionId) {
        if (!StringUtils.hasText(toVersionId)) {
            return;
        }
        for (MzDataset src : listByVersion(reportId, fromVersionId)) {
            DatasetSaveDTO dto = new DatasetSaveDTO();
            dto.setName(src.getName());
            dto.setCode(src.getCode());
            dto.setReportId(reportId);
            dto.setVersionId(toVersionId);
            dto.setDatasourceId(src.getDatasourceId());
            dto.setType(src.getType());
            dto.setResultType(src.getResultType());
            dto.setSqlText(src.getSqlText());
            dto.setApiUrl(src.getApiUrl());
            dto.setApiMethod(src.getApiMethod());
            dto.setApiHeaders(src.getApiHeaders());
            dto.setJsonText(src.getJsonText());
            dto.setRemark(src.getRemark());
            dto.setStatus(src.getStatus());
            dto.setFields(listFields(src.getId()));
            dto.setParams(listParams(src.getId()));
            // 同 copyToReport：走 insert 不走 create，源数据集本来就存在，不该被界面级校验挡下
            insert(dto);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeByVersion(String reportId, String versionId) {
        for (MzDataset ds : listByVersion(reportId, versionId)) {
            remove(ds.getId());
        }
    }

    /** 「作用范围 + code」的对齐键：版本 id 可能为 null / 空串，统一归一后再拼。 */
    private String scopeKey(String versionId, String code) {
        return scopeOf(versionId) + " " + code;
    }

    /**
     * code 只在**同一作用范围**内唯一：不同报表各自的内部数据集允许同名（各报表独立设计，
     * 谁都可能建一个 main），所以校验必须带上 report_id。
     *
     * <p>额外拦一条：**报表级**内部数据集不能与公共数据集重名 —— 两者同名时 {@code #{code.字段}}
     * 到底指哪一个只能靠优先级规则解释，与其让人猜，不如建的时候就说清楚。
     * 反过来（先有内部集、再建同名公共集）不拦，否则一个不相干报表里的内部集
     * 就能占掉全局的编码。
     *
     * <p><b>版本级的那些不受这一条约束</b>：同 code 覆盖上一层正是它存在的理由
     * （「这一版换个接口，模板不动」，见 {@link #getByCode}），拦掉就等于把这个功能拦没了。
     */
    private void validate(DatasetSaveDTO dto, String excludeId) {
        if (!StringUtils.hasText(dto.getName())) {
            throw new BizException("数据集名称不能为空");
        }
        if (!StringUtils.hasText(dto.getCode()) || !dto.getCode().matches(CODE_PATTERN)) {
            throw new BizException("数据集编码必须以字母开头，只能包含字母、数字、下划线");
        }
        String scope = scopeOf(dto.getReportId());
        String version = PUBLIC.equals(scope) ? ALL_VERSIONS : scopeOf(dto.getVersionId());
        LambdaQueryWrapper<MzDataset> wrapper = new LambdaQueryWrapper<MzDataset>()
                .eq(MzDataset::getCode, dto.getCode())
                .eq(MzDataset::getReportId, scope)
                .eq(MzDataset::getVersionId, version);
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne(MzDataset::getId, excludeId);
        }
        if (count(wrapper) > 0) {
            throw new BizException("数据集编码已存在: " + dto.getCode());
        }
        if (!PUBLIC.equals(scope) && ALL_VERSIONS.equals(version)
                && count(new LambdaQueryWrapper<MzDataset>()
                .eq(MzDataset::getCode, dto.getCode())
                .eq(MzDataset::getReportId, PUBLIC)) > 0) {
            throw new BizException("编码已被公共数据集占用: " + dto.getCode());
        }
    }

    /** null / 空白都归一成空串（公共作用范围 / 不限版本），唯一索引里 NULL 是可以重复的。 */
    private String scopeOf(String scope) {
        return StringUtils.hasText(scope) ? scope : PUBLIC;
    }

    private MzDataset toEntity(DatasetSaveDTO dto) {
        MzDataset ds = new MzDataset();
        ds.setId(dto.getId());
        ds.setName(dto.getName());
        ds.setCode(dto.getCode());
        ds.setReportId(scopeOf(dto.getReportId()));
        // 公共集恒为「不限版本」：版本是报表内部的概念，公共集不属于任何报表
        ds.setVersionId(StringUtils.hasText(dto.getReportId()) ? scopeOf(dto.getVersionId()) : ALL_VERSIONS);
        ds.setDatasourceId(dto.getDatasourceId());
        ds.setType(StringUtils.hasText(dto.getType()) ? dto.getType() : "sql");
        // SQL 恒为集合型：分页要数据库端 limit/offset 再加一条 count，不在本项目范围内
        ds.setResultType("sql".equals(ds.getType()) || !isPaged(dto.getResultType()) ? "list" : "page");
        ds.setSqlText(dto.getSqlText());
        ds.setApiUrl(dto.getApiUrl());
        ds.setApiMethod(dto.getApiMethod());
        ds.setApiHeaders(dto.getApiHeaders());
        ds.setJsonText(dto.getJsonText());
        ds.setRemark(dto.getRemark());
        ds.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        return ds;
    }

    /** 子表先删后插，保证与前端提交的内容完全一致。 */
    private void saveChildren(String datasetId, DatasetSaveDTO dto) {
        deleteChildren(datasetId);

        List<MzDatasetField> fields = dto.getFields();
        if (fields != null) {
            for (int i = 0; i < fields.size(); i++) {
                MzDatasetField f = fields.get(i);
                f.setId(null);
                f.setDatasetId(datasetId);
                if (f.getSortNo() == null) {
                    f.setSortNo(i + 1);
                }
                if (!StringUtils.hasText(f.getFieldText())) {
                    f.setFieldText(f.getFieldName());
                }
                if (!StringUtils.hasText(f.getFieldType())) {
                    f.setFieldType("string");
                }
                fieldMapper.insert(f);
            }
        }

        List<MzDatasetParam> params = dto.getParams();
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                MzDatasetParam p = params.get(i);
                p.setId(null);
                p.setDatasetId(datasetId);
                if (p.getSortNo() == null) {
                    p.setSortNo(i + 1);
                }
                if (!StringUtils.hasText(p.getParamType())) {
                    p.setParamType("string");
                }
                if (p.getRequired() == null) {
                    p.setRequired(0);
                }
                paramMapper.insert(p);
            }
        }
    }

    private void deleteChildren(String datasetId) {
        fieldMapper.delete(new LambdaQueryWrapper<MzDatasetField>()
                .eq(MzDatasetField::getDatasetId, datasetId));
        paramMapper.delete(new LambdaQueryWrapper<MzDatasetParam>()
                .eq(MzDatasetParam::getDatasetId, datasetId));
    }

    /* ------------------------------ 解析 / 预览 ------------------------------ */

    @Override
    public DatasetParseResultDTO parse(DatasetParseDTO dto) {
        String type = StringUtils.hasText(dto.getType()) ? dto.getType() : "sql";
        List<MzDatasetParam> params = mergeDiscoveredParams(dto, type);

        List<MzDatasetField> fields;
        if ("sql".equals(type)) {
            if (!StringUtils.hasText(dto.getSqlText())) {
                throw new BizException("请先填写 SQL");
            }
            SqlParamParser.ParsedSql parsed = sqlParamParser.parse(dto.getSqlText(), params, paramDefaults(params));
            String dsCode = resolveDatasourceCode(dto.getDatasourceId());
            List<ColumnMetaDTO> cols = jdbcExecutor.queryColumns(dsCode, parsed.getSql(), parsed.getArgs());
            fields = new ArrayList<>();
            for (int i = 0; i < cols.size(); i++) {
                fields.add(field(cols.get(i).getName(), cols.get(i).getType(), i + 1));
            }
        } else {
            DatasetRowsDTO result = execute(toTempDataset(dto), paramDefaults(params));
            fields = fieldsFromRows(result.getRows());
        }
        return new DatasetParseResultDTO(fields, params);
    }

    /**
     * 从 SQL / 接口地址中发现参数名；已存在的同名参数保留其原有配置。
     *
     * <p>api 类型看的是 URL —— {@code executeApi} 会把里面的 {@code ${name}} 按 URL 编码替换掉
     * （见 {@link #substituteUrl}），既然是参数就该跟 SQL 一样在「解析字段」时自动列出来，
     * 让用户填默认值、试预览。
     */
    private List<MzDatasetParam> mergeDiscoveredParams(DatasetParseDTO dto, String type) {
        List<MzDatasetParam> existing = dto.getParams() == null ? new ArrayList<>() : new ArrayList<>(dto.getParams());
        String source = switch (type) {
            case "sql" -> dto.getSqlText();
            case "api" -> dto.getApiUrl();
            default -> null;
        };
        if (!StringUtils.hasText(source)) {
            return existing;
        }
        Set<String> names = sqlParamParser.extractParamNames(source);
        Map<String, MzDatasetParam> byName = new LinkedHashMap<>();
        for (MzDatasetParam p : existing) {
            if (p.getParamName() != null) {
                byName.put(p.getParamName(), p);
            }
        }
        int sort = byName.size();
        for (String name : names) {
            if (byName.containsKey(name)) {
                continue;
            }
            MzDatasetParam p = new MzDatasetParam();
            p.setParamName(name);
            p.setParamText(name);
            p.setParamType("string");
            p.setDefaultValue("");
            p.setRequired(0);
            p.setSortNo(++sort);
            byName.put(name, p);
        }
        return new ArrayList<>(byName.values());
    }

    /** 用参数定义的默认值构造一份参数值，供解析/预览时试执行。 */
    private Map<String, Object> paramDefaults(List<MzDatasetParam> params) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (params != null) {
            params.forEach(p -> values.put(p.getParamName(), p.getDefaultValue()));
        }
        return values;
    }

    private MzDatasetField field(String name, String type, int sortNo) {
        MzDatasetField f = new MzDatasetField();
        f.setFieldName(name);
        f.setFieldText(name);
        f.setFieldType(type == null ? "string" : type);
        f.setSortNo(sortNo);
        return f;
    }

    /** 从首行数据推断字段。 */
    private List<MzDatasetField> fieldsFromRows(List<Map<String, Object>> rows) {
        List<MzDatasetField> fields = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return fields;
        }
        int i = 0;
        for (Map.Entry<String, Object> en : rows.get(0).entrySet()) {
            fields.add(field(en.getKey(), inferType(en.getValue()), ++i));
        }
        return fields;
    }

    private String inferType(Object v) {
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof LocalDate || v instanceof LocalDateTime || v instanceof Date) {
            return "date";
        }
        return "string";
    }

    /** 把请求体转成一个不入库的临时数据集，复用统一的取数逻辑。 */
    private MzDataset toTempDataset(DatasetParseDTO dto) {
        MzDataset ds = new MzDataset();
        ds.setCode("__preview__");
        ds.setDatasourceId(dto.getDatasourceId());
        ds.setType(StringUtils.hasText(dto.getType()) ? dto.getType() : "sql");
        ds.setResultType(dto.getResultType());
        ds.setSqlText(dto.getSqlText());
        ds.setApiUrl(dto.getApiUrl());
        ds.setApiMethod(dto.getApiMethod());
        ds.setApiHeaders(dto.getApiHeaders());
        ds.setJsonText(dto.getJsonText());
        return ds;
    }

    @Override
    public DatasetPreviewResultDTO preview(DatasetParseDTO dto) {
        List<MzDatasetParam> defs = dto.getParams() == null ? List.of() : dto.getParams();
        Map<String, Object> values = dto.getParamValues() != null && !dto.getParamValues().isEmpty()
                ? dto.getParamValues() : paramDefaults(defs);

        MzDataset temp = toTempDataset(dto);
        String type = temp.getType();
        DatasetRowsDTO fetched;
        if ("sql".equals(type)) {
            fetched = DatasetRowsDTO.of(executeSql(temp, defs, values));
        } else if ("api".equals(type)) {
            fetched = executeApi(temp, defs, values);
        } else {
            fetched = extractRows(temp.getJsonText(), temp.getResultType());
        }
        List<Map<String, Object>> rows = fetched.getRows();

        long pageNo = dto.getPageNo() == null || dto.getPageNo() < 1 ? 1 : dto.getPageNo();
        long pageSize = dto.getPageSize() == null || dto.getPageSize() < 1
                ? props.getPreviewRows() : dto.getPageSize();

        List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        int from = (int) Math.min((long) rows.size(), (pageNo - 1) * pageSize);
        int to = (int) Math.min((long) rows.size(), from + pageSize);
        // 分页型数据集把接口自报的总数带回去，编辑弹窗里显示出来 —— 「取回 20 条 / 共 1280 条」
        // 就是分页配对没配对的最直接证据
        return new DatasetPreviewResultDTO(columns, new ArrayList<>(rows.subList(from, to)),
                fetched.getTotal());
    }
}
