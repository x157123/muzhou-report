# 木舟报表 MuZhou Report — 共享契约 (SINGLE SOURCE OF TRUTH)

> 所有前后端实现必须严格遵守本文件。**不得修改本文件**，不得偏离字段名、路径、JSON 结构。

## 0. 目录结构

```
muzhou-report/
├── docs/CONTRACT.md          # 本文件
├── README.md
├── backend/                  # Spring Boot 3 + MyBatis-Plus + Aviator + dynamic-datasource
│   ├── pom.xml
│   └── src/main/java/com/muzhou/report/
│       ├── MuZhouReportApplication.java
│       ├── common/           # Result / PageResult / BizException / GlobalExceptionHandler / enums
│       ├── config/           # MybatisPlusConfig / WebConfig / JacksonConfig
│       ├── entity/           # MzDatasource MzDataset MzDatasetField MzDatasetParam MzReport
│       ├── mapper/
│       ├── dto/
│       ├── service/ (+impl/)
│       ├── engine/           # 报表渲染引擎
│       ├── datasource/       # 动态数据源注册与 JDBC 元数据
│       ├── export/           # Excel 导出
│       └── controller/
└── frontend/                 # Vue3 + Vite + Pinia + FortuneSheet + vuedraggable + Element Plus
    ├── package.json / vite.config.js / index.html
    └── src/{main.js,App.vue,router,api,stores,views,components,styles}
```

## 1. 统一响应体

```json
{ "code": 0, "msg": "ok", "data": {} }
```
`code=0` 成功；`code=500` 业务异常；`code=400` 参数错误。前端 axios 拦截器已解包，业务代码直接拿到 `data`。

分页：
```json
{ "records": [], "total": 0, "pageNo": 1, "pageSize": 10 }
```

后端基础包 `com.muzhou.report.common`：
- `Result<T>`：静态方法 `Result.ok()`, `Result.ok(T)`, `Result.fail(String)`, `Result.fail(int,String)`
- `PageResult<T>`：`records/total/pageNo/pageSize`，静态 `PageResult.of(IPage<T>)`
- `BizException extends RuntimeException`：`BizException(String msg)`
- 所有 Controller 返回 `Result<...>`

## 2. 数据库表（H2 与 MySQL 同结构；主键统一 `VARCHAR(32)`，由 MyBatis-Plus `ASSIGN_UUID` 生成）

### mz_datasource
| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar(32) PK | |
| name | varchar(100) | 显示名 |
| code | varchar(50) unique | 动态数据源 key，英文数字下划线 |
| db_type | varchar(20) | mysql/postgresql/oracle/sqlserver/h2 |
| driver_class_name | varchar(200) | |
| url | varchar(1000) | |
| username | varchar(100) | |
| password | varchar(500) | |
| remark | varchar(500) | |
| status | int | 1 启用 0 停用 |
| create_time / update_time | timestamp | |

### mz_dataset
| id | varchar(32) PK |
| name | varchar(100) |
| code | varchar(50) | 单元格中 `#{code.field}` 使用，在同一 report_id 内唯一（`uk_dataset_code_scope(code, report_id)`） |
| report_id | varchar(32) NOT NULL DEFAULT '' | **作用范围**：`''` = 公共数据集（所有报表可用）；报表 id = 该报表的内部数据集（只有它可用）。恒为非 NULL，否则唯一索引拦不住重名 |
| datasource_id | varchar(32) |
| type | varchar(20) | sql / api / json |
| result_type | varchar(20) DEFAULT 'list' | 返回结果形态：`list` 集合（整份取回）/ `page` 分页（响应带数据数组 + 总数）。只对 api / json 有意义，sql 恒为 list |
| sql_text | clob/longtext |
| api_url / api_method / api_headers | varchar |
| json_text | clob/longtext |
| remark | varchar(500) |
| status | int |
| create_time / update_time | timestamp |

### mz_dataset_field
`id, dataset_id, field_name, field_text, field_type(string|number|date|boolean), sort_no`

### mz_dataset_param
`id, dataset_id, param_name, param_text, param_type(string|number|date|boolean), default_value, required(int), sort_no`

### mz_param

**全局参数**：一份系统级的参数定义，所有报表共用，在「参数管理」页维护。

| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar(32) PK | |
| param_name | varchar(64) | 参数名，单元格里写 `${param_name}`；**全局唯一**（`uk_param_name(param_name)`） |
| param_text | varchar(128) | 显示名 |
| param_type | varchar(20) | string / number / date / boolean |
| widget | varchar(20) | input / number / date / daterange / select |
| default_value | varchar(500) | |
| required | int | 1 必填。缺值时渲染直接报错，与报表参数同一条规则 |
| options | varchar(2000) | 下拉选项 JSON 数组 `[{value,label}]`，`widget=select` 时用 |
| remark | varchar(500) | |
| status | int | 1 启用 0 停用。**停用 = 不参与合并**，等于这个参数不存在 |
| deleted / create_time / update_time | | |

列名跟 `mz_dataset_param` 一套（`param_name/param_text/param_type`）而不是 `name/text/type`：
`text` 在几种数据库里是类型名，当列名要处处加引号。转成接口上的 `ReportParam`（§4）时改回 `name/text/type`。

**唯一索引不带 `deleted` 条件**，所以删掉再建同名参数会撞唯一键 —— 新建/改名前先物理清掉同名的
已删除行（`MzParamMapper#purgeDeletedByName`），与 `uk_dataset_code_scope` 那处是同一个坑。

参数值怎么跟报表参数合并、谁覆盖谁，见 §5「参数值从哪来」。

### mz_report
`id, name, code(unique), type(默认 'sheet'), content(clob/longtext), version_config(varchar(500)), remark, status, create_by, create_time, update_time`

- `content` **已废弃**（deprecated）：报表内容按**版本**存在 `mz_report_version.content` 里。
  这一列只在懒迁移时被读一次（见下），之后不再读写 —— **不做双写**，双写必然漂移。
  接口上的 `content` 字段不受影响（`GET /api/report/{id}` 装的是选中版本的内容）。
- `version_config`：版本切换规则，见 §4「版本」。规则是**报表级**的，不能进 content ——
  content 本身就是被版本化的那个东西，规则放进去就成了「每个版本各有一套怎么选自己」，逻辑成环。

### mz_report_version

一张报表的一份**版式**（一份完整的 `ReportContent`）。

| 列 | 类型 | 说明 |
|---|---|---|
| id | varchar(32) PK | |
| report_id | varchar(32) | 所属报表 |
| version_no | int | 报表内唯一（`uk_version_report_no(report_id, version_no)`），展示成 v1/v2/v3。**发号要含已逻辑删除的行**（唯一索引管着全部行），走 `MzReportVersionMapper#maxVersionNo` |
| name | varchar(50) | 版本名，空则显示 `v{version_no}` |
| content | clob/longtext | 这一版完整的 ReportContent，见 §4 |
| effective_from | timestamp | **生效起始时刻**；NULL = 最早的那一版（区间左端 -∞） |
| match_rules | varchar(2000) | **匹配条件**（离散那一维：单据类型、区域…）的 JSON 数组，空 = 无条件匹配。见 §4.1 |
| is_default | int | 基准版本，报表内恰好一条。判定值取不到时用它；不能停用、不能删除 |
| status | int | 1 启用（参与自动选择）/ 0 停用（只能在设计器里显式打开） |
| remark | varchar(500) | |
| create_by / create_time / update_time | | |

**版本化的是「版式」，不是数据集**：数据集是「取数」，跨版本共用（改 SQL 会同时影响所有版本，
删字段会让老版本模板里的 `#{code.field}` 取不到数 —— 版本管理页的「校验」按钮扫这个）。
数据集一旦分叉，主接口、父子关联、分页 total、内部/公共作用域全要跟着分叉，成本是版式版本化的
好几倍，而实际诉求（「5 月起单据换个抬头 / 加一列」）几乎都是版式。

**只存起点，区间靠排序推**（左闭右开）：存两端必然出现重叠和空洞，且改一处要改两处。
**停用的版本不参与推导**，它那段自动被前一版吞掉 —— 这正是「临时回滚版式」想要的行为。

| 版本 | `effective_from` | 推导区间 |
|---|---|---|
| v1 | `NULL` | `(-∞, 2026-05-01)` |
| v2 | `2026-05-01` | `[2026-05-01, 2026-08-01)` |
| v3 | `2026-08-01` | `[2026-08-01, +∞)` |

**区间只在 `match_rules` 相同的那几版之间推**（见 §4.1）：条件不同的版本压根不在同一条时间轴上
竞争，混在一起推出来的右端是假的。前端 `utils/version.js#versionIntervals` 也按条件分组，
两边必须一致。

**懒迁移**：报表一条版本行都没有时，用 `mz_report.content` 建 v1（默认、启用、`effective_from` 空），
挂在 `getDetail` 与渲染入口上（`ReportVersionService#ensureMigrated`），靠唯一索引兜幂等，老报表零感知。
报表**删除时版本行跟着逻辑删除，复制时所有版本行一起复制**（保留 version_no / effective_from /
status / is_default）—— 漏了后者，副本会是一张没有版式的报表。

## 3. REST API（统一前缀 `/api`）

### 3.1 数据源 `/api/datasource`
| 方法 | 路径 | 入参 | 出参 |
|---|---|---|---|
| GET | `/page?pageNo&pageSize&name` | | `PageResult<MzDatasource>` |
| GET | `/list` | | `List<MzDatasource>` |
| GET | `/{id}` | | `MzDatasource` |
| POST | `` | MzDatasource | `String id` |
| PUT | `` | MzDatasource | `Boolean` |
| DELETE | `/{id}` | | `Boolean` |
| POST | `/test` | MzDatasource | `Boolean`（失败抛 BizException 带原因） |
| GET | `/{id}/tables` | | `List<String>` |
| GET | `/{id}/columns?table=` | | `List<ColumnMetaDTO{name,type,comment}>` |

