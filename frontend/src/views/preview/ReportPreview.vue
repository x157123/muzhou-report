<!--
  报表预览页：整屏只读展示。顶部栏（返回/报表名/参数表单/查询/视图切换/导出 Excel|PDF/打印）+ 预览区。

  预览区有两条路，**默认是 PDF**：
  - **PDF 预览**（默认）：显示后端出的那份 PDF —— 纸张/方向/页边距/缩放/分页位置/页头页尾/
    页码/水印全是打印时的样子，「所见即所打」。此时「打印」按钮直接打这份已经拿到的 PDF，
    不再请求第二遍。
  - **表格预览**：只读 FortuneSheet，能滚动、选中复制、看清具体数据；出纸只能靠 `@page` 大致
    对齐（页头页尾/页码浏览器给不出来，见下面 applyPrintStyle 的说明）。

  两条路共用同一次 JSON 渲染的结果（分页条的总条数、版本标签都从它来），PDF 那条路**另外**
  请求一次导出 —— 两次请求并行发出，见 refresh()。
-->
<template>
  <div class="report-preview">
    <div class="preview-header no-print">
<!--      <el-button link :icon="ArrowLeft" @click="goBack">返回</el-button>-->
      <div class="report-title" :title="reportName">{{ reportName || '报表预览' }}</div>
      <!-- 用哪一版是后端按版本切换规则选的，这里把结果和理由摆出来，省得「怎么和我看到的不一样」 -->
<!--      <el-tag v-if="versionText" size="small" type="info" effect="plain" :title="versionMatch">-->
<!--        {{ versionText }}-->
<!--      </el-tag>-->
<!--      <el-divider direction="vertical" />-->

<!--      <ParamForm ref="paramFormRef" :params="params" v-model="paramValues">-->
<!--        <template #actions>-->
<!--          <el-button type="primary" :icon="Search" :loading="loading" @click="handleQuery">刷新</el-button>-->
<!--        </template>-->
<!--      </ParamForm>-->

      <div class="flex-spacer">
        <el-radio-group v-model="viewMode" size="small">
          <el-radio-button value="pdf" title="按打印的样子预览（后端出的 PDF）">PDF</el-radio-button>
          <el-radio-button value="sheet" title="表格预览，可滚动、可选中复制">表格</el-radio-button>
        </el-radio-group>
      </div>
      <el-dropdown trigger="click" :disabled="exporting" @command="handleExport">
        <el-button :icon="Download" :loading="exporting">
          导出<el-icon class="el-icon--right"><ArrowDown /></el-icon>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="excel">导出 Excel</el-dropdown-item>
            <el-dropdown-item command="pdf">导出 PDF</el-dropdown-item>
            <el-dropdown-item command="word">导出 Word</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <!-- 打的范围跟着看到的：PDF 视图是整本，表格视图只打当前那张 —— 按钮上说清楚，
           否则多工作表报表点下去只出一张纸，看着像是漏印了 -->
      <el-button :icon="Printer" :loading="printing" :title="printHint" @click="handlePrint">打印</el-button>
    </div>

    <div class="preview-body" v-loading="loading || pdfLoading">
      <el-result v-if="errorMsg" icon="error" title="渲染失败" :sub-title="errorMsg">
        <template #extra>
          <el-button type="primary" @click="goDesigner">返回设计器</el-button>
        </template>
      </el-result>

      <!--
        PDF 预览：blob 挂给 iframe，用浏览器自带的 PDF 阅读器显示（自带缩放/翻页/打印）。
        它显示的是**整本**（所有工作表连成一份文档），与「导出 PDF」拿到的文件一模一样。
      -->
      <template v-else-if="viewMode === 'pdf'">
        <el-result v-if="pdfError" icon="warning" title="PDF 预览失败" :sub-title="pdfError">
          <template #extra>
            <el-button @click="loadPdf">重试</el-button>
            <el-button type="primary" @click="viewMode = 'sheet'">改用表格预览</el-button>
          </template>
        </el-result>
        <iframe v-else-if="pdfUrl" class="pdf-frame" :src="pdfUrl" title="PDF 预览"></iframe>
      </template>

      <!-- 表格预览：v-if 而不是 v-show —— FortuneSheet 的 data 只在挂载时读一次，重新挂载才拿得到最新数据 -->
      <template v-else>
        <!--
          没挂全时必须说一句，否则就是「我的单据怎么只剩 10 张」。
          三件事都要写清楚：一共几张、这里只显示几张、以及**导出和 PDF 预览不受影响**。
        -->
        <div v-if="sheetTruncated" class="sheet-limit-tip no-print">
          <el-icon><Warning /></el-icon>
          <span>
            共 {{ sheetTotal }} 张工作表，表格预览只显示前 {{ PREVIEW_MAX_SHEETS }} 张（全挂上会卡住浏览器）。
            <b>导出的文件与 PDF 预览都是完整的 {{ sheetTotal }} 张</b>，要看后面的请切到 PDF 或直接导出。
          </span>
          <el-button link type="primary" size="small" @click="viewMode = 'pdf'">切到 PDF 看整本</el-button>
        </div>
        <FortuneSheet
          ref="sheetRef"
          class="preview-sheet"
          :data="sheets"
          :allow-edit="false"
          :show-toolbar="false"
          :show-formula-bar="false"
          @sheet-activate="onSheetActivate"
        />
      </template>
    </div>

    <!--
      分页条只在「主接口是分页型数据集」时出现 —— 总条数由主接口自报（渲染结果的 total），
      翻页就是带上新的 pageNo/pageSize 重新渲染一次整张报表。
    -->
    <div v-if="total !== null" class="preview-pager no-print">
      <el-pagination
        v-model:current-page="pageNo"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[20, 50, 100, 200]"
        :disabled="loading || pdfLoading"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="onPageChange"
        @current-change="onPageChange"
      />
    </div>

    <!--
      打印水印：position:fixed 的元素在打印时每一页都会画一次，正好就是水印要的效果
      （页头页尾做不到这一点，见下面 applyPrintStyle 的说明）。

      只给**表格预览**用：PDF 那条路的水印是后端画进 PDF 里的，再盖一层就是两个水印。
    -->
    <div v-if="watermark.text && viewMode === 'sheet'" class="print-watermark" aria-hidden="true">
      <span :style="watermarkStyle">{{ watermark.text }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowDown, Search, Download, Printer, Warning } from '@element-plus/icons-vue'

