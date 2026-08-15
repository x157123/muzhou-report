# `feat/sheet-level-split` 迁移说明

> **这份文档是干什么的**：把分支 `feat/sheet-level-split` 上的全部改动整理成一份可以照着做的清单，
> 用于把这套能力搬进另一个（从本项目 fork 或复制出去的）工程。
> 逐文件、逐方法列出改了什么、为什么这么改、以及照搬时最容易漏掉的点。
>
> 分支上的四个提交（时间顺序，可直接 cherry-pick）：
>
> | commit | 说明 |
> |---|---|
> | `51dee83` | 拆分下放到模板级：清单列表 + 每条数据一份详情（核心） |
> | `f843212` | 清单页跨段时 sheet 名带上模板名，补两个导出契约用例 |
> | `800ffdb` | `SheetConcat` 那个按 `i%m` 推算的老重载注明只剩测试在用（纯注释） |
> | `aa3079d` | 多 sheet 配置界面优化：作用范围三选一、新增「工作表」一览页签 |
>
> 合计 14 个文件、约 950 行新增。**没有数据库变更**（新配置项落在 `mz_report_version.content`
> 这个 JSON 列里），**没有接口签名变更**（`content` 结构向后兼容）。

---

## 0. 一句话说清这次做了什么

**「按主接口每条数据拆单据」从「整份报表一刀切」变成「按模板逐张决定谁跟着拆」。**

诉求是这种报表：**第一张 sheet 是全部数据的清单列表，第二张（及以后）是每条数据各一份详情**。
老代码里 `splitMode=perRow/perRowPage` 会把**所有**模板都复制 N 遍、每份只喂一行数据 ——
清单页也被复制成 N 份、每份只有一行，就不成其为清单了。

做法：新增 `content.sheetSplits`（按模板下标存 `once` = 不跟着拆），
引擎把模板按这个标记切成**连续的几段**，`once` 段整段只渲染一次（主接口给**全量**数据），
其余段整段按行拆。

### 行为对照

| 场景（模板：清单 + 详情，主接口 3 行） | 改之前 | 改之后（清单标 `once`） |
|---|---|---|
| `splitMode=perRow` | 6 张 sheet：`SO-1-清单 / SO-1-详情 / SO-2-清单 / …`，每张清单只有 1 行数据 | 4 张：`清单（含 3 行）/ SO-1 / SO-2 / SO-3` |
| `splitMode=perRowPage` | 1~2 张（按打印设置分组），清单与详情混在一起摞 | 2 张：`清单` 一张 + `详情` 一张（3 份首尾相接、一份一页） |
| 页码 `${page}/${pages}` | 清单跟着单据连续编号 | 清单**自成一份单据**，从「第 1 页」数起 |
| 老报表（没有 `sheetSplits`） | —— | **一字不变**（缺省 = 全部跟着拆） |

---

## 1. 数据结构变更：`content.sheetSplits`

唯一的新增字段，落在 `ReportContent`（`mz_report_version.content` 的 JSON）里：

```jsonc
{
  // …原有字段…
  "primaryDataset": "orders",
  "splitMode": "perRowPage",
  "sheetNameField": "order_no",

  // 【新增】哪几张模板跟着拆，key = 模板下标（字符串，与 pageConfigs 同一套寻址）
  //   "once"  = 不跟着拆：整份只渲染一次，主接口给它**全量**数据（清单列表页）
  //   缺省/其它值 = 跟着拆（默认）
  // 只存改过的那些；老报表一项都没有 = 全部跟着拆，行为不变
  "sheetSplits": { "0": "once" }
}
```

三条必须记住的规矩：

1. **key 是模板下标，不是 sheet id 也不是 order** —— 与 `cellConfigs`（`${sheetIndex}_${r}_${c}`）、
   `pageConfigs` 完全同一套寻址。**因此 sheet 增删时必须跟着搬 key**（见 §3.3）。
2. **只存 `once`**，「跟着拆」是缺省。存一堆 `perRow` 只会让 content 变脏，也让
   「这张有没有特殊设置」不好判断。前后端各有一处归一（`splitsSheet` / `normalizeSheetSplits`）。
3. **不需要洗数据、不需要 DB 迁移**。老 content 没有这个字段 → 反序列化成空 map →
   `splitsSheet(i)` 恒为 true → 走老路径。

它随 content 一起被版本化、随报表导入导出包一起走 —— **这两处都不用改**（整份 content 搬运）。

---

## 2. 后端改动

### 2.1 `dto/ReportContentDTO.java`

**新增字段 + 一个判定方法：**

