# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 契约优先

`docs/CONTRACT.md` 是本项目的 **single source of truth**：接口路径、JSON 字段名、数据库列名、
`mz_report.content` 结构、渲染算法七步都写在里面。**改动任何跨前后端的结构前先读它**，
文件头部明确要求实现服从契约而不是反过来改契约。README.md 是面向使用者的说明，
「渲染算法要点」「已知边界」两节与 CONTRACT §7 对应。

中文项目：注释、提交信息、UI 文案都用中文，保持一致。

## 常用命令

后端（JDK 21 + Maven 3.9，无 wrapper，直接用系统 `mvn`）：

```bash
cd backend
mvn spring-boot:run                 # 起服务，8888；首启自动建表+灌演示数据
mvn test                            # 全部测试
mvn test -Dtest=RenderEngineTest    # 单个测试类
mvn test -Dtest=RenderEngineTest#方法名
mvn clean package                   # 打 jar
```

前端：

```bash
cd frontend
npm install
npm run dev      # 5173，/api 已代理到 localhost:8888
npm run build
```

无 lint / formatter 配置，不要引入。

## 架构要点

### 渲染引擎（`backend/.../engine/`）是核心

数据流：`mz_report.content`(JSON) → `TemplateParser` → `SheetTemplate/TemplateCell`
→ `ExpandProcessor` → `RenderGrid/GridCell` → FortuneSheet `Sheet[]`。

- `ReportRenderEngine` 只做编排。**取数刻意抽象成 `BiFunction<datasetCode, params, List<Map>>` 参数**，
  引擎不依赖 Spring 与数据库；`RenderServiceImpl` 传入委派给 `DatasetService::fetchDataByCode` 的实现。
  写引擎测试时手工构造数据 Map 直接调 `ExpandProcessor.process`，不要启动 Spring 上下文
  （`RenderEngineTest` / `A1RefUtilsTest` 就是纯 POJO 测试）。
- 扩展的核心概念是**行带**：含 `expandType=down` 单元格的模板行按数据行数 n 复制成 n 行，
  **下方所有行的 `r` 以及 `config.merge` / `rowlen` / `borderInfo` 都要同步偏移 n-1**。
  横向扩展同理偏移 `c` 与 `columnlen`。漏偏移某一项是这里最典型的 bug。
  **行带那一行上的合并是「复制 n 份」不是「拉长成一个」**（拉长了只有第一条数据看得见），
  只有跨到行带以外的合并（`rs>1`）才按展开后的行数拉长；合并区里每个格子的 `mc` 标记
  由 `ExpandProcessor#applyMergeMarkers` 按结果重建，模板里那份是模板坐标、不能沿用
  （所以 `TemplateParser` 把 `mc` 挡在样式外面）。
- `aggregate != none` 的单元格**不参与扩展**，在整个数据集上求一个值。
- **图片单元格**（`type=img` 值是地址 / `base64` 值是 base64 / `barcode`、`qrcode` 值是要编成码的
  那串字）的取数与扩展跟 `data` 一模一样，
  所以引擎里判的是 `CellConfigDTO#isDataBound()` 而不是 `"data".equals(type)`；区别只在 `buildCell`：
  值归一化成一个 src 挂到 `GridCell#image`（输出成 `v.mzImg.src`），**文字置空**
  ——不置空的话导出的 Excel 里会写进一长串 base64。图片**等比例装进**格子（合并区则整块）并居中，
  **不铺满**——格子的宽高比几乎不可能等于图片的，铺满就是把图片拉变形。这条规则在
  `export/ImageFit#contain` 一处，三条输出路共用；预览那边靠 `object-fit: contain`（见下）。
  分组合并只对 `data` 生效：图片格的值已经是空串，按值比对会把整列合成一格。
- **条码（`barcode` / `qrcode`）是 base64 图片格的延长线，不是第四条导出路**：
  `engine/BarcodeGenerator`（ZXing core，纯 Java）在**渲染时**把那串字编成 PNG、归一化成
  `data:` URI 交回 `CellImage`，于是预览、Excel、PDF、Word 拿到的是同一张图，下游一处都不用改。
  放到前端画的话导出那三条路（都在服务端）还得再实现一遍，两份实现迟早对不齐。
  两个容易踩的点：① **`ERROR_CORRECTION` 这个 hint 各家写法不通用** —— 只有 `QRCodeWriter` 收
  `ErrorCorrectionLevel`，Aztec / PDF417 收的是整数，一律塞进去它们会在 `Integer.parseInt("M")`
  上抛出来；② **编不出码只让这一格出空白并记 warn**（EAN_13 只收 13 位数字、CODE_39 不认小写），
  500 行里有一条脏数据就整份渲染失败没法交代 —— 同 `ImageLoader` 对坏图链的态度。
- 公式两条路：`!{}` 走 Aviator（`FormulaEvaluator` + `GridFunctions`，区间函数在**渲染后的网格**上求值），
  `=` 原生公式只做 A1 引用偏移（`A1RefUtils`）后交给 FortuneSheet 前端算。
- `RenderServiceImpl` 手写构造器而非 `@RequiredArgsConstructor`：容器里有两个 `ObjectMapper`，
  必须 `@Qualifier("mzObjectMapper")`。改这个类时别顺手换成 Lombok。

### 三处共用同一份 `pageConfig`

打印设置（纸张/方向/页边距/缩放/打印区域/limitWidth/顶端标题行/页头页尾/水印）同时驱动：
① 设计器画布上的分页线与打印区域叠加层，② 预览页浏览器打印的 `@page`，③ Excel 导出的页面设置。

**顶端标题行**（`pageConfig.titleRows`，形如 `"1:3"`）= 跨页重复的表头，与页头页尾同一套路数：
`ExcelExporter#applyTitleRows` **一处**写进 xlsx 的 `_xlnm.Print_Titles`，
`PdfExporter#readTitleRows` 与 `WordExporter#titleRows` 从 xlsx 读回来。
PDF 那条路的做法是**把标题行从分页的行流里摘掉**（`Geom#paginate`）、每页预留它那么高，
再由 `drawPage` 分两段画（标题行一段、正文一段，`drawBand` 两段共用）；Word 交给
`w:tblHeader` 让 Word 自己重画。画布分页线也要跟着扣掉这一截（`utils/print.js#titleRowBand`），
否则设计器上看到的分页位置和出纸对不上。**只认内容范围最上面的连续若干行**，
落在中间的三条路语义对不齐（PDF 是照行流分页的），一律忽略并记 warn ——
设计器在确定时就拦掉了，后端 **Excel 那道**（`applyTitleRows` 的范围校验）是给手写 content 的：
Excel 本身写得进中间的重复行，放过去就是「Excel 里能重复、PDF 里没有」。
PDF 还多一道「**标题行占掉半页以上就不生效**」（`Geom#titleEnabled`）——否则正文被挤成一行一页；
这个判定分页与绘制**必须共用**（两边不一致，行流里没摘掉的标题行会再被标题带盖一遍），
而且要用撑高后的行高判（`readTitleRows` 那道跑在 `growWrapRows` 之前，只够拦静态行高）。
`perRowPage` 那种拼接出来的 sheet 上别配它：每一份自己都带着表头，再重复一遍就是两份表头
（Excel 里同样如此，不特殊处理）。

