<!--
  报表设计器：左（数据集面板） / 中（FortuneSheet 工作簿） / 右（单元格属性面板）。

  **顶上没有通栏**（曾经有一条 44px 的标题栏），三样东西各归各位，编辑区因此高了一整条：
    ① 身份    —— 报表名 + 当前版式（版本下拉），占**左栏顶上一格**（`.report-identity`）。
                 通栏时代它横铺整屏、纵向却吃掉编辑区一条，而上面只有这两样东西。
                 **没有返回、没有导出**：设计器是嵌进外部系统用的，导航由宿主页面给
                 （浏览器后退、或宿主自己的标签页），塞一个跳回 `/report` 的按钮在嵌入场景下
                 反而是把人带出宿主系统；导出属于「出成品」，归预览页（那里有 Excel/PDF/Word
                 三条路，且所见即所打）。
    ② 命令（保存 / 预览 / 导入 Excel / 打印设置 / 表达式帮助）**长在 FortuneSheet
                 自己的工具栏里**（`toolbarExtras` → `customToolbarItems`，见 FortuneSheet.vue），
                 **只有图标、名字靠 tooltip**，排在内置排版按钮左边、由它自动补一条分隔符 ——
                 与那些排版按钮长得一样，整条读起来是一条工具栏。
                 曾经它们单占一带文字按钮，与下面那条排版工具栏是并排的两条。
    ③ 状态栏  —— 未保存、页数纸张、超宽/补格提示。这些是**状态与建议**不是命令，
                 跟命令按钮并排长成一个样子最伤专业感，一律沉到底部。

  搬进工具栏要认它的三条规矩（都在 FortuneSheet.vue 的 customToolbarItems 上写着）：
  自定义项不参与溢出折叠 —— 窄窗口下先被收进「更多」的反而是高频的字体字号，所以只放这五个；
  下拉做不了（CustomButton 只是个按钮），所以「版本」只能待在左栏那一格里；
  开关与 loading 也做不了（Toolbar 没把 selected 透传下去）—— 原来那个「分页线」开关因此下线，
  分页线与打印区域改成**常显**（本来也几乎没人关它），而「保存」改由 `handleSave` 自己防重入。

  「参数配置」也不在这儿了：参数改成**全局**的（`/param` 参数管理页，CONTRACT §3.5），
  一处配、所有报表共用，不再一张报表配一份。老报表 `content.params` 里那份照旧参与渲染
  （渲染时与全局参数合并，同名以报表那条为准），只是设计器里不再有编辑入口。
