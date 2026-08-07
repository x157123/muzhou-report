# 报表版本设计方案（**已实现**，Phase 1 + Phase 2）

> 本文是**设计方案**，不是契约。**落地结果以 `docs/CONTRACT.md` 为准**
> （§2 `mz_report_version` / §3.3 版本接口 / §3.4 `versionId` / §4.1 版本 / §5 保留参数名，
> 均已并入）；日常改动看 CLAUDE.md 的「报表版本」一节。本文保留下来是为了记着**为什么**这么设计。
>
> ## 落地时与本文不同的六处
>
> 1. **§6.4 演示数据脚本**：本仓库没有 `db/data-report.sql`，演示数据是按表拆开的 H2 快照
>    （`db/mz_report.sql` 等）且是纯 `INSERT` + `continue-on-error`，只对空库生效，
>    不存在「MERGE 按 id 覆盖把改动改回去」的问题 —— 所以没有再往脚本里写死版本行，
>    演示报表靠懒迁移拿到 v1。建表/加列一律写成 `IF NOT EXISTS`。
> 2. **发号要含已删除的行**：`deleted` 是逻辑删除，而 `uk_version_report_no` 管着全部行 ——
>    删掉 v3 再建一个就撞车。所以走手写 SQL 的 `MzReportVersionMapper#maxVersionNo`。
> 3. **§7「保存版本时校验必须与默认版本一致」改成不拦、只体检**：主接口/输出方式/父子关联
>    不一致时渲染以默认版本为准，硬拦保存会把人卡在半路。这条并进了那个
>    `GET /{id}/version/{versionId}/check`（同时扫数据集/字段还在不在，即 §7 说的「校验」按钮）。
> 4. **Phase 2 的还债做成「加一条、老的降级为兜底」**：引擎在 `RenderResult.sheetPageConfigs` 里
>    直接产出每张结果 sheet 的打印设置，导出与预览优先认它；`pageConfigOfSheet` /
>    `pageConfigOfRendered` 那两份重复实现**没有删掉**，留给老结果与手工构造的结果兜底。
> 5. **Phase 2 还踩到本文没提的一处**：`SheetConcat` 原来按「第 i 份出自第 i%m 张模板」推算，
>    各版模板张数不同时同样会崩。现在由调用方把每一份的模板与打印设置一路传进来。
> 6. **探测用的参数**：探测走的是「默认版本的参数定义补过默认值」的那份 params，
>    与引擎随后 `mergeParams` 出来的一致，缓存 key 才对得上（否则主接口会被打第二遍）。
>    各版本参数默认值不同时，这一点仍以默认版本为准。

## 目标

一张报表在不同时间段用不同版式：

| 时间段 | 用哪一版 |
|---|---|
| 2026-05-01 之前 | v1 |
| 2026-05-01 ~ 2026-08-01 | v2 |
| 2026-08-01 之后 | v3 |

设计器顶部下拉切换版本、可以从任一版本复制出新版本；**用哪一版由主接口的某个字段值决定**，
规则在「打印设置」里配。

## 0. 一句话结论

**版本化的是「版式」（`content`），不是数据集**：新建 `mz_report_version` 表，每行持有一份完整的
`ReportContent`；报表级新增一条「版本切换规则」（依据主接口的哪个字段 + 无法判定时怎么办），
每个版本只存**生效起始时刻**，区间由排序推导。渲染时先探一次主接口拿到判定值，选中版本再进引擎
—— 引擎本身不需要知道有版本这回事。

---

## 1. 数据模型

### 1.1 新表 `mz_report_version`

```sql
CREATE TABLE mz_report_version (
  id             varchar(32)  NOT NULL,
  report_id      varchar(32)  NOT NULL,
  version_no     int          NOT NULL,   -- 报表内唯一，展示成 v1/v2/v3
  name           varchar(50),             -- 版本名，空则显示 v{version_no}
  content        clob,                    -- 这一版完整的 ReportContent（CONTRACT §4）
  effective_from timestamp,               -- 生效起始时刻；NULL = 最早的那一版（兜底）
  is_default     int DEFAULT 0,           -- 基准版本，报表内恰好一条
  status         int DEFAULT 1,           -- 1 启用（参与自动选择）/ 0 停用（只能设计器里显式打开）
  remark         varchar(500),
  deleted        int DEFAULT 0,
  create_by varchar(50), create_time timestamp, update_time timestamp
);
CREATE UNIQUE INDEX uk_version_report_no ON mz_report_version (report_id, version_no);
CREATE INDEX idx_version_report ON mz_report_version (report_id);
```

主键同全项目规矩：`VARCHAR(32)` + MyBatis-Plus `ASSIGN_UUID`；`deleted` 走 `@TableLogic`。
切 MySQL 时 `CLOB` → `LONGTEXT`。