**预览页不在这三处之内**：它整个走的是导出那条路。预览区默认就是**后端出的那份 PDF**
（`ReportPreview.vue` 的 `viewMode`：`pdf` 默认 / `sheet` 只读 FortuneSheet，顶部可切）——
请求 `export/pdf` 拿 blob 挂给 `<iframe>`，所见即所打。「打印」按钮同一条路
（`utils/printPdf.js`：blob 挂进隐藏 iframe 再 `contentWindow.print()`），**打的范围跟着看到的**：
PDF 视图下就是屏幕上那份整本（直接复用手上的 blob，不再请求），表格视图下带 `sheetIndex`
只打当前那张。网页那条路出纸永远差一口气（页头页尾、页码画不出来，分页只能靠 `@page`
大致对齐），所以打印质量的问题一律去 `ExcelExporter` / PDF 转换器那边找，别在 `@media print` 里补。
`@page` 与 `position:fixed` 水印只剩表格视图 + 用户直接按 Ctrl+P 这一种情况，仍归 ② 管
（PDF 视图下水印是后端画进 PDF 的，那层 fixed 水印要关掉，否则打出来两个）。

PDF 视图下**一次查询打两次后端**（JSON 渲染 + 导出 PDF，`refresh()` 里并行发）：分页条的
总条数与版本标签只有渲染结果带得回来，导出接口只还字节流。别为了省这一次就把分页条摘了。

**打印设置是按 sheet 生效的**：`content.pageConfigs[sheetIndex]` 是该 sheet 的覆盖值，
没设过就退回报表级的 `content.pageConfig`（老报表只有后者）。取值一律走
`ReportContentDTO#pageConfigOf` / `utils/print.js#pageConfigOf`，别直接读 `content.pageConfig`
——那是「默认值」不是「生效值」。设计器里 `store.pageConfig` getter 已按当前 `sheetIndex` 解析好。
改打印相关行为时三处都要检查：`frontend/src/utils/print.js`、
`frontend/src/views/designer/components/PrintAreaOverlay.vue`、
`backend/.../export/ExcelExporter.java`、`backend/.../dto/PageConfigDTO.java`。

**图片也归 `ExcelExporter` 一处管**（`applyImages`）：按 celldata 上的 `v.mzImg.src` 取回字节
（`export/ImageLoader`：`data:` URI 解码、http(s) 下载，超时/超限/取不到就跳过那一张，
不让整份导出挂掉），锚进 xlsx。**锚点框 = 图片等比例装进格子后居中的那个框**，不是整格
（Excel 会把图片拉伸填满锚点框），并且用 `MOVE_DONT_RESIZE` —— 跟随缩放的话，列宽一变
图片就被横向拉长了。PDF / Word 两条路都从这份 xlsx 读回来：
`PdfExporter#readImages` 连**格内偏移**一起读（丢了偏移就等于重新拉伸到满格）；
`WordExporter#readPictures` 只取占了哪几行哪几列，再按 Word 自己的列宽重新装一次
（`readPictures#lastIndex`：终点偏移为 0 表示贴着那一格的边、占的是前一格）。
**别在各条路上再写一遍取图** —— 一张图会被下载三遍，还会三处不一致。

「预览里有图、导出的文件里没有」是这块最常见的报障，两类原因：
① **取图的是服务端**（预览是浏览器）—— JDK 的 HttpClient 默认不走系统代理
（已显式挂 `ProxySelector.getDefault()`）、图片地址要登录态、存的是相对路径
（`muzhou.report.image.base-url`）；② **格式**——浏览器认的图片格式比 Excel 多得多，
xlsx 只收 PNG/JPEG/GIF/BMP，`ImageLoader#toPicture` 会把 ImageIO 解得开的（TIFF 等）转成 PNG，
**ico / webp / svg 解不开只能跳过**（演示数据一度用 `favicon.ico` 当图片地址，
预览好好的、导出永远是空的，查了半天）。每种失败都记了 warn 并写清是哪一种，排查先看那一行。

前端预览是另一回事：FortuneSheet 的格子画不了图，`utils/cellImage.js` 把 `v.mzImg` 摊成
`sheet.images` 浮动图片（坐标 = 行高列宽累加），所以那边的几何要自己算、且必须排在
`applyWrapRowHeights` 之后；「等比例居中」靠 `styles/index.css` 里那条 `object-fit: contain`
（FortuneSheet 给 `<img>` 写死了宽高，不加就是拉伸）。

PDF / Word 导出**不是第三处**：两条路都是把 `ExcelExporter` 出的 xlsx 读回来，照着里面已经写好的
页面设置分页再画。所以改 pageConfig 只需管好 ExcelExporter，PDF/Word 自动跟着走；反过来，
ExcelExporter 里页面设置的 bug 会同时体现在 PDF 和 Word 上。都是纯 Java，
不要引入 LibreOffice / MS Office 这类外部转换器。

**边框同理，只在 `ExcelExporter#applyBorders` 一处翻译**（`config.borderInfo` → xlsx 的单元格边框），
**线型和颜色都要照搬**。曾经这里一律写「THIN + 不写颜色」，只还原「有没有边框」这一层语义：
不写颜色在 OOXML 里等于 `auto`，而 PDF / Word 取不到颜色时各自退回黑色
（`PdfExporter#line` / `WordExporter#border`）—— 于是**用户把边框设成白色（最常见的「把这条线
擦掉」的做法）导出后三种格式里照旧是黑线**，`border-none`（无边框）反而画出一圈。
两种形态都要认：`rangeType=range`（区域 + `border-all/outside/inside/horizontal/vertical/
top/bottom/left/right/none`）与 `rangeType=cell`（逐格的 `value.{l,r,t,b}`，复制粘贴时
FortuneSheet 写的就是这种）；**顺序即优先级**（后刷的白色覆盖先画的黑框），所以要先顺序合成
一份「每格四条边」再统一往单元格上写，不能边遍历边往格子上盖。**区域边界上还要把外面那一圈
已经有边框的邻格朝向这里的那条边一起改掉** —— Excel 里相邻两格共用一条线，只改自己这边的话
邻格那条老边框仍然画得出来，「刷成白色却还是黑线」就是这么来的（邻格本来没边框就别凭空加）。
线型码 0–13 与 Excel 的边框线型一一对应（见 @fortune-sheet/core 的 `setLineDash`），
斜线 `border-slash` 跳过。逐格边框的坐标是 `value.row_index/col_index`，扩展与拼接时
**要和 `range[].row` 一样偏移**（`ExpandProcessor#remapCellBorder` 还得按行带复制成 n 条，
`SheetConcat#shiftBorder`），漏了就是「复制粘贴出来的边框全堆在第一行/第一份上」。