-->
<template>
  <div class="report-designer" v-loading="loading">
    <div class="designer-body">
      <div class="panel-left">
        <!--
          ① 身份：报表名 + 当前版式，一行排开。名字占满剩下的宽度、长了截断成「…」，
          版本恒定靠右且不参与压缩（宽度固定的是它，该缩的是名字）。
        -->
        <div class="report-identity">
          <div v-if="!editingName" class="report-name" title="点击修改报表名" @click="startEditName">
            {{ store.report.name || '未命名报表' }}
          </div>
          <el-input
            v-else
            ref="nameInputRef"
            v-model="nameDraft"
            class="report-name-input"
            size="small"
            @blur="confirmEditName"
            @keyup.enter="confirmEditName"
          />

          <!--
            版本下拉：一张报表可以有好几份版式，这里选的是「现在设计哪一份」。
            切版本会重挂工作簿（FortuneSheet 的 data 只在挂载时读一次），所以 dirty 时先拦一道。
          -->
          <el-dropdown
            v-if="store.versions.length"
            class="version-drop"
            trigger="click"
            placement="bottom-end"
            @command="onVersionCommand"
          >
            <el-button size="small" :loading="versionSwitching">
              {{ currentVersionText }}<el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="v in versionRows"
                  :key="v.id"
                  :command="{ type: 'open', id: v.id }"
                  :class="{ 'is-current': v.id === store.versionId }"
                >
                  <span class="v-label">{{ v.label }}</span>
                  <span class="v-range">{{ intervalText(v) }}</span>
                  <span class="v-flag">
                    <template v-if="v.isDefault">默认</template>
                    <template v-else-if="v.id === store.versionId">● 当前</template>
                  </span>
                </el-dropdown-item>
                <el-dropdown-item divided :command="{ type: 'copy' }">从当前版本复制新版本</el-dropdown-item>
                <el-dropdown-item :command="{ type: 'manage' }">版本管理…</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <DatasetPanel
          class="panel-left-datasets"
          :datasets="normalizedDatasets"
          :primary-dataset="store.content.primaryDataset"
          :links="store.content.datasetLinks"
          @refresh="loadDatasets"
          @insert-all="onInsertAll"
          @create="openDatasetCreate"
          @edit="openDatasetEdit"
          @remove="handleDatasetDelete"
          @set-primary="onSetPrimary"
          @link-create="openLinkDialog(-1)"
          @link-edit="openLinkDialog"
          @link-remove="handleLinkDelete"
        />
      </div>

      <div
        class="designer-center"
        :class="{ 'drop-active': dropActive }"
        @dragover.prevent="onDragOver"
        @dragleave="onDragLeave"
        @drop="onDrop"
      >
        <!--
          Workbook 只在挂载时读取一次 data，所以必须等报表 content 加载回来后再挂载，
          否则画布会停在空白模板上（且随后的编辑/保存会把已设计的模板覆盖掉）。
          key 绑定报表 id，切换报表时强制重建工作簿。
        -->
        <FortuneSheet
          v-if="contentReady"
          :key="store.report.id || 'new'"
          ref="sheetRef"
          :data="store.content.sheets"
          :options="sheetOptions"
          :toolbar-extras="toolbarExtras"
          @change="onSheetChange"
          @op="onSheetOp"
          @selection-change="onSelectionChange"
          @sheet-activate="onSheetActivate"
        >
          <template #overlay="{ rect }">
            <PrintAreaOverlay
              :rect="rect"
              :breaks="pageBreaks"
              :geometry="geometry"
              :paper-label="paperLabel"
            />
          </template>
        </FortuneSheet>
        <el-empty v-else-if="!loading" description="报表加载失败，请返回列表重试" />
      </div>

      <CellPropertyPanel
        class="panel-right"
        :config="store.activeConfig"
        :cell="store.activeCell"
        :size="activeCellSize"
        :datasets="normalizedDatasets"
        @update="onCellConfigUpdate"
        @clear="onCellConfigClear"
      />
    </div>

    <!--
      ③ 状态栏：左边「在哪儿」，右边「怎么样」。
      两条警告仍然可点（点了就是修复动作），但降到状态栏的量级 —— 它们是提示，不是命令。
    -->
    <div class="designer-statusbar">
      <span class="sb-item">{{ currentSheet?.name || 'Sheet1' }}</span>
      <!-- 只报坐标，宽高留给右侧属性面板，别两处说同一件事 -->
      <span v-if="store.activeCell" class="sb-item sb-muted">{{ cellLabel }}</span>

      <div class="flex-spacer"></div>

      <el-button
        v-if="widthOverflow > 1"
        link
        type="warning"
        class="sb-action"
        :title="`列宽之和比 ${pageConfig.paperSize} 可打印宽度多出 ${Math.round(widthOverflow)}px，点击按比例压回一页`"
        @click="shrinkToPageWidth"
      >
        超出纸张宽度 · 压缩到一页宽
      </el-button>
      <el-button
        v-if="needsGridFill"
        link
        type="warning"
        class="sb-action"
        title="表格比一页还小，纸张边界落在网格之外，补足空行空列后才能显示"
        @click="ensurePaperVisible"
      >
        表格不足一页 · 补足网格
      </el-button>
      <span v-if="pageBreaks.pages" class="sb-item sb-muted">
        {{ pageBreaks.pages }} 页 · {{ pageConfig.paperSize }}
        {{ pageConfig.orientation === 'landscape' ? '横向' : '纵向' }}
        <template v-if="store.hasOwnPageConfig"> · 本表单独设置</template>
      </span>
      <span class="sb-item dirty-indicator" :class="{ dirty: store.dirty }">
        {{ store.dirty ? '● 未保存' : '已保存' }}
      </span>
    </div>

    <PrintConfigDialog
      v-model="printDialogVisible"
      :sheet="currentSheet"
      :get-selection="getSelectionRange"
      @applied="onPageConfigApplied"
    />
    <PreviewDrawer
      v-model="previewVisible"
      :report-id="store.report.id"
      :content="store.content"
      :params="store.content.params"
      :external-params="externalParams"
      :version-id="store.versionId"
    />
    <VersionManageDialog
      v-model="versionDialogVisible"
      :report-id="store.report.id"
      :current-id="store.versionId"
      @open="switchVersion"
      @changed="onVersionsChanged"
    />
    <ExcelImportDialog v-model="excelDialogVisible" @imported="onExcelImported" />
    <ExpressionHelpDialog v-model="helpVisible" />
    <DatasetLinkDialog v-model="linkDialogVisible" :datasets="normalizedDatasets" :index="editingLinkIndex" />
    <!-- 与数据集管理页同一个弹窗，只是带上 reportId -> 建出来的是本报表的内部数据集 -->
    <DatasetEditDialog
      v-model="datasetDialogVisible"
      :dataset-id="editingDatasetId"
      :report-id="datasetDialogReportId"
      @saved="onDatasetSaved"
    />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Check, View, Upload, Printer, QuestionFilled } from '@element-plus/icons-vue'
// 只为了拿工具栏的默认按钮清单，见下面的 HIDDEN_TOOLBAR_ITEMS
import { defaultSettings } from '@fortune-sheet/core'

import FortuneSheet from '@/components/FortuneSheet.vue'
import DatasetPanel from './components/DatasetPanel.vue'
import CellPropertyPanel from './components/CellPropertyPanel.vue'
import PrintConfigDialog from './components/PrintConfigDialog.vue'
import PrintAreaOverlay from './components/PrintAreaOverlay.vue'
import PreviewDrawer from './components/PreviewDrawer.vue'
import VersionManageDialog from './components/VersionManageDialog.vue'
import ExcelImportDialog from './components/ExcelImportDialog.vue'
import ExpressionHelpDialog from './components/ExpressionHelpDialog.vue'
import DatasetLinkDialog from './components/DatasetLinkDialog.vue'
import DatasetEditDialog from '@/views/dataset/DatasetEditDialog.vue'

import { useDesignerStore } from '@/stores/designer'
import { getReport, updateReport, listVersion, copyVersion } from '@/api/report'
import { listDataset, deleteDataset } from '@/api/dataset'
import {
  findCell,
  fieldToken,
  getCellText,
  replaceToken,
  toA1,
  DATA_BOUND_TYPES,
  FIELD_DRAG_MIME
} from '@/utils/sheet'
import {
  computePageBreaks,
  requiredGridForPage,
  clampColumnGrowth,
  shrinkColumnsToWidth,
  widthOverflowPx,
  gridRange,
  colWidth,
  rowHeight,
  cellSizePx
} from '@/utils/print'
import { computeWrapRowHeights } from '@/utils/wrapHeight'
import { queryParams, queryVersionId } from '@/utils/params'
import { versionIntervals, intervalText } from '@/utils/version'

const route = useRoute()
const router = useRouter()
const store = useDesignerStore()

/**
 * 设计器地址上带来的外部参数（`/designer/{id}?id=11233`），预览、导出都带上，方便边设计边试真实参数。
 *
 * `versionId` 是**保留参数名**，被 queryParams 剔在外面（它是「现在设计哪一版」的状态，
 * 不是报表参数；不剔的话会被塞进数据集 SQL 的 `${versionId}` 里去）。
 */
const externalParams = queryParams(route.query)

