package com.muzhou.report.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.Method;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部报表导入工具：把另一套报表系统上设计好的报表（版式 + 数据集定义）转换成本系统的
 * {@code ReportContent} 并写进本地后端。源端存的是 x-spreadsheet 结构
 * （rows/cells/styles），本系统是 FortuneSheet celldata 结构，两者的差异全在这个类里抹平。
 * <p>
 * 纯 main() 驱动，不依赖 Spring 上下文，可直接在 IDE 里跑。
 * <p>
 * <b>源端的地址、路径前缀与凭据一律来自外部配置文件</b>（{@code source.apiBase} + token，
 * 或从配置里那条源报表地址上拆出来），源码里一个都不写死 —— 换一套源系统只改配置。
 * 相对 apiBase 依次读四样东西：报表头（name/type）、sheet 列表、每个 sheet 的**设计** JSON、
 * 数据集全量定义（reportDb + fieldList + paramList）。**要的是设计模板不是渲染结果** ——
 * 源端的渲染/预览接口还回来的是已经出好数的那张表，扩展行都摊开了，导进来就不是模板了。
 * <p>
 * 写目标走本系统的 REST 接口（CONTRACT §3，无鉴权）：
 * - GET  /api/report/{id}      报表不存在时后端会按 id 自动建空白报表，顺便拿 versionId/code
 * - PUT  /api/report           content 写进默认版本
 * - GET  /api/dataset/list?reportId= + POST/PUT/DELETE /api/dataset   内部数据集按 code upsert
 * <p>
 * 转换规则（x-spreadsheet → FortuneSheet celldata）：
 * - rows/cells 的 text/style/merge → celldata 的 v/m/ct + 样式键(ht/vt/fs/ff/bl/it/fc/bg/tb)
 *   + config.merge/rowlen/columnlen；样式表 styles[i] 按下标展开进每个格子。
 * - 边框：四边俱全的样式转成 borderInfo 的 range/border-all（三条导出路都认）；
 *   只有部分边的转成 rangeType=cell 按边写（设计器/预览认，Excel 导出会忽略——
 *   ExcelExporter 只读 range 形态，这类局部边框会丢，属已知取舍）。
 * - 占位符：{@code #{code.field}} 与本系统语法相同，纵向扩展（expandType=down）；
 *   源端的 {@code ${code.field}}（点号前是数据集 code）是「单值不扩展」，而本系统里 ${} 是
 *   参数语法，所以改写成 {@code #{code.field}} 并配 expandType=none 的显式 cellConfig；
 *   {@code ${param}}（无点号）原样保留，两边同义。
 * - 图片格：源端 display=base64Img/img 转成本系统 type=base64/img 的图片单元格。
 * - 数字格式：源端样式 format=number 转成 formatType=number（#,##0.00）。
 * - printConfig → pageConfig：纸张/横纵向/页边距/水印（角度符号相反：CSS 顺时针为正，
 *   本系统逆时针为正）；页头页尾只在源端 headerFooterShow 时带过来。
 * - 数据集：dbType=1(api) → type=api；dbType=0/5(sql) → type=sql（dbSource 指向源实例上的
 *   数据源，本地多半没有，导入后要在数据集管理里重新选）；isPage → resultType。
 *   api 地址里的 127.0.0.1/localhost 会被替换成源实例的 host（业务接口通常与源系统同机部署，
 *   本地照抄 127.0.0.1 连不上），rewriteApiHost=false 可关。
 * - 数据集只有一个时顺手设为 content.primaryDataset。
 * <p>
 * 重跑幂等：报表 content 整体覆盖默认版本；数据集按 code upsert，源上已不存在的
 * 内部数据集会被删掉 ——「源报表即真相」，目标上手工改过的内容会被覆盖。
 * <p>
 * 已知边界：源端的分组/聚合/横向扩展等高级单元格配置存在它自己的 config 字段里，
 * 不在转换范围内；跨 sheet 引用、图表、二维码同理。转换求「设计器里打开是那张表、
 * 预览能出数」，像素级还原不是目标。
 */
public class SpreadsheetImportTool {

    /** 源端 ${code.field}：点号前是数据集 code 时代表「单值取数」，要改写成 #{} */
    private static final Pattern SINGLE_VALUE_TOKEN =
            Pattern.compile("\\$\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_$]*)\\s*}");

    /** 本系统 #{code.field}（与源端纵向扩展格同形） */
    private static final Pattern DATA_TOKEN =
            Pattern.compile("#\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_$]*)\\s*}");

    /** x-spreadsheet 边框线型 → FortuneSheet 边框 style 码 */
    private static final Map<String, String> BORDER_STYLE = Map.of(
            "thin", "1", "hair", "2", "dotted", "3", "dashed", "4",
            "double", "7", "medium", "8", "thick", "13");

    /** 配置文件路径的三个来源，优先级从高到低。 */
    private static final String CONFIG_PROP = "muzhou.import.config";
    private static final String CONFIG_ENV = "MUZHOU_IMPORT_CONFIG";

    /**
     * 入口：从**外部配置文件**读要导入哪几张报表。
     *
     * <p>路径依次取：命令行第一个参数 → 系统属性 {@code -Dmuzhou.import.config=} →
     * 环境变量 {@code MUZHOU_IMPORT_CONFIG}。三处都没有就打印模板后退出。
     *
     * <p><b>配置一律不进代码</b>：源实例地址、登录 token、报表 id 写死在方法体里的话，
     * 一份能调用源系统全部接口的凭据就跟着这个文件被提交进 git 历史了 —— 而这个文件是要
     * 跟着仓库发出去的。token 会过期、地址每个环境都不一样，本来也不该由源码承担。
     */
    public static void main(String[] args) throws Exception {
        String typePath = "D:\\code\\muzhou-report\\backend\\src\\main\\resources\\muzhou-import\\muzhou-import01.json";
        String path = args.length > 0 && StrUtil.isNotBlank(args[0]) ? args[0]
                : StrUtil.blankToDefault(System.getProperty(CONFIG_PROP), typePath);
        if (StrUtil.isBlank(path)) {
            System.out.println(usage());
            return;
        }
        Path file = Paths.get(path);
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("配置文件读不到: " + file.toAbsolutePath());
        }
        System.out.println("读取导入配置: " + file.toAbsolutePath());
        runBatch(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** 三处都没给配置时打印的用法与模板（**不含任何真实地址与凭据**）。 */
    private static String usage() {
        return """
                用法：给一个配置文件路径，三选一
                  java ... SpreadsheetImportTool <配置文件路径>
                  java -Dmuzhou.import.config=<配置文件路径> ... SpreadsheetImportTool
                  set MUZHOU_IMPORT_CONFIG=<配置文件路径> && java ... SpreadsheetImportTool

                配置文件（JSON）模板 —— 含 token，别提交进仓库（.gitignore 已挡）：
                {
                  "targetApi": "http://localhost:8888",
                  "rewriteApiHost": true,
                  "reports": [
                    {
                      "sourceUrl": "<源系统那张报表的地址>/index/<报表id>?token=<token>",
                      "targetUrl": "http://localhost:5173/#/designer/<报表id>"
                    }
                  ]
                }

                sourceUrl 里 /index/ 之前的那一段就是源端的接口根（apiBase），token 取地址上
                的 ?token=。也可以把 apiBase + token 提到顶层的 source 里，reports 只写
                sourceId/targetId：
                {
                  "targetApi": "http://localhost:8888",
                  "source": { "apiBase": "<源系统接口根>", "token": "<token>" },
                  "reports": [ { "sourceId": "<源报表id>", "targetId": "<本系统报表id>" } ]
                }
                """;
    }

    // ------------------------------------------------------------------
    // 批量入口
    // ------------------------------------------------------------------

    /** 逐项同步，单项失败打印异常后继续，最后汇总；有失败项时抛异常让退出码非零。 */
    public static void runBatch(String configJson) {
        JSONObject root = JSON.parseObject(configJson);
        List<SyncItem> items = parseItems(root);

        List<String> okList = new ArrayList<>();
        List<String> failList = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            SyncItem item = items.get(i);
            String tag = "[" + (i + 1) + "/" + items.size() + "] " + item.sourceId + " -> " + item.targetId;
            System.out.println("================ " + tag + " 开始 ================");
            try {
                syncOne(item);
                okList.add(tag);
                System.out.println("================ " + tag + " 成功 ================");
            } catch (Exception e) {
                failList.add(tag + " : " + e.getMessage());
                System.err.println("================ " + tag + " 失败 ================");
                e.printStackTrace();
            }
        }

        System.out.println();
        System.out.println("======== 同步汇总：成功 " + okList.size() + " / 失败 " + failList.size() + " ========");
        okList.forEach(s -> System.out.println("  成功 " + s));
        failList.forEach(s -> System.out.println("  失败 " + s));
        if (!failList.isEmpty()) {
            throw new IllegalStateException("有 " + failList.size() + " 个报表同步失败，见上方日志");
        }
    }

    // ------------------------------------------------------------------
    // 单个报表同步
    // ------------------------------------------------------------------

    private static void syncOne(SyncItem item) {
        // ---- 1. 读源报表 ----
        JSONObject head = sourceGet(item, "/get/" + item.sourceId).getJSONObject("result");
        if (head == null) {
            throw new IllegalStateException("源报表不存在: " + item.sourceId);
        }
        String reportName = StrUtil.blankToDefault(head.getString("name"), "同步报表");

        JSONArray sheetList = sourceGet(item, "/sheet/getSheetList/" + item.sourceId).getJSONArray("result");
        List<JSONObject> sheets = new ArrayList<>();
        for (int i = 0; i < sheetList.size(); i++) {
            JSONObject meta = sheetList.getJSONObject(i);
            JSONObject json = sourceGet(item, "/sheet/getSheetData?reportId=" + item.sourceId
                    + "&sheetId=" + meta.getString("id")).getJSONObject("result");
            if (json == null) {
                throw new IllegalStateException("源报表 sheet[" + meta.getString("id") + "] 没有内容（报表从未保存过？）");
            }
            JSONObject sheet = new JSONObject();
            sheet.put("meta", meta);
            sheet.put("json", json);
            sheets.add(sheet);
        }

        List<JSONObject> datasets = new ArrayList<>();
        for (String dbId : listDbIds(item)) {
            JSONObject dbData = sourceGet(item, "/loadDbData/" + dbId + "?reportId=" + item.sourceId)
                    .getJSONObject("result");
            if (dbData != null && dbData.getJSONObject("reportDb") != null) {
                datasets.add(dbData);
            }
        }

        Set<String> codes = new LinkedHashSet<>();
        for (JSONObject db : datasets) {
            codes.add(db.getJSONObject("reportDb").getString("dbCode"));
        }
        System.out.println("源报表 [" + reportName + "] 读取完成: " + sheets.size() + " 个sheet, "
                + datasets.size() + " 个数据集 " + codes);

        // ---- 2. 目标报表就位（GET 不存在时后端按 id 自动建），拿 versionId/code ----
        JSONObject local = localCall(Method.GET, item.targetApi + "/api/report/" + item.targetId, null)
                .getJSONObject("data");
        String versionId = local.getString("versionId");
        String localCode = local.getString("code");

        // ---- 3. 数据集按 code upsert 成目标报表的内部数据集 ----
        syncDatasets(item, datasets);

        // ---- 4. 版式转换 + 覆盖默认版本 ----
        JSONObject content = buildContent(sheets, codes);
        JSONObject body = new JSONObject();
        body.put("id", item.targetId);
        body.put("code", localCode);
        body.put("name", reportName);
        body.put("status", 1);
        body.put("versionId", versionId);
        body.put("content", content.toJSONString());
        localCall(Method.PUT, item.targetApi + "/api/report", body);

        System.out.println("目标设计器地址: http://localhost:5173/#/designer/" + item.targetId);
    }

    /** /field/tree/{reportId} 返回按数据集分组的二维数组，从中抽出全部 dbId */
    private static List<String> listDbIds(SyncItem item) {
        List<String> dbIds = new ArrayList<>();
        JSONArray groups = sourceGet(item, "/field/tree/" + item.sourceId).getJSONArray("result");
        if (groups != null) {
            for (int i = 0; i < groups.size(); i++) {
                JSONArray group = groups.getJSONArray(i);
                for (int j = 0; j < group.size(); j++) {
                    String dbId = group.getJSONObject(j).getString("dbId");
                    if (StrUtil.isNotBlank(dbId) && !dbIds.contains(dbId)) {
                        dbIds.add(dbId);
                    }
                }
            }
        }
        return dbIds;
    }

    // ------------------------------------------------------------------
    // 数据集：源端 reportDb → 本系统内部数据集，按 code upsert
    // ------------------------------------------------------------------

    private static void syncDatasets(SyncItem item, List<JSONObject> datasets) {
        // 目标报表现有的内部数据集：code -> id（list 接口同时返回公共数据集，要按 reportId 过滤）
        Map<String, String> existing = new LinkedHashMap<>();
        JSONArray list = localCall(Method.GET,
                item.targetApi + "/api/dataset/list?reportId=" + item.targetId, null).getJSONArray("data");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                JSONObject ds = list.getJSONObject(i).getJSONObject("dataset");
                if (ds != null && item.targetId.equals(ds.getString("reportId"))) {
                    existing.put(ds.getString("code"), ds.getString("id"));
                }
            }
        }

        Set<String> sourceCodes = new LinkedHashSet<>();
        for (JSONObject dbData : datasets) {
            JSONObject payload = convertDataset(item, dbData);
            String code = payload.getString("code");
            sourceCodes.add(code);
            String existId = existing.get(code);
            if (existId != null) {
                payload.put("id", existId);
                localCall(Method.PUT, item.targetApi + "/api/dataset", payload);
            } else {
                localCall(Method.POST, item.targetApi + "/api/dataset", payload);
            }
        }

        // 源上已不存在的内部数据集删掉，保持「源报表即真相」
        existing.forEach((code, id) -> {
            if (!sourceCodes.contains(code)) {
                localCall(Method.DELETE, item.targetApi + "/api/dataset/" + id, null);
            }
        });
    }

    /** 源端 loadDbData 的结果 → 本系统 DatasetSaveDTO。 */
    private static JSONObject convertDataset(SyncItem item, JSONObject dbData) {
        JSONObject db = dbData.getJSONObject("reportDb");
        String dbType = StrUtil.blankToDefault(db.getString("dbType"), "0");

        JSONObject dto = new JSONObject();
        dto.put("name", StrUtil.blankToDefault(db.getString("dbChName"), db.getString("dbCode")));
        dto.put("code", db.getString("dbCode"));
        dto.put("reportId", item.targetId);
        dto.put("status", 1);
        dto.put("remark", "由外部报表导入，源实例 " + item.sourceApiBase);

        if ("1".equals(dbType)) {
            dto.put("type", "api");
            dto.put("apiUrl", rewriteApiUrl(item, db.getString("apiUrl")));
            // 源端 apiMethod：0=GET 1=POST（部分版本直接存 get/post 字符串）
            String method = StrUtil.blankToDefault(db.getString("apiMethod"), "0");
            dto.put("apiMethod", "1".equals(method) || "post".equalsIgnoreCase(method) ? "POST" : "GET");
            dto.put("resultType", "1".equals(db.getString("isPage")) ? "page" : "list");
        } else if ("2".equals(dbType)) {
            dto.put("type", "json");
            dto.put("jsonText", db.getString("jsonData"));
            dto.put("resultType", "list");
        } else {
            // 0/5 = SQL。dbSource 指向源实例上的数据源 id，本地没有对应记录，
            // datasourceId 留空，同步后需在数据集管理里补选数据源
            dto.put("type", "sql");
            dto.put("sqlText", db.getString("dbDynSql"));
            dto.put("resultType", "list");
            System.out.println("  注意: 数据集[" + db.getString("dbCode") + "]是 SQL 类型，"
                    + "数据源没跟过来，需在本地重新选择数据源");
        }

        JSONArray fields = new JSONArray();
        JSONArray rawFields = dbData.getJSONArray("fieldList");
        if (rawFields != null) {
            for (int i = 0; i < rawFields.size(); i++) {
                JSONObject rf = rawFields.getJSONObject(i);
                JSONObject f = new JSONObject();
                f.put("fieldName", rf.getString("fieldName"));
                f.put("fieldText", StrUtil.blankToDefault(rf.getString("fieldText"), rf.getString("fieldName")));
                f.put("fieldType", normalizeType(rf.getString("widgetType")));
                f.put("sortNo", i);
                fields.add(f);
            }
        }
        dto.put("fields", fields);

        JSONArray params = new JSONArray();
        JSONArray rawParams = dbData.getJSONArray("paramList");
        if (rawParams != null) {
            for (int i = 0; i < rawParams.size(); i++) {
                JSONObject rp = rawParams.getJSONObject(i);
                JSONObject p = new JSONObject();
                p.put("paramName", rp.getString("paramName"));
                p.put("paramText", StrUtil.blankToDefault(rp.getString("paramTxt"), rp.getString("paramName")));
                p.put("paramType", normalizeType(rp.getString("widgetType")));
                // 源端存的默认值是 URL 编码过的；本系统渲染时替换接口地址会自己再编码
                p.put("defaultValue", urlDecode(rp.getString("paramValue")));
                p.put("required", 0);
                p.put("sortNo", i);
                params.add(p);
            }
        }
        dto.put("params", params);
        return dto;
    }

    /** api 地址里的 127.0.0.1/localhost 指的是「源系统服务器自己」，替换成源实例的 host。 */
    private static String rewriteApiUrl(SyncItem item, String apiUrl) {
        if (StrUtil.isBlank(apiUrl) || !item.rewriteApiHost) {
            return apiUrl;
        }
        String sourceHost = URI.create(item.sourceApiBase).getHost();
        if (StrUtil.isBlank(sourceHost) || "127.0.0.1".equals(sourceHost) || "localhost".equals(sourceHost)) {
            return apiUrl;
        }
        String rewritten = apiUrl.replaceFirst("//(127\\.0\\.0\\.1|localhost)([:/])", "//" + sourceHost + "$2");
        if (!rewritten.equals(apiUrl)) {
            System.out.println("  接口地址 host 已替换: " + apiUrl + " -> " + rewritten);
        }
        return rewritten;
    }

    private static String normalizeType(String widgetType) {
        String t = widgetType == null ? "" : widgetType.toLowerCase();
        return switch (t) {
            case "number", "int", "double" -> "number";
            case "date", "datetime", "time" -> "date";
            case "boolean" -> "boolean";
            default -> "string";
        };
    }

    private static String urlDecode(String s) {
        if (StrUtil.isBlank(s)) {
            return s;
        }
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------
    // 版式：源端 x-spreadsheet JSON → ReportContent
    // ------------------------------------------------------------------

    private static JSONObject buildContent(List<JSONObject> sheets, Set<String> codes) {
        JSONObject content = new JSONObject();
        content.put("version", 1);

        JSONArray outSheets = new JSONArray();
        JSONObject cellConfigs = new JSONObject();
        for (int i = 0; i < sheets.size(); i++) {
            JSONObject meta = sheets.get(i).getJSONObject("meta");
            JSONObject json = sheets.get(i).getJSONObject("json");
            outSheets.add(convertSheet(i, meta, json, codes, cellConfigs));
        }
        content.put("sheets", outSheets);
        content.put("cellConfigs", cellConfigs);
        content.put("params", new JSONArray());
        content.put("splitMode", "single");
        if (codes.size() == 1) {
            content.put("primaryDataset", codes.iterator().next());
        }
        // 打印设置整份一套，取第一张 sheet 的 printConfig
        content.put("pageConfig", buildPageConfig(sheets.get(0).getJSONObject("json").getJSONObject("printConfig")));
        return content;
    }

    private static JSONObject convertSheet(int sheetIndex, JSONObject meta, JSONObject json,
                                           Set<String> codes, JSONObject cellConfigs) {
        JSONObject rows = json.getJSONObject("rows");
        JSONArray styles = json.getJSONArray("styles");

        // celldata 按 "r_c" 收集，方便合并区补被盖住的格子
        Map<String, JSONObject> cells = new LinkedHashMap<>();
        JSONObject mergeObj = new JSONObject();
        JSONObject rowlen = new JSONObject();
        JSONObject columnlen = new JSONObject();
        JSONArray borderInfo = new JSONArray();
        int maxRow = 0;
        int maxCol = 0;

        if (rows != null) {
            for (String rowKey : rows.keySet()) {
                if (!rowKey.chars().allMatch(Character::isDigit)) {
                    continue; // "len" 等非行号键
                }
                int r = Integer.parseInt(rowKey);
                JSONObject row = rows.getJSONObject(rowKey);
                Double height = row.getDouble("height");
                if (height != null && height > 0) {
                    rowlen.put(String.valueOf(r), (int) Math.round(height));
                }
                JSONObject rowCells = row.getJSONObject("cells");
                if (rowCells == null) {
                    continue;
                }
                for (String colKey : rowCells.keySet()) {
                    int c = Integer.parseInt(colKey);
                    JSONObject cell = rowCells.getJSONObject(colKey);
                    maxRow = Math.max(maxRow, r);
                    maxCol = Math.max(maxCol, c);
                    convertCell(sheetIndex, r, c, cell, styles, codes,
                            cells, mergeObj, borderInfo, cellConfigs);
                }
            }
        }

        JSONObject cols = json.getJSONObject("cols");
        if (cols != null) {
            for (String colKey : cols.keySet()) {
                if (!colKey.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                JSONObject col = cols.getJSONObject(colKey);
                Double width = col == null ? null : col.getDouble("width");
                if (width != null && width > 0) {
                    columnlen.put(colKey, (int) Math.round(width));
                    maxCol = Math.max(maxCol, Integer.parseInt(colKey));
                }
            }
        }

        // 合并区里被盖住的格子补 mc 指回首格（FortuneSheet 画合并要两份数据对得上）
        for (String key : mergeObj.keySet()) {
            JSONObject m = mergeObj.getJSONObject(key);
            int mr = m.getIntValue("r");
            int mc = m.getIntValue("c");
            for (int r = mr; r < mr + m.getIntValue("rs"); r++) {
                for (int c = mc; c < mc + m.getIntValue("cs"); c++) {
                    if (r == mr && c == mc) {
                        continue;
                    }
                    JSONObject covered = cells.computeIfAbsent(r + "_" + c, k -> {
                        JSONObject cd = new JSONObject();
                        cd.put("r", Integer.parseInt(k.split("_")[0]));
                        cd.put("c", Integer.parseInt(k.split("_")[1]));
                        cd.put("v", new JSONObject());
                        return cd;
                    });
                    JSONObject v = covered.getJSONObject("v");
                    JSONObject marker = new JSONObject();
                    marker.put("r", mr);
                    marker.put("c", mc);
                    v.put("mc", marker);
                    // 被盖住的格子只留 mc，文字/占位符一并清掉，免得渲染引擎重复取数
                    v.remove("v");
                    v.remove("m");
                    v.remove("ct");
                    maxRow = Math.max(maxRow, r);
                    maxCol = Math.max(maxCol, c);
                }
            }
        }

        JSONObject config = new JSONObject();
        config.put("merge", mergeObj);
        config.put("rowlen", rowlen);
        config.put("columnlen", columnlen);
        config.put("borderInfo", borderInfo);

        Integer rowsLen = rows == null ? null : rows.getInteger("len");
        JSONObject sheet = new JSONObject();
        sheet.put("name", StrUtil.blankToDefault(meta.getString("name"), "Sheet" + (sheetIndex + 1)));
        sheet.put("id", "sheet_" + (sheetIndex + 1));
        sheet.put("order", sheetIndex);
        sheet.put("status", sheetIndex == 0 ? 1 : 0);
        sheet.put("row", Math.max(rowsLen == null ? 0 : rowsLen, Math.max(maxRow + 10, 60)));
        sheet.put("column", Math.max(maxCol + 3, 20));
        sheet.put("celldata", new JSONArray(cells.values()));
        sheet.put("config", config);
        return sheet;
    }

    /** 单个源端格子 → celldata 条目（+ merge/border/cellConfig 副产物）。 */
    private static void convertCell(int sheetIndex, int r, int c, JSONObject cell, JSONArray styles,
                                    Set<String> codes, Map<String, JSONObject> cells, JSONObject mergeObj,
                                    JSONArray borderInfo, JSONObject cellConfigs) {
        String rawText = cell.getString("text");
        Integer styleIdx = cell.getInteger("style");
        JSONObject style = styleIdx != null && styles != null && styleIdx < styles.size()
                ? styles.getJSONObject(styleIdx) : null;

        int rs = 1;
        int cs = 1;
        JSONArray mergeAttr = cell.getJSONArray("merge");
        if (mergeAttr != null && mergeAttr.size() >= 2) {
            rs = mergeAttr.getIntValue(0) + 1;
            cs = mergeAttr.getIntValue(1) + 1;
        }

        boolean hasContent = StrUtil.isNotEmpty(rawText) || style != null || rs > 1 || cs > 1;
        if (!hasContent) {
            return; // 纯占位的空对象（多半是合并区被盖住的格子），等补 mc
        }

        // ${code.field} 单值 → #{code.field}；原本就写 #{} 的是源端的纵向扩展格
        boolean expandDown = rawText != null && DATA_TOKEN.matcher(rawText).find();
        String text = rawText == null ? "" : rewriteSingleTokens(rawText, codes);

        JSONObject v = new JSONObject();
        v.put("v", text);
        v.put("m", text);
        JSONObject ct = new JSONObject();
        boolean placeholder = text.contains("#{") || text.contains("${") || text.contains("!{");
        ct.put("fa", placeholder ? "@" : "General");
        ct.put("t", placeholder ? "s" : "g");
        v.put("ct", ct);
        applyStyle(v, style);

        if (rs > 1 || cs > 1) {
            JSONObject m = new JSONObject();
            m.put("r", r);
            m.put("c", c);
            m.put("rs", rs);
            m.put("cs", cs);
            mergeObj.put(r + "_" + c, m);
            v.put("mc", m.clone());
        }

        appendBorder(borderInfo, style, r, c, rs, cs);

        JSONObject cd = new JSONObject();
        cd.put("r", r);
        cd.put("c", c);
        cd.put("v", v);
        cells.put(r + "_" + c, cd);

        // 数据绑定 → 显式 cellConfig（单值格必须显式，否则 TemplateParser 按文本推断成纵向扩展）
        Matcher dm = DATA_TOKEN.matcher(text);
        if (dm.find()) {
            String display = StrUtil.blankToDefault(cell.getString("display"), "");
            JSONObject cfg = new JSONObject();
            cfg.put("sheetIndex", sheetIndex);
            cfg.put("r", r);
            cfg.put("c", c);
            if (display.toLowerCase().contains("base64")) {
                cfg.put("type", "base64");
            } else if (display.toLowerCase().contains("img") || display.toLowerCase().contains("image")) {
                cfg.put("type", "img");
            } else {
                cfg.put("type", "data");
            }
            cfg.put("datasetCode", dm.group(1));
            cfg.put("field", dm.group(2));
            cfg.put("expandType", expandDown ? "down" : "none");
            cfg.put("groupType", "list");
            cfg.put("aggregate", "none");
            boolean numeric = "number".equals(display)
                    || (style != null && "number".equals(style.getString("format")));
            if (numeric && "data".equals(cfg.getString("type"))) {
                cfg.put("formatType", "number");
                cfg.put("formatPattern", "#,##0.00");
            } else {
                cfg.put("formatType", "text");
            }
            cellConfigs.put(sheetIndex + "_" + r + "_" + c, cfg);
        }
    }

    /** 源端 ${code.field}（code 是数据集）→ #{code.field}；${param} 原样保留。 */
    private static String rewriteSingleTokens(String text, Set<String> codes) {
        Matcher m = SINGLE_VALUE_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = codes.contains(m.group(1))
                    ? "#{" + m.group(1) + "." + m.group(2) + "}"
                    : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** x-spreadsheet 样式对象 → FortuneSheet 单元格样式键。 */
    private static void applyStyle(JSONObject v, JSONObject style) {
        if (style == null) {
            return;
        }
        String align = style.getString("align");
        if (align != null) {
            v.put("ht", switch (align) {
                case "center" -> 0;
                case "right" -> 2;
                default -> 1;
            });
        }
        String valign = style.getString("valign");
        if (valign != null) {
            v.put("vt", switch (valign) {
                case "top" -> 1;
                case "bottom" -> 2;
                default -> 0;
            });
        }
        JSONObject font = style.getJSONObject("font");
        if (font != null) {
            Integer size = font.getInteger("size");
            if (size != null && size > 0) {
                v.put("fs", size);
            }
            if (Boolean.TRUE.equals(font.getBoolean("bold"))) {
                v.put("bl", 1);
            }
            if (Boolean.TRUE.equals(font.getBoolean("italic"))) {
                v.put("it", 1);
            }
            String name = font.getString("name");
            if (StrUtil.isNotBlank(name)) {
                v.put("ff", name);
            }
        }
        String color = style.getString("color");
        if (StrUtil.isNotBlank(color)) {
            v.put("fc", normalizeColor(color));
        }
        String bgcolor = style.getString("bgcolor");
        if (StrUtil.isNotBlank(bgcolor)) {
            v.put("bg", normalizeColor(bgcolor));
        }
        if (Boolean.TRUE.equals(style.getBoolean("textwrap"))) {
            v.put("tb", "2");
        }
        if (Boolean.TRUE.equals(style.getBoolean("strike"))) {
            v.put("cl", 1);
        }
        if (Boolean.TRUE.equals(style.getBoolean("underline"))) {
            v.put("un", 1);
        }
    }

    /**
     * 边框：四边俱全 → range/border-all（Excel/PDF/Word 三条导出路都认）；
     * 只有部分边 → rangeType=cell 按边写（设计器认，Excel 导出忽略，属已知取舍）。
     * 合并格的边框按整个合并区算。
     */
    private static void appendBorder(JSONArray borderInfo, JSONObject style, int r, int c, int rs, int cs) {
        JSONObject border = style == null ? null : style.getJSONObject("border");
        if (border == null || border.isEmpty()) {
            return;
        }
        JSONArray top = border.getJSONArray("top");
        JSONArray bottom = border.getJSONArray("bottom");
        JSONArray left = border.getJSONArray("left");
        JSONArray right = border.getJSONArray("right");

        if (top != null && bottom != null && left != null && right != null) {
            JSONObject entry = new JSONObject();
            entry.put("rangeType", "range");
            entry.put("borderType", "border-all");
            entry.put("style", borderStyleCode(top));
            entry.put("color", borderColor(top));
            JSONObject range = new JSONObject();
            range.put("row", new JSONArray(List.of(r, r + rs - 1)));
            range.put("column", new JSONArray(List.of(c, c + cs - 1)));
            entry.put("range", new JSONArray(List.of(range)));
            borderInfo.add(entry);
            return;
        }

        JSONObject value = new JSONObject();
        value.put("row_index", r);
        value.put("col_index", c);
        putSide(value, "t", top);
        putSide(value, "b", bottom);
        putSide(value, "l", left);
        putSide(value, "r", right);
        JSONObject entry = new JSONObject();
        entry.put("rangeType", "cell");
        entry.put("value", value);
        borderInfo.add(entry);
    }

    private static void putSide(JSONObject value, String key, JSONArray side) {
        if (side == null) {
            return;
        }
        JSONObject s = new JSONObject();
        s.put("style", Integer.parseInt(borderStyleCode(side)));
        s.put("color", borderColor(side));
        value.put(key, s);
    }

    private static String borderStyleCode(JSONArray side) {
        String name = side.size() > 0 ? StrUtil.blankToDefault(side.getString(0), "thin") : "thin";
        return BORDER_STYLE.getOrDefault(name.toLowerCase(), "1");
    }

    private static String borderColor(JSONArray side) {
        return normalizeColor(side.size() > 1 ? side.getString(1) : "#000000");
    }

    /** #000 → #000000（FortuneSheet/POI 那头按 6 位解析）。 */
    private static String normalizeColor(String color) {
        if (color == null) {
            return "#000000";
        }
        String s = color.trim();
        if (s.matches("#[0-9a-fA-F]{3}")) {
            return "#" + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2) + s.charAt(3) + s.charAt(3);
        }
        return s;
    }

    /** 源端 printConfig → 本系统 pageConfig。 */
    private static JSONObject buildPageConfig(JSONObject pc) {
        JSONObject out = new JSONObject();
        String paper = pc == null ? "A4" : StrUtil.blankToDefault(pc.getString("paper"), "A4");
        out.put("paperSize", paper.toUpperCase());
        String layout = pc == null ? "" : StrUtil.blankToDefault(pc.getString("layout"), "");
        out.put("orientation", "landscape".equalsIgnoreCase(layout) ? "landscape" : "portrait");
        int marginX = pc == null ? 10 : intOr(pc.getInteger("marginX"), 10);
        int marginY = pc == null ? 10 : intOr(pc.getInteger("marginY"), 10);
        out.put("marginTop", marginY);
        out.put("marginBottom", marginY);
        out.put("marginLeft", marginX);
        out.put("marginRight", marginX);
        out.put("scale", 100);
        out.put("fitToWidth", false);
        out.put("limitWidth", true);
        out.put("printArea", "");

        if (pc != null && Boolean.TRUE.equals(pc.getBoolean("headerFooterShow"))) {
            String headerText = StrUtil.blankToDefault(pc.getString("headerText"), "");
            String footerText = StrUtil.blankToDefault(pc.getString("footerText"), "");
            out.put("header", headerFooter(headerText, pc.getString("headerLocation")));
            out.put("footer", headerFooter(footerText, pc.getString("footerLocation")));
        }
        if (pc != null && Boolean.TRUE.equals(pc.getBoolean("watermarkShow"))
                && StrUtil.isNotBlank(pc.getString("watermarkText"))) {
            JSONObject wm = new JSONObject();
            wm.put("text", pc.getString("watermarkText"));
            wm.put("fontSize", 60);
            wm.put("color", StrUtil.blankToDefault(pc.getString("watermarkColor"), "#C0C0C0"));
            wm.put("opacity", 30);
            // 源端 rotationAngle 是 CSS 角度（顺时针为正），本系统逆时针为正
            wm.put("rotation", -intOr(pc.getInteger("rotationAngle"), -45));
            out.put("watermark", wm);
        }
        return out;
    }

    private static JSONObject headerFooter(String text, String location) {
        JSONObject hf = new JSONObject();
        hf.put("left", "");
        hf.put("center", "");
        hf.put("right", "");
        String slot = switch (StrUtil.blankToDefault(location, "left")) {
            case "center", "middle" -> "center";
            case "right" -> "right";
            default -> "left";
        };
        hf.put(slot, text);
        hf.put("fontSize", 9);
        hf.put("margin", 5);
        return hf;
    }

    private static int intOr(Integer v, int def) {
        return v == null ? def : v;
    }

    // ------------------------------------------------------------------
    // 配置解析
    // ------------------------------------------------------------------

    private static class SyncItem {
        String sourceApiBase;
        String sourceToken;
        String sourceId;
        String targetApi;
        String targetId;
        boolean rewriteApiHost = true;
    }

    private static List<SyncItem> parseItems(JSONObject root) {
        JSONObject source = root.getJSONObject("source");
        String targetApi = StrUtil.blankToDefault(root.getString("targetApi"), "http://localhost:8888");
        boolean rewriteApiHost = !Boolean.FALSE.equals(root.getBoolean("rewriteApiHost"));
        JSONArray reports = root.getJSONArray("reports");
        if (reports == null || reports.isEmpty()) {
            throw new IllegalStateException("配置缺少 reports 数组");
        }

        List<SyncItem> items = new ArrayList<>();
        for (int i = 0; i < reports.size(); i++) {
            JSONObject r = reports.getJSONObject(i);
            SyncItem item = new SyncItem();
            item.targetApi = StrUtil.blankToDefault(r.getString("targetApi"), targetApi);
            item.rewriteApiHost = rewriteApiHost;

            if (StrUtil.isNotBlank(r.getString("sourceUrl"))) {
                String[] parts = parseIndexUrl(r.getString("sourceUrl"));
                item.sourceApiBase = parts[0];
                item.sourceId = parts[1];
                item.sourceToken = parts[2];
            } else {
                item.sourceId = r.getString("sourceId");
                item.sourceApiBase = source == null ? null
                        : StrUtil.blankToDefault(source.getString("apiBase"), source.getString("baseUrl"));
                item.sourceToken = source != null ? source.getString("token") : null;
            }

            if (StrUtil.isNotBlank(r.getString("targetUrl"))) {
                item.targetId = parseDesignerId(r.getString("targetUrl"));
            } else {
                item.targetId = r.getString("targetId");
            }

            if (StrUtil.hasBlank(item.sourceApiBase, item.sourceToken, item.sourceId, item.targetId)) {
                throw new IllegalStateException("reports[" + i + "] 配置不完整：要么给 sourceUrl（源报表地址）"
                        + "+ targetUrl（本系统设计器地址），要么给 sourceId/targetId 并在顶层 source 里配 apiBase+token");
            }
            items.add(item);
        }
        return items;
    }

    /**
     * 拆源报表地址 {@code <apiBase>/index/{id}?token=xxx} → [apiBase, reportId, token]。
     * <p>{@code /index/} 之前的那一整段（含主机与路径前缀）就是接口根 —— 这样源端的路径前缀
     * 是配置给的、不是源码写死的，换一套源系统不用改代码。
     */
    private static String[] parseIndexUrl(String url) {
        String marker = "/index/";
        int idx = url.indexOf(marker);
        if (idx < 0) {
            throw new IllegalStateException("不是合法的源报表地址（缺少 /index/）: " + url);
        }
        String baseUrl = url.substring(0, idx);
        String rest = url.substring(idx + marker.length());
        int q = rest.indexOf('?');
        String reportId = q >= 0 ? rest.substring(0, q) : rest;
        String token = null;
        if (q >= 0) {
            for (String kv : rest.substring(q + 1).split("&")) {
                if (kv.startsWith("token=")) {
                    token = kv.substring("token=".length());
                    break;
                }
            }
        }
        if (StrUtil.hasBlank(baseUrl, reportId, token)) {
            throw new IllegalStateException("源报表地址缺少 apiBase/报表id/token: " + url);
        }
        return new String[]{baseUrl, reportId, token};
    }

    /** 拆本系统设计器地址 http://host:port/#/designer/{id}[?query] → 报表 id */
    private static String parseDesignerId(String url) {
        String marker = "/designer/";
        int idx = url.indexOf(marker);
        if (idx < 0) {
            throw new IllegalStateException("不是合法的设计器地址（缺少 /designer/）: " + url);
        }
        String rest = url.substring(idx + marker.length());
        for (char sep : new char[]{'?', '&', '#', '/'}) {
            int p = rest.indexOf(sep);
            if (p >= 0) {
                rest = rest.substring(0, p);
            }
        }
        if (StrUtil.isBlank(rest)) {
            throw new IllegalStateException("设计器地址缺少报表 id: " + url);
        }
        return rest;
    }

    // ------------------------------------------------------------------
    // HTTP 调用
    // ------------------------------------------------------------------

    /** 源端接口：token 挂 URL，响应认 success 字段。 */
    private static JSONObject sourceGet(SyncItem item, String path) {
        String sep = path.contains("?") ? "&" : "?";
        String url = item.sourceApiBase + path + sep + "token=" + item.sourceToken;
        System.out.println("==> GET " + url);
        try (HttpResponse resp = HttpRequest.get(url).timeout(15000).execute()) {
            String body = resp.body();
            JSONObject json = StrUtil.isBlank(body) ? null : JSON.parseObject(body);
            if (!resp.isOk() || json == null || !Boolean.TRUE.equals(json.getBoolean("success"))) {
                throw new RuntimeException("源端接口调用失败: " + path + " -> " + StrUtil.brief(body, 500));
            }
            return json;
        }
    }

    /** 本系统接口：响应是 Result{code,msg,data}，code=0 才算成功。 */
    private static JSONObject localCall(Method method, String url, JSONObject body) {
        System.out.println("==> " + method + " " + url);
        HttpRequest req = HttpRequest.of(url).method(method).timeout(15000);
        if (body != null) {
            req.header("Content-Type", "application/json;charset=UTF-8").body(body.toJSONString());
        }
        try (HttpResponse resp = req.execute()) {
            String respBody = resp.body();
            JSONObject json = StrUtil.isBlank(respBody) ? null : JSON.parseObject(respBody);
            if (!resp.isOk() || json == null || json.getIntValue("code", -1) != 0) {
                throw new RuntimeException("本系统接口调用失败: " + url + " -> " + StrUtil.brief(respBody, 500));
            }
            System.out.println("<== " + resp.getStatus() + " code=0");
            return json;
        }
    }
}