### 3.2 数据集 `/api/dataset`
| GET | `/page?pageNo&pageSize&name` | `PageResult<MzDataset>`（**只返回公共数据集**，即数据集管理页） |
| GET | `/list?reportId` | `List<DatasetDetailDTO>`（含 fields，用于设计器左侧树）。公共数据集 + `reportId` 那张报表的内部数据集；不传 `reportId` 只有公共的 |
| GET | `/{id}` | `DatasetDetailDTO{dataset, fields, params}` |
| POST | `` | `DatasetSaveDTO` → `String id` |
| PUT | `` | `DatasetSaveDTO` → `Boolean` |
| DELETE | `/{id}` | `Boolean` |
| POST | `/parse` | `{datasourceId, type, resultType, sqlText, apiUrl, apiMethod, apiHeaders, jsonText, params:[MzDatasetParam]}` → `{fields:[MzDatasetField], params:[MzDatasetParam]}` |
| POST | `/preview` | 同 parse + `{pageNo,pageSize}` → `{columns:[String], records:[Map], total:Long?}`（`total` 仅分页型有） |
| POST | `/{id}/data` | `{params:{}}` → `List<Map<String,Object>>` |

`DatasetSaveDTO` = MzDataset 全字段 + `List<MzDatasetField> fields` + `List<MzDatasetParam> params`。

**数据集的两种作用范围**（由 `reportId` 区分，见 §2 `mz_dataset.report_id`）：

| | 公共数据集 | 内部数据集 |
|---|---|---|
| `reportId` | 空 | 所属报表 id |
| 在哪里建 | 数据集管理页 | 报表设计器左侧数据集面板 |
| 谁能用 | 所有报表 | 只有所属那张报表 |
| code 唯一性 | 公共数据集之间唯一 | 同一报表内唯一；不同报表可以同名 |
| 跟随报表 | 不 | 报表删除时一并删除，报表复制时整套复制（code 不变） |

- 新建时 `POST` 带 `reportId` 就是内部数据集；`PUT` 更新**不能改作用范围**（后端以库里存的为准）。
- 内部数据集不允许与公共数据集重名（建的时候直接报错），免得 `#{code.字段}` 指哪一个要靠规则猜；
  反过来不拦，所以取数时的解析顺序是**先本报表内部、后公共**。
- 渲染取数因此必须带上「谁在渲染」：`DatasetService#fetchDataByCode(reportId, code, params)`。

**返回结果的两种形态**（由 `resultType` 区分，见 §2 `mz_dataset.result_type`）：

| | `list` 集合（默认） | `page` 分页 |
|---|---|---|
| 含义 | 整份数据一次取回 | 响应里带数据数组 + 总条数 |
| 数据数组 | 根数组直接用；对象依次找 `data/records/rows/result/list`；都不是就当成一行单对象 | 同左，**但找不到就报错**（支持嵌一层，如 `{data:{list:[...],total:n}}`） |
| 总条数 | 无（`rows.size()` 就是全部） | 依次找 `total/totalCount/totalRow/totalRows/count`，先在数组的同级对象上找、再退回根对象；**找不到就报错** |
| 翻页 | 不翻 | 接口地址里用 `${pageNo}` / `${pageSize}` 接（见 §5 保留参数名） |

标了分页却认不出结构时**必须报错而不是静默降级** —— 翻页和总页数都会是错的，
静默兜底只会让人以为「数据不全」。sql 类型恒为 `list`（分页要数据库端 limit/offset
再加一条 count，不在本项目范围内），后端保存时会强制改回来。

`DatasetService#fetchRowsByCode` 返回 `DatasetRowsDTO{rows, total}`，
`fetchDataByCode` 是它只取 `rows` 的薄封装（渲染引擎的取数函数签名不变）。

### 3.3 报表 `/api/report`
| GET | `/page?pageNo&pageSize&name` | `PageResult<MzReport>`（不含 content） |
| GET | `/{id}?versionId=` | `MzReport`（`content` = 该版本的内容，省略 `versionId` = 默认版本；响应里的 `versionId` 说明这是哪一版） |
| POST | `` | MzReport → `String id`（同时建出 v1） |
| PUT | `` | MzReport → `Boolean`（body 带 `versionId` 时 `content` 写进该版本，省略 = 默认版本；**不回写 `mz_report.content`**） |
| DELETE | `/{id}` | `Boolean`（版本行、内部数据集一并删） |
| POST | `/{id}/copy` | `String newId`（版本行、内部数据集整套复制） |
| POST | `/export` | `["报表id", ...]` → 字节流（一个报表包，一张或多张，见下）。内容是 JSON，但 `Content-Type` 报 `application/octet-stream` —— 前端那道「200 里装着错误结构」的识别只把 json 类型的 blob 读成文本，报成 json 就是每次导出白解码一遍整个包 |
| POST | `/import` | multipart：`file` = 报表包，`mode` = `skip`\|`overwrite`\|`copy` → `ReportImportResultDTO` |
| POST | `/parse-excel` | multipart：`file` = `.xlsx` → `{content, warnings}`，**只解析不落库**（见下） |

**报表包（导入导出）** —— 测试环境调好，整包带进正式环境。文件是一份 JSON
（下载名 `xxx.mzreport.json`），结构见 `dto/ReportPackageDTO`：

```jsonc
{
  "fileType": "muzhou-report-package",   // 导入时认这个标识
  "formatVersion": 1,
  "exportTime": "2026-08-09 12:00:00",
  "reports": [{
    "name": "销售单", "code": "sales",    // 报表定义（不含 id）
    "type": "sheet", "versionConfig": "...", "remark": "", "status": 1,
    "versions": [                          // 全部版式，按 versionNo 升序
      { "versionNo": 1, "name": null, "content": "{...}",
        "effectiveFrom": null, "matchRules": null,   // 匹配条件跟着走，见 §4.1
        "isDefault": 1, "status": 1, "remark": null }
    ],
    "datasets": [                          // 内部数据集 + 内容里引用到的公共数据集
      { "name": "明细", "code": "items", "shared": false,
        "datasourceCode": "biz",           // 数据源按 **code** 引用，见下
        "type": "sql", "sqlText": "...", "fields": [...], "params": [...] }
    ]
  }]
}
```

四条规矩，改这块之前先看：

1. **包里一律按 code 引用，不带任何主键** —— 主键是 UUID，两个环境必定对不上。报表认 `code`、
   数据集认 `code`、数据源认 `datasourceCode`。
2. **数据源本身不进包**：它带着业务库地址与口令（口令还是 `WRITE_ONLY` 的，压根导不出来），
   两个环境的连接信息本来就该不一样。目标环境没有这个 code 时**不拦**，导入照常进行、
   在结果里报一条 warning 让人去补 —— 版式和数据集都是对的，只差一个连接。
3. **内容里引用到的公共数据集也一起打包**（`shared: true`）：不带的话报表进了目标环境就是一张
   取不到数的空表。引用要从**三处**扫全（单元格绑定、`primaryDataset`、`datasetLinks` 的主表/子表），
   漏一处就是「某个数据集没跟来」。但导入时**公共数据集只新建、不覆盖** ——
   它被多张报表共用，为了导一张报表改掉别人的取数是灾难。
4. **一张报表一个事务**：批量导入时某一张失败不该把已经导好的那几张一起回滚掉，
   失败的那条在 `ReportImportResultDTO.items` 里单独列出来（`action` =
   `created`/`updated`/`skipped`/`failed`，`warnings` 是不影响导入但要人去补的事）。

`mode`（目标环境已有**同编码**报表时怎么办）：`skip` 跳过 / `overwrite` 覆盖
（**报表 id 不变** —— 外部系统拿 id 开设计器的链接不能断；版式与内部数据集整套替换，
包里没有的删掉）/ `copy` 一律新建（编码加 `_import_时间戳` 后缀）。

**导入 Excel 模板**（`/parse-excel`）—— 拿一份现成的 xlsx 当版式起点，省掉照着它重画表头、
边框、合并、列宽。响应是 `{ content: ReportContent, warnings: ["…"] }`：

- **只解析、不落库**。导入是不可逆的，直接写库会把用户当前的版式冲掉；接口只把 xlsx 翻译成
  `content` 还给设计器，用户在画布上看过、点「保存」才走 `PUT /api/report` 落到当前版本。
- **只做静态版式**：带过来的是 §4 里的 `sheets`（值/样式/合并/行高列宽/边框）与
  `pageConfig`/`pageConfigs`（纸张、页边距、缩放、打印区域、顶端标题行、页眉页脚）。
  **`cellConfigs` 恒为空** —— 占位符与取数由用户导入之后在设计器里手工配，猜错了让人逐格去拆
  比自己画还慢。
- **数值和日期落成文字**（Excel 里显示成什么样就是什么样）：渲染时 `ct` 会被 `CellFormatter`
  按 cellConfig 重建，模板里带的 `ct.fa` 活不到出纸那一步 —— 照搬「值 + 格式串」的话
  `1,234.00` 会出成 `1234`、日期会出成序列号。代价是这些格子在导出的 xlsx 里是文本。
- 带不过来的（水印 —— xlsx 里没有这个概念、条件格式、数据有效性、图片、图表、隐藏 sheet…）
  逐条写在 `warnings` 里**原样给用户看**，不说清楚就是「导进来不对」的报障。
- 只收 `.xlsx`；行数/单元格数的闸门用 `muzhou.report.max-rows` / `max-cells`，超了直接报错
  不许截断。实现见 `export/ExcelImporter`，它是 `ExcelExporter` 的逆映射，两个类必须对着改。