const loading = ref(false)
const saving = ref(false)

const sheetRef = ref(null)
/** 报表 content 是否已加载完成（决定工作簿何时挂载） */
const contentReady = ref(false)
const dropActive = ref(false)
/** 拖拽字段落格时，是否自动在其上一格写入表头（字段说明） */
const autoHeader = ref(true)

const editingName = ref(false)
const nameDraft = ref('')
const nameInputRef = ref(null)

const printDialogVisible = ref(false)
const excelDialogVisible = ref(false)
const previewVisible = ref(false)
const helpVisible = ref(false)

/**
 * FortuneSheet 工具栏最左边的报表专有按钮（桥接翻成 customToolbarItems，见 FortuneSheet.vue）。
 *
 * **只有图标，名字交给 tooltip**（悬停显示）—— 与内置的排版按钮长得一样，两段读起来是一条工具栏；
 * 文字标签塞不进那个 24px 的图标盒子（会溢出来盖住旁边的按钮），细节在桥接那边写着。
 *
 * 五个都是「点一下就走」的命令，正好是 CustomButton 给得出的那种按钮。放不进来的有两类：
 * 开关型（Toolbar 没把 selected 透传下来）—— 原来的「分页线」因此下线、改成常显；
 * 下拉型（CustomButton 只是个按钮）—— 「版本」因此留在左栏顶上那一格里。
 * 另外两样是**整个从设计器拿掉**的，不是放不进来：「导出」归预览页（见顶部注释），
 * 「参数配置」归全局参数管理页（`/param`，CONTRACT §3.5）—— 参数不再一张报表配一份，
 * 老报表 content 里那份 params 照旧参与渲染，只是没有编辑入口。
 *
 * 保存**没有 loading 态**（CustomButton 给不出），所以 handleSave 自己防重入。
 * 引用稳定：数组只建一次，桥接那边 watch 到引用变化才重挂工具栏。
 */
const toolbarExtras = [
  { key: 'save', icon: Check, tooltip: '保存', onClick: () => handleSave() },
  { key: 'preview', icon: View, tooltip: '预览', onClick: () => openPreview() },
  {
    key: 'excel-import',
    icon: Upload,
    tooltip: '导入 Excel（拿一份现成的表当版式起点）',
    onClick: () => (excelDialogVisible.value = true)
  },
  {
    key: 'print-config',
    icon: Printer,
    tooltip: '打印设置（纸张 / 页边距 / 打印区域 / 页头页尾 / 输出）',
    onClick: () => (printDialogVisible.value = true)
  },
  { key: 'expr-help', icon: QuestionFilled, tooltip: '表达式帮助（占位符与公式速查）', onClick: () => (helpVisible.value = true) }
]

/**
 * 兼容两种数据集接口返回形态：
 * 1) DatasetDetailDTO { dataset:{id,name,code,...}, fields:[...], params:[...] }
 * 2) 直接的数据集对象（含 fields 数组）
 */
function normalizeDataset(item) {
  if (!item) return { id: '', name: '', code: '', reportId: '', resultType: 'list', fields: [], params: [] }
  // reportId 非空 = 本报表的内部数据集，面板据此分组；resultType=page 的才驱动得了分页
  const src = item.dataset || item
  return {
    id: src.id,
    name: src.name,
    code: src.code,
    reportId: src.reportId || '',
    resultType: src.resultType || 'list',
    fields: item.fields || [],
    // params 是父子关联那边要的：子表参数的下拉就是它
    params: item.params || src.params || []
  }
}

const normalizedDatasets = computed(() => store.datasets.map(normalizeDataset))

/* ------------------------- 初始化 ------------------------- */

onMounted(async () => {
  loading.value = true
  try {
    // 数据集拉取失败不应连带阻塞报表本身的回填
    // 数据集列表要带上报表 id，否则拿不到本报表的内部数据集
    // 地址上带了 versionId 就打开那一版（刷新页面还停在同一版），否则是默认版本
    const [reportRes, listRes, versionRes] = await Promise.allSettled([
      getReport(route.params.id, queryVersionId(route.query)),
      listDataset(route.params.id),
      listVersion(route.params.id)
    ])
    if (reportRes.status === 'fulfilled') {
      store.setReport(reportRes.value)
      contentReady.value = true
      // 限宽比对的基线：没有它，加载后的第一次改动会被误判成「拖宽了整张表」
      prevColumnWidths = snapshotColumnWidths(currentSheet.value)
    }
    if (listRes.status === 'fulfilled') {
      store.setDatasets(listRes.value)
    }
    if (versionRes.status === 'fulfilled') {
      store.setVersions(versionRes.value)
    }
  } catch (e) {
    // 错误已由 axios 拦截器提示
  } finally {
    loading.value = false
  }
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})

onBeforeRouteLeave((to, from, next) => {
  if (!store.dirty) {
    next()
    return
  }
  ElMessageBox.confirm('有未保存的修改，确定离开？', '提示', {
    type: 'warning',
    confirmButtonText: '离开',
    cancelButtonText: '取消'
  })
    .then(() => next())
    .catch(() => next(false))
})

function onKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    handleSave()
  }
}

/* ------------------------- 报表名 ------------------------- */

function startEditName() {
  nameDraft.value = store.report.name
  editingName.value = true
  nextTick(() => nameInputRef.value?.focus())
}

function confirmEditName() {
  const val = nameDraft.value.trim()
  if (val && val !== store.report.name) {
    store.report.name = val
    store.dirty = true
  }
  editingName.value = false
}

/* ------------------------- 版本 ------------------------- */

const versionDialogVisible = ref(false)
const versionSwitching = ref(false)

/** 版本列表 + 推导出来的生效区间（左闭右开，停用的不参与） */
const versionRows = computed(() => versionIntervals(store.versions))

const currentVersionText = computed(() => {
  const cur = versionRows.value.find((v) => v.id === store.versionId)
  if (!cur) return '版本'
  const range = cur.enabled && (cur.from || cur.to) ? ` · ${cur.from || ''}起` : ''
  return `${cur.label}${range}`
})