```java
/**
 * <b>哪几张模板参与按行拆分</b>，key = 模板下标（字符串，与 pageConfigs 同一套寻址），
 * 值 {@code once} = 不参与（整份只渲染一次，主接口拿<b>全量</b>数据）/ 缺省 = 参与。
 */
private Map<String, String> sheetSplits = new LinkedHashMap<>();

/** 第 sheetIndex 张模板参不参与按行拆分（缺省参与）。 */
public boolean splitsSheet(int sheetIndex) {
    String v = sheetSplits == null ? null : sheetSplits.get(String.valueOf(sheetIndex));
    return !"once".equals(v);
}
```

**`splitByRow()` 加一条「真有模板参与拆分」：**

```java
public boolean splitByRow() {
    if (!("perRow".equals(splitMode) || "perRowPage".equals(splitMode))
            || primaryDataset == null || primaryDataset.isBlank()) {
        return false;
    }
    int count = sheets == null ? 0 : sheets.size();
    for (int i = 0; i < count; i++) {
        if (splitsSheet(i)) {
            return true;
        }
    }
    return false;
}
```

> **为什么**：整份都标了 `once` 时拆分是恒等变换，直接退回普通渲染那条路 ——
> 少一次主接口探测，也少给下游一堆「一份单据」的标记。
>
> **顺带的连锁**：`RenderServiceImpl#perRowVersioning` 判的就是 `base.splitByRow()`，
> 于是「全部标 once」的报表自动退回「整份定一版」而不是逐行选版本。这是对的，不必额外处理。

`pageConfigOfRendered`（按 `renderedIndex % 模板张数` 推算的**兜底**路径）只加了一段注释：
混着 `once` 模板时这个推算同样不成立。**逻辑没动** —— 那条路只在结果既没有
`sheetPageConfigs` 又没有 `mzTemplateIndex` 时才走得到（手工构造的结果／老数据）。

### 2.2 `engine/ReportRenderEngine.java`（核心，改动最大）

#### 2.2.1 新增四个私有成员

```java
/** 一段**连续**的模板：整段按主接口逐行拆（perRow），或整段各只渲染一次（清单页）。 */
private record Segment(boolean perRow, List<SheetTemplate> templates) {
}

/** 按 content.sheetSplits 把模板切成连续的几段（相邻同类合并）。 */
private List<Segment> segments(ReportContentDTO content, List<SheetTemplate> templates) {
    List<Segment> out = new ArrayList<>();
    for (SheetTemplate st : templates) {
        boolean perRow = content.splitsSheet(st.getSheetIndex());
        if (out.isEmpty() || out.get(out.size() - 1).perRow() != perRow) {
            out.add(new Segment(perRow, new ArrayList<>()));
        }
        out.get(out.size() - 1).templates().add(st);
    }
    return out;
}

/** 这一版的第 perRowSeq 个按行拆分的段是哪几张模板（逐行换版式时按段序号配对）。 */
private List<SheetTemplate> perRowSegment(List<Segment> segs, int perRowSeq) {
    int seq = -1;
    for (Segment s : segs) {
        if (s.perRow() && ++seq == perRowSeq) {
            return s.templates();
        }
    }
    return List.of();
}

/** 这一版里跟着拆的模板一共几张（= 一条数据出几张单据），跨段累加。 */
private int perRowCount(List<Segment> segs) {
    int n = 0;
    for (Segment s : segs) {
        if (s.perRow()) {
            n += s.templates().size();
        }
    }
    return n;
}
```

> **为什么按「连续段」而不是各标各的散着渲染**：输出顺序得说得清。按模板下标顺序走一遍，
> 遇到不拆的就出一份、遇到连着的几张拆的就整段展开 N 遍。
> 「清单 + 明细 + 附页」（清单标 once）出来是
> 「清单、(数据1 明细/附页)、(数据2 明细/附页)…」—— 一条数据的几张纸仍然挨在一起。
>
> 把标记放在**最后**一张模板上，出来就是「每条数据的详情…、末尾一张汇总」。

#### 2.2.2 清单段专用的取数函数

```java
/**
 * 清单段的取数函数：主接口还**全量**，其它数据集与单据段共用同一份缓存。
 *
 * 主接口直接还手上那份 rows —— 拆分前已经取过一次了，再打一遍既慢又可能因为
 * 分页/时间点拿到不一样的数据，清单与单据就对不上号了。
 */
private BiFunction<String, Map<String, Object>, List<Map<String, Object>>> wholeFetcher(
        String primary, List<Map<String, Object>> rows,
        Map<String, List<Map<String, Object>>> shared,
        BiFunction<String, Map<String, Object>, List<Map<String, Object>>> dataFetcher) {
    return (code, p) -> primary.equals(code) ? rows
            : shared.computeIfAbsent(code, c -> fetchOne(c, p, dataFetcher));
}
```

