# 报表图表设计方案（**已实现**，一期）

> 本文是**设计方案**，不是契约。**落地结果以 `docs/CONTRACT.md` 为准**
> （§4 `cellConfig.chart` / §7 第 8 步「图表出图」，均已并入）；日常改动看 CLAUDE.md
> 「渲染引擎」一节里图表那一段。本文保留下来是为了记着**为什么**这么设计、以及**否掉了什么**。
>
> 一句话：**图表是「图片单元格」的延长线，不是第五条导出路** —— 与条码
> （`engine/BarcodeGenerator`）走同一套路数，在**渲染时**于服务端画成一张 PNG、
> 归一化成 `data:` URI 挂到 `v.mzImg.src` 上，于是预览、Excel、PDF、Word 拿到的是同一张图，
> 下游一处都不用改。
>
> ## 落地时与本文不同的五处
>
> 1. **§0 与 §9.2 说「XChart 或 JFreeChart」，实际是 Java2D 自绘**（`engine/ChartRenderer`，零新依赖）。
>    直接原因是落地时那台机器上**没有 JDK / Maven / 本地 m2 仓库**，加一条拉不到的依赖等于让
>    第一个 `mvn package` 的人吃一发构建失败；根本原因是这件事本来就只有「算刻度 + 画矩形/折线/扇形」，
>    引一个库要连着它的许可、体积、升级一起背 —— PDF 那条路上已经因为 Aspose 栽过一次。
>    §9.2 那个「换实现只换一个类」的口子仍然留着：上下游只认 `ChartRenderer#dataUri` 一个入口。
> 2. **§3.3 说做成独立的编排 pass，实际挂在 `ExpandProcessor#process` 的末尾**（`engine/ChartProcessor`
>    仍是独立的类，只是由 `ExpandProcessor` 在最后一步调）。理由是**调用点唯一**：
>    `render` 与 `renderPerRow` 两条路都经由 `ExpandProcessor#process`，挂在编排层要挂两处，
>    漏一处就是「拆分模式下没有图」。收集方式照抄公式那条路的「先占位、末尾统一处理」。
> 3. **多了一道「一次渲染最多画多少张图」的闸门**（`ChartProcessor#MAX_CHARTS`，200）。
>    本文 §3 只想到单张图的尺寸上限，没算总量：拆分模式下 500 份单据就是 500 张图，
>    光 base64 就能把渲染结果撑到几十 MB。超出的留空并 warn，不报错。
> 4. **字体没有「配置项」**：直接复用 `muzhou.report.pdf.font-path`（`engine/ChartFonts`）。
>    「这台服务器上的中文字体在哪」是同一件事，没道理配两遍；配了 PDF 却发现图表还是方框
>    才是最难查的。另外候选清单与 `PdfExporter#FONT_CANDIDATES` **是两份**且故意不同 ——
>    AWT 读不了 `.ttc`，Windows 上雅黑和宋体都是 ttc，能用的是黑体。
> 5. **`echarts` 那个未使用的依赖没有删**（§9.1 建议删）：本次没有能跑 `npm install` 的环境，
>    不在验证不了的地方动 `package.json`。它仍然是死依赖，后续可以清掉。
> 6. **`xAxisTitle` / `yAxisTitle` 改名成 `categoryAxisTitle` / `valueAxisTitle`**。
>    不是为了好看：Jackson 默认的 bean 命名对 `getXAxisTitle()` 反推出来的属性名是
>    **`xaxisTitle`**（开头连续两个大写字母会被整段小写化），前端传 `xAxisTitle` 就绑不上，
>    而这种坑在界面上只表现为「轴标题填了没反应」。顺带语义也更准 ——
>    横向柱状图里 x 轴装的是数值、y 轴才是类目。

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 能不能加 | 能，而且比想象中便宜 —— 图片通道（`v.mzImg` → xlsx 锚图 → PDF/Word 读回）是现成的 |
| 在哪画 | **服务端**，纯 Java，渲染时画 |
| 用什么画 | XChart（Apache-2.0，轻）或 JFreeChart（LGPL，类型全）；**不引入 Node / GraalJS / Aspose** |
| 前端 ECharts 画行不行 | **不行**，见 §9.1（导出三条路都在服务端，前端画的图它们拿不到） |
| Excel 里是真图表吗 | 一期**不是**，是一张图片。原生图表见 §9.3，代价是 PDF/Word 要再画一遍 |
| 改动面 | 后端 4 个新文件 + 3 处改；前端 2 处改；契约 2 处补 |
| 分期 | 一期柱/折/饼三种，二期堆叠/双轴/主题，三期（可选）Excel 原生图表 |