/**
 * 版本列表变了（在版本管理弹窗里改过）。
 *
 * 正编辑的这一版被删掉时要退回默认版本 —— 不退的话画布上还留着它，一保存就是
 * 「版本不存在」，用户根本不知道自己在编辑一个已经没了的东西。
 */
function onVersionsChanged(list) {
  store.setVersions(list)
  if (store.versionId && !list.some((v) => v.id === store.versionId)) {
    const fallback = list.find((v) => v.isDefault === 1) || list[0]
    if (fallback) {
      ElMessage.warning('正在编辑的版本已被删除，已切回默认版本')
      switchVersion(fallback.id)
    }
  }
}

function onVersionCommand(cmd) {
  if (cmd?.type === 'open') switchVersion(cmd.id)
  else if (cmd?.type === 'copy') copyCurrentVersion()
  else if (cmd?.type === 'manage') versionDialogVisible.value = true
}

/**
 * 有未保存修改时先拦一道：保存 / 放弃 / 取消。
 * 切版本会整体换掉画布内容，不问一句的话用户这一版的改动就没了。
 *
 * @returns 是否可以继续
 */
async function confirmLeaveDirty(action) {
  if (!store.dirty) return true
  try {
    const res = await ElMessageBox.confirm(`当前版本有未保存的修改，${action}前要先处理它们。`, '提示', {
      type: 'warning',
      distinguishCancelAndClose: true,
      confirmButtonText: '保存',
      cancelButtonText: '放弃修改'
    })
    if (res === 'confirm') {
      await handleSave()
      return !store.dirty // 保存失败就别继续，改动还在
    }
    return true
  } catch (e) {
    // 'cancel' = 放弃修改，'close' = 关闭弹窗（取消这次操作）
    return e === 'cancel'
  }
}

/**
 * 切到另一版。
 *
 * 必须 `sheetRef.reload()` 重挂工作簿 —— FortuneSheet 桥接的 `data` prop 只在挂载时读一次
 * （见 CLAUDE.md「三个必须记住的坑」），只改 store 画布不会跟着换。
 */
async function switchVersion(versionId) {
  if (!versionId || versionId === store.versionId) return
  if (!(await confirmLeaveDirty('切换版本'))) return
  versionSwitching.value = true
  try {
    const report = await getReport(store.report.id, versionId)
    store.setReport(report) // 会把 sheetIndex 归 0、dirty 清掉
    await nextTick()
    await sheetRef.value?.reload(store.content.sheets)
    prevColumnWidths = snapshotColumnWidths(currentSheet.value)
    // 地址上留个记号，刷新还回到这一版（versionId 是保留参数名，不会被当成报表参数）
    router.replace({ query: { ...route.query, versionId } })
    const cur = versionRows.value.find((v) => v.id === versionId)
    ElMessage.success(`已切换到 ${cur?.label || '该版本'}`)
  } catch (e) {
    // 错误已提示
  } finally {
    versionSwitching.value = false
  }
}

/** 从当前版本复制一份新版本（停用、无生效时间），复制完直接打开它 */
async function copyCurrentVersion() {
  if (!store.versionId) return
  if (!(await confirmLeaveDirty('复制版本'))) return
  try {
    const newId = await copyVersion(store.report.id, store.versionId)
    store.setVersions(await listVersion(store.report.id))
    await switchVersion(newId)
    ElMessage.success('已复制出新版本（停用中），在「版本管理」里配好生效时间再启用')
  } catch (e) {
    // 错误已提示
  }
}

/* ------------------------- 导入 Excel 版式 ------------------------- */

/**
 * 把解析出来的 Excel 版式挂上画布。
 *
 * 和切版本一样必须 `sheetRef.reload()` 重挂工作簿 —— FortuneSheet 桥接的 `data` prop 只在
 * 挂载时读一次。挂完只是画布上有了，**没有落库**，用户点「保存」才写进当前版本。
 *
 * 重挂之后立刻更新列宽快照：`enforceWidthLimit` 是拿快照比对「用户又拖宽了多少」的，
 * 不更新的话导入进来的那份宽度会被当成一次拖动，当场被压回去（同 switchVersion）。
 */
async function onExcelImported({ content, mode }) {
  store.importSheets(content, mode)
  await nextTick()
  await sheetRef.value?.reload(store.content.sheets)
  prevColumnWidths = snapshotColumnWidths(currentSheet.value)
}

/* ------------------------- 数据集 ------------------------- */

const datasetDialogVisible = ref(false)
const editingDatasetId = ref('')
/** 传给弹窗的归属报表 id：新建时是本报表（内部数据集），编辑时以库里存的为准，传空即可 */
const datasetDialogReportId = ref('')

async function loadDatasets(silent = false) {
  try {
    const list = await listDataset(store.report.id)
    store.setDatasets(list)
    if (!silent) ElMessage.success('数据集已刷新')
  } catch (e) {
    // 错误已提示
  }
}

function openDatasetCreate() {
  editingDatasetId.value = ''
  datasetDialogReportId.value = store.report.id
  datasetDialogVisible.value = true
}

function openDatasetEdit(ds) {
  editingDatasetId.value = ds.id
  datasetDialogReportId.value = ''
  datasetDialogVisible.value = true
}

function onDatasetSaved() {
  loadDatasets(true)
}

/** 设/取消主接口。单值存储，「一张报表只能有一个」是结构本身保证的 */
function onSetPrimary(ds) {
  const wasPrimary = store.content.primaryDataset === ds.code
  store.setPrimaryDataset(ds.code)
  if (wasPrimary) {
    ElMessage.info(`已取消主接口，报表不再分页`)
  } else if (ds.resultType === 'page') {
    ElMessage.success(`已把「${ds.name}」设为主接口，预览页将按它的总数分页`)
  } else {
    ElMessage.warning(`已把「${ds.name}」设为主接口。它是集合型数据集（整份取回），不会出现分页条`)
  }
}