**这是「换取数函数」这一招的第三次复用**（前两次：`perRow` 把主接口锁成单行、
`LinkedDataFetcher` 做父子关联）。引擎的扩展/公式/格式化一步都不知道有「清单页」这回事。

#### 2.2.3 `renderPerRow` 重构为「按段渲染」

改动要点（完整代码见分支上的文件）：

```java
// ① 取完主接口之后、空数据分支之前，先切段
List<Segment> baseSegments = segments(content, templates);

// ② 空数据分支：照段输出，并记下每份出自第几段（有数据时段间是断开的，没数据时也得断开）
for (int segIndex = 0; segIndex < baseSegments.size(); segIndex++) {
    for (SheetTemplate st : baseSegments.get(segIndex).templates()) {
        …
        segmentIds.add(segIndex);
    }
}

// ③ 主循环：外层遍历段，perRow 段内层才遍历行
int doc = 0;            // 全局递增的单据号（不再等于行下标）
int perRowSeq = -1;     // 这是第几个按行拆的段
boolean warnedSegment = false;

for (int segIndex = 0; segIndex < baseSegments.size(); segIndex++) {
    Segment seg = baseSegments.get(segIndex);

    if (!seg.perRow()) {                       // ---- 清单段：整段只渲染一次 ----
        Map<String, List<Map<String, Object>>> datasets = fetchDatasets(seg.templates(), params,
                LinkedDataFetcher.wrap(links, wholeFetcher(primary, rows, shared, dataFetcher),
                        props.getMaxLinkRows()));
        for (SheetTemplate st : seg.templates()) {
            Map<String, Object> sheet = toSheet(st, expandProcessor.process(st, datasets, params));
            // 只出一份，id 不会撞车，照用模板的；order/status 跟着结果顺序重编
            sheet.put("order", outSheets.size());
            sheet.put("status", outSheets.isEmpty() ? 1 : 0);
            sheet.put("name", uniqueName(st.getName(), usedNames));
            outSheets.add(sheet);
            sheetTemplates.add(st);
            pageConfigs.add(content.pageConfigOf(st.getSheetIndex()));
            docIndexes.add(doc++);             // 清单自成一份单据
            docNames.add(st.getName());
            segmentIds.add(segIndex);
        }
        continue;
    }

    perRowSeq++;                               // ---- 单据段：整段对每一行渲染一遍 ----
    for (int i = 0; i < rows.size(); i++) {
        …
        List<Segment> rowSegments = rowContent == content ? baseSegments
                : segmentCache.computeIfAbsent(rowContent, c -> segments(c, rowTemplates));
        List<SheetTemplate> segTemplates = perRowSegment(rowSegments, perRowSeq);
        if (segTemplates.isEmpty()) {          // 那一版没有这么多 perRow 段 → 跳过并 warn 一次
            if (!warnedSegment) { log.warn(…); warnedSegment = true; }
            continue;
        }
        // 取数范围从「全部模板」收窄到「本段模板」（顺带修了逐行换版式时的一个既有 bug，见下）
        Map<String, List<Map<String, Object>>> datasets = fetchDatasets(segTemplates, params,
                LinkedDataFetcher.wrap(links, perRowFetcher, props.getMaxLinkRows()));
        …
        for (SheetTemplate st : segTemplates) {
            …
            // 补不补模板名看「这一条数据一共出几张单据」，不是本段几张
            sheet.put("name", splitSheetName(docName, st, perRowCount(rowSegments), usedNames));
            docIndexes.add(doc);
            segmentIds.add(segIndex);
        }
        doc++;
    }
}
```

**四个改动点单独拎出来说：**

| 改动 | 老代码 | 新代码 | 为什么 |
|---|---|---|---|
| `docIndexes` 的含义 | 行下标 `i` | **全局递增的单据号** `doc` | 清单页也要各自算一份单据；同一行落在被清单隔开的两个段里时也是两份（纸都被隔开了，页码接着数没有意义）。`markDocBreaks` 只比对相邻两份号相不相同，编号怎么来的不管 |
| `fetchDatasets` 的模板范围 | `templates`（基准全部） | `segTemplates`（本段的） | 顺带修了一个既有 bug：逐行换版式时收集数据集 code 用的是**基准**模板，那一版新引用的数据集会取不到数 |
| sheet 名的 `templateCount` | `rowTemplates.size()` | `perRowCount(rowSegments)` | 清单夹在中间时（详情A / 清单 / 详情B）两个段各只有一张，按段数判就都叫「SO-1」，去重后成了「SO-1」和「SO-1(2)」，分不出哪张是哪张。没有清单页时 `perRowCount` 恒等于模板张数，**与老行为一字不差** |
| 新增 `segmentIds` | —— | 每份出自第几段 | 拼接屏障，见下 |