**水印是这条规则唯一的例外**：xlsx 里没有水印这个概念（Office 的「水印」是往页眉塞图片伪造的），
存不进去，所以 PDF / Word 的转换器除了 xlsx 还要额外收一份 `PageConfigDTO`
（`RenderServiceImpl#exportPdf/exportWord` 传）。**页头页尾不走这条**，它照旧存在 xlsx 的页眉页脚里
让下游读回来，报表里的 `${page}` 与 Excel 的 `&P` 之间由 `export/HeaderFooterText` 互转
（编、按 `&L/&C/&R` 拆段、解格式码三步都在那儿，用户文字里的 `&` 必须转义成 `&&`，
否则「A&C公司」的 `&C` 会被当成分段标记）。加通道时先看 CONTRACT §4 的能力表——
不是每条路都做得到：xlsx 没有水印，Word 的水印只能在正文下面，浏览器打印没有页头页尾。

**页头页尾里没写页码的 sheet 不参与页码统计**（`HeaderFooterText#hasPageNumber`）：封面 + 正文 +
总结这种报表只有正文写了 `${page}`，正文就该从「第 1 页」数起、「共几页」只数它自己那几页。
判的是**印不印页码**而不是「有没有页头页尾」—— 只写标题的封面照样不占页数，而印了页码的必定被算进
总数，不会「印了却没算」。PDF 整张跳过（`PdfExporter#numbering` 里 `Geom.numbered` 为 false 的页
既不占页号也不计总数），Excel 只能把起始页号钉成 1（`ExcelExporter#pagePins`，`&N` 恒是整本页数
改不了），Word 做不到（页眉页脚整份一套、跟第一张 sheet）。
**跳过与「按单据编页码」叠加时有个必踩的坑**：一条数据出封面/正文/总结三张时，单据的起点
（`mzDocBreaks` / Excel 那边的钉页号）落在**封面**上，而封面不参与统计 —— 见到它就跳过的话
各条数据的正文被并成一组，「共几页」变成**全部数据**的页数。所以起点要攒着（`numbering` 的
`pending`）、留给下一张印页码的页；Excel 同理，钉在每份单据里**第一张印页码**的 sheet 上而不是
起点那张（钉在封面上，正文第一页在 Excel 里印出来还是「第 2 页」）。

**xlsx 页眉串里的字号恒写两位**（`&09` 不是 `&9`）：字号码是「`&` + 一串数字」，紧跟的文字也以数字
开头就分不清边界 —— 页头写「2026 年度经营分析」会被解成 92026 磅，页头大到把正文挤成一行一页，
「2026」也跟着没了。`append` 写两位、`parseParts` 只吃两位，两边配对，别只改一边。

页头页尾**画在页边距里**（同 Excel），页边距装不下时正文往下让。这个 `margin + fontSize × 1.35`
的换算在四处必须一致：`PageConfigDTO#effectiveMarginTopMm`、`utils/print.js#effectiveMarginsMm`
（画布分页线要跟着上移）、`PdfExporter#readHeaderFooter`、`WordExporter#reserve`。
Excel 自己会让，`ExcelExporter` 不用管。

- `export/PdfExporter.java`：PDF 只此一条路 —— POI 读 xlsx + OpenPDF 逐格画，只还原报表用得到的
  那部分表现（含自动换行：`wrapLines` 按格宽折行，此时不再往相邻空格上溢出）。
  **没设打印区域时内容范围从 A1 起算**
  （`contentRange`），不是从第一个有值的格子起算 —— Excel 排的就是 A1 到最后一个有内容的格子，
  左上角的空行空列照样占位。从第一个有值的格子起算会把表整体挪到左上页边距，内容宽度也少算掉
  前面那几列，于是「设成 A4 横向了，内容却只铺了纵向那么宽」，fitToWidth 的缩放和分页位置
  跟着一起错，与导出的 Excel 对不上。
- 环境依赖是**中文字体**：PDF 必须内嵌字体（`muzhou.report.pdf.font-path`，会自动探测系统字体）。
  纯 Java，不要为了「还原度更高」再引入 Aspose / LibreOffice / MS Office 这类外部渲染器 ——
  以前挂过 Aspose.Cells，商业授权、没 `.lic` 就带评估水印，已经整个删掉了。

Word 那条路是 `export/WordExporter.java`，用 POI **转结构**而不是渲染 ——
逐格搬成一张真正的 Word 表格，能在 Word 里继续改。两个必须记住的点：
**横向只取到打印区域为止**（Word 的表格不能横向分页，比正文宽的表会溢出到纸外面），
**纵向不裁**（打印区域是模板期设的，扩展出来的数据行本来就长到它下面去，按它裁会把数据切掉）。
**每张 sheet 自成一节**（docx 的页面设置是按节存的）：前面几张的 `sectPr` 挂在各自末尾那个
空段落的 `pPr` 里（「下一页」分节符，自带分页所以不再插 page break），最后一张挂在 body 上；
表格铺的是**该 sheet 那一节**的正文宽度（`printableWidth`），第二张设成横向就该铺横向那么宽。
分节符里必须把 body 上那套页眉页脚的 `headerReference` / `footerReference` 抄一份 ——
一节没写引用时 Word 是往**前**一节找，不抄的话页头页尾只出现在最后一张 sheet 上。
（页眉页脚与水印本身仍是整份一套、跟第一张 sheet：每节各一套要给每节建一个 header 部件。）

边框要**自己声明、自己写颜色**：POI 建表时会塞一整套 `<w:tblBorders>`（六条边全 `single`，
**不带 `w:color`**）。不带颜色在 OOXML 里等于 `auto`，Word 是拿它跟单元格底色反推的 ——
深色表头上的边框直接被判成**白色**，而且这套默认边框画在每一条格子边上，Excel 里没画边框的地方
也有线。所以 `layoutTable` 把表格级的六条边全抹成 `nil`（Word 里单元格边框优先于表格边框），
`applyBorders` 只画 Excel 里真有的边、颜色照搬那条边的颜色，取不到就退回**黑色** ——
与 `PdfExporter#line` 同一条规则。

合并单元格是这条路最容易写错的地方，三件事都不能少（`applyMerges`）：
① 合并后的 `tcW` 要改成**被跨掉那几列的宽度之和**，只留第一列的宽度，固定布局下整行都会错位；
② 同一行有多个合并时**从右往左**处理，删格子只影响右边的下标，反过来做会串位；
③ 表格必须写 `<w:tblGrid>`（POI 建表时不写），固定布局下 Word 是按它定列的，缺了列宽不作数。

### Excel 导入（`export/ExcelImporter.java`）是导出的逆映射

把一张现成的 xlsx 读成设计器能打开的版式（`ReportContentDTO`），省掉照着它重画表头、边框、
合并、列宽的功夫。**只做静态版式**：产出的 `cellConfigs` 恒为空，占位符与取数由用户导入之后
在设计器里手工配 —— 猜绑定猜错了让人逐格去拆，比自己画还慢。纯 POJO、不依赖 Spring，
静态入口 `ExcelImporter.parse(bytes)`。只收 `.xlsx`。