/**
 * 删除内部数据集。已经拖到单元格里的字段不会跟着清掉（模板里留着 #{code.字段}），
 * 所以这里把还在用它的单元格数报出来，让用户知道删完要去改哪些格子。
 */
function handleDatasetDelete(ds) {
  const used = Object.values(store.content.cellConfigs || {}).filter((cfg) => cfg.datasetCode === ds.code).length
  const tip = used
    ? `确定删除内部数据集「${ds.name}」吗？当前报表还有 ${used} 个单元格在用它，删除后这些单元格会取不到数。`
    : `确定删除内部数据集「${ds.name}」吗？`
  ElMessageBox.confirm(tip, '提示', { type: 'warning' })
    .then(async () => {
      try {
        await deleteDataset(ds.id)
        ElMessage.success('删除成功')
        loadDatasets(true)
      } catch (e) {
        // 错误已提示
      }
    })
    .catch(() => {})
}

/* ------------------------- 父子关联 ------------------------- */

const linkDialogVisible = ref(false)
/** 正在编辑第几条关联，-1 = 新增 */
const editingLinkIndex = ref(-1)

function openLinkDialog(index = -1) {
  editingLinkIndex.value = index
  linkDialogVisible.value = true
}

function handleLinkDelete(index) {
  const link = store.content.datasetLinks[index]
  if (!link) return
  ElMessageBox.confirm(
    `确定删除父子关联「${link.name || `${link.master} → ${link.child}`}」吗？删除后子表将按自己的参数整份取数。`,
    '提示',
    { type: 'warning' }
  )
    .then(() => {
      store.removeDatasetLink(index)
      ElMessage.success('已删除')
    })
    .catch(() => {})
}

/* ------------------------- 工作簿事件 ------------------------- */

function onSheetChange(sheets) {
  // content 未就绪时不接受工作簿回写，避免空白模板覆盖尚未回填的设计
  if (!contentReady.value) return
  store.setSheets(sheets)
  enforceWidthLimit()
  autoFitWrapRows()
}

/**
 * 行/列的插入删除。
 *
 * 单元格配置与打印区域都是按坐标寻址的，插入/删除一行之后必须跟着挪 —— 而 `change` 事件
 * 只给结果、认不出「删的是哪一行」，能说清这件事的只有 op（见 store 的 applyRowColChange）。
 * op 在 change 之前发出，挪完正好赶上随后的 setSheets。
 */
function onSheetOp(ops) {
  if (!contentReady.value) return
  ;(ops || []).forEach((op) => {
    if (op?.op === 'insertRowCol' || op?.op === 'deleteRowCol') store.applyRowColChange(op.id, op)
  })
}

function onSelectionChange() {
  const cell = sheetRef.value?.getActiveCell()
  store.setActiveCell(cell)
}

function onSheetActivate(sheetId) {
  const idx = store.content.sheets.findIndex((s) => s.id === sheetId)
  if (idx < 0) return
  store.sheetIndex = idx
  // 选中格是按 sheet 记的（cellConfigs 的 key 含 sheetIndex），切表后必须重新取，
  // 否则属性面板还停在上一张表的坐标上，一改就写到新表的同坐标去了
  store.setActiveCell(sheetRef.value?.getActiveCell() || null)
  prevColumnWidths = snapshotColumnWidths(currentSheet.value)
}

/* ------------------------- 拖拽落格 ------------------------- */

function onDragOver() {
  dropActive.value = true
}

function onDragLeave() {
  dropActive.value = false
}

/** 落格时若目标单元格上方一格为空，自动写入字段说明作为表头 */
function maybeWriteHeader(r, c, text) {
  if (!autoHeader.value || r <= 0) return
  const sheet = sheetRef.value?.getSheet()
  const headerCell = findCell(sheet, r - 1, c)
  const isEmpty = !headerCell || !headerCell.v || (typeof headerCell.v === 'object' ? !headerCell.v.v && !headerCell.v.m : !headerCell.v)
  if (isEmpty) {
    sheetRef.value?.setCellValue(r - 1, c, text)
  }
}

function onDrop(e) {
  dropActive.value = false
  // 自定义 MIME 优先，text/plain 只作兜底：后者容易被别的东西改写（见 DatasetFieldList）
  const raw = e.dataTransfer?.getData(FIELD_DRAG_MIME) || e.dataTransfer?.getData('text/plain')
  if (!raw) return
  let payload
  try {
    payload = JSON.parse(raw)
  } catch (err) {
    // 别静默失败：负载被改写过的话，用户看到的就是「拖过去没反应」，无从下手
    ElMessage.warning('拖拽内容无法识别，请从左侧数据集面板拖动字段')
    return
  }
  const cell = sheetRef.value?.getActiveCell()
  if (!cell) {
    ElMessage.warning('请先在表格中点选一个单元格作为落点')
    return
  }
  const { r, c } = cell
  const token = store.bindField(r, c, payload.datasetCode, payload.field, payload.fieldType)
  sheetRef.value.setCellValue(r, c, token)
  maybeWriteHeader(r, c, payload.fieldText || payload.field)
}

/** 数据集面板「插入全部」：以当前选中单元格为起点向右铺开该数据集所有字段 */
function onInsertAll(ds) {
  const cell = sheetRef.value?.getActiveCell()
  if (!cell) {
    ElMessage.warning('请先在表格中点选一个单元格作为起始位置')
    return
  }
  const { r, c } = cell
  ;(ds.fields || []).forEach((f, i) => {
    const col = c + i
    const token = store.bindField(r, col, ds.code, f.fieldName, f.fieldType)
    sheetRef.value.setCellValue(r, col, token)
    maybeWriteHeader(r, col, f.fieldText || f.fieldName)
  })
}

/* ------------------------- 打印区域 / 分页线 ------------------------- */

