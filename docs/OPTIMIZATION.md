# 木舟报表 优化清单

> 一次全项目走查的结论。**前提**：本项目**刻意不做鉴权**，为的是能直接嵌进宿主系统
> （外部系统拿自己的业务 KEY 打开设计器 / 预览页，见 `ReportServiceImpl#autoCreate`）。
> 所以下面不提"加登录"，而是把与鉴权相关的部分重排成 §3「部署边界约定」——
> 那些能力必须由**宿主系统或网关**兜住，代码这边只做力所能及的收敛。
>
> 契约相关的改动一律先读 `docs/CONTRACT.md`；本清单里没有任何一条需要改契约。

---

## 0. 进度

清单里的条目**已全部实施**（2026-08-07），每批改完跑一次全量测试，213 个测试保持全绿。
下面各节保留原始的问题描述与理由，标题上标了状态；`✅` = 已改，改法与当初的建议不同的地方写在条目里。

**还剩一件事需要你拍板**，代码这边动不了：

1. **`docs/CONTRACT.md §8` 里的端口仍写着 8080**（实际是 8888，见 §4.1）。契约文件头部写着
   「不得修改本文件」，所以我没动它 —— 这处是文档落后于实现的事实错误，不是实现在偏离契约
   （`application.yml` / `vite.config.js` / `SpreadsheetImportTool` 三处一致都是 8888，
   而且这台机器的 8080 上跑着别的服务，改代码去迁就文档是错的）。要么你改契约，要么明确
   授权我改。`CLAUDE.md` 那两处已经改过来了。

> 原清单里「git 历史里的凭据要不要清」那条已经作废：本仓库是重新 `git init` 建的，
> 没有承接任何历史提交，源码里也不再有任何写死的地址与凭据（见 §3 那一节）。

---

## 1. 缺陷（会出错的，优先修）

### 1.1 api 数据集走 POST + date 参数必然失败 ⭐ ✅

`service/impl/DatasetServiceImpl.java:82`

```java
private static final ObjectMapper MAPPER = new ObjectMapper();   // 裸的，没有 JavaTimeModule
```

`:181` 用它序列化 POST 请求体：

```java
reqBody = MAPPER.writeValueAsString(values);
```

而 `values` 来自 `sqlParamParser.resolveValues(defs, params)`，其中 `paramType=date` 的参数
已经被 `SqlParamParser#convertValue`（`datasource/SqlParamParser.java:215`）转成了 `LocalDate`。
裸 ObjectMapper 不注册 jsr310 模块，序列化 `LocalDate` 直接抛
`InvalidDefinitionException: Java 8 date/time type not supported by default`，
被 `:203` 的 `catch (Exception)` 包成"接口请求失败: ..."。

**也就是说「api 型数据集 + POST + 任意日期参数」这条组合现在是走不通的**，而报错信息完全看不出原因。

修：注入 `@Qualifier("mzObjectMapper")` 的那个 ObjectMapper（`config/WebConfig.java:69` 已经把
日期格式配全了，且与 Web 出参同一套规则），或退一步 `new ObjectMapper().findAndRegisterModules()`。
同一个 MAPPER 还承担 `readTree` / `convertValue`（`:239,296,316,409`），换成配置完整的那个
顺带让接口返回的日期字段与系统其它地方一致。

### 1.2 报表编码校验被注释掉 ✅（改法与原计划不同）

`service/impl/ReportServiceImpl.java:237-239`

```java
private void validate(MzReport report) {
    if (!StringUtils.hasText(report.getName())) { ... }
//  if (!StringUtils.hasText(report.getCode()) || !report.getCode().matches(CODE_PATTERN)) {
//      throw new BizException("报表编码必须以字母开头，只能包含字母、数字、下划线");
//  }
}
```

常量 `CODE_PATTERN`（`:33`）还留着，只有校验体被注掉。后果：

- `code` 可以为 null。`checkCodeUnique(null, ...)` 生成的是 `code = null`，SQL 里恒不匹配，
  于是能建出任意多条 code 为 null 的报表，`uk_report_code` 也拦不住（NULL 可重复）。
- `code` 可以带任意字符，而它会出现在 `/designer/{code}` 这类地址里。

要么恢复校验，要么把"允许任意 code"这件事写进注释说明原因（比如宿主系统的业务 KEY 不守这个规则
—— 如果是这样，那 null 的情况仍然要拦）。