版本（见 §2 `mz_report_version`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/{id}/version` | `List<MzReportVersion>`（**不含 content**，同 `/page` 不传大字段的规矩），按 `versionNo` 升序 |
| POST | `/{id}/version/{versionId}/copy` | 从某版本复制新版本 → `String newId`（content 与 `matchRules` 原样，`status=0` 停用、`effectiveFrom` 空、不是默认版本） |
| PUT | `/version` | `ReportVersionSaveDTO{id, name, effectiveFrom, matchRules, status, remark}` → `Boolean`。`effectiveFrom` / `matchRules` 传 null 分别表示「最早的那一版」「无条件匹配」，都能被显式清空；`matchRules` 是 JSON 数组串，存之前字段名空的条目会被丢掉，格式非法直接报错 |
| POST | `/{id}/version/{versionId}/default` | 设为默认（停用中的会一并启用） → `Boolean` |
| DELETE | `/version/{versionId}` | `Boolean`。**默认版本、最后一个启用版本不许删** |
| GET | `/{id}/version/{versionId}/check` | `List<String>` 体检：这一版引用的数据集/字段是否还在、**匹配条件引用的字段/参数是否还在**（字段名写错时这一版只是永远匹配不上，渲染既不报错也没有别的痕迹），以及主接口 / 输出方式 / 父子关联与默认版本是否一致（不一致时以默认版本为准，只提示不拦） |

### 3.4 渲染 `/api/render`
| POST | `/report/{id}` | `{params:{k:v}, versionId?}` → `RenderResult`（`params` 里的 `pageNo`/`pageSize` 驱动主接口翻页，见 §5） |
| POST | `/preview` | `{reportId, content:ReportContent, params:{}, versionId?}` → `RenderResult`（设计器免保存预览；`reportId` 用于解析该报表的内部数据集，见 §3.2。**不走版本选择** —— 渲染的就是请求里这份 content，`versionId` 只原样回显） |
| GET | `/report/{id}/params` | `List<ReportParam>`（全局参数 + **默认版本**的报表参数，已按 §5 合并去重；参数表单照这份渲染） |
| POST | `/report/{id}/export/excel` | `{params:{}, versionId?}` → `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 字节流 |
| POST | `/report/{id}/export/pdf` | `{params:{}, sheetIndex?, versionId?}` → `application/pdf` 字节流（后端先出 xlsx 再转 PDF，页面设置同 Excel 导出）。`sheetIndex` = 只出**渲染结果**里的第几张 sheet，省略/越界 = 整本；预览页的「打印」打的就是这份 PDF，见 §7 |
| POST | `/report/{id}/export/word` | `{params:{}, versionId?}` → `application/vnd.openxmlformats-officedocument.wordprocessingml.document` 字节流（同样先出 xlsx 再转，版式与 PDF 一致） |

`versionId` = 用哪一版版式，省略 = 按报表的版本切换规则自动选（见 §4「版本」）。
> ⚠️ **`versionId` 必须走请求体，不能只挂在地址栏 query 上**：地址上的 query 会被
> `utils/params.js#queryParams` 原样透传成报表参数（§5），`?versionId=xxx` 会被塞进 SQL 的
> `${versionId}`。设计器 / 预览页确实用地址保持版本状态，所以 `versionId` 是**保留参数名**，
> 在 `queryParams` 里剔除、单独走请求体（见 §5）。
>
> 预览、导出、打印**四条路都要带上同一个 `versionId`**，否则会出现
> 「预览看的是 v2、导出的是 v3」。

> 导出接口失败时返回的仍是 HTTP 200 + `Result`（JSON），只是 `Content-Type` 为 `application/json`；
> 前端按 blob 收流，需识别出这种「200 里装着错误结构」的情况，否则会把错误 JSON 当文件下载。

### 3.5 全局参数 `/api/param`

系统级参数定义（表 `mz_param`），所有报表共用。参数**不属于任何一张报表**，
所以这里是纯 CRUD，没有 `reportId` 这一维。

| 方法 | 路径 | 入参 | 出参 |
|---|---|---|---|
| GET | `/page?pageNo&pageSize&name` | | `PageResult<MzParam>`（`name` 按参数名/显示名模糊匹配） |
| GET | `/list` | | `List<MzParam>`（**只返回启用的**，按参数名排序） |
| GET | `/{id}` | | `MzParam` |
| POST | `` | MzParam | `String id` |
| PUT | `` | MzParam | `Boolean` |
| DELETE | `/{id}` | | `Boolean` |

参数名重复、为空、或不合 `[A-Za-z_][A-Za-z0-9_]*` 一律 `BizException` 拦下 ——
它要进 SQL 的 `${}` 与接口地址，名字随便起会在取数那头才炸。

## 4. 报表内容 JSON（`mz_report.content`）