### 1.2 `mz_report` 新增一列

```sql
ALTER TABLE mz_report ADD COLUMN version_config varchar(500);
-- {"source":"field","field":"order_date","fallback":"default"}
```

| 字段 | 取值 | 说明 |
|---|---|---|
| `source` | `field`(默认) / `param` / `now` | 判定值从哪来：主接口字段 / 报表参数 / 渲染当日 |
| `field` | 字段名或参数名 | `source=now` 时无意义 |
| `fallback` | `default`(默认) / `error` | 判定值取不到时：用默认版本 / 直接报错 |

**为什么规则不能放进 `content`**：`content` 是按版本存的，规则放进去就成了「每个版本各有一套
怎么选自己」，逻辑成环。规则必须在报表级。

### 1.3 只存起点，区间靠排序推

用户配的是「v2 从 5/1 开始」，系统推出的是区间，**左闭右开**：

| 版本 | `effective_from` | 推导区间 |
|---|---|---|
| v1 | `NULL` | `(-∞, 2026-05-01)` |
| v2 | `2026-05-01` | `[2026-05-01, 2026-08-01)` |
| v3 | `2026-08-01` | `[2026-08-01, +∞)` |

存两端（from + to）必然出现重叠和空洞，且改一处要改两处；只存起点在结构上就不可能有洞。
**停用的版本不参与推导** —— 把 v2 停掉，它那段自动被 v1 吞掉，这正是「临时回滚版式」想要的行为。

---

## 2. 版本选择算法

`ReportVersionResolver`（纯 POJO，不依赖 Spring，可以像 `RenderEngineTest` 那样直接测）：

```
1. 显式 versionId（设计器/预览指定）→ 直接用它，含停用版本，不走下面的规则
2. 启用版本 ≤ 1 个 → 就是它（或默认版本）
3. 取判定值 value：
     source=field → probe(主接口).第一行[field]      ← 默认，单据类报表用这条
     source=param → params[field]                     （报表参数 / URL 透传的日期）
     source=now   → 渲染当日
4. value 归一化成 LocalDateTime：支持 java.sql.Date/Timestamp、LocalDate(Time)、
   epoch millis、字符串 yyyy-MM-dd[ HH:mm[:ss]]；解析不了当作取不到
5. 取不到 → fallback：default = 用默认版本 / error = BizException（说清是哪个字段没取到）
6. 命中 = effective_from ≤ value 的最后一个；一个都不满足（早于所有起点）→ effective_from
   为 NULL 的那一版；没有 NULL 版 → 默认版本
```

### 2.1 先有鸡还是先有蛋：主接口只许打一次

判定值来自数据，而模板由判定值决定 —— 探测这一次取数不能白取。做法是在 `RenderServiceImpl` 里
给现有的取数函数**再包一层按 `(code, params)` 记忆的 memo**（只活一次渲染）：

```java
// RenderServiceImpl#render
Map<String, Long> totals = new HashMap<>();
var fetcher = CachingDataFetcher.wrap(dataFetcher(reportId, totals));  // ← 新增
ReportContentDTO content = versionResolver.resolve(reportId, params, versionId, fetcher);
RenderResultDTO result = renderEngine.render(content, params, fetcher);
```

探测用它、引擎也用它，同一个 `(code, params)` 第二次命中缓存。

> **缓存 key 必须含 params，且做不可变拷贝**。漏掉 params 的话，父子关联里「主表每行查一次子表」
> 会全部串成第一行的明细 —— 这是这一层唯一容易写错的地方，要写测试锁死。
> `LinkedDataFetcher` / `perRowFetcher` 仍然包在它外面，行为不变。

---

## 3. 打印设置里的配置项

在 `frontend/src/views/designer/components/PrintConfigDialog.vue` 加一个**「版本」tab**。

不塞进「输出」tab：那里全是按 sheet 的设置，版本规则是报表级的，混在一起会让人以为能按 sheet 配。
数据绑 `store.report.versionConfig`，**不是** `store.content.*`（content 本身就是被版本化的那个东西）。

```
版本 ──────────────────────────────────────────
  判定依据   ( ) 主接口字段  ( ) 报表参数  ( ) 渲染当日
  字段        [ 下单日期 (order_date) ▾ ]     ← 取主接口 fields，同 sheetNameField 的下拉
  无法判定时  ( ) 用默认版本 v1   ( ) 直接报错

  当前生效区间（只读，实时推导）
  ┌──────┬───────────────────────────┬──────┐
  │ v1   │            ~ 2026-05-01   │ 默认 │
  │ v2   │ 2026-05-01 ~ 2026-08-01   │      │
  │ v3   │ 2026-08-01 ~              │      │
  └──────┴───────────────────────────┴──────┘
  在「版本管理」里改各版本的生效起始时间
```