### 1.3 逻辑删除与唯一索引不配套 ✅

`resources/db/schema-h2.sql:113,90,27`

```sql
CREATE UNIQUE INDEX uk_report_code        ON public.mz_report   (code);
CREATE UNIQUE INDEX uk_ds_code            ON public.mz_datasource (code);
CREATE UNIQUE INDEX uk_dataset_code_scope ON public.mz_dataset  (code, report_id);
```

三个唯一索引都**不含 `deleted` 列**，而 MyBatis-Plus 的 `count()` 只数未删除的行。
于是"删掉一张报表 → 用同一个 code 再建一张"会通过 `checkCodeUnique`、
在 `save()` 时撞唯一索引，抛 `DuplicateKeyException`，用户看到的是
`GlobalExceptionHandler` 兜出来的"系统异常: ..."。

`ReportServiceImpl#autoCreate`（`:199-209`）已经 catch 住并给了人话提示，
但 `create` / `update` 那条正常路径、以及数据源、数据集三处都没有。

两种修法，选一种统一：① 唯一索引改成 `(code, deleted)`；② 校验时不过滤 `deleted`，
直接告诉用户"该编码曾被使用且记录仍在（已删除），请换一个"。

### 1.4 `max-cells` 守卫在整条行带展开完之后才触发 ✅

`engine/ExpandProcessor.java:77-89`

```java
for (int i = 0; i < n; i++) {          // n = 数据行数，可达 max-rows(20000)
    emitRow(...);
}
applyGroupMerge(...);

if (grid.size() > props.getMaxCells()) {   // 检查在这里
    throw new BizException("渲染结果过大...");
}
```

检查是**按模板行**做的，一条行带无论多长都会先整个 emit 出来。一张 20000 行 × 50 列的
行带 = 100 万个 `GridCell` 已经在堆上建好了，才轮到"超过 500000 个单元格"这句报错。
守卫拦住了 CPU，没拦住那一次内存峰值。

改法很轻：进入行带前先 `grid.size() + n * cells.size()` 预判一次，超了当场抛。

### 1.5 api / json 数据集完全不受 `max-rows` 约束 ✅

`muzhou.report.max-rows: 20000`（`application.yml:68`）只在 sql 分支生效：

```java
// DatasetServiceImpl.java:139
return jdbcExecutor.query(dsCode, parsed.getSql(), parsed.getArgs(), props.getMaxRows());
```

`executeApi`（`:158`）用 `HttpResponse.BodyHandlers.ofString` **把整份响应读进内存**，
既没有响应体大小上限，也没有行数上限；`json` 型同理（整份 `jsonText` 解析）。
一个还几百 MB 的接口就是一次 OOM，而 `max-cells` 是**渲染阶段**才拦的，那时内存已经吃掉了。

对比 `export/ImageLoader.java:151` 那边就做对了 —— `readNBytes(maxBytes + 1)`，多读一个字节
判断是否超限，不把整个流读进来。api 取数应该照抄这个写法，并补一条行数上限
（`toRows` 里数着截断 + 记 warn，或者直接报错让人去加筛选条件）。

---

## 2. 资源与性能

### 2.1 每次 api 取数都 new 一个 HttpClient ⭐ ✅

`service/impl/DatasetServiceImpl.java:168`

```java
HttpClient client = HttpClient.newBuilder()      // 每次调用都建一个
        .connectTimeout(...)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
```

JDK 的 HttpClient 每个实例自带一条 SelectorManager 线程和独立连接池，这里建完从不 close
（JDK 21 起它是 AutoCloseable）。放大倍数很吓人：`perRowPage` 200 条单据 × 父子关联的
子表每行一次 = 几百个实例、几百条线程，全靠 GC 回收。

同一个项目里 `export/ImageLoader.java:51-62` 就是正确写法，注释还专门写了"每张图新建一个会把
连接数打满"。这里照搬即可：一个字段持有、构造时建好、复用。

### 2.2 表达式编译缓存无界 ✅

`engine/FormulaEvaluator.java:31`

```java
private final Map<String, Expression> cache = new ConcurrentHashMap<>();
```

key 是表达式原文，永不淘汰。表达式来自用户 content，每保存一版新版式就可能多出若干条，
长期运行是缓慢泄漏。加个容量上限（Caffeine，或简单的 size 判断 + clear）即可，
缓存本来就只是省编译时间，丢了不影响正确性。