```jsonc
{
  "version": 1,
  // FortuneSheet Sheet[]，统一存 celldata 形态（{r,c,v} 列表）。
  // 注意：工作簿实例 getAllSheets()/onChange 返回的是运行时结构（二维 data，celldata 已被删除），
  // 必须经 utils/sheet.js#toCelldataSheets 转换后再持久化 —— 否则后端 TemplateParser 解析为空。
  "sheets": [ /* Sheet[]，见上 */ ],
  "cellConfigs": {
    // key = `${sheetIndex}_${r}_${c}`，sheetIndex 是 sheets 数组的下标，见下方「按下标寻址」
    "0_3_1": {
      "sheetIndex": 0, "r": 3, "c": 1,
      // data | formula | param | text | img | base64 | barcode | qrcode
      // img / base64 / barcode / qrcode = **图片单元格**：取数方式与 data 完全一样
      // （datasetCode + field + expandType + groupType），区别只在于取到的值是当图片画还是当文字写。
      //   img     值是图片地址（http/https）
      //   base64  值是图片的 base64（带不带 `data:image/png;base64,` 前缀都认）
      //   barcode 值是要编成一维条形码的那串字，由服务端现画一张 PNG
      //   qrcode  值是要编成二维码的那串字（支持中文，按 UTF-8 编）
      // 图片**等比例装进单元格并居中**（落在合并区里就按整块装）：宽、高哪个先顶到边就以哪个
      // 为准，另一个方向留白 —— 铺满整格等于把图片拉变形。格子本身不再输出文字
      // ——照常输出的话导出的 Excel 里会写进一长串 base64。
      // aggregate / formatType 对图片格无意义（设计器里也不显示）。
      "type": "data",
      "datasetCode": "orders",
      "field": "amount",
      "expandType": "down",       // none | down | right
      "groupType": "list",        // list | group  (group=相同值纵向合并，图片格不参与)
      "aggregate": "none",        // none | sum | avg | max | min | count
      "formatType": "number",     // text | number | currency | percent | date
      // 格式化模板。**货币符号写在模板里**（`¥#,##0.00` / `$#,##0.00`），引擎不再另拼前缀
      // —— 拼前缀的话符号只进得了显示文本 m、进不了 ct.fa，导出的 Excel 按 fa 重新格式化就没符号了。
      //   number  DecimalFormat 串，默认 `#,##0.##`
      //   currency DecimalFormat 串，默认 `¥#,##0.00`；特殊值 `[中文大写]` = 人民币大写
      //            （壹仟贰佰叁拾肆元伍角陆分，四舍五入到分、到角为止补「整」、负数前缀「负」）。
      //            大写出的是文字不是数字，所以 ct 标成文本 `{fa:"@",t:"s"}`
      //            —— 标成数字的话导出时 Excel 照 fa 重算，大写全变回阿拉伯数字。
      //   percent DecimalFormat 串（值 ×100 后再套），默认 `0.00`，显示时补 `%`
      //   date    Java 日期格式串，默认 `yyyy-MM-dd`，**允许自定义**（`yyyy年MM月dd日`）；
      //            导出时由 ExcelExporter#toExcelDateFormat 转成 Excel 格式码
      //            （MM→mm、HH→hh，中文字面量裸着写 —— POI 的 DataFormatter 不脱引号，
      //             加了引号 PDF/Word 里会打成 2024"年"01"月"31"日"）
      // 设计器里只让配 currency 与 date 两种（另外几种没什么可选的，按默认模板出）；
      // 换 formatType 时模板一并换成该类型的默认值（utils/sheet.js#defaultFormatPattern）
      // —— 留着上一个类型的模板，界面上看不见、渲染时却在生效。
      "formatPattern": "#,##0.00",
      "expression": "",           // type=formula 时的 Aviator 表达式
      // 图片单元格没出成图时，改用**同一数据集同一行**的这个字段的值当**普通文字**写。
      // 空 = 不兜底，出空白（老报表没有这一项，行为不变）。
      // 签字栏是典型场景：base64 那个字段没值（这单还没签）时整格空白，纸上看不出是「没签」
      // 还是「这张表压根没这一项」；配上签字人姓名，至少还印得出「张三」。
      // **只兜「渲染时就知道出不了图」的那几种**：字段值为空、条码编不出来（位数/字符不合码制）。
      //   `img` 的地址取不回来（服务端下载失败）是**导出时**才知道的，那条路上兜不了底
      //   —— 渲染时该格已经当成有图处理，v.mzImg 挂上了。
      // 兜底文本不按 formatType 格式化（图片格的 formatType 在设计器里压根不给配），
      // 落成普通文字格（不挂 v.mzImg），所以预览与三条导出路认不出区别、无需各自实现。
      "fallbackField": "",
      // 以下三项只对 barcode / qrcode 有意义（engine/BarcodeGenerator）
      // 码制，ZXing 的 BarcodeFormat 名。空 = 按类型给默认：barcode → CODE_128，qrcode → QR_CODE。
      // 设计器给的是一份精选清单：一维 CODE_128 / CODE_39 / CODE_93 / EAN_13 / EAN_8 / UPC_A /
      // ITF / CODABAR，二维 QR_CODE / DATA_MATRIX / PDF_417 / AZTEC。
      // **各码制能编什么是有硬限制的**（EAN_13 只收 13 位数字、CODE_39 不认小写），
      // 编不出来的那一格记一条 warn 后出空白，不让整份渲染失败。
      "barcodeFormat": "",
      "qrLevel": "",              // 二维码纠错级别 L/M/Q/H，空按 M。**只有 QR_CODE 收**
                                  // （Aztec/PDF417 的纠错参数是整数，塞 ErrorCorrectionLevel 会编不出码）
      "barcodeText": true         // 条形码下方印不印那串原文，默认印；二维码无此概念
    }
  },
  "params": [
    { "name": "startDate", "text": "开始日期", "type": "date",
      "widget": "date", "defaultValue": "", "required": false, "options": [] }
  ],
  // 主接口：驱动本报表分页的数据集 code。**一张报表只有一个**（单值天然唯一），空 = 不分页。
  // 只有它是分页型（resultType=page）时预览页才出分页条，总条数取它自报的 total。
  // 标记记在报表里而不是数据集上 —— 公共数据集被多张报表共用，
  // 它在 A 报表里是主接口不代表在 B 报表里也是。
  "primaryDataset": "orders",
  // 输出方式：single 单 sheet 输出（默认）/ perRowPage 每条数据一页、拼在同一张 sheet 里
  // （**设计器界面上叫「多 sheet 输出」** —— 打印设置不同的相邻份会断成几张 sheet）。
  // 拆分方式是：整份模板复制 N 遍（N = 主接口行数），第 i 份只喂主接口的第 i 行，
  // 其它数据集每份都拿全量 —— 「一条数据一张单据」就是这么出的；再把它们**首尾相接拼成一张**
  // （engine/SheetConcat），并在每份的起始行打行分页符（见 mzRowBreaks），
  // 于是 Excel 里是一张连续的表、打印出来仍是一条数据一页。最多 200 条。
  // **还有个已下线的 perRow**（「每条数据一个 sheet」）：同一段拆分代码的另一个出口，
  // 不拼接、直接输出 N×M 张 sheet。它与 perRowPage 功能重叠，而 M×N 张会把同一条数据的
  // 几张单据打散在整个工作簿里对不上号，所以**设计器里已经没有这个选项**
  // （`stores/designer.js#setSheetSplit` 把传进来的 perRow 一律迁成 perRowPage）。
  // **引擎仍完整支持它**，没在新版设计器里存过盘的老报表照旧按老样子渲染；
  // 下面凡是提到 perRow 的地方都是这条兼容路径。
  // **拼接顺序 = 渲染顺序（行优先）**：数据1的模板1/模板2/模板3、数据2的模板1/模板2/模板3…
  // 一条数据的几张单据挨着打，不是「全部数据的模板1、全部数据的模板2」。跨模板挨着排
  // 只能在同一张 sheet 里做（sheet 与 sheet 之间必定断页），而**一张 sheet 只放得下一套
  // 打印设置**，所以**只有打印设置相同的相邻份才拼在一起**：各模板设置都一样时拼成一张
  // （最常见），第 2 张模板单独设成横向时就断成几张，顺序不变。一张里含几张模板时
  // **列宽逐列取各模板最大值**（一列只有一个宽度）。结果 sheet 数因此不再是模板张数的整数倍，
  // 每张自带 mzTemplateIndex 说明出自哪张模板（见下）。
  // 只对**集合型**主接口有效（分页型自己就在按页取数）。
  "splitMode": "single",
  // 单据名取主接口这一行的哪个字段；取不到值退回「第 n 条」。
  // perRowPage 拼成一张之后 sheet 名说不了话（整张只有一个名字，还是模板名），
  // 它落在 mzDocNames 上，供页头页尾里的 ${sheet} 逐份取用（见下）。
  // 老的 perRow 共用这一项，那边拿它当 sheet 名（重名自动挂 (2)(3)；模板有多张 sheet 时
  // 补上模板名 `SO-001-明细`）—— 引擎里那份逻辑留给老数据。
  "sheetNameField": "order_no",
  // 父子关联（子接口查询）：先查主表，再拿主表返回的字段值去查子表。
  // 主表返回 N 行 = 子表被调用 N 次（最多 muzhou.report.max-link-rows 行，默认 500），
  // N 次的结果**首尾拼成一份**当作子表数据，且主表这一行的字段会合进它的每条子行
  // （同名字段子表优先）—— 于是「主表 + 明细」在模板里就是一条普通的扩展行带，
  // 主表那几列配 groupType=group 就是跨行合并。取数细节见 engine/LinkedDataFetcher。
  // 约束：一个子表只能挂一个主表（挂两个则取数规则有歧义，后端直接报错）；不能成环。
  "datasetLinks": [
    {
      "name": "订单明细",        // 只用于设计器里认人
      "master": "orders",       // 主表数据集 code
      "child": "orderItems",    // 子表数据集 code
      // 参数传递：把主表 field 的值，作为子表 param 参数的值传下去。
      // 覆盖同名的报表参数（否则每行查的都是同一份数据）；子表参数不必事先声明，
      // SQL 的 ${param} / 接口地址的 ${param} 直接接得住。
      "mappings": [{ "param": "orderId", "field": "id" }]
    }
  ],
  // 报表级打印设置：设计器画布分页线、预览页浏览器打印(@page)、Excel/PDF 导出页面设置共用。
  // 没有在 pageConfigs 里单独设置过的 sheet 都用这一份（老报表也只有这一份）。
  "pageConfig": {
    "paperSize": "A4",          // A3 | A4 | A5 | B4 | B5 | Letter | Legal
    "orientation": "portrait",  // portrait | landscape
    "marginTop": 10, "marginBottom": 10, "marginLeft": 10, "marginRight": 10,  // mm
    "scale": 100,               // 打印缩放百分比 10~400
    "fitToWidth": false,        // 所有列压缩到一页宽（为 true 时忽略 scale）
    "limitWidth": true,         // 限宽：设计器里「会被打印的那些列」（打印区域，未设则是有内容的区域）
                                //      宽度之和不得超过一页可打印宽度。约束设计阶段的拖动；
                                //      同时在 scale=100 时让导出的 xlsx 按「一页宽」出纸，
                                //      避免 px→Excel 列宽的量化误差把最后一列挤到第二页。
                                //      缺省视为 true
    "printArea": "",            // 打印区域 "A1:F30"，空=有内容的区域
    "titleRows": "",            // 顶端标题行（跨页重复的表头）"1:3"，1 起算的闭区间，空=不重复。
                                //      **只能是内容范围最上面的连续若干行**（设了打印区域就从区域
                                //      的第一行起算）；落在中间的三条导出路语义对不齐，一律忽略。
                                //      存进 xlsx 的 _xlnm.Print_Titles，PDF / Word 从那里读回来
    "rowOverflow": "slice",     // 比一页还高的行（长备注那种自动换行格）怎么出纸：
                                //      slice = 横着劈开跨页印（默认，也是老报表的行为），
                                //              切口两边的格子各缺一条横边；
                                //      split = 续行，装不下的文字另起一行接着印，每页上都是
                                //              边框闭合的完整格子，同一行里的短格子按顶对齐、
                                //              跟着留在第一页。
                                //      **只作用于 PDF / Word 两条导出路**（见下面的能力表）：
                                //      xlsx 里没有「把一行拆成两行」这种表达，和水印一样存不进去，
                                //      由 PdfExporter / WordExporter 各自从 PageConfigDTO 里读。
                                //      缺省视为 slice
    // 页头 / 页尾：左中右三段，三段都空 = 没设置。缺省视为「没设置」（老报表没有这两项）。
    // 文字里可以写占位符，见下方「页头页尾占位符」。
    "header": {
      "left": "", "center": "", "right": "",
      "fontSize": 9,            // 磅，6~72
      "margin": 5               // 页头距纸张上边（页尾距下边）的 mm
    },
    "footer": { "left": "", "center": "", "right": "", "fontSize": 9, "margin": 5 },
    // 水印：text 为空 = 不加水印。缺省视为「没有水印」。
    "watermark": {
      "text": "",
      "fontSize": 60,           // 磅，8~200
      "color": "#C0C0C0",
      "opacity": 30,            // 不透明度百分比 0~100
      "rotation": 45            // 倾斜角度 -90~90，**逆时针为正**
    }
  },
  // 按 sheet 单独设置的打印设置，key = sheet 下标（字符串），值的结构同 pageConfig。
  // 只存改过的那些 sheet，其余退回上面的 pageConfig；一份宽表和一份窄表可以各用各的纸张方向。
  // 与 cellConfigs 一样按下标寻址，见下方「按下标寻址」。
  "pageConfigs": {
    "1": { "paperSize": "A3", "orientation": "landscape", "marginTop": 10, "marginBottom": 10,
           "marginLeft": 10, "marginRight": 10, "scale": 100, "fitToWidth": false,
           "limitWidth": true, "printArea": "" }
  }
}
```

**取值规则**（前后端必须一致）：第 `i` 张 sheet 生效的打印设置 = `pageConfigs[String(i)] ?? pageConfig`。
后端见 `ReportContentDTO#pageConfigOf`，前端见 `utils/print.js#pageConfigOf`。
预览页浏览器打印的 `@page` 一份文档只能有一套，跟随当前激活的那张 sheet。

**预览页默认显示的就是 `export/pdf` 出的那份 PDF**（blob 挂给 `<iframe>`，整本、不带
`sheetIndex`），顶部可切回只读表格（`ReportPreview.vue#viewMode`：`pdf` / `sheet`）。
「打印」按钮同样不走浏览器打印：把 PDF 挂进隐藏 iframe 再 `contentWindow.print()`，
**打的范围跟着看到的** —— PDF 视图下复用屏幕上那份（不再请求一次），表格视图下带上当前激活
sheet 的下标只打那一张。打出来与「导出 PDF」的文件逐页一致，页头页尾、页码、水印、
分页位置一个不少。浏览器直接 Ctrl+P 仍走网页那条路（`@page` + `position:fixed` 水印，
只在表格视图下有意义），能力见下表。