**那张只读的区间表是这个面板的重点**：用户输入的是起点，脑子里想的是区间。不把推导结果摆出来，
「5 月 1 号那天到底走 v1 还是 v2」每次都要试一遍。

---

## 4. 接口变更（CONTRACT §3 增补）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/report/{id}?versionId=` | 省略 = 默认版本；`content` 返回该版本的内容 |
| GET | `/api/report/{id}/version` | 版本列表（**不含 content**，同 `/page` 不传大字段的规矩） |
| POST | `/api/report/{id}/version/{versionId}/copy` | 从某版本复制新版本 → 新 id（content 原样，`status=0` 停用，`effective_from` 空） |
| PUT | `/api/report/version` | 改版本元信息（name / effectiveFrom / status / remark） |
| POST | `/api/report/{id}/version/{versionId}/default` | 设为默认 |
| DELETE | `/api/report/version/{versionId}` | 默认版本、最后一个启用版本不许删 |
| PUT | `/api/report` | body 带 `versionId` 时，`content` 写进该版本 |

渲染四个接口（`/api/render/report/{id}` 与三个 `export/*`）以及 `/api/render/preview` 的 body
统一新增可选 `versionId`；`RenderResult` 回传 `versionId / versionNo / versionName`，
预览页显示「当前版本 v2（order_date=2026-06-01 命中）」。

> ⚠️ **`versionId` 必须走请求体，不能只挂在地址栏 query 上。**
> `utils/params.js#queryParams` 会把地址上的 query **原样透传成报表参数**（CONTRACT §5），
> `?versionId=xxx` 会被塞进 SQL 的 `${versionId}`。设计器 / 预览页想用 URL 保持版本状态，
> 就得把 `versionId` 加进**保留参数名**（和 `pageNo` / `pageSize` 一起写进 CONTRACT §5），
> 在 `queryParams` 里剔除。

---

## 5. 前端设计器交互

**工具栏**，报表名右边一个下拉：

```
未命名报表  [ v3 · 2026-08-01 起 ▾ ]  | 保存 预览 导出 ...
                ├ v1  ~2026-05-01        默认
                ├ v2  2026-05-01~08-01
                ├ v3  2026-08-01~        ● 当前
                ├ ────────────────────
                ├ 从当前版本复制新版本
                └ 版本管理…
```

- **切版本前 dirty 要拦**：保存 / 放弃 / 取消 三选一。
- 切完必须 `sheetRef.reload(newSheets)` 重挂工作簿 —— FortuneSheet 桥接的 `data` prop
  只在挂载时读一次（见 CLAUDE.md「三个必须记住的坑」之一），只改 store 画布不会跟着换；
  同时 `sheetIndex` 归 0。
- **版本管理弹窗**：表格列出 版本 / 名称 / 生效起始 / 状态 / 更新时间 / 备注，
  行操作 = 改生效时间、改名、设为默认、启停、复制、删除、单独预览。

---

## 6. 迁移与兼容

1. `db/schema-h2.sql` 加表 + 给 `mz_report` 加 `version_config` 列。
2. **懒迁移**：`ReportVersionService#ensureMigrated(reportId)` —— 该报表一条版本行都没有时，
   用 `mz_report.content` 建 v1（默认、启用、`effective_from` 空）。挂在 `getDetail` 和渲染入口，
   事务内 + 靠 `uk_version_report_no` 兜幂等。老报表零感知。
3. 迁移后 **`mz_report.content` 不再读写**，列保留，CONTRACT §2 标 deprecated。
   不做双写 —— 双写必然漂移，违背契约优先。
4. **演示数据脚本要同步**：`db/data-report.sql` 用 `MERGE INTO mz_report` 改 content，
   但版本行已存在就不会再触发懒迁移，改动不生效。所以演示报表要在脚本里直接
   `MERGE INTO mz_report_version ... KEY(id)` 给出 v1（多版本演示可以顺手给 v2）。
   这和现有那条「改演示数据要改脚本」是同源的坑，要写进 CLAUDE.md。
5. `ReportServiceImpl#copy`：复制报表时**所有版本行一起复制**（新 id，保留 version_no /
   effective_from / status / is_default），内部数据集照旧复制。漏了这条，副本会是一张没有版本的报表。
6. `ReportServiceImpl#remove`：版本行跟着逻辑删除。

---

## 7. 与现有机制的相互作用（重点）