// 打印设置可以按 sheet 单独设，这里取的是「当前 sheet 生效的那份」——
// 切换工作表时分页线、限宽上限、页数指示会跟着一起变
const pageConfig = computed(() => store.pageConfig)
const currentSheet = computed(() => store.content.sheets?.[store.sheetIndex] || null)
const pageBreaks = computed(() => computePageBreaks(currentSheet.value, pageConfig.value))

/** 采样出的画布坐标，交给叠加层绘制：{ cols: {idx:[x1,x2]}, rows: {...}, zoomX, zoomY } */
const geometry = ref(null)
/** 当前这一帧的采样缓冲 */
let sampleBuf = null

/**
 * 渲染钩子只做一件事：把「关心的那几列/行在画布上的实际起止像素」记下来。
 *
 * 线本身由 DOM 叠加层画（见 PrintAreaOverlay），这样线是完整的一条、浮在表格之上，
 * 不会因为某些格没渲染而断，也不会被后续绘制覆盖。
 * 但坐标必须来自 FortuneSheet 自己 —— 行高列宽 + 滚动 + 缩放叠加后自己算总会差几像素。
 */
function sampleCellGeometry(cell, info) {
  if (!sampleBuf) return
  const pb = pageBreaks.value
  const { row, column, startX, startY, endX, endY } = info

  if (pb.colBreaks.has(column) || column === pb.lastCol || column === pb.area?.c1 || column === pb.area?.c2) {
    sampleBuf.cols[column] = [startX, endX]
    if (!sampleBuf.zoomX) sampleBuf.zoomX = zoomOf(endX - startX, colWidth(currentSheet.value, column))
  }
  if (pb.rowBreaks.has(row) || row === pb.lastRow || row === pb.area?.r1 || row === pb.area?.r2) {
    sampleBuf.rows[row] = [startY, endY]
    if (!sampleBuf.zoomY) sampleBuf.zoomY = zoomOf(endY - startY, rowHeight(currentSheet.value, row))
  }
}

/** 每帧开始清空缓冲，绘制结束（同一 tick）后提交一次，避免逐格触发响应式更新 */
function beginSampling() {
  sampleBuf = { cols: {}, rows: {}, zoomX: 0, zoomY: 0 }
  queueMicrotask(commitSampling)
}

function commitSampling() {
  if (!sampleBuf) return
  const next = sampleBuf
  sampleBuf = null
  const a = JSON.stringify(next)
  const b = JSON.stringify(geometry.value)
  if (a !== b) geometry.value = next
}

/** 由「画布上的实际尺寸 / 配置里的尺寸」反推缩放比，用于把 sheet 坐标换算成画布坐标 */
function zoomOf(canvasSize, sheetSize) {
  if (!sheetSize || sheetSize <= 0) return 1
  const z = canvasSize / sheetSize
  return Number.isFinite(z) && z > 0.1 && z < 5 ? z : 1
}

/**
 * 工具栏里屏蔽掉的按钮：货币格式、百分比格式、减少小数、增加小数、自动求和（Σ）、
 * 冻结、条件格式、筛选、超链接、图片、批注、数据验证、分列、定位条件、截图、搜索。
 * 剔完只剩「排版」这一类：撤销重做、格式刷、数字格式、字体字号字形、颜色边框合并、对齐换行旋转。
 *
 * 从官方默认清单里剔除而不是自己抄一份 —— 抄一份的话升级 FortuneSheet 后新增的按钮会全丢。
 * 剔完顺手合并连续的分隔符「|」，免得那一组按钮空了留下两条竖线。
 */
const HIDDEN_TOOLBAR_ITEMS = [
  'currency-format',
  'percentage-format',
  'number-decrease',
  'number-increase',
  'quick-formula',
  'freeze',
  'conditionFormat',
  'filter',
  'link',
  'image',
  'comment',
  'dataVerification',
  'splitColumn',
  'locationCondition',
  'screenshot',
  'search'
]
const toolbarItems = defaultSettings.toolbarItems
  .filter((name) => !HIDDEN_TOOLBAR_ITEMS.includes(name))
  .filter((name, i, arr) => name !== '|' || (i > 0 && i < arr.length - 1 && arr[i - 1] !== '|'))

/** 传给 Workbook 的额外配置；hooks 引用必须稳定，内部读取响应式状态即可保持最新 */
const sheetOptions = {
  toolbarItems,
  hooks: {
    beforeRenderCellArea: beginSampling,
    afterRenderCell: sampleCellGeometry
  }
}

const paperLabel = computed(
  () => `${pageConfig.value.paperSize} ${pageConfig.value.orientation === 'landscape' ? '横向' : '纵向'} 纸张边界`
)

function repaintSheet() {
  sheetRef.value?.repaint()
}

/**
 * 把网格补到比一页多一行/一列，让纸张边界有地方可画。
 *
 * 表格只有 60 行 20 列，纸张比它还大时边界落在网格之外 —— 那里既没有单元格触发绘制，
 * 也没有可滚动的画布空间，线就永远看不见。补出来的是空行空列，本来设计满页表单也要用到。
 * 只在用户主动要看边界（开分页线 / 应用打印设置）时补，不在打开报表时擅自改表。
 */
/** 一页装得下、但网格没这么大时，还差多少行/列 */
const gridShortfall = computed(() => {
  const sheet = currentSheet.value
  if (!sheet) return { rows: 0, cols: 0 }
  const need = requiredGridForPage(sheet, pageConfig.value)
  const grid = gridRange(sheet)
  return {
    rows: Math.max(need.rows - (grid.r2 + 1), 0),
    cols: Math.max(need.cols - (grid.c2 + 1), 0)
  }
})

const needsGridFill = computed(() => gridShortfall.value.rows > 0 || gridShortfall.value.cols > 0)

/** 补足网格的行列上限，防止缩放调得极小时补出几千行卡死 */
const MAX_GRID_ROWS = 1000
const MAX_GRID_COLS = 200