### 2.3 取数的元数据查询按行放大（N+1） ✅

一次 `fetchRowsByCode`（`DatasetServiceImpl.java:100`）固定打两条 master 库查询：

- `getByCode(reportId, code)` —— 内部集找不到还要再查一次公共集，最多 2 条；
- `execute` 里的 `listParams(ds.getId())`（`:122`）。

而 `CachingDataFetcher` **只记主接口那一个 code**（这是有意的，见其类注释）。
于是 `perRow` 200 条数据 × 3 个数据集 ≈ 1000+ 条查询，全都只为拿同一份不会变的元数据。

修法：在 `RenderServiceImpl#dataFetcher` 那一层加一个"单次渲染内的数据集定义缓存"
（key = `reportId + code`，值 = `MzDataset` + `params`）。它与 `CachingDataFetcher` 缓存
**行数据**是两回事，不冲突 —— 那个不敢全记是因为行数据占内存，元数据只有几个字段。

顺带：`json` 分支根本用不到 `defs`（`:126` 只传了 jsonText 和 resultType），那条 `listParams`
是纯浪费。

### 2.4 数据集预览的分页是假的 ⚠️ 只做到「兜住」，没有消除

`service/impl/DatasetServiceImpl.java:838-867`：先把**全部**行取回来（`:846-853`），
再在内存里 `rows.subList(from, to)`（`:861-865`）。

sql 型有 `max-rows` 兜着还能接受，api 型正好撞上 §1.5 —— 用户在编辑弹窗里点一下"预览"，
后端就把整份接口响应拉进内存。分页型数据集本来就该靠 `${pageNo}/${pageSize}` 让对端分页，
这里至少不该在集合型上假装分页。

**实际改动**：§1.5 那两条闸（`api-max-bytes` + `max-rows` 截断）已经把这里的内存上界
钉死了 —— 最坏情况从「整份响应」变成「32MB 响应 / 20000 行」。

**但"取全量再切片"这个形态没有变，也不打算变**：`resultType=list` 的语义就是「整份数据一次
取回」（CONTRACT §3.2），对端根本不接受 `pageNo/pageSize`，服务端无从少取。真要按页取数，
正确做法是把数据集**标成分页型**、在接口地址里接住那两个保留参数 —— 那是配置问题，
不是这段代码能替用户决定的。所以这里保持现状：编辑弹窗上的分页只是"翻看已取回的这批"，
不承诺少取数据。

### 2.5 导出全程在内存里，且没有并发闸 ✅

`RenderController` 三个导出接口都返回 `byte[]`。PDF 那条路的峰值是
「xlsx 字节 + POI 对象树 + PDF 字节」三份同时在堆上（`RenderServiceImpl#renderToXlsx`
→ `PdfExporter#convert`）。`max-cells: 500000` 拦的是**单次**规模，不拦并发 ——
十个人同时导出一份大报表就能把堆打满。

不必改成流式（改动面太大且 POI 那头也不好流），加一个信号量限制并发导出数就够了，
超了直接告诉用户"导出繁忙，请稍后重试"，比 OOM 之后整个服务不可用强。

### 2.6 Aviator 的 StackOverflowError 接不住 ✅

`engine/FormulaEvaluator.java:79` 是 `catch (Exception e)`。

特性集（`:37-43`）已经关掉了 `NewInstance` / `StaticMethods`，也没开 `While` / `ForLoop`，
这块的加固是做对的。残余的一个口子是 `Lambda` + `Let` 能写出无限递归，抛的是
`StackOverflowError`（Error 不是 Exception），会穿过这个 catch 一路上抛。
影响仅限那一次请求，改成 `catch (Throwable)` 即可 —— 反正返回值本来就是 `"#ERR: 原因"`。

### 2.7 `mz_dataset` 缺 report_id 单列索引 ✅

`listForReport`（`:474`）按 `status` + `report_id in (...)` 查，而
`uk_dataset_code_scope(code, report_id)` 的前导列是 `code`，用不上。
数据量小的时候无所谓，补一条 `idx_dataset_report(report_id)` 是顺手的事
（`mz_dataset_field` / `mz_dataset_param` / `mz_report_version` 都已经有对应的了）。

---

## 3. 部署边界约定（无鉴权的前提下，宿主系统必须兜住的）

**这一节不是"要加鉴权"**，而是把"不加鉴权"这个决定的后果写清楚，
免得将来有人把这套服务直接挂到公网上。