#### 2.2.4 拼接屏障：`ConcatKey`

```java
/**
 * 拼接的判据：段号 + 打印设置。SheetConcat 只对它做 equals，
 * 所以段一变必定另起一张 sheet（清单不与单据摞在一起），段内仍照打印设置比对。
 */
private record ConcatKey(int segment, PageConfigDTO setup) {
}

private List<Object> concatKeys(List<PageConfigDTO> pageConfigs, List<Integer> segmentIds) {
    List<Object> keys = new ArrayList<>(pageConfigs.size());
    for (int i = 0; i < pageConfigs.size(); i++) {
        int seg = segmentIds == null || i >= segmentIds.size() ? 0 : segmentIds.get(i);
        keys.add(new ConcatKey(seg, pageConfigs.get(i)));
    }
    return keys;
}
```

调用处（`concatIfNeeded` 多收一个 `List<Integer> segmentIds` 参数）：

```java
SheetConcat.Concated concated = SheetConcat.concat(outSheets, sheetTemplates,
        concatKeys(pageConfigs, segmentIds), docIndexes, docNames);
```

> **关键**：`SheetConcat` **一行代码都没改**。它的 `perSheetSetups` 声明成 `List<?>`、
> 只做 `Objects.equals` 比对，所以传什么都行 —— 把段号包进判据即可。
>
> **为什么必须断开**：两段的打印设置碰巧相同时（很常见，都用报表级那份），
> 清单会被摞进后面那一摞单据里去。而清单本该是能在 Excel 里单独筛选排序的一张表；
> Word 的节、Excel 的起始页号也都只到 sheet 级。
>
> 老报表只有一个段 → `ConcatKey` 恒是 `(0, 打印设置)` → 与从前只比打印设置**完全等价**。

#### 2.2.5 抽出 `uniqueName`

`splitSheetName` 里的去重那几行抽成独立方法，清单页的 sheet 名（模板名）复用它：

```java
/** 重名挂 (2)(3)：Excel 不允许同名工作表，而清单页的模板名与单据名都可能撞上。 */
private String uniqueName(String base, Set<String> used) {
    String unique = base;
    for (int n = 2; used.contains(unique); n++) {
        unique = base + "(" + n + ")";
    }
    used.add(unique);
    return unique;
}
```

清单页与单据页**共用同一个 `usedNames` 集合**（一次渲染一个），所以两边撞名也能去重。

### 2.3 `engine/SheetConcat.java`

**只改了一段注释**（那个按 `i % m` 展开的老重载现在只剩测试在用；有清单页时它同样崩，
原注释只提了逐行换版式一种，容易让人以为「不换版式就能用」）。生产代码走的是
「由调用方把模板与打印设置一路传进来」那个重载。

### 2.4 新增测试 `engine/RenderSplitSegmentTest.java`（8 个用例，纯 POJO 不启 Spring）

| 用例 | 锁住什么 |
|---|---|
| `listSheetRendersOnceWithAllRows` | 清单只出一份且拿到**全量**，后面才是一条数据一张详情；顺序 `清单/SO-1/SO-2/SO-3` |
| `listSheetIsItsOwnDocument` | 每张的 `mzDocBreaks` 都是 `[0]`（清单自成一份单据） |
| `listSheetIsNeverConcatenatedIntoDocuments` | `perRowPage` 下 2 张 sheet；清单无行分页符；详情那张 2 个 `mzRowBreaks` + 3 个 `mzDocBreaks` + `mzDocNames=[SO-1,SO-2,SO-3]` |
| `segmentsNeverMergeEvenWithIdenticalPageSetup` | **打印设置一模一样时段与段之间照样断开** |
| `allOnceFallsBackToPlainRender` | 整份标 `once` → 退回普通渲染，且不挂 `mzDocBreaks` |
| `legacyReportsUnchanged` | **没配 `sheetSplits` 的老报表一字不变**：6 张、名字 `SO-1-清单/SO-1-详情/…` |
| `listSheetInTheMiddleSplitsIntoTwoSegments` | 清单夹中间时两个段各自展开，名字带得出是哪张模板 |
| `perSheetMarkersStayAlignedForExporters` | **导出契约**：`sheetPageConfigs` 与 `sheets` 等长、每张有 `mzTemplateIndex`、同一张里 `mzDocBreaks` 与 `mzDocNames` 等长 |