**页头页尾占位符**：报表里存的是下面这套写法，导出时才翻成 Excel 的页眉代码
（后端 `export/HeaderFooterText`，前端 `utils/print.js#expandHeaderFooter`）。
用户文字里的 `&` 会被转义成 `&&`，否则「A&C公司」里的 `&C` 会被 Excel 当成「居中段从这里开始」。

| 占位符 | Excel 代码 | 含义 |
|---|---|---|
| `${page}` | `&P` | 当前页码 |
| `${pages}` | `&N` | 总页数 |
| `${date}` | `&D` | 日期 |
| `${time}` | `&T` | 时间 |
| `${sheet}` | `&A` | 工作表名；「每条数据一页」时是**本单据**的名字，见 `mzDocNames` |

**页头页尾里没写页码的 sheet 不参与页码统计**：封面 + 正文 + 总结这种报表，只有正文那张写了
`${page}` / `${pages}`，它就该从「第 1 页」数起、「共几页」也只数它自己那几页 —— 封面白占一个页号的话
正文第一页印出来是「第 2 页 共 3 页」。判定看的是**印不印页码**（`HeaderFooterText#hasPageNumber`）
而不是「有没有页头页尾」：只写了标题的封面照样不占页数，而**印了页码的 sheet 必定被算进总数**，
不会出现「印了却没算」。跳过的那些页拿不到页号（给 0），反正它们没有占位符可印。
各通道能做到多少见下面的能力表 —— 只有 PDF 两个数都准。

**跳过与「按单据编页码」叠加时，跳过不能把单据的起点一起跳掉**：一条数据出封面/正文/总结三张
（`perRow` / `perRowPage`）时，`mzDocBreaks` 落在**封面**那张上，而封面是不参与统计的 ——
见到它就跳过的话，各条数据的正文页会被并成一组，「共几页」印出来是**全部数据**的页数而不是这一条的。
所以单据起点要先记下来、攒到下一张参与统计的页再另起一组（`PdfExporter#numbering` 的 `pending`）。
**「共几页」永远是「本单据里印页码的那几页」**，不是整份文档的页数。

**字号在 xlsx 页眉串里恒写成两位**（`&09` 而不是 `&9`）：Excel 的字号码是「`&` + 一串数字」，
紧跟的文字要是也以数字开头就分不清哪几位是字号 —— 页头写「2026 年度经营分析」时
`&92026…` 会被解成 92026 磅，页头大到把正文挤成一行一页，「2026」还跟着一起没了。
写死两位、解析也只吃两位（`HeaderFooterText#append` / `#parseParts`，设计器里字号限制 6~72）。

**页头页尾画在页边距里**（和 Excel 一样），正文仍从 `marginTop` 开始；页边距装不下
（`margin + fontSize × 1.35` 超过 `marginTop`）时正文往下让，让到页头下面。这个换算在四处必须一致：
`PageConfigDTO#effectiveMarginTopMm`、`utils/print.js#effectiveMarginsMm`（画布分页线）、
`PdfExporter#readHeaderFooter`、`WordExporter#reserve`；Excel 自己会做，`ExcelExporter` 不必处理。

**各输出通道支持的范围**（不是所有通道都做得到）：

| | 页头页尾 | 水印 | 顶端标题行（`titleRows`） | 行分页符（`mzRowBreaks`） | 按单据编页码（`mzDocBreaks`） | 按单据取名（`mzDocNames`） | 跳过不印页码的 sheet | 超高行续行（`rowOverflow=split`） |
|---|---|---|---|---|---|---|---|---|
| Excel (.xlsx) | ✅ 写进 sheet 的页眉页脚 | ❌ Excel 没有水印这个概念 | ✅ `_xlnm.Print_Titles`（**只此一处写**） | ✅ 手动行分页符 | ⚠️ 只到 sheet 级：起始页号钉成 1；`&N` 恒是整个打印任务的页数 | ⚠️ 只到 sheet 级：`&A` 就是工作表名，`perRowPage` 拼成一张后整张同名 | ⚠️ 起始页号钉在**每份单据里第一张印页码**的 sheet 上（`&P` 对了，见 `pagePins`）；`&N` 仍是整本页数 | ❌ xlsx 里一行就是一行，「把一行拆成两行」表达不了（Excel 自己也是把超高行硬切开跨页印的） |
| PDF（OpenPDF） | ✅ | ✅ 压在内容之上 | ✅ 标题行不参与分页，每页顶部重画一遍 | ✅ 读 xlsx 里那份 | ✅ 逐页自己画，页码/总页数都按本单据 | ✅ 逐页换名 | ✅ 整张跳过，两个数都对 | ✅ 切口对到两行文字之间，每页上都是边框闭合的完整格子 |
| Word（POI） | ✅ 页码是 `PAGE` 域 | ✅ 页眉里的 VML 艺术字，**在正文下面** | ✅ `w:tblHeader`，Word 自己重画 | ✅ 落成行首格的 `pageBreakBefore` | ⚠️ 只到节级：`w:pgNumType` 重编 + `SECTIONPAGES`；`perRowPage` 做不到 | ⚠️ 只到节级（一张 sheet 一节）：`perRowPage` 全在同一节里，做不到 | ❌ 页眉页脚整份只有一套、跟**第一张** sheet，封面没设就等于整份都没有 | ⚠️ 真的多出一行（`w:cantSplit`），但切在哪儿是**估**的（字宽/行距按 `RowSpill#wrapEstimate` 算），与 Word 自己排出来的会差一两行 |
| 预览页（默认 PDF 视图 / 「打印」按钮） | ✅ 看的打的都是后端 PDF | ✅ 同 PDF | ✅ 同 PDF | ✅ 同 PDF | ✅ 同 PDF | ✅ 同 PDF | ✅ 同 PDF | ✅ 同 PDF |
| 预览页浏览器直接打印（Ctrl+P，只在表格视图下） | ❌ 见下 | ✅ `position:fixed` 每页重画 | ❌ FortuneSheet 是一整张 canvas | ❌ FortuneSheet 是一整张 canvas | ❌ 连页码都给不出来 | ❌ 同左 | ❌ 同左 | ❌ 同左 |

页头页尾**在浏览器打印里做不到**：要每页出现只能用 `position:fixed`，而它定位的是页边距**以内**
的区域，画不进页边距；画在里面就会压住表格（表格是一整张 canvas，没法像 `<thead>` 那样每页留白），
页码更是 CSS 给不出来的（`counter(page)` 只在 `@page` 的边距框里可用，Chrome 不支持）。

水印**存不进 xlsx**，所以 PDF / Word 转换器除了 xlsx 还要额外收一份 `PageConfigDTO`
（见 `RenderServiceImpl#exportPdf/exportWord`）；页头页尾则一律走 xlsx，转换器读回来即可。
`mzDocBreaks` 同理（xlsx 只有 sheet 级的起始页号，一张 sheet 内部无从表达），
所以 `PdfExporter#convert` / `WordExporter#convert` 各多收一个按 sheet 取值的
`IntFunction<List<Integer>>`，与那份 xlsx 一一对齐（见 `RenderServiceImpl#renderToXlsx`）。
`mzDocNames` 走同一条通道（`&A` 在 xlsx 里只有工作表名一个值），只有 `PdfExporter` 收
—— 另两条路做不到逐份换名，见上面的能力表。

**超高行的续行（`rowOverflow`）与水印同一条通道**：xlsx 里表达不了「把一行拆成两行」，
所以它也是顺着 `PageConfigDTO` 递给两条转换器的。两边的做法不同、但**切口规则同一份**
（`export/RowSpill`：切口只落在两行文字之间，绝不从一行字中间切过去）——
PDF 是逐页自己画的，把格子的底色边框夹进本页就得到闭合的框（`PdfExporter#drawCell`）；
Word 是转结构，得**真的多出一行**，于是要自己模拟一遍分页才知道切在哪儿
（`WordExporter#plan`，行高按 Word 自己的列宽把文字重新折一遍算 —— xlsx 里那份被 Excel 的
409.5pt 行高上限夹过，照它判永远触发不了续行）。
Word 的 `rowOverflow` 与水印一样**整份一套、跟第一张 sheet**；PDF 是按 sheet 取的。

**图片的三条导出路**：与水印相反，图片是**一处写、三处读**。`ExcelExporter#applyImages` 按
`v.mzImg.src` 把图片取回字节（`export/ImageLoader`：`data:` URI 直接解码，http(s) 现下载，
超时/超过 `muzhou.report.image.max-bytes` 就跳过这一张、导出照常），锚进 xlsx。
**锚的那个框不是整格，而是图片等比例装进这一格之后居中的框**（`export/ImageFit#contain`）——
Excel 是把图片拉伸到填满锚点框的，锚成整格就等于把图片拉变形；锚定方式用 `MOVE_DONT_RESIZE`，
否则列宽一变图片就被横向拉长。下游两条路都从这份 xlsx 里读：
`PdfExporter#readImages` 连**格内偏移（EMU）**一起读、画出来的就是
Excel 里那一块；`WordExporter#readPictures` 只取「占了哪几行哪几列」，再按 Word 自己的列宽
（正文宽度重分配过）重新装一次 —— 格子的宽高比不同，照抄尺寸反而会变形。
**别在各条路上再实现一遍「地址怎么变成字节」** —— 那意味着一张图要下载三遍，还会三处不一致。
只认两点锚定的图片（报表出的都是这种），手工插进 xlsx 的浮动图片不在还原范围内。

**条码不是第四条路**：`barcode` / `qrcode` 的图是**渲染时**就画好的（`engine/BarcodeGenerator`
把那串字编成 PNG，归一化成一个 `data:` URI 挂到同一个 `v.mzImg.src` 上），
所以预览、Excel、PDF、Word 拿到的是同一张图，谁也认不出它是画的还是取回来的。
放到前端画的话导出那头（三条路都在服务端）还得再实现一遍，两份实现迟早对不齐。