`/api/**` 无认证意味着**能访问到 8888 端口的任何人，等价于本系统的管理员**。
具体能拿到什么：

| 接口 | 实际能力 |
|---|---|
| `POST /api/datasource/test`（`DatasourceController.java:72`） | 任意 JDBC URL + 任意 driverClassName 交给 `DriverManager.getConnection`（`DynamicDatasourceRegistry.java:109-121`）。h2 驱动是 compile 作用域、mysql/pg 是 runtime，**等价于服务器上的代码执行与任意文件读能力**（H2 的 `INIT=RUNSCRIPT`、MySQL 的 `allowLoadLocalInfile`）。`create`/`update` 落库后 `getOrRegister` 惰性注册时会再执行一次。 |
| `POST /api/dataset/parse` / `/preview`（`DatasetController.java:76,82`） | 整条 `sqlText` 由请求方给，在任意已配数据源上执行。`SqlParamParser` 的 `${}` 绑定与 `$!{}` 白名单防的是"报表参数注入 SQL"，对"整条 SQL 都是外部给的"这个场景不起任何作用。 |
| 同上（api 型） | 任意 URL + 任意请求头 + 跟随重定向，**响应体原样回显**（`DatasetServiceImpl.java:158-207`）。在云上就是元数据服务，在内网就是端口扫描 + 内容读取。 |
| `GET /api/datasource/page` `/list` `/{id}` | `MzDatasource#password`（`entity/MzDatasource.java:39`）没有 `@JsonIgnore`，所有业务库口令明文出参。 |

**结论**：这套服务只能部署在**不可从用户浏览器直达**的位置，由宿主系统在自己的鉴权之后
反向代理 `/api/**`；或者至少在网关上把 `/api/datasource/**` 与 `/api/dataset/parse|preview`
这几条"设计期接口"挡在管理员之外，只放行 `/api/render/**`（渲染 / 导出）。
建议把这段写进 README 的部署章节 —— 现在文档里一个字都没提，接手的人不会知道。

### 代码这边仍然值得做的三件小事 ✅ 已做

即使不做鉴权，这三条也应该改，成本很低：

**a) 密码不要出参、不要进日志。**
`@JsonIgnore` 挂到 `MzDatasource#password` 上（前端编辑弹窗改成"留空表示不修改"）；
`RequestLogFilter` 的 `exclude` 加上 `/api/datasource/**`。现在
`muzhou.report.log.body: true` 默认开、`include: /api/**`（`application.yml:78-90`），
`PUT /api/datasource` 的请求体连同明文口令一起以 INFO 级别写进日志文件
（`config/RequestLogFilter.java:58-63`）。

**b) CORS 别再反射任意 Origin。**
`config/WebConfig.java:29-36`：

```java
.allowedOriginPatterns("*")
.allowCredentials(true)
```

`allowCredentials(true)` 在没有会话的系统里**一点用都没有**，却让 Spring 把请求的 Origin
原样回写。要么改成宿主系统域名的白名单，要么至少 `allowCredentials(false)`。

**c) `GET /api/report/{id}` 不该有写副作用。**
`ReportServiceImpl.java:73-81,181`：报表不存在就当场建一张。这个设计本身是合理的
（外部系统拿业务 KEY 直接开设计器，不必先 POST 一次），但挂在 GET 上意味着任何爬虫
扫一遍地址就往 `mz_report` + `mz_report_version` 里灌任意多行，无上限、无清理。
建议加一个显式开关（query 参数或 `muzhou.report.auto-create: true`），默认只在设计器那条路上开。

**实际改动**（三条都做了，其中 a 与建议的写法不同）：

- **a**：用的是 `@JsonProperty(access = WRITE_ONLY)` 而**不是** `@JsonIgnore` ——
  后者会把**两个**方向都挡掉，新建/修改数据源时前端就再也提交不上来了。
  出参没有这一项之后前端拿不到原值，所以配套加了 `DatasourceServiceImpl#keepOldPassword`
  （提交空口令 = 沿用库里那个），否则用户改一下备注就把口令清空了，还要等下次取数才发现；
  「测试连接」也走同一条兜底。日志那侧新加了 `muzhou.report.log.mask`（默认 `/api/datasource/**`）——
  原来的 `exclude` 只管**响应体**，而口令是从**请求体**进去的，加 `exclude` 治不了。