import FortuneSheet from '@/components/FortuneSheet.vue'
import ParamForm from '@/components/ParamForm.vue'
import { getReport } from '@/api/report'
import { renderReport, getReportParams, exportExcel, exportPdf, exportWord } from '@/api/render'
import { downloadBlob, fileNameFromResponse, limitPreviewSheets, PREVIEW_MAX_SHEETS } from '@/utils/sheet'
import { normalizePageConfig, pageConfigOfResult, paperSizeMm } from '@/utils/print'
import { printPdfBlob, disposePrintFrame } from '@/utils/printPdf'
import { applyWrapRowHeights } from '@/utils/wrapHeight'
import { applyCellImages } from '@/utils/cellImage'
import { queryParams, queryVersionId } from '@/utils/params'

const route = useRoute()
const router = useRouter()
const reportId = route.params.id

/**
 * 外部地址上带来的参数：`/view/{id}?id=11233`。
 *
 * 报表里**声明过**的参数用它当表单初值（用户还能改），**没声明**的也一起提交 ——
 * 数据集里 `${id}` 照样取得到（SQL 走 JDBC 绑定、API 地址走 URL 编码替换），
 * 所以门户/iframe 只要把业务 id 挂在地址上就行，不必先到报表里声明一遍参数。
 */
const externalParams = queryParams(route.query)

/**
 * 地址上指定的版本（`/view/{id}?versionId=xxx`），空 = 让后端按版本切换规则自动选。
 *
 * versionId 是**保留参数名**：它走渲染请求体，不能混进报表参数（否则会被塞进 SQL 的
 * `${versionId}`），`queryParams` 已经把它剔出去了。
 */
const versionId = queryVersionId(route.query)

const reportName = ref('')
/** 这一次实际渲染用的是哪一版（后端回传），以及为什么是它 */
const versionText = ref('')
const versionMatch = ref('')
const params = ref([])
const paramValues = ref({})
const sheets = ref([])
/**
 * 报表 content，打印设置按 sheet 取值时**兜底**要用。
 *
 * 注意它未必是这次渲染真正用的那一版（地址上没指定版本时，后端是按规则选的，甚至可能逐行选）——
 * 所以打印设置一律优先认渲染结果里的 `sheetPageConfigs`，见 `pageConfigOfResult`。
 */
const reportContent = ref(null)
/** 最近一次渲染结果（打印设置从它身上取） */
const renderResult = ref(null)