最后两个是给导出链路兜底的 —— Excel/PDF/Word 三条路都是「按结果 sheet 下标取」，
错位一个下标就全串。

---

## 3. 前端改动

### 3.1 `utils/sheet.js`

```js
// createEmptyContent() 里新增
sheetSplits: {},

// 新增导出函数
/**
 * 归一 sheetSplits：**只留标了 once 的那些**。
 * 与后端 ReportContentDTO#splitsSheet 是同一条规则：认不得的值一律当「跟着拆」。
 */
export function normalizeSheetSplits(splits) {
  const out = {}
  Object.entries(splits || {}).forEach(([idx, v]) => {
    if (v === 'once') out[String(idx)] = 'once'
  })
  return out
}

// pruneSheetConfigs() 里加两行（清掉指向已删 sheet 的孤儿标记）
Object.keys(content.sheetSplits || {}).forEach((k) => mark(Number(k)))
…
content.sheetSplits = remapPageConfigs(content.sheetSplits, dead)
```

`remapPageConfigs` **原样复用**（key 就是下标、值原样搬），只补了一句注释说明 `sheetSplits` 共用它。

### 3.2 `utils/print.js`

只改注释：`pageConfigOfRendered` 的取模推算在「有清单页」时同样不成立。**逻辑没动。**

### 3.3 `stores/designer.js`

四处（**第 2 处是必须的，漏了就是难查的 bug**）：

```js
// ① 载入时归一
content.sheetSplits = normalizeSheetSplits(content.sheetSplits)

// ② setSheets 里跟着搬 key —— sheetSplits 与 cellConfigs/pageConfigs 同一套下标寻址
this.content.sheetSplits = remapPageConfigs(this.content.sheetSplits, remap)

// ③ importSheets 的 replace 分支：版式整个换掉了，清单页标记说的是老模板
this.content.sheetSplits = {}

// ④ setSheetSplit 多收一个 sheetSplits，整份替换
setSheetSplit({ splitMode, sheetNameField, sheetSplits }) {
  const mode = ['perRow', 'perRowPage'].includes(splitMode) ? splitMode : 'single'
  this.content.splitMode = mode
  this.content.sheetNameField = mode === 'single' ? '' : sheetNameField || ''
  if (mode === 'single') {
    this.content.sheetSplits = {}          // 没拆分就没有「跟不跟着拆」这回事
  } else if (sheetSplits) {
    this.content.sheetSplits = normalizeSheetSplits(sheetSplits)
  }
  this.dirty = true
}
```

> **② 搬错的后果特别难查**：清单页的标记跑到详情模板上，于是详情只出一份、清单被复制 N 份。

新增 getter：

```js
/** 当前 sheet 是不是「清单页」 */
sheetSplitOnce(state) {
  return state.content.sheetSplits?.[String(state.sheetIndex)] === 'once'
}
```

`setSheets` 另外改成**返回被删工作表里带过配置的那几张名字**（给 §3.5 的提示用）：

```js
setSheets(sheets) {
  if (!Array.isArray(sheets) || !sheets.length) return { dropped: [] }
  const next = toCelldataSheets(sheets)
  const dropped = []
  const remap = sheetIndexRemap(this.content.sheets, next)
  if (remap) {
    remap.forEach((to, from) => {
      if (to >= 0) return
      const key = String(from)
      const had = !!this.content.pageConfigs?.[key] || !!this.content.sheetSplits?.[key]
        || Object.keys(this.content.cellConfigs || {}).some((k) => k.startsWith(`${key}_`))
      if (had) dropped.push(this.content.sheets[from]?.name || `工作表${from + 1}`)
    })
    …
  }
  …
  return { dropped }
}
```

> store 里**不弹 UI**，只把事实返回给调用方。

### 3.4 `views/designer/components/PrintConfigDialog.vue`（改动最多的前端文件）

四件事，可以分开搬：

**(a) 作用范围从二选一改成三选一**（原先那个「改回跟随报表设置」链接是**点下去立刻写库**的，
和弹窗其余部分「点确定才写回」不是一套，点取消也撤不回）：

```vue
<el-radio-group v-model="scope">
  <el-radio-button value="sheet">仅「{{ sheetName }}」</el-radio-button>
  <el-radio-button value="all">全部工作表</el-radio-button>
  <!-- 本来就没有独立设置时这一项没有意义，灰掉 -->
  <el-radio-button value="follow" :disabled="!store.hasOwnPageConfig">跟随报表设置</el-radio-button>
</el-radio-group>
```