**它和 `ExcelExporter` 必须对着改**：值/样式/合并/行高列宽/边框/页面设置，每一项都能在正向
那边找到对应的一处（`resolveStyle` / `applyConfig` / `applyBorders` / `applyPageSetup`），
`ExcelImporterTest` 用「content → 导出 xlsx → 导回来 → 逐项比对」的往返用例锁着。
页头页尾走 `HeaderFooterText#fromExcelCode`（`toExcelCode` 的逆，**占位符原样还回去**
而不是像 `parse` 那样展开成当时的页码 —— 展开了就成了死值）。

**数值和日期一律落成文字**（`DataFormatter` 取「Excel 里显示的那串」）：渲染时每个格子的 `ct`
都会被 `CellFormatter` 重建（没配过格式的格子拿到的是 `General`），模板里带的 `ct.fa` 活不到
出纸那一步 —— 老实照搬「值 + 格式串」的话 `1,234.00` 出成 `1234`、日期出成序列号 `45878`
（`CellFormatter#toDateTime` 不认序列号）。代价是这些格子在导出的 xlsx 里是文本、不能在 Excel
端继续算；原本是数值的格子补一个 `ct={fa:"@",t:"s"}`，免得前端或二次导出又识别回数字。

几条容易写反的：**边框逐格照搬成 `rangeType=cell`，不必去重** —— xlsx 里相邻两格虽然共用一条
线，但写回去时 `applyCellBorderEntry` 也只管自己那一格；线型和颜色照搬（白边框那个坑）。
**行高列宽逐行逐列全写** —— Excel 默认 ≈17.6px / 64px，设计器是 19px / 73px，缺一项整表就偏、
分页位置跟着错。**没显式写过纸张的按 A4 收** —— OOXML 里 `paperSize` 的缺省是 1（Letter），
国内的表多半是 A4 只是没落这个属性。内容范围**不裁空行空列**：`cellConfigs` 按 `sheetIndex_r_c`
寻址，挪了坐标用户回头绑定就对不上号了。**`fitToWidth` 是不可逆的一项**（正向 `limitWidth`
兜底也写成 `fitToPage`，反读分不清是谁写的），与上面那条数值变文本一样，各有一个用例说明，
别当 bug 去「修」。带不过来的（水印/条件格式/数据有效性/图片/图表/隐藏 sheet…）逐条记
warning 返回给用户 —— 不说清楚就是「导进来不对」的报障。

### 自动换行的行高要自己算

FortuneSheet **只在改字号(fs)时重算行高**，改自动换行(tb)时不算（core 的 `updateFormatCell`）；
**Excel 打开文件时也不会重算行高**，行高就是文件里存着的那个数。两头都不算，结果就是
「换行了但只看得到第一行」。所以行高在三处各补了一刀，**用的必须是同一个公式**
（`行数 × 字号 × 1.35 + 内边距`，改一处就要改另一处）：

- `frontend/src/utils/wrapHeight.js`：`computeWrapRowHeights` 用画布 measureText 量，
  设计器在 `onSheetChange` 里调（`autoFitWrapRows`），预览页拿到渲染结果后调 `applyWrapRowHeights`。
- `backend/.../export/ExcelExporter.java#applyWrapRowHeights`：导出时按**运行时真实文字**再算一遍
  —— 扩展出来的数据行文字长度模板期算不出来，只能在这里补。字宽按「全角 1 个字号、半角半个」估，
  不加载字体（服务器未必有那款字体）。

三处都是**只增不减**（不改小用户拖出来的行高）、**只折了一行就不动**、**跨行合并跳过**。

OpenPDF 那条导出路上还有第四刀：`PdfExporter#growWrapRows` 在分页前**按 PDF 真正内嵌的那款字体
再量一遍**，不够高就把行撑高。上面的估算是「全角一个字号、半角半个」，而中文字体里半角字符
往往更宽（微软雅黑的 latin 就是），破折号「—」这类还被估成半角、实际是全角，同一个格宽实际
折出的行数会比估算多，行高不够时垂直居中的文字上下两头就被 clip 掉，看着像「字被遮挡了一半」。
它用的宽度/字号/行距与 `drawText` 同源（所以算多高就画多高），规则同样是只增不减、跨行合并跳过。
Excel / Word 不需要这一刀 —— 它们按自己的字体折行，与估算对得上，所以这个偏差只有 PDF 露馅。

**撑到比一页还高也照撑，然后由 `Geom#splitRows` 把那一行横着劈开跨页印**（`RowBlock#skip` =
这一页要从这行的第几磅接着印，`drawPage` 据此把行的上边界抬到正文区外面，再照这一段
**实际印到哪儿**（`RowBlock#height` → `Band#bottom`）裁一刀 —— 裁在页边距上是不够的，
见下面「切口」那段）。
早先是「行高按一页封顶 + 装不下就整行挪到下一页」，而超高行挪过去照样放不下，于是
**第一页只剩表头、下面大片空白**（长备注格是典型触发点），封顶砍掉的文字还再也印不出来。
同理 `scaleOf` **不再按最高的那一行缩放**——那会把整张表连表头一起缩小，而缩完那行仍是一页高、
照旧独占一页，空白一点没少。三件事是一套，别只改其中一件。
劈开时 `drawText` 要**把整行落在本段外面的那些文字行跳过**：裁剪只是让它们看不见，
字仍然写在这一页的内容流里，PDF 里一搜就是每页都带一整段。列方向没有这回事
（`split` 照旧整列走，比一页宽的列只能靠缩放兜底）—— 字劈成左右两半没法看。

**切口要躲开文字行**（`Geom#snapToLines` + `growWrapRows` 记下的 `Geom#lineGrid`），否则一行字
上下两页各露半个。这里有两个必踩的坑：① 切口往上挪之后页底空出一条，**裁剪必须跟着挪到切口上**
（`Band#bottom`），裁在页边距上的话那一条里会露出下一行文字；② 中文字体的字面高度比行距略大，
**相邻两行的字面本来就叠着一点**，所以「整行在外面才跳过」永远跳不干净 —— 对齐过的行改判
**露不到一半就不画**（`drawText` 里的 `snapped`），一行字只属于一页。没对齐过的（硬切）照旧
两页各画半个，不然那半个字就彻底没了。

### FortuneSheet 的 Vue 桥接（`components/FortuneSheet.vue`）

官方只有 React 包。该组件在 `onMounted` 用 `react-dom/client` 的 `createRoot` 挂 `<Workbook>`，
对外只暴露 Vue 的 props/emits/`defineExpose`（`reload` `getAllSheets` `setCellValue` `measureCanvas` 等）。
业务代码不应出现 React。三个必须记住的坑：

1. **`data` prop 只在挂载时读一次**，之后由 FortuneSheet 内部状态接管。
   要整体换数据必须 `sheetRef.reload(newSheets)`（会重挂工作簿），异步数据要等就绪再挂载组件。
2. **`getAllSheets()` / `change` 返回的是运行时结构（二维 `data`，`celldata` 已被删）**，
   持久化前必须过 `utils/sheet.js#toCelldataSheets` 转回 `celldata`，
   否则后端 `TemplateParser` 解析出空模板。