## 1. 为什么图表能便宜地落地

CLAUDE.md 里那条「图片也归 `ExcelExporter` 一处管」的规矩，恰好把图表要走的路全铺好了：

```
渲染时画一张 PNG
   → data URI 挂到 GridCell#image（输出成 celldata 的 v.mzImg.src）
   → ① 预览：utils/cellImage.js 摊成 sheet.images 浮动图片
     ② Excel：ExcelExporter#applyImages 按 ImageFit#contain 算锚点框、锚进 xlsx
     ③ PDF：  PdfExporter#readImages 从那份 xlsx 读回来（连格内偏移一起）
     ④ Word： WordExporter#readPictures 从那份 xlsx 读回来、按 Word 列宽重装
```

②③④ 都只认 `v.mzImg.src`，**认不出这张图是下载来的、编成码的、还是画出来的**。
条码就是这么加进来的（`BarcodeGenerator` 的类注释写着这条），图表照抄即可。

**所以图表的工作量几乎全在「怎么把一份 `List<Map>` 画成一张合适尺寸的 PNG」上**，
四条出纸路一行都不用动。

## 2. 数据模型

### 2.1 新单元格类型 `chart`

`cellConfig.type` 的取值补一个 `chart`（现有：`text|data|formula|param|img|base64|barcode|qrcode`）。

它与其它类型最大的不同：**取的不是一行的一个字段，而是整个数据集的多行多列**。
所以它：

- `expandType` 恒 `none` —— 不参与行带扩展（同 `aggregate != none` 的格子）；
- 不进 `CellConfigDTO#isDataBound()` —— 那条路是「按字段取当前行的值」，图表不走；
- 不进 `isImage()` 的**取值**分支，但**输出形态**与图片格完全相同（挂 `mzImg`、文字置空）。

### 2.2 `cellConfig.chart`（新 DTO `ChartConfigDTO`）

图表的配置项比一个格子的其它属性多得多，平铺进 `CellConfigDTO` 会把它撑爆，
所以**嵌一层**（JSON 里就是 `cellConfigs["0_5_1"].chart = {...}`）：

```jsonc
{
  "chartType": "bar",              // bar | hbar | line | area | pie（一期这五种）
  "categoryField": "month",        // 类目轴字段（饼图是扇区名）
  "series": [                      // 数值系列，一期允许多条
    { "field": "amount", "name": "销售额", "aggregate": "sum" }
  ],
  "title": "",                     // 图表标题，支持 ${param} 与 #{code.field}
  "categoryAxisTitle": "",         // 落地时改的名，理由见开头偏差第 6 条
  "valueAxisTitle": "",
  "showLegend": true,
  "legendPosition": "bottom",      // top | bottom | left | right | none
  "showValueLabels": false,        // 柱顶/点上印数值
  "theme": "default",              // 配色主题，见 §5.3
  "maxCategories": 30              // 类目上限，超了截断并 warn，见 §6.4
}
```

**为什么聚合挂在每条系列上而不是格子上**：数据集给的往往是**明细行**（一行一笔订单），
而图表要的是「按月份汇总的销售额」。同一张图里「金额求和 + 单量计数」是常见配法，
聚合方式必须逐条系列给。`aggregate=none` 表示不聚合、按行序直接画（数据集本身已经是汇总结果时用）。