```js
const followMode = computed(() => scope.value === 'follow')

// 选「跟随」时把报表级那份装进表单（那三张表单同时禁用），看到的就是确定之后的样子
watch(scope, (v, old) => {
  if (!visible.value || v === old) return
  if (v === 'follow') {
    form.value = normalizePageConfig(JSON.parse(JSON.stringify(store.content.pageConfig)))
  } else if (old === 'follow') {
    form.value = normalizePageConfig(JSON.parse(JSON.stringify(store.pageConfig)))
  }
})
```

「页面 / 页头页尾 / 水印」三张 `el-form` 加 `:disabled="followMode"`
（Element Plus 的 `el-form` 有 `disabled` 属性，一个属性禁用整块，不必逐个控件加）。
「输出 / 导出」两张**不加** —— 它们是报表级的。

`onConfirm` 三条路互斥：

```js
if (scope.value === 'follow') {
  store.clearPageConfig()
} else {
  store.setPageConfig(form.value, scope.value)
}
```

**(b) 「全部工作表」的破坏性告警**（`setPageConfig(cfg,'all')` 会 `pageConfigs = {}`，
把各表的独立设置一起清掉，而这不在 FortuneSheet 的撤销栈里、Ctrl+Z 救不回来）：

```vue
<el-alert
  v-if="scope === 'all' && ownConfigSheets.length"
  type="warning" :closable="false" show-icon
  :title="`将清除 ${ownConfigSheets.length} 张工作表的独立设置：${ownConfigSheets.join('、')}`"
/>
```

```js
const ownConfigSheets = computed(() =>
  Object.keys(store.content.pageConfigs || {})
    .map((k) => Number(k))
    .filter((i) => Number.isInteger(i) && i >= 0 && i < sheetCount.value)
    .sort((a, b) => a - b)
    .map((i) => store.content.sheets[i]?.name || `工作表${i + 1}`)
)
```

**(c) 新增「工作表」页签**（多 sheet 才有；一行一张，只读概览 + 唯一可编辑的「只出一份」）：

```vue
<el-tab-pane v-if="multiSheet" label="工作表" name="sheets">
  <el-table :data="sheetRows" size="small" max-height="360">
    <!-- 工作表名（当前那张加粗 + 「当前」标签） / 纸张方向 / 单独设置还是跟随报表 /
         打印区域与标题行 / 「只出一份」勾选框 -->
    <el-table-column label="拆分" width="120">
      <template #default="{ row }">
        <el-checkbox :model-value="row.once" :disabled="!canSplitSheet"
                     @change="(v) => toggleOnce(row.index, v)">只出一份</el-checkbox>
      </template>
    </el-table-column>
  </el-table>
</el-tab-pane>
```

```js
const splits = ref({})   // content.sheetSplits 的草稿，打开弹窗时 { ...store.content.sheetSplits }

const canSplitSheet = computed(() =>
  canSplit.value && split.value.splitMode !== 'single' && multiSheet.value)

const sheetRows = computed(() =>
  (store.content.sheets || []).map((s, i) => {
    const cfg = pageConfigOf(store.content, i)          // ← 从 utils/print 新引入
    return {
      index: i,
      name: s.name || `工作表${i + 1}`,
      current: i === store.sheetIndex,
      own: !!store.content.pageConfigs?.[String(i)],
      paper: `${cfg.paperSize} ${cfg.orientation === 'landscape' ? '横向' : '纵向'}`,
      printArea: cfg.printArea || '',
      titleRows: cfg.titleRows || '',
      once: splits.value[String(i)] === 'once'
    }
  })
)

function toggleOnce(index, once) {
  const next = { ...splits.value }
  if (once) next[String(index)] = 'once'
  else delete next[String(index)]
  splits.value = next
}
```

> **设计取舍**：纸张、页边距这些**只看不改** —— 要改还是切到那张工作表去改。
> 两处都能改，迟早对不齐。唯一可编辑的是「只出一份」，因为它本来就是**跨表**的一项，
> 逼用户切表最没道理。

`onConfirm` 里整份写回：

```js
store.setSheetSplit(
  canSplit.value
    ? { ...split.value, sheetSplits: canSplitSheet.value ? splits.value : {} }
    : { splitMode: 'single' }
)
```

**(d) 「输出」页签**里原来那个只管当前表的开关**删掉**，改成回显 + 指向「工作表」页签的链接。

样式补两条：

```css
/* 「工作表」页签里没有 label 那一列，tips 不必跟着缩进 96px */
.tips-flush { padding-left: 0; padding-top: 8px; }
.row-current { font-weight: 600; }
```

### 3.5 `views/designer/ReportDesigner.vue`

**状态栏加一格**（画布上完全看不出这张会不会跟着拆，而它决定的是出一份还是出 N 份）：