3. **切换工作表没有可靠的钩子**：`afterActivateSheet` 只有 core 的 `changeSheet()` 会触发
   （新增工作表、超链接跳转），而**点标签页是 React 层直接改 `context.currentSheetId`**，
   删除工作表也一样，都不走 `changeSheet`。所以 `sheet-activate` 事件由桥接自己合成
   （`syncActiveSheet`：标签栏点击 + `change` + 两个钩子，比对 id 变化才发），
   业务侧只管监听 `@sheet-activate`。这个事件断了就意味着「当前是第几张 sheet」永远是 0，
   按 sheet 生效的打印设置（`pageConfigs`）、`cellConfigs` 的 key 会全写到第一张上去。

打印区域/纸张边界不画在 canvas 上，而是 `#overlay` 具名插槽里的 DOM 叠加层，
坐标来自渲染钩子采样（`beforeRenderCellArea` 清缓冲 + `afterRenderCell` 采样实际像素），
不要改成自己按行高列宽推算——会有像素误差。

### 数据集与动态数据源

数据集有**作用范围**：`mz_dataset.report_id` 为空串是公共数据集（所有报表可用，在数据集管理页建），
非空则是那张报表的内部数据集（只有它能用，在设计器左侧面板里建）。空串而不是 NULL —— 唯一索引
`uk_dataset_code_scope(code, report_id)` 里 NULL 是可以重复的，用 NULL 就拦不住公共集重名了。
由此推出的三件事：**取数必须知道「谁在渲染」**（`fetchDataByCode(reportId, code, params)`，
先找内部再找公共；引擎那个 `BiFunction` 签名没变，报表 id 是 `RenderServiceImpl#dataFetcher`
绑进去的）；**免保存预览的请求体要带 `reportId`**（content 里没有 id）；**报表删除/复制时
内部数据集要跟着删/跟着复制**（`ReportServiceImpl` 调 `DatasetService#removeByReport/copyToReport`，
不复制的话副本里的 `#{code.字段}` 会全部取不到数）。code 只在同一作用范围内唯一，
不同报表的内部集允许同名，所以任何按 code 查询的地方都得带上 report_id。

`DynamicDatasourceRegistry` 运行时注册/切换数据源（dynamic-datasource），`JdbcExecutor` 执行查询。
`SqlParamParser` 是安全边界：`${param}` 转成 JDBC `?` 顺序绑定；`$!{param}` 才是字符串拼接，
仅用于动态表名/排序且做标识符白名单校验。改这里必须同步 `SqlParamParserTest`。

**引擎按字段名取值是大小写敏感的**（`ExpandProcessor` 的 `row.get(field)`），所以「行数据的 key」
与「字段名」必须是同一个写法，各自的来源要对齐：

- **api / json**：`DatasetServiceImpl#toRows` **原样保留接口返回的 key**，「解析字段」也是照这份
  key 生成的（`fieldsFromRows`），两边天然一致；外部导入进来的报表带的是接口的 camelCase
  （`SpreadsheetImportTool` 原样抄 `fieldName` 与 `#{code.field}`），同样对得上。
  **别在这里做大小写归一** —— 曾经统一转小写，接口还的 `suppliesName` 落到行里成了
  `suppliesname`，模板里 `#{code.suppliesName}` 就永远取不到数，而恰好全小写的字段
  （seq / brand / unit）又是好的：现象是「数据集预览里明明有值，报表上一半列空着」，
  最难查的那种（`DatasetRowsCaseTest` 锁着）。
- **SQL**：列标签由数据库给、各家大小写规则不一，`JdbcExecutor#label` 统一小写，
  「解析字段」用的是同一个 label，自成一套也是对齐的。

`ReportRenderEngine#fieldText`（sheet 名/单据名）、`LinkedDataFetcher#fieldValue`（父子关联传参）、
`RenderServiceImpl` 的逐行版本 picker 这三处**额外**有「取不到就退回小写」的兜底 ——
那是给用户在配置里手写字段名准备的，不是取值的通用规则。

数据集还有**返回结果形态** `mz_dataset.result_type`：`list` 集合（整份取回，默认）/ `page` 分页
（响应里带数据数组 + 总数）。分页型认不出结构时**必须报错**，不许静默降级成集合 ——
翻页和总页数会跟着一起错，兜底只会让人以为「数据不全」。总数是这样带出来的：
引擎的取数函数只还行数据，所以 `DatasetService#fetchRowsByCode` 返回 `rows + total`，
`RenderServiceImpl#dataFetcher` 在闭包里拿个 map 把各数据集的 total 收着，
渲染完按 `content.primaryDataset`（**主接口**，一张报表一个，记在报表里而不是数据集上 ——
公共数据集被多张报表共用）挑出那一个放进 `RenderResult.total`。引擎两件事都不知道。
分页条只在「主接口是分页型」时出现，翻页 = 带着 `pageNo`/`pageSize`（**保留参数名**）
重新渲染整张报表。

主接口还管第二件事：`content.splitMode`（打印设置弹窗的「输出」页签）非 `single` 时
**按主接口的每一行拆**，一条数据一张单据。`perRow`（拆成 N 张 sheet）**已从设计器下线**
——它和下面的 `perRowPage` 是同一套拆分的两个出口，功能重叠，而 M×N 张 sheet 会把同一条数据的
几张单据打散在整个工作簿里；`stores/designer.js#setSheetSplit` 把它一律迁成 `perRowPage`。
**引擎这一份实现要留着**（老报表照旧渲染），下面讲的也仍是它。
实现在 `ReportRenderEngine#renderPerRow`：
不是改扩展逻辑，而是**换掉取数函数** —— 第 i 遍渲染时主接口只返回第 i 行，其它数据集返回全量
且缓存复用（否则 N 条数据会把从表接口打 N 遍）。拆出来的 sheet 必须重编 `id`/`order`/`status`，
名字取 `sheetNameField` 字段值（重名挂 `(2)`，多模板补模板名）。**`sheetNameField` 两种拆法共用**：
`perRowPage` 拼回一张后 sheet 名说不了话，它改落在 `mzDocNames` 上给页头页尾的 `${sheet}` 用（见下）。
**由此带出一个容易漏的坑**：`pageConfigs` 是按**模板**下标存的，拆完之后结果 sheet 是模板的 N 倍，
所以导出和预览取打印设置一律走 `pageConfigOfRendered`（`renderedIndex % 模板张数`），
后端 `ReportContentDTO` 与前端 `utils/print.js` 各一份，改一处要改另一处。