**为什么不复用格子上那个 `aggregate` 字段**：那一个是「整列算成一个值」的语义，
图表要的是「按类目分组算」，两回事，混用会让 `ExpandProcessor` 里的聚合分支跟着变复杂。

### 2.3 图表格在画布上留什么

图片格靠格子里那串 `#{code.field}` 当「绑定还在」的凭据 —— 清空格子时
`pruneEmptyCellConfigs` 就是照它回收配置的（CLAUDE.md「前后端对应关系」一节）。
图表格没有单字段绑定，但**这条规矩必须守住**，否则画布上删干净了、导出照旧出图。

做法：设计器在配置图表时往格子里写一段**占位文本**（`syncCellText` 加一条分支）：

```
[图表] 销售趋势        // 有标题时
[图表] orders.month    // 没标题时用「数据集.类目字段」
```

渲染时 `chart` 分支把文字置空（同图片格），所以这段文字只活在设计器里。
`chart` 加进 `utils/sheet.js` 的 `BOUND_TYPES` —— 删掉这段文字 = 撤销图表，与图片格一致。

**不要**让它长得像 `#{...}` 占位符：那样 `autoBindCellConfigs`（手写占位符自动补绑定）
会把它认成数据格，两套推断打架。

### 2.4 图表占的是一片区域，不是一个格子

图表格**必须是合并区**（单格也能画，但小得没法看）。这一条不新增任何机制 ——
`ExcelExporter#applyImages` 早就是「合并区则整块」的（`regionAt`），
前端 `utils/cellImage.js` 用的是同一套 `mergeSpanAt`。设计器上给一句提示即可：
**先合并出一片区域，再把它设成图表格**。

## 3. 出图：`engine/ChartRenderer`

纯 Java、静态方法、不依赖 Spring（同 `BarcodeGenerator`），签名：

```java
/**
 * @param cfg   图表配置
 * @param rows  整份数据集（已按 §4 决定好是全量还是这一份单据的那几行）
 * @param boxW  目标框宽（像素，见 §4.2）
 * @param boxH  目标框高
 * @return data URI；画不出来返回 null（该格当空格子处理并记 warn）
 */
public static String dataUri(ChartConfigDTO cfg, List<Map<String, Object>> rows, int boxW, int boxH)
```

### 3.1 按类目聚合

```
rows ──按 categoryField 分组（保持首次出现的顺序，LinkedHashMap）
     ──每条 series 在组内按自己的 aggregate 算一个数
     ──得到 categories[] + series[i].values[]
```

字段取值**大小写敏感**（同 `ExpandProcessor#row.get(field)`），取不到就退回小写 ——
与 `ReportRenderEngine#fieldText`、`LinkedDataFetcher#fieldValue` 那两处的兜底同一条规则，
理由也一样：用户在配置里手写字段名。

类目为 null / 空串的行归到 `(空)` 一组，别整行丢掉 —— 丢掉了图上的合计与表里的合计对不上。

### 3.2 尺寸：按目标框出图，再乘一个超采样倍数

这是图表与条码**唯一的实质差异**，也是最容易做错的一处。

条码是固定像素出图、再由 `ImageFit#contain` 等比装进格子 —— 因为条码等比缩放不失真。
**图表不行**：坐标轴文字、图例、数据标签的字号是画在图里的，等比缩放之后
「按格子放大 3 倍」会让字大得离谱，「缩小到 1/3」会让字糊得看不清。

所以图表**按目标框的实际像素尺寸出图**，宽高比与格子一致，`ImageFit#contain` 算出来的框
就等于整格（等比缩放系数 = 1），三条导出路一行不用改。

```java
int w = clamp((int) Math.round(boxW * SUPERSAMPLE), MIN_PX, MAX_PX);   // SUPERSAMPLE = 2
int h = clamp((int) Math.round(boxH * SUPERSAMPLE), MIN_PX, MAX_PX);
```