| 机制 | 影响 | 处理 |
|---|---|---|
| `cellConfigs` / `pageConfigs` 按 sheet 下标寻址 | 无影响 | 每个版本自带完整 content，天然隔离；复制版本时整份拷贝，别「合并」 |
| 内部数据集（`mz_dataset.report_id`） | **跨版本共用，不随版本走** | 见下 |
| `content.primaryDataset` / `splitMode` / `datasetLinks` | 各版本各存一份，可能不一致 | 保存版本时**校验必须与默认版本一致**并提示；判定用的主接口以默认版本为准 |
| `pageConfigOfRendered` 的取模 | Phase 1 无影响；Phase 2 会崩 | 见 §8 |
| 导出 / 打印链路 | 漏传 = 「预览看的是 v2、导出的是 v3」 | `PreviewDrawer`、`ReportPreview`、`utils/printPdf.js`、设计器导出，四处都要透传 `versionId` |

**为什么数据集不版本化**：数据集是「取数」，版本是「版式」。数据集一旦分叉，主接口、父子关联、
分页 total、内部/公共作用域全要跟着分叉，成本是版式版本化的好几倍，而实际诉求
（「5 月起单据换个抬头 / 加一列」）几乎都是版式。

代价要认：改 SQL 会同时影响所有版本，删字段会让老版本模板里的 `#{code.field}` 取不到数。
**缓解**：版本管理页给一个「校验」按钮，扫这一版 `cellConfigs` 引用到的 `datasetCode` / `field`
是否还存在，不存在就标红。

---

## 8. 分两期做

### Phase 1 —— 整份报表选一个版本

覆盖「5 月 1 号前用 v1」这个诉求的全部。改动：新表 + 版本 CRUD + `ReportVersionResolver`
+ `CachingDataFetcher` + 设计器下拉 / 版本管理 + 打印设置版本 tab + `versionId` 透传。
**渲染引擎一行不用改。**

### Phase 2 —— `perRow` 逐行选版本（批量打印跨期单据）

「打印 3 月到 9 月的一批单据，每张按自己的下单日期用对应版式」。这才是「主表字段驱动」的完全体，
但它会捅到一个真实的坑：

- 做法沿用本项目一贯的思路 —— **换函数**：`ReportRenderEngine` 入口加一个
  `Function<row, ReportContentDTO> versionPicker`（默认 `row -> content`），`renderPerRow`
  每行用它换模板；基准 content（决定 `splitMode` / `primaryDataset` / `datasetLinks` / 参数）
  恒取默认版本；templates 按版本 parse 一次缓存。引擎依然不依赖 Spring。
- **坑**：`pageConfigOfRendered` 靠 `renderedIndex % 模板张数` 映射回模板下标，前提是
  「结果 sheet 数 = 模板张数 × N」。不同版本的模板张数可能不同，这个前提直接崩。
- **修法（顺带还债）**：让引擎在 `RenderResult` 里直接产出 `sheetPageConfigs` —— 每张结果 sheet
  一份已解析好的打印设置，导出和预览按下标取。取模逻辑收进引擎内部，
  `ReportContentDTO#pageConfigOfRendered` 和 `utils/print.js` 里那两份
  「改一处要改另一处」的重复实现同时消掉。

---

## 9. 测试点

纯 POJO 测试（照 `RenderEngineTest` 的路子，不启 Spring 上下文）：

- **边界**：`2026-05-01 00:00:00` 归 v2（左闭右开），`2026-04-30 23:59:59` 归 v1。
- 早于所有起点 → `effective_from=NULL` 的那一版；没有 NULL 版 → 默认版本。
- **停用 v2 后，5/1 ~ 8/1 落回 v1**（区间被前一版吞掉）。
- 判定字段取不到 / 解析不了 → `fallback=default` 走默认版；`fallback=error` 抛 `BizException`
  且消息里带字段名。
- 日期归一化：`java.sql.Date` / `Timestamp` / `LocalDate` / `String` / epoch millis 各一条。
- **`CachingDataFetcher`：主接口只被打一次**（计数 fetcher 断言）、
  **子表按 params 区分、不串行**（同 code 不同 params 各查一次）。
- Phase 2：逐行不同版本时每张结果 sheet 的打印设置正确（模板张数不同 + 横纵混排各一条）。

---

## 10. 已知边界

- `effective_from` 与判定值一律按 **LocalDateTime** 比较（不带时区）；字符串值只认
  `yyyy-MM-dd[ HH:mm[:ss]]`。跨时区部署要另议。
- `source=field` 在 `single` 模式下取的是**主接口第一行**的字段值。单据类报表（perRow）语义清晰；
  汇总类报表第一行的日期未必代表整张表 —— 这类场景建议改用 `source=param` 或 `source=now`。
- 两个人同时在设计器改同一版本仍是「后写覆盖」（与现状一致）。版本让这件事更容易撞，
  需要的话给 `mz_report_version.update_time` 加乐观锁。
- 版本数量不设上限，但版本列表接口不返回 content，设计器一次只加载一版，成本与单版本时相同。