`splitMode` 的另一个值 `perRowPage`（每条数据一页，**设计器界面上叫「多 sheet 输出」**，
现在只有它和 `single`）：
拆分**完全复用上面那段代码**
（`splitByRow()` 两种模式共用），只在 `renderPerRow` 的**出口**多一道拼接
（`engine/SheetConcat`）—— 渲染出来的那一摞**按原顺序**首尾相接摞成一张，Excel 里是一张
连续的表，打印时靠行分页符保证一份一页。要偏移的有五样：`celldata[].r`、`v.mc.r`、`v.f` 里的
原生公式引用（`A1RefUtils.shiftFormula`）、`config.merge`/`rowlen`、`borderInfo[].range[].row`，
**漏一样就是这块最典型的 bug**；每份高度取这四处的最大值，只看 celldata 会吃掉「有边框没有值」
的尾行。`!{}` 公式不必特殊处理 —— 它在每一份自己的网格上求值完就落成字面值了，
所以「本单据小计」天然只统计本单据。

**模板有多张 sheet 时，摞的顺序是「数据1的模板1/2/3、数据2的模板1/2/3」**（就是 `renderPerRow`
的行优先输出顺序，`SheetConcat` 原样照抄）—— 一条数据的几张单据挨着打才对得上号。
早先是按模板分组（每个模板各拼一张），出纸变成「全部数据的模板1、全部数据的模板2」，
同一条数据的几张纸被拆到工作簿两头。跨模板挨着排只能在同一张 sheet 里做（sheet 之间必定断页），
而**一张 sheet 只放得下一套页面设置**，所以**拼接只发生在打印设置相同的相邻份之间**：
三张模板同设置就拼成一张，第 2 张单独设了横向就在那里断开、它自成一张。
**不比对就拼的话，横向那张被拼进纵向那张里，导出全变纵向** —— 这块踩过的坑。
一张里含几张模板时列宽逐列取最大值（一列只有一个宽度）。

由此**结果 sheet 数不再是模板张数的整数倍**，`% 模板张数` 那套推算失效：每张结果 sheet
带一个 **`mzTemplateIndex`**（`toSheet` 写、拼接时取该组第一份的）。
**再往后（逐行选版本，见「报表版本」一节）连 `mzTemplateIndex` 也不够用了** —— 各版模板张数
可以不同，光知道「第几张模板」说不清是哪一版的第几张。所以现在打印设置由引擎随结果一起产出：
**`RenderResult.sheetPageConfigs` 与 sheets 一一对应，导出与预览一律优先认它**
（`RenderServiceImpl#pageConfigAt` / `utils/print.js#pageConfigOfResult`）。
`ReportContentDTO#pageConfigOfSheet` 与 `utils/print.js#pageConfigOfRendered`（认 `mzTemplateIndex`、
再退到取模）只剩兜底作用，给老结果与手工构造的结果用。

**行分页符是 `sheet.mzRowBreaks`（值 = 每一份的起始行），一处写、两处读**：
`ExcelExporter#applyRowBreaks` 写成 Excel 的手动分页符（POI 的 `setRowBreak(r)` 是「在第 r 行
**下方**断页」，所以要减一），`PdfExporter#readRowBreaks`（喂给 `Geom.split` 的强制断点）、
`WordExporter#rowBreaks`（落成表格行首格的 `pageBreakBefore` —— 一张表中间插不了分节符）。
**别在各条路上再造一份**。xlsx 里除了手动分页符没有别的地方能表达
「这两条不许挤在一张纸上」，所以这条通道断了这个模式就等于没做，三条路各有测试锁着。

**页码也得按单据重编**：拆开之后每份都是一张独立的单据，页头页尾里的 `${page}` / `${pages}`
说的是**本单据**第几页 / 共几页 —— 整份连续编号的话第 7 张上印的是「第 7 页 共 20 页」，
对收到那一张的人没有意义。载体是 `sheet.mzDocBreaks`（每份**单据**的起始行，
`ReportRenderEngine#markDocBreaks` 与 `SheetConcat` 挂上，`single` 输出不挂 = 老的连续编号），
**它是 `mzRowBreaks` 的子集** —— 一条数据有 3 张模板时 3 份各占一页，但只有第 1 份是新单据的起点，
两者不能混用。**这一项存不进 xlsx**（Excel 只有 sheet 级的起始页号），所以和水印一样由
`RenderServiceImpl#renderToXlsx` 顺着方法签名递给 `PdfExporter#convert` / `WordExporter#convert`。
三条路能做到的**不一样**（CONTRACT §4 有能力表）：PDF 是逐页自己画的，两个数都对
（`PdfExporter#numbering`）；Excel 只能把那张 sheet 的起始页号钉成 1（`&N` 恒是整个打印任务的
页数，改不了）；Word 只能按**节**重编（`w:pgNumType` + 总页数换成 `SECTIONPAGES`），
而 `perRowPage` 全拼在同一张 sheet=同一节里，那个模式下 Word 出的页码仍是连续的。
要「本单据第 x 页 共 y 页」精确出纸就走 PDF（预览页的「打印」按钮本来就走它）。

**单据名同理**：`perRowPage` 拼成一张之后，页头页尾里的 `${sheet}`（Excel 的 `&A`）只剩工作表名
一个值，而纸是一份份发出去的 —— 每张该印自己那一份的单号。载体是 `sheet.mzDocNames`
（与 `mzDocBreaks` **一一对应**：一条数据有 3 张模板时 3 份共用一个名字、只记一次），
值就是 `sheetNameField` 那个字段算出来的（`ReportRenderEngine#docName`，与 `perRow` 的 sheet 名
**同一项配置、同一个值**，只是那边由 sheet 名自己承担、不挂这一项）。同样存不进 xlsx，
顺着 `RenderServiceImpl#renderToXlsx` 递给 `PdfExporter#convert`（`headerNames` 逐页选名，
取不到退回工作表名）—— **只有 PDF 收**，Excel 的 `&A` 与 Word 的页眉都只到工作表/节这一级，
拼在同一张里的几份分不开。

**父子关联（子接口查询）也是「换取数函数」这一招**：`content.datasetLinks` 里配
`主表 code + 子表 code + 参数传递(子表参数 ← 主表字段)`，渲染时由 `engine/LinkedDataFetcher`
把引擎认的那个取数函数包一层 —— 要子表数据时先取主表，主表每行调一次子表，N 次结果拼成一份，
并把主表这一行的字段合进它的每条子行（同名字段子表优先）。合并这一步是为了让「主表 + 明细」
在模板里就是**一条普通的扩展行带**（主表列配 `groupType=group` 就是跨行合并），
扩展/公式/格式化一步都不知道有父子关联这回事。四条约束都在这个类里：一个子表只能挂一个主表
（挂两个则取数规则有歧义）、不能成环、主表行数超 `muzhou.report.max-link-rows`（默认 500）报错、
主表被模板同时用到时只取一次（实例内按 code 缓存，**实例只活一次渲染**）。
与 `perRow` 叠加时关联要套在 perRow 取数函数**外面**（子表向里问主表要数据时拿到的是当前那一行，
于是只查这张单据的明细），并且**子表不能进 perRow 那个跨行复用的缓存**，否则每张 sheet 的明细都一样。
设计器入口在左下角（`DatasetPanel` 的「父子关联」区 + `DatasetLinkDialog`）。