`SUPERSAMPLE = 2`：屏幕上 400×240 的框，打印到纸上是 ~150 DPI，1 倍出图会糊。
`MAX_PX = 2400` 挡住「用户把图表格拖成整页那么大」时画出一张 20MB 的 PNG。
字号跟着 `SUPERSAMPLE` 一起放大，所以最终视觉大小不变。

### 3.3 目标框从哪来

**这决定了出图这一步排在哪儿**：要知道框有多大，就得知道行高、列宽、以及这一格合并到了哪里，
而这三样在**扩展完成之后**才定得下来（行带复制会把下方所有行的 `r`、`merge`、`rowlen` 整体偏移）。

所以图表出图**不在 `ExpandProcessor#buildCell` 里**，而是扩展完成后的**一个独立 pass**
（`engine/ChartProcessor#process(RenderGrid, SheetTemplate, datasets)`），排在
`applyMergeMarkers` 之后 —— 与前端 `cellImage.js` 必须排在 `applyWrapRowHeights`
之后是同一个道理（几何要等布局定下来）。

框的算法与 `ExcelExporter#applyImages` 里那一段**同源**：合并区内列宽求和 × 行高求和，
默认值取设计器的 73px / 19px（不是 Excel 的 64 / 17.6 —— 画布上看到多大就该出多大）。

### 3.4 中文字体

AWT 画图用 `java.awt.Font`，服务器上没有中文字体就是一堆方框（**必踩**）。
复用 PDF 那条路已经探测好的字体文件（`muzhou.report.pdf.font-path` / `PdfFonts` 的兜底字体）：

```java
Font.createFont(Font.TRUETYPE_FONT, new File(path)).deriveFont(size)
```

三件事要写清楚：

- **`.ttc` 用不了**（`createFont` 不收字体集），退回 `SANS_SERIF` 并记一条 warn ——
  与前端 `@font-face` 跳过 ttc 是同一类问题，提示语要说明「PDF 里正常、图表里是方框」；
- **字体只解析一次**（静态缓存一份 `Font`），逐张图 `createFont` 的话 500 份单据就是 500 次读盘；
- **`java.awt.headless=true`** 必须开（Spring Boot 默认就开），否则某些环境下 AWT 会去连 X11。

单元格上设的字体名（`ff`）**一期不跟进图表** —— 图表里的字是画在图里的，
要跟就得走 `PdfFonts` 那套「三级往下找」，留到二期。

### 3.5 画不出来就出空格

同 `BarcodeGenerator` / `ImageLoader` 的态度：**一张图画不出来不许拖垮整份渲染**。
数据为空、类目字段不存在、系列全不是数字 —— 记一条说清是哪一种的 warn，该格空白。
一份 500 行的报表里有一条脏数据就整份 500 没法交代。

## 4. 与现有机制的相互作用（重点）

这一节是这个方案真正的价值 —— 下面每一条都是**免费得到的**，因为图表复用的是取数函数。

### 4.1 拆分（`splitMode` / `sheetSplits`）

`perRow` / `perRowPage` 是**换取数函数**实现的（第 i 遍渲染时主接口只返回第 i 行）。
于是详情页上的图表**自动只画这一条单据的数据**，清单页（`sheetSplits=once`）上的图表
拿到的是全量 —— 一行代码都不用写。

### 4.2 父子关联

同理：子表数据由 `LinkedDataFetcher` 拼好，图表拿到的就是拼好的那份。
「主表一条单据 + 明细画一张饼图」天然成立。

### 4.3 版本

`chart` 配置在 `cellConfigs` 里，而 `cellConfigs` 是 content 的一部分 —— 跟着版本走、
跟着导入导出包走、跟着报表复制走，全都是免费的。

### 4.4 多 sheet / 行列增删