图片取不回来时**只跳过这一张并记一条 warn**（导出不中断）。「预览里看得到、导出的文件里没有」
基本都出在这里：预览是**浏览器**去拉图，导出是**服务端**去拉 —— 服务端可能没有代理
（JDK 的 HttpClient 默认不走系统代理，所以显式挂了 `ProxySelector.getDefault()`）、
没有登录态（图片地址要 cookie 时会拿回一个登录页，日志里会写明）、或者拿到的是相对路径
（要配 `muzhou.report.image.base-url` 才知道去哪个站点取）。

**按下标寻址**：`cellConfigs` 与 `pageConfigs` 的 key 里的 sheetIndex 都是 **`sheets` 数组的下标**，
不是 sheet 的 `id`、也不是 `order`（标签页的显示顺序按 `order` 排，与数组顺序无关）。
下标寻址的前提是**写入方负责在 sheets 增删后把 key 对齐**：删除一张 sheet 会让它后面每张的
下标往前挪一位，设计器按 sheet `id` 比对前后两份 sheets 并搬 key
（`utils/sheet.js#sheetIndexRemap` / `remapCellConfigs` / `remapPageConfigs`，在
`stores/designer.js#setSheets` 里调用），被删 sheet 的配置一并丢弃。
读取方（后端、预览页）只按下标取，遇到下标越界的孤儿 key 一律忽略
（老报表里可能有，设计器载入时会顺手清掉）。

**绑定与格子内容同生共死**：`type` 为 `data` / `formula` / `param` 的配置，格子里必须留着对应的
占位符（`#{code.field}` / `!{表达式}` / `${param}`）—— 渲染时**以配置为准**（`TemplateParser`
有显式配置就不再看文本），占位符是给人看的，也是「这条绑定还在不在」的唯一凭据。
所以**格子被清空就等于绑定作废，写入方必须把配置一起回收**：FortuneSheet 删内容只是把该格
置成 `{}`（既不删 celldata 条目也没有钩子），留着配置的话画布上明明删干净了，
一预览/导出数据照旧出来，空格子上的 `expandType=down` 还会凭空多出一条扩展行带。
设计器在 `stores/designer.js#setSheets` 里对着最新的 sheets 回收
（`utils/sheet.js#pruneEmptyCellConfigs`，只回收上面这三类，`text` 类配置只有格式化，
用户可能先设格式再填内容，不动）。手写 content（如 `db/data-report.sql` 的演示报表）也要守这条。

**模板下标 `sheet.mzTemplateIndex`**（每张渲染结果 sheet 都带，值是它出自 `content.sheets`
的第几张）：
```json
{ "name": "SO-001", "celldata": [ /* … */ ], "mzTemplateIndex": 1 }
```
`pageConfigs` / `cellConfigs` 存的都是**模板**下标，而结果 sheet 与模板不是一一对应的
（`perRow` 拆成 N 倍、`perRowPage` 又把若干份拼成一张），**取打印设置一律按这个值**：
后端 `ReportContentDTO#pageConfigOfSheet`、前端 `utils/print.js#pageConfigOfRendered`
（第三个参数传那张 sheet），两边同一条规则，改一处要改另一处。
没有这个标记时（老结果、手工构造的）才退回按下标推算 `renderedIndex % 模板张数`
—— 这条只对 `perRow` 成立，`perRowPage` 拼过之后算不出来。

**行分页符 `sheet.mzRowBreaks`**（`perRowPage` 的产物，值是每一份的**起始行**）：
```json
{ "name": "订单", "celldata": [ /* … */ ], "mzRowBreaks": [12, 24, 36] }
```
它是「一条数据一页」在整条链路上唯一的载体 —— xlsx 里除了手动分页符没有别的地方能表达
「这两条不许挤在一张纸上」。`ExcelExporter` 写成 Excel 的手动行分页符（POI 的
`setRowBreak(r)` 是「在第 r 行**下方**断页」，所以起始行要减一），PDF / Word 三条路都从那份
xlsx 读回来（`getRowBreaks()`），**别在各条路上再造一份**。前端不消费它（FortuneSheet
画不出分页线，预览页的「打印」走的是 `export/pdf`）。

**单据起始行 `sheet.mzDocBreaks`**（`perRow` / `perRowPage` 的产物，值是**每份单据**的起始行）：
```json
{ "name": "订单", "celldata": [ /* … */ ], "mzRowBreaks": [12, 24, 36], "mzDocBreaks": [0, 24] }
```
页头页尾里的 `${page}` / `${pages}` **在这些行上从 1 重编**：单据是一份份发出去的，
第 7 张上印「第 7 页 共 20 页」对收到的人没有意义，该印的是「第 1 页 共 2 页」。
`perRow` 时一张结果 sheet 一份（值只会是 `[0]` 或 `[]`——`[]` 表示延续上一张，
比如同一条数据的第 2 张模板），`perRowPage` 时是拼在一起的那张里的若干行。
**它是 `mzRowBreaks` 的子集**：一条数据有 3 张模板时 3 份各占一页（3 个行分页符），
但只有第 1 份是新单据的起点。由 `ReportRenderEngine#markDocBreaks` / `SheetConcat` 挂上，
普通输出（`single`）**没有这一项** = 整份连续编号。各通道能做到多少见上面的能力表。

**单据名 `sheet.mzDocNames`**（`perRowPage` 的产物，与 `mzDocBreaks` **一一对应**，
值来自 `content.sheetNameField` 那个字段、取不到是「第 n 条」）：
```json
{ "name": "订单", "mzDocBreaks": [0, 24], "mzDocNames": ["SO-001", "SO-002"] }
```
页头页尾里的 `${sheet}` 在这些行之后印的是**这一份单据**的名字而不是工作表名：
拼成一张之后整张 sheet 只有一个名字，而纸是一份份发出去的，每张上该写自己那一份的单号。
`perRow` **没有这一项** —— 那边这件事由 sheet 名自己承担（同一个字段算出来的），
下游取不到名字就退回工作表名。空串同样表示「退回工作表名」。
一一对应的是**起始行**不是每一份：一条数据有 3 张模板时 3 份共用一个名字，只记一次。

## 4.1 版本（`mz_report.version_config` + `mz_report_version.match_rules`）

一张报表可以有好几份**版式**（见 §2 `mz_report_version`），用哪一份由**两维**决定：
每一版自带的**匹配条件**（离散维度：单据类型、区域…）先筛，再在筛出来的那几版里按**生效时间**
推区间。时间那一维的判定值从哪来由这条**报表级**规则说了算：

```jsonc
{
  // 判定值从哪来：field 主接口字段（默认）/ param 报表参数 / now 渲染当日
  "source": "field",
  // 字段名或参数名；source=now 时无意义。**留空 = 时间这一维不参与**（只按条件选）
  "field": "order_date",
  // 判定值取不到时：default 用默认版本（默认）/ error 直接报错
  "fallback": "default"
}
```

**匹配条件**（`mz_report_version.match_rules`，**按版本存**，同一版内多条是 AND）：

```jsonc
[
  // 值从哪来：field 主接口字段（默认）/ param 报表参数
  { "source": "field", "field": "order_type", "op": "eq", "value": "A" },
  { "source": "field", "field": "area", "op": "in", "value": "华东,华南" }
]
```

| `op` | 含义 |
|---|---|
| `eq` / `ne` | 等于 / 不等于 |
| `in` / `notIn` | 属于 / 不属于（`value` 是逗号分隔的一串） |
| `contains` | 包含子串（实际值为空时恒不满足） |
| `empty` / `notEmpty` | 为空 / 不为空（`value` 无意义） |

值比较**忽略大小写与首尾空格**，两边都是数字时按数值比（`1` 与 `1.0` 相等）——
判定值来自数据库/接口，同一个类型码大小写不一致的比比皆是。注意这与「按字段名取值大小写敏感」
不是一回事：字段名照旧先按原样取、取不到再试小写。

**规则为什么挂在版本行上而不是报表级**：报表级只放得下一套判定依据，放不下「每一版各自适用于
什么」。老版本没有 `match_rules` = 无条件匹配，所以这一维加上来对老报表零影响。

**选择算法**（`version/ReportVersionResolver`，纯 POJO，不依赖 Spring）：

1. 显式 `versionId`（设计器/预览指定）→ 直接用它，**含停用版本**，不走下面的规则；
2. 启用版本 ≤ 1 个 → 就是它（或默认版本）——**这一条同时省掉了下面那次探测取数**，
   绝大多数报表只有一版，不该为版本功能多打一次 SQL；
3. **条件筛选**：一个版本的条件要**全部**满足才算匹配（没配条件 = 无条件匹配）；匹配到的版本里
   只留**条件数最多**的那一批（**特异度优先**：「类型=A 且 区域=华东」压过「类型=A」，
   都压过无条件的那一版 —— 没有这一条的话配了条件的版本会跟兜底版平起平坐，谁赢要看生效时间，
   配条件这件事就白做了）。一批都不匹配（每一版都带条件而这次数据一条也不满足）→ `fallback`；
4. 取时间判定值：`field` 主接口**第一行**的该字段（拆分报表是**这一条数据**的该字段）/
   `param` 报表参数 / `now` 渲染当日；
5. 归一化成 `LocalDateTime`：`java.sql.Date/Timestamp`、`LocalDate(Time)`、`java.util.Date`、
   epoch 毫秒（数字或纯数字串）、字符串 `yyyy-MM-dd[ HH:mm[:ss]]`（也认 ISO 的 `T` 与毫秒尾巴）。
   解析不了当作取不到；