**参数是「一路透传」的**：`ReportRenderEngine#mergeParams` 与 `SqlParamParser#resolveValues`
都只对**声明过**的参数补默认值/校验/转类型，没声明的原样留在 map 里传到底。前端因此把
预览/设计器地址上的 query 一并提交（`utils/params.js#queryParams`，见 CONTRACT §5），
`/preview/{id}?id=11233` 里的 id 不必在报表参数里声明就能被 SQL 的 `${id}` 和
api 数据集接口地址里的 `${id}`（`DatasetServiceImpl#substituteUrl`，值做 URL 编码）取到。
改参数合并逻辑时别顺手加「未声明就丢弃」的过滤——那会把这条链路整个掐断。
行数/超时/单元格数上限在 `application.yml` 的 `muzhou.report.*`，读取自 `MzProperties`。

**参数定义本身是全局的**（`mz_param` + 「参数管理」页 `/param`，CONTRACT §2/§3.5/§5）：
公司抬头、打印人这类每张报表都要的东西，一处配好所有报表都能用，**设计器里已经没有参数配置入口了**。
老报表 `content.params` 里那份照旧参与渲染 —— 渲染前由 `RenderServiceImpl#withGlobalParams`
把两份合成一份（`ReportParamDTO#merge`，同名时**报表那条整条覆盖全局那条**，不是只换默认值），
**引擎完全不知道有全局参数这回事**，它照旧只看 `content.params`，于是默认值填充、必填校验、
参数表单、`${}` 取值都不必各写一遍。合并挂在**两个入口**上：`parseContent`（库里存的 content
进本类的唯一通道，渲染/换版/`listParams` 都过它）与 `preview`（content 来自请求体，不走 parseContent）
—— **漏了后者就是「预览取不到全局参数、正式渲染又取得到」**。
`mz_param` 的唯一索引不带 deleted 条件，删掉再建同名参数会撞唯一键，
所以新建/改名前先 `MzParamMapper#purgeDeletedByName`（同 `uk_dataset_code_scope` 那个坑）。

### 报表版本：版本化的是「版式」，不是数据集

一张报表可以有好几份版式（`mz_report_version`，每行一份完整的 `ReportContent`），
用哪一份由**报表级**的一条规则决定（`mz_report.version_config`：依据主接口字段/报表参数/渲染当日 +
取不到时的兜底）。规则**不能放进 content** —— content 本身就是被版本化的那个东西，
放进去就成了「每个版本各有一套怎么选自己」，逻辑成环。数据集**不随版本走**（跨版本共用）：
数据集是「取数」，版本是「版式」；改 SQL 会同时影响所有版本，删字段会让老版本模板里的
`#{code.field}` 取不到数（版本管理页的「校验」按钮扫这个）。

**`mz_report.content` 已废弃**，只在懒迁移时读一次（`ReportVersionService#ensureMigrated`：
一条版本行都没有就用它建 v1，挂在 `getDetail` 与渲染入口上，靠 `uk_version_report_no` 兜幂等）。
**不做双写** —— 双写必然漂移。接口形态没变：`GET /api/report/{id}?versionId=` 把那一版的内容
装进 `content` 回给前端，`PUT /api/report` 带 `versionId` 就写进那一版。
报表删除时版本行跟着删、复制时**所有版本行一起复制**（漏了这条，副本是一张没有版式的报表）。

**每个版本只存生效起点，区间靠排序推，左闭右开**（`ReportVersionResolver`，纯 POJO 可直接测）：
存两端必然出现重叠和空洞。`effective_from` 为 NULL 的那一版是「最早的那一版」；
**停用的版本不参与推导**，它那段被前一版吞掉（临时回滚版式就靠这个）；判定值取不到时用默认版本
（`is_default`，报表内恰好一条，不能停用/删除）。**只有一个启用版本时直接短路**，
不去探测取数 —— 绝大多数报表只有一版，不该为版本功能多打一次 SQL。

**先有鸡还是先有蛋**：判定值来自数据、模板由判定值决定，所以渲染前要先探一次主接口。
那一次不能白取 —— `RenderServiceImpl` 给取数函数包了一层 `engine/CachingDataFetcher`
（按 `(code, params)` 记忆，只活一次渲染），探测用它、引擎也用它。
**缓存 key 必须含 params 并做不可变拷贝**：父子关联里「主表每行查一次子表」是同一个 code
不同参数，只按 code 缓存会把所有明细串成第一行的 —— 这是这一层唯一容易写错的地方，测试锁着。

**`versionId` 必须走请求体**（渲染四个接口 + preview 都收）。它是**保留参数名**：
地址上的 query 会被 `utils/params.js#queryParams` 原样透传成报表参数，`?versionId=xxx`
不剔掉就会跑进 SQL 的 `${versionId}` 里去（设计器/预览页确实用地址记着当前版本，
所以有 `RESERVED_QUERY` + `queryVersionId` 这一对）。**预览、导出、打印四处都要带同一个
versionId**，漏一处就是「预览看的是 v2、导出的是 v3」。

`perRow` / `perRowPage` 时版本是**逐行**选的（「打印 3 月到 9 月的一批单据，每张按自己的下单日期
用对应版式」）：照旧是**换函数** —— 引擎的 `render` 多收一个 `versionPicker`，
基准 content 恒取默认版本（`splitMode` / `primaryDataset` / `datasetLinks` / 参数都以它为准），
换掉的只有模板与打印设置。**引擎依然不知道「版本」是什么**。
由此**结果 sheet 数不再是模板张数的整数倍**（各版模板张数可以不同），`% 模板张数` 与
`mzTemplateIndex` 都不够用了，所以引擎直接在 `RenderResult.sheetPageConfigs` 里给出**每张结果
sheet 的打印设置**，导出与预览一律优先认它（`RenderServiceImpl#pageConfigAt` /
`utils/print.js#pageConfigOfResult`），认不到才退回老的推算。`SheetConcat` 也因此不再按
「第 i 份出自第 i%m 张模板」推算，改成由调用方把每一份的模板与打印设置一路传进来。

### 报表导入导出：包里只有 code，没有主键

报表列表页勾几张 → `POST /api/report/export` 出一个 JSON 包（`.mzreport.json`），
到另一个环境 `POST /api/report/import` 导进去（`service/ReportPackageService` + `dto/ReportPackageDTO`）。
包里装的是**报表定义 + 全部版式 + 内部数据集 + 内容里引用到的公共数据集**。四条规矩：

- **一律按 code 引用，不带任何主键** —— 主键是 UUID，两个环境必定对不上。报表认 `code`、
  数据集认 `code`、数据源认 `datasourceCode`。
- **数据源不进包**：它带着业务库地址与口令（口令还是 `WRITE_ONLY` 的，压根导不出来），
  两个环境的连接信息本来就该不一样。目标环境没有这个 code 时**不拦**，导入照常、只报一条 warning
  —— 版式和数据集都是对的，只差一个连接，卡住整张报表没有意义。
- **公共数据集也一起打包**（`shared=true`），不带的话报表进了目标环境就是一张取不到数的空表；
  引用要从**三处**扫全（单元格绑定、`primaryDataset`、`datasetLinks` 的主表/子表，
  `referencedCodes` 一处），漏一处就是「某个数据集没跟来」。但导入时**公共数据集只新建不覆盖**
  —— 它被多张报表共用，为了导一张报表改掉别人的取数是灾难。