`cellConfigs` 按 `sheetIndex_r_c` 寻址，删 sheet 时 `remapCellConfigs` 搬 key、
插删行列时 `shiftCellConfigs` 挪坐标 —— 图表格跟着走，不必特殊处理。

### 4.5 分页与超高行（**这一条要小心**）

PDF 那条路会把「比一页还高」的行横着劈开跨页印（`Geom#splitRows`）。
图表所在的合并区如果比一页高，**图会被从中间切成两半**，而且切口两边各是半张图。

一期的处理：**不特殊处理，但在设计器里拦**（图表格高度超过一页正文高度时给出警告，
同「顶端标题行落在中间」那道校验的做法）。真要把图表撑到整页，让用户自己缩一点。

### 4.6 Excel 导入

`ExcelImporter` 带不过来图表（本来也带不过来图片），warning 清单里已经有这一条，不用改。

## 5. 前端改动

### 5.1 设计器：单元格类型下拉加一项

`utils/sheet.js#CELL_TYPES` 加 `{ label: '图表', value: 'chart' }`。
`CellPropertyPanel.vue` 里加一段 `v-if="isChart"` 的表单：图表类型（图形化的五个按钮）、
类目字段、系列（可增删的若干行：字段 + 名称 + 聚合）、标题、图例位置、数值标签、主题。
字段下拉复用现有的 `matchFields`（字段名与中文名都能搜）。

面板顶部给一句提示（同图片格那条 `type-hint` 的位置）：

> 图表画在整个合并区里。先框选一片区域合并，再设成图表格；数据取自整个数据集，不随行扩展。

### 5.2 设计器：画布上的占位文本

`ReportDesigner.vue#syncCellText` 加一条 `chart` 分支，写 §2.3 那段 `[图表] xxx`。

### 5.3 预览：零改动

图表是 `mzImg`，`utils/cellImage.js` 已经会把它摊成浮动图片。
**预览页默认就是后端出的那份 PDF**，所以图表在预览里长什么样 = 打印出来什么样，
本来就是所见即所得。

配色主题（`theme`）在**后端**定（`ChartRenderer` 里一份 `Map<String, Color[]>`），
前端只出一个下拉 —— 配色定在前端的话，服务端画图时还得再抄一份，两份迟早对不齐。

## 6. 契约与文档改动

- **CONTRACT §4**：`cellConfig` 的 `type` 取值表补 `chart`，新增 `chart` 子对象的字段表；
  能力表（§4 那张「不是每条路都做得到」）补一行：图表在四条路上**都是静态图片**。
- **CONTRACT §7**：渲染算法在「扩展 → 合并标记」之后补一步「图表出图」。
- **CLAUDE.md**：在「图片单元格」那一段后面补一节，写清「图表是图片格的延长线」
  与 §3.2 那条「按目标框出图而不是等比装进去」的理由。
- **README**：功能清单加一条。

## 7. 分期

### 一期（核心，估 2~3 天）

- 后端：`dto/ChartConfigDTO`、`engine/ChartRenderer`、`engine/ChartProcessor`、
  `CellConfigDTO#isChart()`；`ReportRenderEngine` 编排里插一个 pass。
- 前端：类型下拉 + 属性面板 + 占位文本。
- 图表类型：柱、横向柱、折线、面积、饼。
- 验收：同一张报表的预览 / xlsx / PDF / Word 四份里，图表位置与大小一致。

### 二期

堆叠柱 / 双轴 / 散点、颜色主题、数据标签格式化（复用 `CellFormatter`）、
标题里的 `${param}` 与 `#{code.field}` 展开、图表跟随单元格字体。

### 三期（可选，见 §9.3）

Excel 原生图表。

## 8. 测试点

- `ChartRendererTest`（纯 POJO，不起 Spring）：分组聚合的结果、类目超限截断、
  空数据返回 null、尺寸钳制、系列字段全非数字时的兜底。