6. 取不到 → `fallback`：`default` 用默认版本 / `error` 抛 `BizException`（消息里带字段名）。
   **但条件已经把那一批筛成唯一一版时就用它** —— 条件够定案了，不该再被时间这一维推翻；
7. 命中 = 该批里 `effective_from ≤ 判定值` 的最后一个（**左闭右开**）；一个都不满足
   （早于所有起点）→ `effective_from` 为 NULL 的那一版；没有 NULL 版 → 默认版本
   （**条件筛过的那一批则退回本批里最早的一版** —— 退回默认版本等于把已经命中的条件推翻）。

**`field` 留空（只按条件选）时时间这一维直接不参与**，取匹配那批里生效最晚的一版。
只有条件、没有时间是完全合法的配法。

**探测取数的时机跟着两维走**：时间判定值取自主接口字段、**或哪一版的条件取自主接口字段**时才探
（`ReportVersionResolver#needsRow`）。条件全取自参数、判定依据是 `param`/`now` 时一次都不探。

`versionMatch` 那句话会把命中的条件一并写上：
`条件[order_type=B 且 area∈华东,华南] + order_date=2026-06-01 命中 v3`。

**先有鸡还是先有蛋**：判定值来自数据，而模板由判定值决定 —— 探测这一次取数不能白取。
`RenderServiceImpl` 给取数函数包了一层按 `(code, params)` 记忆的 `engine/CachingDataFetcher`
（只活一次渲染），探测用它、引擎也用它，主接口只被打一次。
**缓存 key 必须含 params 且做不可变拷贝** —— 漏掉 params 的话，父子关联里
「主表每行查一次子表」会全部串成第一行的明细。`LinkedDataFetcher` / `perRowFetcher`
仍然包在它外面，行为不变。

**`splitMode=perRow` / `perRowPage` 时版本是逐行选的**（判定**跟着行走** —— 时间判定依据是主接口
字段，或哪一版的匹配条件取主接口字段；且没有显式指定版本、确实有多个启用版本）：
「打印 3 月到 9 月的一批单据，每张按自己的下单日期用对应版式」「一批单据里类型 A 和类型 B
各出各的版式」。
做法照旧是**换函数** —— `ReportRenderEngine#render` 多收一个
`Function<row, ReportContentDTO> versionPicker`（默认 null = 全用同一份），每行用它换模板；
基准 content（决定 `splitMode` / `primaryDataset` / `datasetLinks` / 参数）恒取默认版本，
换掉的只有**模板与打印设置**，模板按版本 parse 一次缓存。引擎依然不知道「版本」是什么。

**由此结果 sheet 数不再是「模板张数 × 数据条数」**（各版模板张数可以不同），
`renderedIndex % 模板张数` 那套推算彻底失效，光有 `mzTemplateIndex` 也说不清是哪一版的第几张
—— 所以打印设置由引擎随结果一起产出，见下面的 `sheetPageConfigs`。

`RenderResult`：
```json
{
  "sheets": [ /* 填充后的 FortuneSheet Sheet[] */ ],
  "elapsed": 12, "message": null, "total": 1280,
  "versionId": "a1b2…", "versionNo": 2, "versionName": null,
  "versionMatch": "order_date=2026-06-01 命中 v2",
  "sheetPageConfigs": [ { "paperSize": "A4", "orientation": "portrait" } ]
}
```

`total` = **主接口**（`content.primaryDataset`）自报的总条数，前端据此画分页条。
为 null 的三种情况：没设主接口、主接口是集合型、主接口这次没被用到（模板里没有引用它的单元格）。

`versionId / versionNo / versionName` = 这一次用的是哪一版；`versionMatch` 是给人看的一句话
（预览页显示「当前版本 v2（order_date=2026-06-01 命中）」）。逐行选版本时整份报表没有单一版本，
只有 `versionMatch` 说明「按每条数据的 xxx 逐行选版式」。免保存预览只回显请求里带的 `versionId`。

**`sheetPageConfigs` 与 `sheets` 一一对应**，是每张**结果** sheet 已经解析好的打印设置。
**导出与预览取打印设置一律优先认它**（后端 `RenderServiceImpl#pageConfigAt`、前端
`utils/print.js#pageConfigOfResult`），认不到（老结果、手工构造的）才退回下面那条按
`mzTemplateIndex` 推算的路。

**图片单元格的输出**：`type=img/base64/barcode/qrcode` 的格子在 celldata 里多带一个 `v.mzImg`，
它是图片在整条链路上唯一的载体（`v`/`m` 都是空串）：

```json
{ "r": 3, "c": 1, "v": { "v": "", "m": "", "mzImg": { "src": "https://.../a.png" } } }
```

`src` 已经归一化好，可以直接当 `<img src>` 用（`base64` 补成了 `data:image/png;base64,...`，
两种条码是现画的一张 PNG，同样以 `data:` URI 给出）。
FortuneSheet 的单元格画不了图片，所以**前端要把它摊成浮动图片**（`sheet.images`，坐标由行高列宽
累加得到，框就是该格/该合并块），见 `utils/cellImage.js#applyCellImages` ——
图片在这个框里**等比例居中**是靠一条全局 CSS（`styles/index.css` 里给
`.luckysheet-modal-dialog-image img` 加 `object-fit: contain`）：FortuneSheet 给 `<img>` 写死了
宽高，不加这一条就是拉伸变形，与导出的三种格式对不上 ——
预览页与设计器预览抽屉在拿到渲染结果后各调一次（预览页要排在 `applyWrapRowHeights` **之后**，
撑高过的行会让它下面的图片整体下移）。导出那头不看 `sheet.images`，只认 `v.mzImg`，
见 §4 的「图片的四条导出路」。

## 5. 单元格表达式语法

| 语法 | 含义 |
|---|---|
| `#{datasetCode.fieldName}` | 数据单元格，按 cellConfig 的 expandType 扩展；图片单元格（img/base64）写的也是它 |
| `${paramName}` | 报表参数替换（文本内） |
| `!{aviator 表达式}` | 服务端 Aviator 计算，可用函数见 §6 |
| `=SUM(B2:B5)` | 原生公式，扩展时自动偏移引用 |
| 纯文本 | 原样输出 |

**数据单元格的前后缀**：格子里 `#{code.field}` 前后还写了别的字时（`#{orders.amount}元`、
`共 #{orders.cnt} 件`），值**填回占位符原来的位置**，前后缀原样保留（前后缀里的
`${param}` / 别的数据集占位符照常替换）。此时整格是一段文本：值先按 `formatType/formatPattern`
格式化，再与前后缀拼起来，`v` 与 `m` 同为这段文本、`ct` 退回 `General`，
所以 Excel 里它是文本而不是数值。**整格只有一个占位符时不走这条** —— 值保持数字/日期原始类型，
Excel 导出才写得成数值、公式才算得动。值为空（字段没数据、聚合格的非首行）时整格留空，
不会单剩一个「元」。

数据集 SQL 内使用 `${paramName}`，服务端替换为 JDBC `?` 占位并顺序绑定（防注入）；
`$!{paramName}` 表示直接字符串拼接（用于动态表名/排序，需谨慎，会做标识符白名单校验）。
api 类型数据集的**接口地址**里同样写 `${paramName}`，替换时对值做 URL 编码；
地址里留下没人给值的 `${}` 会直接报「接口地址缺少参数值: xxx」。

### 参数值从哪来

四个来源，**后面的覆盖前面的**：

1. 数据集参数（`mz_dataset_param`）的默认值；
2. **全局参数（`mz_param`）的默认值**；
3. 报表参数（`content.params`）的默认值；
4. 渲染请求 `{params:{...}}` 里传进来的值。

全局参数与报表参数**先合成一份定义再进引擎**（`ReportParamDTO#merge`，全局在前、报表在后）：
同名时**报表那条整条覆盖全局那条**（类型/控件/必填/选项一起换，不是只换默认值）——
只换默认值的话会出现「显示名和控件是全局的、值却按报表的类型转」这种半拉状态。
合并只发生在 `RenderServiceImpl` 一处（`parseContent` 与免保存预览两个入口），
**渲染引擎不知道有全局参数这回事**，它照旧只看 `content.params`；
`GET /report/{id}/params` 返回的也是合并后的这一份，所以参数表单里全局参数就是普通参数。

停用（`status=0`）的全局参数不参与合并 —— 等于它不存在，报表里同名的那条照常生效。

第 3 条里包含**预览/设计器地址上的 query 参数**：前端把 `/preview/{id}?id=11233` 的 query
一并提交（`utils/params.js#queryParams`），**报表里没声明过的参数也照传** —— 于是数据集里
`${id}`（SQL 绑定、接口地址替换都算）直接就能取到外部传来的值，嵌入方不必先在报表里
声明一遍参数。声明过的参数则用 query 值作为参数表单的初值，用户仍可改。

未声明的参数走的是同一条安全边界：`${}` 是 JDBC 占位绑定，`$!{}` 过标识符白名单，
接口地址做 URL 编码，所以「外部随便传」不会变成注入面。

### 保留参数名：pageNo / pageSize / versionId

预览页的分页条把当前页码、每页条数按这两个名字塞进渲染请求的 `params`，
和别的参数一样透传给**所有**数据集 —— 主接口在接口地址里用 `${pageNo}` / `${pageSize}` 接住即可，
不需要额外配置。别拿这两个名字当业务参数用。

分页条只有在**主接口是分页型数据集**时才出现（集合型没有总数，也就没有页数可言）；
翻页 = 带着新的 pageNo/pageSize 重新渲染整张报表，非主接口的数据集每页都按原样取。

`versionId` 是另一个保留名，但方向相反：设计器 / 预览页用地址保持「现在看的是哪一版」
（`/designer/{id}?versionId=xxx`），而它**不是报表参数** —— `utils/params.js#queryParams`
把它剔出去（`RESERVED_QUERY`），单独用 `queryVersionId` 取出来放进渲染请求体的 `versionId` 字段。
不剔的话它会被当成普通参数塞进 SQL 的 `${versionId}` 里去。