- **一张报表一个事务**（`TransactionTemplate`，不是 `@Transactional` —— 循环里自调用不走代理，
  注解压根不生效，整包会退化成「一张失败全部回滚」），失败的那条单独列在结果里。

`overwrite` 时**报表 id 不变**（外部系统拿 id 甚至业务 KEY 开设计器，换 id 那些链接全断），
换掉的是元信息（走 `ReportService#overwriteMeta`，**空值也照写** —— 包里没写版本切换规则时
目标环境那份得清掉，否则导进来的报表按一条谁也不知道的老规则选版本）、版式与内部数据集。

**替换这两样都得躲开「唯一索引管着已逻辑删除的行」这个坑**，各自封在自己的服务里：
`ReportVersionService#replaceVersions` 按 `versionNo` 对齐，包里的号在目标环境被用过（哪怕那行是
删掉的）就改发 `maxVersionNo+1`，换完由 `normalizeDefault` 保证仍恰好一条默认版本；
`DatasetService#replaceReportDatasets` 按 `code` 对齐（同 code 原地更新、id 不变），
并且**先把该报表下已逻辑删除的内部数据集物理删掉**（`MzDatasetMapper#purgeDeletedByReport`）——
`uk_dataset_code_scope` 不带 deleted 条件，上一次导入删掉的那条会挡住这一次同 code 的重建。

### 前后端对应关系

`frontend/src/api/{datasource,dataset,report,render}.js` 与 CONTRACT §3 的接口一一对应；
axios 拦截器（`api/request.js`）已解包 `Result`，业务代码直接拿 `data`。
设计器状态集中在 `stores/designer.js`（content、cellConfigs、选中单元格、数据集列表）。
`cellConfigs` 的 key 恒为 `` `${sheetIndex}_${r}_${c}` ``（`utils/sheet.js#cellKey`）——
扩展后行列会偏移，但 cellConfigs 描述的是**模板**坐标，不随渲染结果变。

data / formula / param 三类配置**渲染时以配置为准**（`TemplateParser` 见到显式配置就不看文本了），
格子里那串 `#{code.field}` 只是「绑定还在」的凭据 —— 于是**清空格子必须把配置一起回收**：
FortuneSheet 删内容只是把该格置成 `{}`，不删 celldata 条目也不发钩子，配置留着的话
画布上删干净了、预览导出照旧出数，空格子上的 `expandType=down` 还会凭空多出一条行带。
回收在 `stores/designer.js#setSheets` 里（`utils/sheet.js#pruneEmptyCellConfigs`），
`text` 类配置只有格式化，用户可能先设格式再填内容，不回收。手写 content 也守这条
（`db/mz_report.sql` 演示报表的合计行照样写 `#{orders.amount}`）。

`cellConfigs` 和 `pageConfigs` 都**按 sheet 下标寻址**（数组下标，不是 `id` 也不是 `order`），
所以 sheets 数组一变就得搬 key：删除工作表时 FortuneSheet 是把它 splice 掉的，
后面每张的下标都往前挪一位，不搬 key 的话配置会对到别的 sheet 身上（打印设置、单元格绑定全串）。
`stores/designer.js#setSheets` 每次都按 sheet `id` 比对前后两份 sheets 并搬
（`utils/sheet.js#sheetIndexRemap` / `remapCellConfigs` / `remapPageConfigs`，纯函数），
被删 sheet 的配置丢弃；载入时 `pruneSheetConfigs` 清掉老报表里下标越界的孤儿配置。
**所以 sheets 数组只能经 `setSheets` 改**，别在别处直接动 `content.sheets`。
拖动标签重排只改各 sheet 的 `order` 字段、不动数组顺序，但上面这套按 id 对齐，顺序真变了也对。

**行列这一维同理，但只能从 `op` 事件里认**：插入/删除行列时 FortuneSheet 只挪它自己那份数据
（celldata / merge / rowlen / borderInfo），`cellConfigs` 的 `r`/`c` 与 `pageConfig.printArea`
原地不动 —— 删掉顶上一行，占位符文本整体上移、配置留在原处，那个格子成了「有占位符没配置」，
它下面的空格子还凭空多出一条扩展行带。`change` 事件只给结果、认不出「删的是哪一行」，
所以设计器监听 `@op`（`ReportDesigner.vue#onSheetOp` → `stores/designer.js#applyRowColChange`
→ `utils/sheet.js#shiftCellConfigs` / `utils/print.js#shiftPrintArea`），**op 在 change 之前发出**，
挪完正好赶上随后的 `setSheets`。插入的载荷是 `{index,count,direction}`（`rightbottom` = 在 index
**之后**插），删除是闭区间 `{start,end}`，两者由 `normalizeRowColOp` 归一。
被删行上的配置要**攒着**（`store.droppedCellConfigs`，后进先出）：撤销时 FortuneSheet 发出的是一条
恰好相反的插入 op，位置行数与栈顶那次删除对得上就把配置放回去 —— 不放的话「删错了、Ctrl+Z」
回来的是一片没有绑定的格子。多 sheet 报表**不动报表级的 `pageConfig.printArea`**（它被所有 sheet
共用，跟着某一张挪会把别的 sheet 一起挪错）。

## 数据库

默认内嵌 H2 文件库 `backend/data/muzhoureport.mv.db`，`spring.sql.init` 每次启动执行
`db/schema-h2.sql`（建表）+ `db/*.sql` 那一批**按表拆开的 H2 导出快照**
（`mz_dataset*.sql` / `mz_datasource.sql` / `mz_param.sql` = 全局参数 / `mz_report.sql` = 演示报表，
`orders*.sql` 等 = 演示业务表），
清单写在 `application.yml` 的 `spring.sql.init.data-locations` 里，`continue-on-error: true`。

**这批数据脚本是纯 `INSERT`，不是 `MERGE`**：库已经建好之后再跑，主键冲突直接失败、被
`continue-on-error` 吞掉 —— 也就是**只对空库生效，不会把你在界面上改过的演示数据覆盖回去**。
建表语句同理（`CREATE TABLE` 失败即跳过），所以**新增表/新增列一律写成 `IF NOT EXISTS`**
（`mz_report_version` 与 `mz_report.version_config` 就是这么加的），别让日志里堆一片报错。
新增演示数据要改对应那份 `db/<表名>.sql`；切 MySQL 时 `character large object`→`LONGTEXT`。
主键统一 `VARCHAR(32)` + MyBatis-Plus `ASSIGN_UUID`。

演示报表的**版式**不在 `mz_report.sql` 的 content 列里生效 —— 首次启动后由懒迁移搬进
`mz_report_version` 的 v1（见「报表版本」一节），此后那一列不再被读。要改演示报表的版式，
改完 `mz_report.sql` 还得让 `mz_report_version` 里那行也没了（或者干脆删掉 `backend/data/`
重来一次），否则改的是没人读的那一列。