```vue
<span v-if="splitRole" class="sb-item sb-muted">{{ splitRole }}</span>
```

```js
const splitRole = computed(() => {
  const mode = store.content.splitMode
  if (mode !== 'perRow' && mode !== 'perRowPage') return ''
  if (!store.content.primaryDataset) return ''
  return store.sheetSplitOnce ? '清单页 · 只出一份' : '跟着拆 · 每条数据一份'
})
```

**删工作表时提示一句**（它的绑定/打印设置/清单页标记一并没了，画布上一点痕迹都没有，
而撤销回来的是一张没有绑定的空表）：

```js
function onSheetChange(sheets) {
  if (!contentReady.value) return
  const { dropped } = store.setSheets(sheets) || {}
  if (dropped?.length) {
    ElMessage.warning(`已删除「${dropped.join('、')}」，其单元格绑定与打印设置一并移除`)
  }
  enforceWidthLimit()
  autoFitWrapRows()
}
```

### 3.6 `views/preview/ReportPreview.vue`

打印按钮加悬停提示（**打的范围跟着看到的**：PDF 视图整本、表格视图只打当前那张 ——
这是既有设计，但按钮上看不出来）：

```js
const printHint = computed(() =>
  viewMode.value === 'pdf'
    ? '打印整本（所有工作表，与「导出 PDF」拿到的一样）'
    : '只打印当前这张工作表；要整本请切到 PDF 视图'
)
```

### 3.7 `views/designer/components/VersionManageDialog.vue`

一句文案：逐行选版本时，标成「只出一份」的清单页不属于任何一行，**恒用默认版本的版式**。

---

## 4. **不需要改**的地方（重要，别多做）

这次的设计刻意把影响面收在引擎里。以下全部**一行没动**，照搬时也不要动：

| 模块 | 为什么不用改 |
|---|---|
| `engine/SheetConcat` | 判据声明成 `List<?>`、只做 `equals`，把段号包进 `ConcatKey` 就够了 |
| `engine/ExpandProcessor` / `TemplateParser` / `LinkedDataFetcher` / `CachingDataFetcher` | 「换取数函数」这一招的老规矩，它们不知道有「清单页」这回事 |
| `export/ExcelExporter` | `pagePins` 只看每张的 `mzDocBreaks` 非不非空 → 清单页天然被当成一份独立单据、起始页号钉成 1；`applyRowBreaks` 对缺省值 no-op；`safeSheetName` 兜底去重 |
| `export/PdfExporter` | 逐 sheet 取 `docBreaks/docNames`；`docStartRows` 用 `Math.max(b, bodyStart)` 把 `0` 夹到正文首行（设了打印区域或标题行也对）；`numbering` 让清单页自成一组 |
| `export/WordExporter` | 每张 sheet 一节、各自的页面设置；`perDoc` + `restartNumbering` 让清单节从 1 数起 |
| `service/RenderServiceImpl` | `prepareExport` 里三个 `IntFunction` 全部按**结果 sheet 下标**取，`pageConfigAt` 优先认 `sheetPageConfigs` —— 没有任何「模板张数」推算 |
| `service/ReportPackageService`、版本复制/删除 | 整份 content 搬运，新字段自动跟着走 |
| 数据库 / `db/*.sql` | 新字段在 JSON 列里 |

**支撑这一点的不变量**（改的时候必须守住）：

1. `RenderResult.sheetPageConfigs` 与 `sheets` **恒等长、一一对应**（三条产出路径都保证）。
2. 每张结果 sheet 都带 `mzTemplateIndex`。
3. 同一张 sheet 上 `mzDocBreaks` 与 `mzDocNames` 一一对应。

---

## 5. 迁移步骤（建议顺序）

> 目标工程如果就是本项目的旧副本，最省事的是直接 cherry-pick 那四个提交；
> 下面是手工搬运的顺序。**后端先行、前端跟上**：后端合了之后老前端照旧能用
> （不发 `sheetSplits` = 全部跟着拆），反过来不行。

1. **后端 DTO**：`ReportContentDTO` 加 `sheetSplits` + `splitsSheet()`，改 `splitByRow()`。
2. **后端引擎**：`ReportRenderEngine` 加 `Segment/segments/perRowSegment/perRowCount/wholeFetcher/
   ConcatKey/concatKeys/uniqueName`，重构 `renderPerRow`，`concatIfNeeded` 多收 `segmentIds`。