const sheetRef = ref(null)
const paramFormRef = ref(null)

const loading = ref(false)
const exporting = ref(false)
const printing = ref(false)
const errorMsg = ref('')

/**
 * 预览形态：`pdf` 按打印的样子看（默认）/ `sheet` 只读表格。
 *
 * 默认 PDF 是因为预览多半是「打印前确认一眼」—— 表格预览里看不到页头页尾、页码、
 * 真实分页位置，看着好好的一份表打出来可能是另一回事。要滚动大表、选中复制时再切表格。
 */
const viewMode = ref('pdf')

/** 当前这份 PDF：blob 给打印用，url 给 iframe 用（换一份必须 revoke 旧的，否则内存里越攒越多） */
let pdfBlob = null
/** 这份 PDF 的文件名（后端按导出设置拼的），打印失败退回下载时用 */
let pdfName = ''
const pdfUrl = ref('')
const pdfLoading = ref(false)
const pdfError = ref('')
/** 手上这份 PDF 是不是过期了（参数/翻页变过）—— 切回 PDF 视图时据此决定要不要重取 */
let pdfStale = true
/** 第几次取 PDF：回来时对不上号就丢掉，见 loadPdf */
let pdfSeq = 0

/**
 * 这次渲染一共出了几张工作表，以及表格视图里是不是没挂全（见 `limitPreviewSheets`）。
 *
 * 「每条数据一个 sheet」能出成百上千张，整份挂进 FortuneSheet 会把浏览器卡死，
 * 所以表格视图只挂前 `PREVIEW_MAX_SHEETS` 张。**这是显示上限不是数据上限** ——
 * 导出与 PDF 视图都是整本，所以截了就得在界面上说一句，否则就是「我的单据怎么少了」。
 */
const sheetTotal = ref(0)
const sheetTruncated = ref(false)

/**
 * **表格视图**下当前显示的是渲染结果里的第几张 sheet。
 * 表格视图下打印只打这一张（见 handlePrint），`@page` 也跟着它走；PDF 视图下用不到它。
 */
const activeSheetIndex = ref(0)

/**
 * 打印按钮的悬停提示：**打的范围跟着看到的**。
 * 多工作表报表在表格视图下点打印只出当前那一张，不写清楚就像是漏印了。
 */
const printHint = computed(() =>
  viewMode.value === 'pdf'
    ? '打印整本（所有工作表，与「导出 PDF」拿到的一样）'
    : '只打印当前这张工作表；要整本请切到 PDF 视图'
)

/**
 * 主接口（报表里设的那个分页型数据集）自报的总条数，null = 这张报表不分页。
 * pageNo / pageSize 是**保留参数名**，跟其它报表参数一样透传下去，
 * 接口地址里用 ${pageNo} / ${pageSize} 接。
 */
const total = ref(null)
const pageNo = ref(1)
const pageSize = ref(20)