- **b**：`allowCredentials(false)`，来源仍放开（宿主域名不固定，白名单反而会挡住嵌入方）。
- **c**：加了 `muzhou.report.auto-create`（默认 `true`，保持现有行为）**外加**一个调用点开关
  —— `ReportService#getDetail(id, versionId, autoCreate)`。只有 `GET /api/report/{id}`
  传 `true`；渲染 / 导出 / 查参数一律传 `false`，渲染一张不存在的报表现在直接报
  「报表不存在」，而不是凭空建一张再出一份空模板。

### 仓库里不该出现的两类东西（与鉴权无关） ✅

- **写死在源码里的实例地址与凭据**。`util/SpreadsheetImportTool` 曾把源实例地址与登录
  token 直接写在方法体里 —— 而这个文件是要跟着仓库发出去的。现在源端的接口根、路径前缀
  与 token 一律从外部配置文件读（`muzhou.import.config` / `MUZHOU_IMPORT_CONFIG`），
  源码里一个都不留，配置文件本身由 `.gitignore` 的 `muzhou-import*.json` 挡着。
- **H2 文件库**。库里含 `mz_datasource` 全表 = 所有业务库的明文口令。`.gitignore` 现在
  同时挡 `backend/data/` 与 `backend/data*/`（早先只挡了前者，手工备份出来的 `data1/` 漏网）。

本仓库重新 `git init`，没有承接任何历史提交，上面两类东西都不在历史里。

---

## 4. 一致性与整洁

### 4.1 端口号：文档说 8080，实际是 8888 ⚠️ 契约那份待拍板

- `application.yml:2` → `server.port: 8888`
- `frontend/vite.config.js` 代理目标 → `http://localhost:8888`
- 而 `CLAUDE.md`「常用命令」写的是"起服务，8080"，`docs/CONTRACT.md §8` 写的是
  "vite dev proxy → `http://localhost:8080`"。

契约是 single source of truth，这处是文档落后于实现，改文档。
（`CONTRACT.md §0` 的目录树里那条绝对路径已经改成仓库名 `muzhou-report/` —— 换台机器就失效的
绝对路径本来也不该写进契约。）

### 4.2 `.gitignore` 缺项 ✅

当前未跟踪列表里晃着的：`.jqwik-database`（根目录 / backend / frontend /
`backend/src/test/.../engine/` 四处）、`render1.json`。加进 `.gitignore`。

另外 `pom.xml` 里**没有 jqwik 依赖**，但测试目录下有它的数据库文件 ——
说明有人本地跑过 property-based 测试。要么把依赖补进 pom（那批测试才跑得起来），
要么把残留清掉，现在这个状态会让接手的人困惑。

### 4.3 异常原文直接回前端 ✅

`common/GlobalExceptionHandler.java:37`

```java
String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
return Result.fail("系统异常: " + msg);
```

SQL 语法错误会带出表结构，接口取数失败会带出内网地址与端口状态。在设计器里这是**有用的**
（用户就是要看 SQL 哪里写错了），在预览 / 导出那条路上就不该露。
建议按接口分档：`/api/render/report/**` 只回一句话 + 一个 traceId，设计期接口保持现状。

### 4.4 三个导出器体量偏大 —— ⚠️ 原判断有误，只做了收尾

**动手时发现这一条的前提是错的**：`margin + fontSize × 1.35` 那个换算在后端**早就集中了** ——
常量是 `PageConfigDTO.LINE_HEIGHT`，`PdfExporter.HEADER_LINE_HEIGHT`（`:102`）与
`WordExporter#reserve`（`:345`）都是引用它，不是各写各的。CLAUDE.md 里「四处必须一致」
说的是**含前端那份**，而前端跨不过去，所以后端这边没有可抽的东西。

真正剩下的重复只有两处零散常量，已经就地收掉，没有做大改：

- `ExcelExporter.LINE_HEIGHT` 原来是独立字面量 `1.35f` → 改成引用 `PageConfigDTO.LINE_HEIGHT`。
  （注意它服务的是**另一个**公式：自动换行的 `行数 × 字号 × 1.35 + 内边距`，
  与页头预留同值不同事，注释里写明了。）