3. **后端测试**：搬 `RenderSplitSegmentTest`，先跑 `legacyReportsUnchanged` 确认老行为没破。
4. **前端 utils**：`sheet.js` 的 `createEmptyContent` / `normalizeSheetSplits` / `pruneSheetConfigs`。
5. **前端 store**：`designer.js` 四处 + `sheetSplitOnce` getter + `setSheets` 返回值。
6. **前端弹窗**：`PrintConfigDialog.vue` 的 (a)(b)(c)(d) 四块，可分批。
7. **前端零碎**：状态栏、删表提示、打印按钮提示、版本弹窗文案。
8. **文档**：`docs/CONTRACT.md` §4 的 content 结构与 §7 的算法说明、`README.md`、`CLAUDE.md`。

### 依赖检查（目标工程若版本较老，先确认这些存在）

- 后端：`RenderResultDTO.sheetPageConfigs`、`SheetConcat.Concated` + 5 参 `concat`、
  `sheet.mzDocBreaks` / `mzDocNames` / `mzRowBreaks` 这套标记、`CachingDataFetcher`。
  **缺哪个就得先把那一批补上** —— 本次改动是踩在它们身上的。
- 前端：`utils/sheet.js#sheetIndexRemap/remapPageConfigs/pruneSheetConfigs`、
  `utils/print.js#pageConfigOf/pageConfigOfResult`、store 的 `pageConfigs` 那套。
- 语言级：后端用了 **record**（JDK 16+；本项目是 JDK 21）。

---

## 6. 验收自测清单

**后端（`mvn test`）**

```bash
mvn test -Dtest=RenderSplitSegmentTest
mvn test -Dtest=SheetConcatTest        # 拼接没被带坏
mvn test -Dtest=RenderVersionFlowTest  # 逐行选版本没被带坏
```

**端到端**（两张模板：清单 + 详情，主接口 3 条数据）

| # | 操作 | 期望 |
|---|---|---|
| 1 | 不配 `sheetSplits`，`perRow` | 6 张 sheet，`SO-1-清单/SO-1-详情/…`（老行为） |
| 2 | 清单标 `once`，`perRow` | 4 张：`清单`（3 行数据）+ `SO-1/SO-2/SO-3` |
| 3 | 清单标 `once`，`perRowPage` | **2 张**：清单一张 + 详情一张（2 个行分页符、3 个单据起点） |
| 4 | 同上，两张打印设置**改成一样** | 仍是 **2 张**（段屏障） |
| 5 | 清单横向 + 详情纵向，导出 Excel | 两张各自的纸张方向；清单起始页号钉成 1 |
| 6 | 导出 PDF | 清单页页脚「第 1 页 共 N 页」，详情每份各自从 1 数起 |
| 7 | 导出 Word | 两节各自的页面设置；页眉页脚/水印整份一套、**跟第一张 sheet**（见 §7） |
| 8 | 整份都标 `once` | 退回普通渲染：2 张、各拿全量 |
| 9 | 设计器删掉中间一张 sheet | 后面那张的清单页标记跟着挪、提示一句配置被移除 |
| 10 | 弹窗选「全部工作表」 | 出现告警条并列出会被清掉独立设置的表名 |

---

## 7. 已知边界（不是这次引入的，但清单页会更容易撞上）

- **Word 的页眉页尾与水印整份文档只有一套，跟第一张 sheet 走**。清单页排在第一张、
  又只在详情模板上配了页头页尾的话，导出的 Word 整份都没有 —— Excel / PDF 按张走，不受影响。
  → 建议把页头页尾也配在清单页上。
- **Excel 的 `&N`（总页数）恒是整个打印任务的页数**，没有「本单据共几页」这种代码。
  要两个数都准就走 PDF。
- **`perRowPage` 下 Word 只能按节重编页码**，拼在同一张 sheet 里的那些单据分不开。
- **逐行选版本 × 多个 perRow 段**：按「第几个 perRow 段」与各版配对，
  那一版段数不够时跳过这一条并记一次 warn。绝大多数报表只有一个 perRow 段，配对是平凡的。
- **`MAX_SPLIT_ROWS = 200`** 仍然管着（清单页显示的是这 200 行的全量）。

---

## 8. 当前验证状态（诚实说明）

写这份文档时，改动**没有在本机编译/运行过** —— 开发机上没有 JDK / Maven，
前端也没装 `node_modules`。做过的检查：

- Java：逐文件通读自检 + 静态走查了 `引擎 → RenderResult → xlsx → PDF/Word` 整条链的下标契约；
- JS：改动的 `.js` 与从 `.vue` 抽出的 `<script setup>` 块都过了 `node --check`；
- `.vue` 的 **template 部分没有渲染验证过**（`el-table` / `el-alert` 的用法照着项目里既有写法来的）。

所以搬过去之后，**务必先跑 §6 的后端测试与前端构建**。