## 6. Aviator 可用上下文与函数

上下文变量：
- `params` — Map，报表参数
- `row` — 当前展开行的数据 Map（仅在扩展带内）
- `ds` — Map<datasetCode, List<Map>> 全量数据

内置函数（`com.muzhou.report.engine.function` 注册）：
`SUM(range)` `AVG(range)` `MAX(range)` `MIN(range)` `COUNT(range)` `IF(c,a,b)` `ROUND(n,d)`
`CONCAT(...)` `DATEFMT(date,pattern)` `NUMFMT(num,pattern)` `NVL(a,b)` `ABS(n)` `PERCENT(a,b)`
`range` 为字符串形如 `"A2:A10"`，在**渲染后的网格**上求值。

## 7. 渲染引擎算法（必须按此实现）

1. **解析**：`sheets[i].celldata` → `TemplateCell{r,c,rawValue,config}`；合并单元格信息取自 `sheets[i].config.merge`。
2. **取数**：收集所有 cellConfig 用到的 `datasetCode` → 逐个执行数据集（带报表参数映射到数据集参数，同名传递）→ `Map<String, List<Map<String,Object>>>`。
   `datasetCode` 按 §3.2 的作用范围解析：先找当前报表的内部数据集，再找公共数据集。
3. **纵向扩展（按行带）**：
   - 从上往下扫描行；若某行含 `expandType=down` 的数据单元格，则该行为一个**扩展行带**，其数据集取该行首个 down 单元格的 `datasetCode`，行数 `n = data.size()`。
   - 该行复制为 n 行（n=0 时保留 1 行并置空）；行带内所有单元格随之复制：`data` 单元格填对应行字段值；`formula`/文本单元格复制并做行号偏移；`aggregate != none` 的单元格**不参与扩展**（在整个数据集上聚合，只输出一行值）。
   - 复制后，其下方所有行的 `r` 偏移 `n-1`；`config.merge` / `rowlen` / `borderInfo` 同步偏移。
     `borderInfo` 有两种形态：`rangeType=range`（区域 + 边框类型，行带那一行的区间**拉长**到 n 行）与
     `rangeType=cell`（逐格四条边，行带那一行上的**复制成 n 条**，只挪坐标的话只有第一行有边框）。
   - **行带自己那一行上的合并跟着复制 n 份**（每条数据一份，`rs` 不变），不是纵向拉长成一个跨 n 行的合并
     —— 拉长的话只有第一条数据看得见。只有**跨了行带以外的模板行**的合并（`rs>1`）才按展开后的行数拉长。
4. **横向扩展**：行带内若有 `expandType=right` 单元格，在该单元格所在列向右复制 n 列，右侧列 `c` 偏移 `n-1`，`columnlen` 同步偏移。
5. **分组合并**：行带内 `groupType=group` 的列，纵向相邻且值相等的单元格合并（写入 `config.merge`），显示首行值。
   合并区最后要按 `config.merge` **重建每个格子的 `mc` 标记**（首格 `{r,c,rs,cs}`，被盖住的格子 `{r,c}` 指回首格）：
   FortuneSheet 画合并要两份数据对得上，而模板里的 `mc` 记的是模板坐标、分组合并更是模板里没有的。
6. **公式求值**：扩展完成后，对 `!{}` 单元格用 Aviator 求值（可引用 `A1:A10` 区间）；对 `=` 原生公式做 A1 引用偏移后原样保留给 FortuneSheet 计算。
7. **格式化**：按 `formatType/formatPattern` 生成显示文本 `m`，原始值放 `v`，
   格式串同时写进 `ct`（`{fa, t}`）交给导出那头 —— 所以货币符号必须在模板里，
   金额中文大写则整格标成文本（`{fa:"@",t:"s"}`，`v` 仍是数字），见 §4。
   数据单元格的文本里带前后缀时（见 §5「数据单元格的前后缀」）改为「先格式化、再拼前后缀」，
   拼出来的整段文本同时写进 `v` 与 `m`，`ct` 用 `General`。

`content.datasetLinks` 配了父子关联时，**第 2 步套一层取数装饰器**（`engine/LinkedDataFetcher`），
1、3~8 步一律不变：要子表数据时先取主表（主表自己也可能是别人的子表，递归），
主表每一行调一次子表（`mappings` 里的主表字段值 → 子表参数，覆盖同名报表参数），
N 次结果拼成一份，主表这一行的字段合进它的每条子行（同名字段子表优先）。
主表在模板里也被用到时只取一次（装饰器内按 code 缓存，实例只活一次渲染）；
主表行数超 `muzhou.report.max-link-rows` 报错，成环报错，一个子表挂两个主表报错。

`content.splitMode=perRow` / `perRowPage` 时在**第 2 步之外**再套一层：先取一次主接口拿到 N 行，
然后对每一行把 1~7 步整份跑一遍，跑的时候**换掉取数函数** —— 主接口只返回当前这一行，
其它数据集返回全量且只取一次（缓存复用，否则 N 条数据会把从表接口打 N 遍）。
扩展/公式/格式化一步没变，「一行数据一张单据」在模板里就是普通的取数。
拆出来的 sheet 要重新编 `id`（模板 id + 序号）、`order`、`status`（只有第一张是 1）与 `name`。
两者叠加时**父子关联套在 perRow 的取数函数外面**：子表向里问主表要数据，拿到的是当前这一行，
所以「这张单据的明细」自然只查这一行的；也因此**子表不能进 perRow 那个跨行复用的缓存**
（它每张 sheet 都不一样）。

拆分时还可以**逐行换版式**（见 §4.1「版本」）：`render` 多收一个
`Function<row, ReportContentDTO> versionPicker`，每行用它换模板与打印设置，取数那套一个字没改。
于是每一份出自哪张模板、该用哪份打印设置由引擎一路记着，`SheetConcat` 也不再按
「第 i 份出自第 i%m 张模板」推算（各版模板张数可以不同，那个前提会崩）。

`perRowPage` 走的是上面同一段代码，只在**出口**多一道拼接（`engine/SheetConcat`）：
**按渲染出来的原顺序（行优先：一条数据的几张模板挨着）自上而下摞**，第 i 份整体下移前 i-1 份的
高度；**遇到打印设置与上一份不同就另起一张 sheet**（一张 sheet 只放得下一套页面设置，
不比对的话第 2 张模板设的横向会被前一份的纵向盖掉）。列宽逐列取各模板最大值（一列只有一个宽度）。
每张结果 sheet 记下自己出自哪张模板（`mzTemplateIndex`），打印设置照它取。
要偏移的是 `celldata[].r`、`v.mc.r`（合并区要 `config.merge` 与格子上的 `mc` 两份对得上）、
`v.f` 里的原生公式引用、`config.merge` / `rowlen` / `borderInfo` 的行坐标
（`range[].row` 与逐格边框的 `value.row_index` **两种形态都要偏**）——
**漏一样就是这里最典型的 bug**。每份高度取 celldata / merge / rowlen / borderInfo 四处最大，
只看 celldata 会吃掉「有边框没有值」的尾行。`!{}` 公式不必特殊处理：它在每一份自己的网格上
求值完就落成字面值了，所以「本单据小计」天然只统计本单据。
8. **输出**：重新组装为 FortuneSheet `Sheet` 结构（`celldata/config/row/column`）返回。

## 8. 前端约定

- 基础路径 `import.meta.env.VITE_API_BASE` 默认 `/api`，vite dev proxy → `http://localhost:8080`。
- 路由：`/`→`/report`，`/datasource`，`/dataset`，`/report`，`/designer/:id`，`/preview/:id`。
- Pinia store：`stores/designer.js`（当前报表 content、cellConfigs、选中单元格、数据集列表）、`stores/app.js`。
- api 模块：`api/datasource.js` `api/dataset.js` `api/report.js` `api/render.js`，均 `export` 与 §3 一一对应的函数。
- UI 库：Element Plus（全量引入）；图标 `@element-plus/icons-vue`。
- FortuneSheet：官方只有 React 包，`components/FortuneSheet.vue` 是 React 根挂载的桥接层，对外是纯 Vue 的 props/emits/expose。
  **`data` 只在工作簿挂载时读取一次**，异步加载的内容必须等数据就绪后再挂载组件，或调用 `reload(sheets)` 重建；
  `getAllSheets()`/`change` 给出的是运行时结构（二维 `data`），持久化前必须转回 `celldata`（见 §4）。
  纸张边界/打印区域不画在 canvas 上，而是 `#overlay` 具名插槽里的 DOM 叠加层（`PrintAreaOverlay`）：
  线条完整、恒在表格之上、不受单元格是否渲染影响；但坐标取自渲染钩子
  （`beforeRenderCellArea` 清缓冲 + `afterRenderCell` 采样每列/行的实际画布像素），
  自己按行高列宽 + 滚动 + 缩放推算总会有几像素误差。
- 拖拽：左侧字段列表用**原生 HTML5 拖放**（`DatasetFieldList.vue`），拖到工作簿容器 `@drop` 时
  取 **当前选中单元格** 写入 `#{code.field}` 并生成 cellConfig，同时把字段名称写到它上一格当表头
  （上一格为空才写）。负载放在自定义 MIME `application/x-mz-field` 里（`utils/sheet.js#FIELD_DRAG_MIME`），
  `text/plain` 只是兜底 —— **别再把这个列表套进 vuedraggable / Sortable**：Sortable 的 dragstart
  监听挂在列表根节点上（冒泡时晚于条目自己的处理器），默认的 `setData` 会用
  `dataTransfer.setData('Text', dragEl.textContent)` 把负载覆盖成可见文字，落格那头
  `JSON.parse` 一抛错就是「拖过去什么都没有」。这个列表不排序也不收外部条目，本来就不需要它。