- 页头字号 9pt / 边距 5mm 这两个缺省值原本在 `PageConfigDTO#reserveMm` 与
  `PdfExporter.DEFAULT_HEADER_FONT_SIZE` 各写一份 → 提成
  `HeaderFooterDTO.DEFAULT_FONT_SIZE` / `DEFAULT_MARGIN`。导出那三条路是**读回 xlsx 之后**
  再判一次「页头占多高」的，而 xlsx 页眉串里不一定写了字号，退不到同一个数就会出现
  「PDF 的分页位置和设计器画布对不上」。

**没有做**当初设想的 `export/PageGeometry` 抽取：三个类合计 3600+ 行、各自带着一套
golden-output 测试，为了消化掉上面这点重复去动它们，收益远小于风险。

原始描述如下（保留作背景）：

`export/PdfExporter.java` 1340 行、`ExcelExporter.java` 1295 行、`WordExporter.java` 1049 行。
行数本身不是病（这三个类的注释密度很高，且 CLAUDE.md 已经把"为什么是三条路"讲清楚了），
但其中**页面几何**那部分是同源逻辑：`margin + fontSize × 1.35` 这个换算，
CLAUDE.md 自己就点名"四处必须一致"（`PageConfigDTO#effectiveMarginTopMm`、
`utils/print.js#effectiveMarginsMm`、`PdfExporter#readHeaderFooter`、`WordExporter#reserve`）。

把纸张尺寸、页边距、可打印区域这几样抽成一个 `export/PageGeometry`，
能把"四处必须一致"变成"一处" —— 这是这份代码里最容易退化的约束。
（前端那份改不掉，仍是两处，但比四处好。）

---

## 5. 做对了、不要动的地方

走查中确认这几处是有意为之且实现正确的，改动前先读注释：

- **`SqlParamParser` 的两种语法**：`${}` 走 JDBC 绑定、`$!{}` 过标识符白名单，
  且**先替换 `$!{}` 再替换 `${}`**（否则正则会互相吃）。`SqlParamParserTest` 锁着。
- **`Aviator` 的特性集收敛**（`FormulaEvaluator.java:37-43`）：关掉了反射式的
  `new` 与静态方法调用，也没开循环。除了 §2.6 那个 Error，这块没有可利用面。
- **`ImageLoader` 按文件头认格式**（`:253`），不信地址后缀也不信 data URI 里的 MIME。
- **`CachingDataFetcher` 只记主接口**（类注释讲了为什么全记反而有害）。
- **`DatasetServiceImpl#toRows` 不做大小写归一**（`:391-403`），SQL 那边由
  `JdbcExecutor#label` 统一小写 —— 两套各自对齐。`DatasetRowsCaseTest` 锁着，
  这是踩过坑的地方，别"顺手统一一下"。
- **分页型数据集认不出结构时直接报错**（`:300-309`），不静默降级成集合型。

---

## 实施记录

六批已全部完成，每批之间跑一次全量测试（213 个，始终全绿）。

| 批次 | 内容 | 落点 |
|---|---|---|
| 1 ✅ | §1.1 ObjectMapper、§2.1 HttpClient | `DatasetServiceImpl` 改手写构造器（要 `@Qualifier`，Lombok 搬不过去，同 `RenderServiceImpl`） |
| 2 ✅ | §3 的 a/b/c + 清仓库凭据 | 口令 `WRITE_ONLY` + 「留空不修改」；CORS `allowCredentials(false)`；`auto-create` 开关 + 渲染路不建；`SpreadsheetImportTool` 改读外部配置文件 |
| 3 ✅ | §1.4 / §1.5 / §2.5 | 行带展开前预判格数；api 响应改流式读 + `api-max-bytes`，行数按 `max-rows` 截断；导出加信号量闸 |
| 4 ✅ | §1.2 / §1.3 | code 校验**放宽**而不是恢复（见 §1.2 里的说明）；唯一索引撞车改由 `GlobalExceptionHandler` 翻译成人话 |
| 5 ✅ | §2.3 / §2.2 / §2.7 | 新增 `DatasetService.ResolvedDataset` + `resolve/fetchRows`，渲染层按 code 记一份；表达式缓存加容量上限；补 `idx_dataset_report` |
| 6 ✅ | §4 文档与常量收尾 | 端口改 `CLAUDE.md`（`CONTRACT.md` 待你拍板，见 §0）；异常兜底改成编号 + 日志；§4.4 见该节说明 |

**两处改法与原计划不同，理由都写在对应小节里**：§1.2（放宽而非恢复 code 校验）、
§4.4（原判断有误，没做大重构）。