function ensurePaperVisible() {
  const sheet = currentSheet.value
  if (!sheet || !sheetRef.value || !needsGridFill.value) return
  const grid = gridRange(sheet)
  const addRows = Math.min(gridShortfall.value.rows, MAX_GRID_ROWS - (grid.r2 + 1))
  const addCols = Math.min(gridShortfall.value.cols, MAX_GRID_COLS - (grid.c2 + 1))
  if (addRows <= 0 && addCols <= 0) {
    ElMessage.warning('纸张相对表格过大（或缩放过小），网格已达上限，无法铺满整页')
    return
  }

  if (addRows > 0) sheetRef.value.insertRowOrColumn('row', grid.r2, addRows)
  if (addCols > 0) sheetRef.value.insertRowOrColumn('column', grid.c2, addCols)

  const parts = []
  if (addRows > 0) parts.push(`${grid.r2 + 1 + addRows} 行`)
  if (addCols > 0) parts.push(`${grid.c2 + 1 + addCols} 列`)
  ElMessage.info(`表格不足一页，已按 ${pageConfig.value.paperSize} 补足到 ${parts.join(' × ')}（可 Ctrl+Z 撤销）`)
}

/** 打印设置应用后：先按新纸张补足网格，再重绘；顺带提示宽度是否已超纸张 */
function onPageConfigApplied() {
  ensurePaperVisible()
  repaintSheet()
  prevColumnWidths = snapshotColumnWidths(currentSheet.value)
  if (widthOverflow.value > 1) {
    ElMessage.warning('当前列宽之和已超出纸张宽度，可点击工具栏「压缩到一页宽」压回来')
  }
}

/* ------------------------- 宽度限制 ------------------------- */

/**
 * 限宽模式下，拖宽列不能把整表撑出纸张宽度。
 *
 * FortuneSheet 的列宽拖动没有前置钩子（列宽在 mouseup 时直接写进 context），
 * 所以只能「变更落地后按快照比对、把超出的部分还回去」。快照在每次 change 时更新，
 * 因此比对的永远是紧邻的上一状态，能准确认出被拖的是哪一列。
 */
let prevColumnWidths = null
/** 正在写回钳制结果，忽略它自己引发的 change */
let clampingWidth = false

function snapshotColumnWidths(sheet) {
  const map = {}
  if (!sheet) return map
  const last = gridRange(sheet).c2
  for (let c = 0; c <= last; c += 1) map[c] = colWidth(sheet, c)
  return map
}

/** 写回列宽，并屏蔽它引发的那次 change（否则会被当成用户又拖了一次） */
function applyColumnWidths(widths) {
  clampingWidth = true
  sheetRef.value?.setColumnWidth(widths)
  setTimeout(() => {
    clampingWidth = false
  }, 0)
}

/* ------------------------- 自动换行的行高 ------------------------- */

/** 正在写回自动算出的行高，忽略它自己引发的 change */
let fittingRows = false

/**
 * 开了自动换行的行按内容长高。
 *
 * FortuneSheet 自己只在改字号时重算行高，改自动换行时不算（见 utils/wrapHeight.js），
 * 所以在这里补一刀：换行、改字号、改列宽、编辑文字都会触发 change，行高跟着重算。
 * 只增不减，用户手动拖出来的行高不会被改回去。
 */
function autoFitWrapRows() {
  if (fittingRows) return
  const sheet = currentSheet.value
  if (!sheet) return
  const heights = computeWrapRowHeights(sheet)
  if (!Object.keys(heights).length) return

  fittingRows = true
  sheetRef.value?.setRowHeight(heights)
  setTimeout(() => {
    fittingRows = false
  }, 0)
}

function enforceWidthLimit() {
  const sheet = currentSheet.value
  if (!sheet) return
  const prev = prevColumnWidths
  prevColumnWidths = snapshotColumnWidths(sheet)
  if (clampingWidth || !prev || !pageConfig.value.limitWidth) return
  // 列数变了（插入/删除列）时列号已经错位，比对没有意义，只更新快照
  if (Object.keys(prev).length !== Object.keys(prevColumnWidths).length) return

  const fix = clampColumnGrowth(sheet, pageConfig.value, prev)
  if (!fix) return
  applyColumnWidths(fix.widths)
  // 右侧的列还能让出空间时是「右边界不动」的正常表现，不必打扰；
  // 让无可让（拖不动了）才需要说明为什么
  if (fix.capped) notifyWidthCapped()
}

/** 「拖不动了」的提示做节流：一次拖动可能连着触发多次 change */
let capNoticeAt = 0
function notifyWidthCapped() {
  const now = Date.now()
  if (now - capNoticeAt < 2000) return
  capNoticeAt = now
  ElMessage.warning(`已达 ${pageConfig.value.paperSize} 可打印宽度上限，无法再拖宽（可在打印设置中关闭「限制宽度」）`)
}

/** 整表列宽之和超出纸张多少 px */
const widthOverflow = computed(() => widthOverflowPx(currentSheet.value, pageConfig.value))

function shrinkToPageWidth() {
  const fix = shrinkColumnsToWidth(currentSheet.value, pageConfig.value)
  if (!fix) return
  applyColumnWidths(fix)
  ElMessage.success('已按比例把列宽压缩到一页宽（可 Ctrl+Z 撤销）')
}

function getSelectionRange() {
  return sheetRef.value?.getSelectionRange() || null
}

// 分页位置变了（改了纸张/边距/缩放/打印区域，或调整了行高列宽）才重绘，避免每次编辑都刷画布
watch(
  () => pageBreaks.value.signature,
  () => repaintSheet()
)

/* ------------------------- 单元格属性面板 ------------------------- */

/**
 * 选中单元格的宽高（px）。
 * 取自 store 里的 sheet 而不是画布：拖动行高列宽会走 change 回写 store，两者是同一份数据。
 */