- `ChartProcessorTest`：图表格不参与行带扩展；合并区偏移之后目标框仍然算对。
- `ExcelExporterTest`：图表格在 xlsx 里锚成一张图（复用现有的图片用例）。
- 端到端：`perRow` 拆分时每份的图表数据各不相同（这是最容易写错的一条 ——
  取数缓存跨份复用会让每张图长一样，同 CLAUDE.md 里「子表不能进 perRow 那个缓存」那个坑）。

## 9. 被否掉的方案（**别再走一遍**）

### 9.1 前端 ECharts 画、导出时把图传回后端 ✗

`package.json` 里已经躺着一个 `echarts`（**当前没有任何地方 import 它**，是个未使用的依赖），
所以这条路看着最近。但它是错的：

**导出的三条路全在服务端**（Excel / PDF / Word），前端画出来的图它们拿不到。
要拿到就得让浏览器先画完、把 base64 回传，于是：

- 定时任务、外部系统直接调 `export/pdf` 的场景**永远没有图**（没有浏览器参与）；
- 预览与导出成了两套渲染，「预览里有图、导出的文件里没有」这一类报障会从图片扩散到图表；
- 违反 CLAUDE.md 那条「放到前端画的话导出那三条路还得再实现一遍，两份实现迟早对不齐」。

**结论**：不走。顺便把那个没人用的 `echarts` 依赖删掉（或者留给二期做设计器里的实时预览，
但那也只是**设计期**的示意图，出纸的仍然是服务端画的那张）。

### 9.2 ECharts SSR（Node 侧服务 / GraalJS 跑 echarts） ✗（一期）

出图最漂亮、与前端完全一致。但要么多一个 Node 进程（部署从「一个 jar」变成两个东西），
要么引入 GraalJS（几十 MB 依赖 + 启动开销），都撞上 CLAUDE.md 那条
「纯 Java，不要引入 LibreOffice / Aspose 这类外部渲染器」。

**留一个口子**：`ChartRenderer` 只暴露 `dataUri(cfg, rows, w, h)` 这一个入口，
将来真要换成 SSR，换的是这一个类的实现，上下游不动。

### 9.3 Excel 原生图表（POI 的 `XSSFChart`） △（三期再说）

好处是真的：Excel 里能双击编辑、改数据图跟着变。坏处是**它只在 xlsx 里存在**——
PDF / Word 那两条路是**把 xlsx 读回来逐格画**的，POI 写的 chart part 它们读不出来，
于是 PDF/Word 里没有图，还得再画一张静态图补上 → **同一张图两份实现**，
正是本项目一直在躲的那件事。

真要做，正确的姿势是「**两份都出**：xlsx 里写原生图表，同时把静态图也锚进去给 PDF/Word 读」，
并且接受「Excel 里的图和 PDF 里的图可能长得不完全一样」。这个代价一期不值得付。

### 9.4 图表当成一种「浮动对象」而不是单元格 ✗

FortuneSheet 的浮动图片（`sheet.images`）本身就是个坐标脆弱的东西（行高一变全体位移），
而且它不在 `cellConfigs` 的寻址体系里 —— 版本、复制、导入导出、行列增删这四件事
全都要重写一遍。挂在单元格上则统统免费（§4.3 / §4.4）。

## 10. 已知边界（要写进 README「已知边界」一节）

1. **四条路里都是静态图片**：不能在 Excel 里双击编辑，改了数据图不会跟着变（要重新导出）。
2. **不可交互**：预览里没有 tooltip、没有图例点选 —— 预览页本来就是后端出的 PDF。
3. **图表里的字用兜底字体**（一期），单元格上设的字体名不生效；`.ttc` 环境下退回无衬线体。
4. **比一页高的图表会被 PDF 横切**（§4.5），设计器给警告但不硬拦。
5. **类目上限 30**（可配），超出截断并 warn —— 一张纸上挤 500 根柱子本来也看不清。
6. **`ExcelImporter` 带不进来**：导入的 xlsx 里原有的图表照旧只记一条 warning。