onMounted(async () => {
  loading.value = true
  errorMsg.value = ''
  try {
    // 地址上指定了版本就按那一版加载（参数定义、兜底的打印设置都要跟着那一版）
    const report = await getReport(reportId, versionId)
    reportName.value = report.name
    const content = parseContent(report)
    reportContent.value = content
    params.value = await loadParams(content)
    // 打开时先按第一张工作表出纸，之后随激活的工作表切换
    applyPrintStyle(pageConfigOfResult(null, 0, content))
    initDefaultValues()
    await refresh()
  } catch (e) {
    errorMsg.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  printStyleEl?.remove()
  printStyleEl = null
  disposePrintFrame()
  revokePdf()
})

watch(viewMode, (mode) => {
  if (mode === 'pdf') {
    // 手上那份还新鲜就直接看，不再打一次后端
    if (pdfStale) loadPdf()
    return
  }
  // 表格视图是重新挂载的（FortuneSheet 的 data 只在挂载时读一次），挂出来显示的是第一张，
  // 「当前是第几张」得跟着回到 0 —— 不回的话打印按钮打的是切走之前那张
  activeSheetIndex.value = 0
  applyPrintStyle(pageConfigOfResult(renderResult.value, 0, reportContent.value))
})

function parseContent(report) {
  try {
    return typeof report.content === 'string' ? JSON.parse(report.content) : report.content
  } catch (e) {
    return null
  }
}

/** 优先用 /render/report/{id}/params 接口获取参数定义，失败则从 report.content 中解析 */
async function loadParams(content) {
  try {
    const list = await getReportParams(reportId)
    if (Array.isArray(list) && list.length) return list
  } catch (e) {
    // 忽略，走兜底解析
  }
  return content?.params || []
}

/**
 * 把报表的打印设置写进 @page，浏览器打印时才会按设计器里设定的纸张、方向、页边距出纸。
 * @page 只能通过样式表设置，无法用内联样式，故动态插一个 <style>。
 *
 * 打印设置可以按 sheet 单独设，而 @page 一份文档只能有一套 —— 预览页一次也只显示一张
 * 工作表，所以跟着当前激活的那张走，切换工作表时重写。
 *
 * **页头页尾在浏览器打印里做不到**：要每页都出现只能用 position:fixed，而 fixed 元素定位的是
 * 页面**页边距以内**的那块区域，画不到页边距里去；画在里面就会压住表格（表格是一整张 canvas，
 * 没法像 <thead> 那样让每页给它留出空间）。页码更是 CSS 给不出来的（counter(page) 只在
 * @page 的边距框里能用，Chrome 不支持）。所以页头页尾只在导出的 Excel / PDF / Word 里出现，
 * 这里只画水印 —— 水印本来就要压在内容上，fixed 正合适。
 */
let printStyleEl = null

/** 当前激活 sheet 生效的打印设置 */
const pageConfig = ref(normalizePageConfig(null))
const watermark = computed(() => pageConfig.value.watermark)

/** 水印样式：字号用 pt，和导出的 PDF 同一套单位；CSS 的正角度是顺时针，本项目相反故取负 */
const watermarkStyle = computed(() => ({
  color: watermark.value.color,
  opacity: watermark.value.opacity / 100,
  fontSize: `${watermark.value.fontSize}pt`,
  transform: `rotate(${-watermark.value.rotation}deg)`
}))

function onSheetActivate(sheetId) {
  const idx = sheets.value.findIndex((s) => s.id === sheetId)
  if (idx >= 0) {
    activeSheetIndex.value = idx
    // idx 是**渲染结果**里的下标：结果 sheet 与模板不是一一对应的（拆分/拼接/逐行选版本），
    // 所以认渲染结果自带的那份打印设置，认不到才退回按 mzTemplateIndex 推算
    applyPrintStyle(pageConfigOfResult(renderResult.value, idx, reportContent.value))
  }
}

function applyPrintStyle(cfg) {
  pageConfig.value = normalizePageConfig(cfg)
  const { width, height } = paperSizeMm(pageConfig.value)
  const { marginTop, marginRight, marginBottom, marginLeft } = pageConfig.value
  if (!printStyleEl) {
    printStyleEl = document.createElement('style')
    printStyleEl.id = 'mz-print-page'
    document.head.appendChild(printStyleEl)
  }
  printStyleEl.textContent =
    `@page { size: ${width}mm ${height}mm; ` +
    `margin: ${marginTop}mm ${marginRight}mm ${marginBottom}mm ${marginLeft}mm; }`
}

function initDefaultValues() {
  const obj = {}
  params.value.forEach((p) => {
    // 地址上带了同名参数就用它，默认值退到第二位
    obj[p.name] = externalParams[p.name] ?? p.defaultValue ?? ''
  })
  paramValues.value = obj
}

/** 提交给后端的参数：地址上的外部参数打底，表单里的值覆盖同名项，分页参数最后盖 */
function buildParams() {
  return { ...externalParams, ...paramValues.value, pageNo: pageNo.value, pageSize: pageSize.value }
}

/**
 * 刷新预览：JSON 渲染 + （PDF 视图下）取一份 PDF，**两件事并行发出**。
 *
 * 为什么 PDF 视图下也要 JSON 渲染那一次：分页条的总条数、版本标签只有渲染结果带得回来
 * （导出接口只还字节流），切到表格视图时也是直接用它、不必再等一次。代价是 PDF 视图下
 * 一次查询打两次后端 —— 串着做要等两遍，所以这里并行。
 */
async function refresh() {
  const jobs = [doRender()]
  if (viewMode.value === 'pdf') jobs.push(loadPdf())
  await Promise.all(jobs)
}

/**
 * 取一份**整本**的 PDF 挂到 iframe 上。
 *
 * 整本而不是当前那张工作表：PDF 视图里没有工作表标签这回事，看到的就是导出的那份文档。
 */
async function loadPdf() {
  // 连点两次查询时后发的那次说了算：先发的那份是旧参数出的，回来晚了照样会顶掉新的
  const seq = ++pdfSeq
  pdfLoading.value = true
  pdfError.value = ''
  try {
    // 版本一路带着：不带的话「预览看的是 v2、PDF 出的是规则选出来的 v3」
    const res = await exportPdf(reportId, buildParams(), null, versionId)
    if (seq !== pdfSeq) return
    revokePdf()
    // 后端的 Content-Type 已是 application/pdf，这里再兜一层，免得 iframe 把它当附件下载
    pdfBlob = new Blob([res.data], { type: 'application/pdf' })
    // 打印被浏览器拦下来时会退回「下载」，那时也该是导出设置里配的那个名字
    pdfName = fileNameFromResponse(res, `${reportName.value || 'report'}.pdf`)
    pdfUrl.value = URL.createObjectURL(pdfBlob)
    pdfStale = false
  } catch (e) {
    // 具体原因 axios 拦截器已经提示过，这里只负责让页面上有个能重试/切走的落点
    if (seq === pdfSeq) pdfError.value = e?.message || '生成 PDF 失败'
  } finally {
    if (seq === pdfSeq) pdfLoading.value = false
  }
}

/** 回收上一份 PDF 的 blob URL —— 不回收的话每查询一次就在内存里留一份 */
function revokePdf() {
  if (pdfUrl.value) {
    URL.revokeObjectURL(pdfUrl.value)
    pdfUrl.value = ''
  }
  pdfBlob = null
  pdfName = ''
}

async function doRender() {
  loading.value = true
  errorMsg.value = ''
  // 数据要重取了，手上那份 PDF 就作废；PDF 视图下由 refresh 并行重取，表格视图下等切过去再说
  pdfStale = true
  try {
    const res = await renderReport(reportId, buildParams(), versionId)
    // 渲染结果里的行高是模板的行高，扩展出来的数据行文字更长，开了自动换行的要按真实文字重算，
    // 否则预览里被切、下载下来的 Excel 却是对的
    total.value = res.total ?? null
    renderResult.value = res
    // 逐行选版本时整份报表没有单一版本，只有一句「按什么逐行选」，直接把它当标签文字
    versionText.value = res.versionNo
      ? `当前版本 ${res.versionName || 'v' + res.versionNo}`
      : res.versionMatch || ''
    versionMatch.value = res.versionMatch || ''
    // **先截断再算行高/图片**：这两步都是逐格遍历（`applyWrapRowHeights` 还要拿 canvas
    // measureText 量每一格），几百张 sheet 全过一遍就是几十万次同步调用，页面当场没响应。
    // 放在后面截的话卡死照旧发生，只是最后少挂几张而已
    const limited = limitPreviewSheets(res.sheets)
    sheetTotal.value = limited.total
    sheetTruncated.value = limited.truncated
    // 图片摆位要在行高定下来之后：撑高过的行会让它下面的图片整体下移
    sheets.value = applyCellImages(applyWrapRowHeights(limited.sheets))
    // reload 会重挂工作簿，显示的又是第一张 —— 打印用的下标和纸张设置都要跟着回到第一张
    activeSheetIndex.value = 0
    applyPrintStyle(pageConfigOfResult(res, 0, reportContent.value))
    await nextTick()
    await sheetRef.value?.reload(sheets.value)
  } catch (e) {
    errorMsg.value = e?.message || '渲染失败'
  } finally {
    loading.value = false
  }
}

function handleQuery() {
  if (paramFormRef.value && !paramFormRef.value.validate()) return
  // 改了查询条件就回到第一页 —— 停在第 7 页却换了筛选条件，多半是空的
  pageNo.value = 1
  refresh()
}

function onPageChange() {
  refresh()
}

/**
 * 导出格式 -> [接口, 扩展名]。
 * 统一成 `(id, params, versionId)` 三参 —— PDF 那个接口中间还夹了 sheetIndex（这里整本导出，传 null）。
 */
const EXPORTERS = {
  excel: [exportExcel, 'xlsx'],
  pdf: [(id, params, v) => exportPdf(id, params, null, v), 'pdf'],
  word: [exportWord, 'docx'],
}

/** @param type 'excel' | 'pdf' | 'word' */
async function handleExport(type) {
  const [api, ext] = EXPORTERS[type] || EXPORTERS.excel
  exporting.value = true
  try {
    // 版本一路带着：不带的话「页面上看的是 v2、导出的却是规则选出来的 v3」
    const res = await api(reportId, buildParams(), versionId)
    // 文件名跟着响应头走：后端按报表的「导出」设置拼好了（报表名 + 主接口字段值），
    // 这边没有那份数据，自己拼只会拼成另一个名字
    downloadBlob(res.data, fileNameFromResponse(res, `${reportName.value || 'report'}.${ext}`))
  } catch (e) {
    // 错误已由 axios 拦截器提示
  } finally {
    exporting.value = false
  }
}

/**
 * 打印：取一份**后端出的 PDF** 再交给浏览器打印，而不是把这张网页截下来打。
 *
 * 网页那条路（window.print）出纸永远差一口气 —— 页头页尾和页码浏览器给不出来
 * （fixed 元素画不进页边距、counter(page) 也用不了），表格是一整张 canvas，
 * 分页只能靠 `@page` 大致对齐，行高列宽还会被屏幕缩放带偏。PDF 那条路这些都是现成的，
 * 打出来与「导出 PDF」拿到的文件一模一样。
 *
 * 打的范围**跟着看到的**：PDF 视图下屏幕上就是整本 PDF，直接把手上那份拿去打（不再请求一遍，
 * 点下去就弹打印对话框）；表格视图下页面一次只显示一张工作表，就只打那一张。
 */
async function handlePrint() {
  if (printing.value) return
  printing.value = true
  let blob = null
  let name = pdfName || `${reportName.value || 'report'}.pdf`
  try {
    if (viewMode.value === 'pdf' && pdfBlob && !pdfStale) {
      blob = pdfBlob
    } else {
      const res = await exportPdf(reportId, buildParams(), activeSheetIndex.value, versionId)
      // 后端的 Content-Type 已是 application/pdf，这里再兜一层，免得 iframe 把它当附件下载
      blob = new Blob([res.data], { type: 'application/pdf' })
      name = fileNameFromResponse(res, name)
    }
    await printPdfBlob(blob)
  } catch (e) {
    // 取不到 PDF 的原因已由 axios 拦截器提示；能拿到 PDF 只是打不了（Safari 不允许打
    // iframe 里的 PDF），就退一步开在新标签页里，让用户在那儿按 Ctrl+P
    if (blob && window.open(URL.createObjectURL(blob), '_blank')) {
      ElMessage.info('已在新标签页打开 PDF，请在其中打印')
    } else if (blob) {
      ElMessage.warning('浏览器拦截了打印窗口，已改为下载 PDF')
      downloadBlob(blob, name)
    }
  } finally {
    printing.value = false
  }
}

function goBack() {
  router.push('/report')
}

function goDesigner() {
  router.push(`/designer/${reportId}`)
}
</script>

<style scoped>
.report-preview {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.preview-header {
  height: var(--mz-header-h);
  min-height: var(--mz-header-h);
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  border-bottom: 1px solid var(--mz-border);
  background: #fff;
}

.report-title {
  font-weight: 600;
  font-size: 15px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  /* 表格视图下多了一条提示，工作簿要占掉剩下的高度 —— 不铺开的话它按内容高度算，几乎看不见 */
  display: flex;
  flex-direction: column;
}
.preview-sheet {
  flex: 1;
  min-height: 0;
}

/* 没挂全的提示条：显眼但不喧宾夺主，一行放不下就换行 */
.sheet-limit-tip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-bottom: 1px solid var(--el-color-warning-light-5);
}
.sheet-limit-tip b {
  font-weight: 600;
}

/* 浏览器自带的 PDF 阅读器：铺满预览区，缩放/翻页/它自己那个打印按钮都归它管 */
.pdf-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: var(--mz-panel-bg);
}

.preview-pager {
  display: flex;
  justify-content: flex-end;
  padding: 6px 16px;
  border-top: 1px solid var(--mz-border);
  background: #fff;
}

/* 水印只在打印时出现：屏幕上盖一层半透明大字会挡住数据 */
.print-watermark {
  display: none;
}

@media print {
  .no-print {
    display: none !important;
  }
  .report-preview {
    height: auto;
  }
  .preview-body {
    overflow: visible;
  }
  /* fixed 元素在打印时每页都会画一次 —— 水印靠的就是这个 */
  .print-watermark {
    position: fixed;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    pointer-events: none;
    z-index: 9999;
  }
  .print-watermark span {
    white-space: nowrap;
    font-weight: 700;
  }
}
</style>