const activeCellSize = computed(() => {
  const cell = store.activeCell
  return cell ? cellSizePx(currentSheet.value, cell.r, cell.c) : null
})

/** 状态栏左侧的选中格坐标（A1 记法） */
const cellLabel = computed(() => (store.activeCell ? toA1(store.activeCell.r, store.activeCell.c) : ''))

/**
 * 根据配置把单元格文本同步为对应的占位符（数据/图片/公式/参数），文本类型不覆盖用户手输内容。
 *
 * 图片格（img / base64）与数据格写的是同一个 `#{code.field}`：格子里那串占位符是「绑定还在」
 * 的凭据，缺了它 `pruneEmptyCellConfigs` 会把这条配置当成「格子已清空」回收掉。
 */
function syncCellText(r, c, cfg) {
  if (!cfg) return
  let text = null
  if (DATA_BOUND_TYPES.includes(cfg.type) && cfg.datasetCode && cfg.field) {
    text = fieldToken(cfg.datasetCode, cfg.field)
  } else if (cfg.type === 'formula' && cfg.expression) {
    const expr = cfg.expression.trim()
    text = expr.startsWith('!{') || expr.startsWith('=') ? expr : `!{${expr}}`
  } else if (cfg.type === 'param' && cfg.field) {
    text = `\${${cfg.field}}`
  }
  if (text !== null) {
    // 只换占位符那一段：格子里手写的前后缀（`#{orders.amount}元`）要留着，
    // 渲染时后端会把值填回占位符的位置（ExpandProcessor#affixedText）
    const merged = replaceToken(getCellText(currentSheet.value, r, c), cfg.type, text)
    sheetRef.value?.setCellValue(r, c, merged)
  }
}

function onCellConfigUpdate(patch) {
  if (!store.activeCell) return
  const { r, c } = store.activeCell
  store.setCellConfig(r, c, patch)
  syncCellText(r, c, store.activeConfig)
}

function onCellConfigClear() {
  if (!store.activeCell) return
  const { r, c } = store.activeCell
  store.removeCellConfig(r, c)
}

/* ------------------------- 保存 / 预览 / 导出 ------------------------- */

async function handleSave() {
  if (!sheetRef.value) {
    ElMessage.warning('报表尚未加载完成，暂时无法保存')
    return
  }
  // 保存按钮在工具栏上，CustomButton 给不出 loading 态、拦不住连点，只能自己防重入
  if (saving.value) {
    return
  }
  saving.value = true
  try {
    store.setSheets(sheetRef.value.getAllSheets())
    // versionId 决定这份版式写进哪一版（省略才是默认版本）—— 漏了它，改的永远是 v1
    const payload = { ...store.report, versionId: store.versionId, content: store.serializeContent() }
    await updateReport(payload)
    store.markClean()
    ElMessage.success('保存成功')
  } catch (e) {
    // 错误已提示
  } finally {
    saving.value = false
  }
}

function openPreview() {
  if (sheetRef.value) {
    store.setSheets(sheetRef.value.getAllSheets())
  }
  previewVisible.value = true
}

</script>

<style scoped>
.report-designer {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ③ 状态栏 */
.designer-statusbar {
  height: 24px;
  min-height: 24px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 12px;
  border-top: 1px solid var(--mz-border);
  background: var(--mz-panel-bg);
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.sb-item {
  white-space: nowrap;
}
.sb-muted {
  color: var(--el-text-color-secondary);
}
.designer-statusbar :deep(.sb-action) {
  height: 20px;
  padding: 0;
  font-size: 12px;
}

/*
  ① 身份：报表名 + 当前版式，占左栏顶上一格。

  以前它单占一条 44px 的通栏，而上面只有这两样东西 —— 横向铺满整个屏幕、纵向吃掉编辑区
  一整条。挪进左栏后画布高了一截，两样东西也离左栏里的数据集更近（都是「这张报表是什么」）。
*/
.report-identity {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border-bottom: 1px solid var(--mz-border);
  background: #fff;
}

/*
  名字占满剩下的宽度、长了截断。`min-width: 0` 是关键 —— flex 项的最小尺寸默认是 auto
  （按内容撑开），不写这条 ellipsis 永远不生效，长名字会把版本按钮顶出左栏。
*/
.report-name,
.report-name-input {
  flex: 1;
  min-width: 0;
}

.report-name {
  font-weight: 600;
  font-size: 15px;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.report-name:hover {
  background: var(--mz-panel-bg);
}

/* 版本恒定靠右、不参与压缩：它是这一格里宽度固定的那一样，该缩的是名字 */
.version-drop {
  flex: none;
}

/* 版本下拉：版本号 / 生效区间 / 标记三列对齐，扫一眼就知道哪一版管哪一段 */
.v-label {
  display: inline-block;
  min-width: 44px;
  font-weight: 600;
}
.v-range {
  display: inline-block;
  min-width: 180px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.v-flag {
  display: inline-block;
  min-width: 42px;
  text-align: right;
  color: var(--el-color-primary);
  font-size: 12px;
}

.dirty-indicator {
  color: #67c23a;
}
.dirty-indicator.dirty {
  color: #e6a23c;
}

.designer-body {
  flex: 1;
  min-height: 0;
  display: flex;
}

/* 左栏是一竖列：上面「身份」定高，下面数据集面板占满剩下的，自己内部滚动 */
.panel-left {
  width: 260px;
  min-width: 260px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--mz-border);
}
.panel-left-datasets {
  flex: 1;
  min-height: 0;
  /* .mz-panel 是按 height:100% 撑的，留着会连「身份」那一格的高度一起算进去、把左栏撑长 */
  height: auto;
  /* 边框改由 .panel-left 统一画，否则这里会多出一条 */
  border-right: none;
}

.panel-right {
  width: 300px;
  min-width: 300px;
  border-left: 1px solid var(--mz-border);
  border-right: none;
}

.designer-center {
  flex: 1;
  min-width: 0;
  height: 100%;
  position: relative;
}
</style>
