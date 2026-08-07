<!--
  打印设置弹窗：编辑当前 sheet 生效的打印设置的副本，点击确定才写回 store。
  设置同时作用于：设计器画布上的分页线、预览页浏览器打印、Excel / PDF / Word 导出的页面设置。
  作用范围可选「仅当前工作表」或「全部工作表」——一份宽表和一份窄表可以各用各的纸张方向。
-->
<template>
  <el-dialog v-model="visible" :title="title" width="660px" :close-on-click-modal="false" destroy-on-close>
    <el-form v-if="multiSheet" label-width="96px" size="small">
      <el-form-item label="作用范围">
        <div class="scope-row">
          <el-radio-group v-model="scope">
            <el-radio-button value="sheet">仅「{{ sheetName }}」</el-radio-button>
            <el-radio-button value="all">全部工作表</el-radio-button>
          </el-radio-group>
          <el-button v-if="store.hasOwnPageConfig" link type="primary" @click="followReport">
            改回跟随报表设置
          </el-button>
          <span v-else class="text-muted">当前跟随报表设置</span>
        </div>
      </el-form-item>
    </el-form>

    <el-tabs v-model="tab">
      <!-- ------------------------------ 页面 ------------------------------ -->
      <el-tab-pane label="页面" name="page">
        <el-form label-width="96px" size="small">
          <el-form-item label="纸张">
            <el-select v-model="form.paperSize" style="width: 100%">
              <el-option v-for="p in PAPER_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
            </el-select>
          </el-form-item>

          <el-form-item label="方向">
            <el-radio-group v-model="form.orientation">
              <el-radio-button v-for="o in ORIENTATIONS" :key="o.value" :value="o.value">
                {{ o.label }}
              </el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="页边距(mm)">
            <div class="margin-grid">
              <el-input-number v-model="form.marginTop" :min="0" :max="100" :controls="false" size="small">
                <template #prefix><span class="unit">上</span></template>
              </el-input-number>
              <el-input-number v-model="form.marginBottom" :min="0" :max="100" :controls="false" size="small">
                <template #prefix><span class="unit">下</span></template>
              </el-input-number>
              <el-input-number v-model="form.marginLeft" :min="0" :max="100" :controls="false" size="small">
                <template #prefix><span class="unit">左</span></template>
              </el-input-number>
              <el-input-number v-model="form.marginRight" :min="0" :max="100" :controls="false" size="small">
                <template #prefix><span class="unit">右</span></template>
              </el-input-number>
            </div>
          </el-form-item>

          <el-form-item label="缩放">
            <el-input-number v-model="form.scale" :min="10" :max="400" :step="10" :disabled="form.fitToWidth" />
            <span class="text-muted" style="margin-left: 8px">%</span>
            <el-checkbox v-model="form.fitToWidth" style="margin-left: 16px">所有列打印在一页宽内</el-checkbox>
          </el-form-item>

          <el-form-item label="限制宽度">
            <div class="limit-row">
              <el-switch v-model="form.limitWidth" :disabled="form.fitToWidth" />
              <span class="text-muted">
                {{
                  form.fitToWidth
                    ? '已勾选「所有列打印在一页宽内」，打印时会自动压缩，无需限宽'
                    : `拖动列宽时，会被打印的那些列合计不超过一页可打印宽度（${limitPx} px）；关闭后可随意拖动`
                }}
              </span>
            </div>
          </el-form-item>

          <el-form-item label="打印区域">
            <div class="area-row">
              <el-input v-model="form.printArea" placeholder="留空=有内容的区域，例如 A1:F30" />
              <el-button @click="useSelection">取当前选区</el-button>
              <el-button :disabled="!form.printArea" @click="form.printArea = ''">清除</el-button>
            </div>
          </el-form-item>

          <el-divider style="margin: 4px 0 12px" />

          <el-form-item label="画布显示">
            <el-switch :model-value="showOverlay" @update:model-value="$emit('update:showOverlay', $event)" />
            <span class="text-muted" style="margin-left: 8px">在设计器画布上显示分页线与打印区域</span>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ---------------------------- 页头页尾 ---------------------------- -->
      <el-tab-pane label="页头页尾" name="headerFooter">
        <el-form label-width="96px" size="small">
          <el-form-item label="页头">
            <div class="hf-grid">
              <el-input
                v-for="s in SECTIONS"
                :key="`header-${s.key}`"
                v-model="form.header[s.key]"
                :placeholder="s.label"
                @focus="target = { part: 'header', section: s.key }"
              />
            </div>
          </el-form-item>
          <el-form-item label="页头样式">
            <div class="style-row">
              <el-input-number v-model="form.header.fontSize" :min="6" :max="72" size="small" />
              <span class="text-muted">pt，距纸张上边</span>
              <el-input-number v-model="form.header.margin" :min="0" :max="100" size="small" />
              <span class="text-muted">mm</span>
            </div>
          </el-form-item>

          <el-divider style="margin: 4px 0 12px" />

          <el-form-item label="页尾">
            <div class="hf-grid">
              <el-input
                v-for="s in SECTIONS"
                :key="`footer-${s.key}`"
                v-model="form.footer[s.key]"
                :placeholder="s.label"
                @focus="target = { part: 'footer', section: s.key }"
              />
            </div>
          </el-form-item>
          <el-form-item label="页尾样式">
            <div class="style-row">
              <el-input-number v-model="form.footer.fontSize" :min="6" :max="72" size="small" />
              <span class="text-muted">pt，距纸张下边</span>
              <el-input-number v-model="form.footer.margin" :min="0" :max="100" size="small" />
              <span class="text-muted">mm</span>
            </div>
          </el-form-item>

          <el-form-item label="插入占位符">
            <div class="token-row">
              <el-tag
                v-for="p in HEADER_PLACEHOLDERS"
                :key="p.token"
                class="token"
                type="info"
                effect="plain"
                @click="insertToken(p.token)"
              >
                {{ p.label }}
              </el-tag>
              <span class="text-muted">点击插入到「{{ targetLabel }}」</span>
            </div>
          </el-form-item>

          <div class="tips">
            <p>页头页尾画在页边距里，上/下页边距不够高时正文会自动往下让（和 Excel 一样）。</p>
            <p><b>页头页尾在导出的 Excel / PDF / Word 里生效</b>；预览页用浏览器打印时不出现 —— 浏览器画不到页边距里，也给不出页码。</p>
            <p>
              <b>没写页码的 sheet 不占页数</b>：封面、总结这类没放
              <code>${page}</code> / <code>${pages}</code> 的工作表整张跳过，正文那张从「第 1 页」数起、
              「共几页」也只数正文自己的页。按数据拆单据时两条叠加 ——
              一条数据出「封面 + 正文 + 总结」时，<b>「共几页」数的是这一条数据的正文页</b>。
              <b>PDF 两个数都准</b>（预览与「打印」走的就是它）；
              导出的 Excel 只能把正文的起始页号钉成 1，「共几页」是 Excel 自己数的整本页数，改不了。
            </p>
          </div>
        </el-form>
      </el-tab-pane>

      <!-- ------------------------------ 水印 ------------------------------ -->
      <el-tab-pane label="水印" name="watermark">
        <el-form label-width="96px" size="small">
          <el-form-item label="水印文字">
            <el-input v-model="form.watermark.text" placeholder="留空 = 不加水印，例如 内部资料" clearable />
          </el-form-item>
          <el-form-item label="字号">
            <el-input-number v-model="form.watermark.fontSize" :min="8" :max="200" :step="10" size="small" />
            <span class="text-muted" style="margin-left: 8px">pt</span>
          </el-form-item>
          <el-form-item label="颜色">
            <el-color-picker v-model="form.watermark.color" />
            <span class="text-muted" style="margin-left: 12px">{{ form.watermark.color }}</span>
          </el-form-item>
          <el-form-item label="不透明度">
            <el-slider v-model="form.watermark.opacity" :min="0" :max="100" show-input size="small" />
          </el-form-item>
          <el-form-item label="倾斜角度">
            <el-slider v-model="form.watermark.rotation" :min="-90" :max="90" :step="5" show-input size="small" />
          </el-form-item>

          <el-form-item label="效果">
            <div class="wm-preview">
              <span v-if="form.watermark.text" :style="watermarkPreviewStyle">{{ form.watermark.text }}</span>
              <span v-else class="text-muted">没有水印</span>
            </div>
          </el-form-item>

          <div class="tips">
            <p>水印作用于 PDF / Word 导出和预览页的浏览器打印，每页都画一个。</p>
            <p><b>导出的 Excel 不带水印</b>：Excel 本身没有水印这个概念。Word 里的水印在正文下面，会被有底色的单元格挡住。</p>
          </div>
        </el-form>
      </el-tab-pane>

      <!-- ------------------------------ 输出 ------------------------------ -->
      <el-tab-pane label="输出" name="output">
        <el-form label-width="96px" size="small">
          <el-form-item label="输出方式">
            <el-radio-group v-model="split.splitMode" :disabled="!canSplit">
              <el-radio-button value="single">单 sheet 输出</el-radio-button>
              <el-radio-button value="perRow" :disabled="!canPerRow">每条数据一个 sheet</el-radio-button>
              <el-radio-button value="perRowPage">每条数据一页</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="主接口">
            <span v-if="primary" class="mono">{{ primary.name }}（{{ primary.code }}）</span>
            <span v-else class="text-muted">未设置 —— 在左侧数据集面板点 ☆ 指定一个</span>
          </el-form-item>

          <el-form-item v-if="split.splitMode !== 'single'" :label="split.splitMode === 'perRow' ? 'sheet 名' : '单据名'">
            <el-select v-model="split.sheetNameField" placeholder="取主接口的哪个字段" clearable style="width: 260px">
              <el-option
                v-for="f in primaryFields"
                :key="f.fieldName"
                :label="`${f.fieldText || f.fieldName} (${f.fieldName})`"
                :value="f.fieldName"
              />
            </el-select>
            <span class="text-muted" style="margin-left: 8px">留空 = 第1条、第2条…</span>
          </el-form-item>
          <div v-if="split.splitMode === 'perRowPage'" class="tips" style="margin-top: -8px">
            <p>
              「每条数据一页」拼回同一张 sheet，工作表名对每一份都一样 —— 这里配的名字给
              <b>页头页尾里的 <code>${sheet}</code></b> 用，每张纸印自己那一份的单号。
              配的是同一个字段：换成「每条数据一个 sheet」时它就是 sheet 名。
              <b>只有 PDF（含预览页的打印）逐份换名</b>，Excel / Word 里 <code>${sheet}</code> 恒是工作表名。
            </p>
          </div>

          <div class="tips">
            <p v-if="!primary">
              <b>要先设主接口</b>：拆分是按主接口返回的每一行拆的，没有主接口就不知道按谁拆。
            </p>
            <p v-else-if="primary.resultType === 'page'">
              <b>主接口「{{ primary.name }}」是分页型</b>，它已经在按页取数了，不再支持按行拆 sheet；
              要拆请把它的「返回结果」改成集合。
            </p>
            <p v-else>
              两种拆法都是把当前这份模板整份复制 N 遍（N = 主接口的行数），第 i 份只喂第 i 行数据，
              <b>其它数据集每份都拿全量</b>。一条数据一张单据的报表就是这么出的。
            </p>
            <p v-if="canSplit && multiSheet">
              <b>模板本身就有 {{ sheetCount }} 张 sheet，「每条数据一个 sheet」不可选</b>：
              它会把整份模板复制 N 遍，出来 {{ sheetCount }}×N 张 sheet，
              一条数据的几张单据被打散在整个工作簿里，翻起来根本对不上号。
              要一条数据一份就选「每条数据一页」（各模板各拼成一张、按页分开）。
            </p>
            <p v-if="split.splitMode === 'perRowPage'">
              <b>「每条数据一页」</b>= 复制出来的 N 份<b>首尾相接拼回同一张 sheet</b>，
              并在每份的开头打一个分页符。Excel 里是一张连续的表（能整体滚动、筛选），
              打印/导出 PDF 时<b>一条数据占一页</b>，不会两条挤在同一张纸上。
            </p>
            <p>
              打印设置按模板的 sheet 走：模板第 2 张设成横向，拆出来每一份的第 2 张都是横向。
              最多 200 条，超了会直接报错 —— 先用报表参数把数据筛小。
            </p>
          </div>
        </el-form>
      </el-tab-pane>
      <!-- ------------------------------ 版本 ------------------------------ -->
      <el-tab-pane label="版本" name="version">
        <el-form label-width="96px" size="small">
          <el-form-item label="判定依据">
            <el-radio-group v-model="version.source">
              <el-radio-button value="field">主接口字段</el-radio-button>
              <el-radio-button value="param">报表参数</el-radio-button>
              <el-radio-button value="now">渲染当日</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item v-if="version.source === 'field'" label="字段">
            <el-select v-model="version.field" placeholder="取主接口的哪个字段" clearable style="width: 260px">
              <el-option
                v-for="f in primaryFields"
                :key="f.fieldName"
                :label="`${f.fieldText || f.fieldName} (${f.fieldName})`"
                :value="f.fieldName"
              />
            </el-select>
            <span v-if="!primary" class="text-muted" style="margin-left: 8px">
              未设主接口 —— 在左侧数据集面板点 ☆ 指定一个
            </span>
          </el-form-item>

          <el-form-item v-else-if="version.source === 'param'" label="参数">
            <el-select v-model="version.field" placeholder="取哪个报表参数" clearable style="width: 260px">
              <el-option
                v-for="p in reportParams"
                :key="p.name"
                :label="`${p.text || p.name} (${p.name})`"
                :value="p.name"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="无法判定时">
            <el-radio-group v-model="version.fallback">
              <el-radio-button value="default">用默认版本{{ defaultLabel }}</el-radio-button>
              <el-radio-button value="error">直接报错</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="生效区间">
            <div class="version-table">
              <el-table :data="versionRows" size="small" border>
                <el-table-column label="版本" width="110">
                  <template #default="{ row }">
                    <span>{{ row.label }}</span>
                    <el-tag v-if="row.id === store.versionId" size="small" type="primary" effect="plain">
                      当前
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="区间（左闭右开）">
                  <template #default="{ row }">
                    <span :class="{ 'text-muted': !row.enabled }">{{ intervalText(row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="" width="60">
                  <template #default="{ row }">
                    <el-tag v-if="row.isDefault" size="small" type="success" effect="plain">默认</el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-form-item>

          <div class="tips">
            <p>
              区间是<b>推导</b>出来的：每一版只存生效起点，右端就是下一版的起点。
              在<b>「版本管理」</b>里改各版本的生效时间、启停与默认版本。
            </p>
            <p>
              <b>版本切换规则是报表级的</b>（不按 sheet 分）—— 它决定的是整张报表用哪一份版式，
              和上面几个页签里那些「这张 sheet 怎么出纸」不是一回事。
            </p>
            <p v-if="version.source === 'field' && splitByRow">
              主接口字段 + 「每条数据一张/一页」时，<b>每条数据按自己的这个字段各选各的版式</b> ——
              跨期的一批单据可以一次打完。
            </p>
            <p v-else-if="version.source === 'field'">
              非拆分报表取的是主接口<b>第一行</b>的字段值；汇总类报表第一行的日期未必代表整张表，
              建议改用「报表参数」或「渲染当日」。
            </p>
          </div>
        </el-form>
      </el-tab-pane>
    </el-tabs>

    <div class="preview-summary">
      <span>可打印区域 <b class="mono">{{ printable.width.toFixed(0) }} × {{ printable.height.toFixed(0) }}</b> mm</span>
      <span v-if="reserveMm > 0">页头页尾占 <b class="mono">{{ reserveMm.toFixed(0) }}</b> mm</span>
      <span>当前内容 <b class="mono">{{ pageInfo.pages }}</b> 页</span>
      <span>整表切分 <b class="mono">{{ pageInfo.cols }}</b> 列 × <b class="mono">{{ pageInfo.rows }}</b> 行</span>
      <span :class="{ 'is-over': overflowPx > 1 }">
        打印列宽合计 <b class="mono">{{ Math.round(totalWidth) }}</b> px
        <template v-if="limitPx"> / 上限 <b class="mono">{{ limitPx }}</b> px</template>
      </span>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useDesignerStore } from '@/stores/designer'
import {
  PAPER_OPTIONS,
  ORIENTATIONS,
  HEADER_PLACEHOLDERS,
  normalizePageConfig,
  printableSizeMm,
  computePageBreaks,
  headerFooterReserveMm,
  parseA1Range,
  formatA1Range,
  widthLimitPx,
  widthOverflowPx,
  totalColumnsWidth
} from '@/utils/print'
import { versionIntervals, intervalText } from '@/utils/version'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 画布分页线开关（由设计器持有） */
  showOverlay: { type: Boolean, default: true },
  /** 取当前选区：() => {r1,c1,r2,c2} | null */
  getSelection: { type: Function, default: null },
  /** 当前正在设计的 sheet，用于估算页数 */
  sheet: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'update:showOverlay', 'applied'])

/** 页头页尾的三段 */
const SECTIONS = [
  { key: 'left', label: '左' },
  { key: 'center', label: '居中' },
  { key: 'right', label: '右' }
]

const store = useDesignerStore()
const form = ref(normalizePageConfig(null))
/** 'sheet' 仅当前工作表 | 'all' 全部工作表 */
const scope = ref('sheet')
/**
 * 输出方式（报表级，不按 sheet 分）：单 sheet / 主接口每条数据一个 sheet。
 * 和打印设置一样，编辑的是副本，点确定才写回 store。
 */
const split = ref({ splitMode: 'single', sheetNameField: '' })
/**
 * 版本切换规则（**报表级**，绑的是 `store.report.versionConfig` 而不是 content —— content
 * 本身就是被版本化的那个东西，规则放进去就成了「每个版本各有一套怎么选自己」，逻辑成环）。
 */
const version = ref({ source: 'field', field: '', fallback: 'default' })
const tab = ref('page')
/** 占位符要插到哪个输入框：最后获得焦点的那个 */
const target = ref({ part: 'header', section: 'center' })

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const sheetCount = computed(() => store.content.sheets?.length || 0)
const multiSheet = computed(() => sheetCount.value > 1)
const sheetName = computed(() => props.sheet?.name || `工作表${store.sheetIndex + 1}`)
const title = computed(() => (multiSheet.value ? `打印设置 · ${sheetName.value}` : '打印设置'))

/** 打开时从当前 sheet 生效的设置拷副本，取消不污染原数据 */
watch(visible, (v) => {
  if (!v) return
  // 深拷 header / footer / watermark：浅拷会让弹窗里的输入直接改到 store 上，取消也撤不回来
  form.value = normalizePageConfig(JSON.parse(JSON.stringify(store.pageConfig)))
  const mode = ['perRow', 'perRowPage'].includes(store.content.splitMode) ? store.content.splitMode : 'single'
  split.value = {
    // 多 sheet 模板不许按行拆 sheet（见下方 canPerRow）：老报表里存着的 perRow 在这里退回单 sheet，
    // 否则单选框上会亮着一个禁用项，点确定又原样写回去
    splitMode: mode === 'perRow' && multiSheet.value ? 'single' : mode,
    sheetNameField: store.content.sheetNameField || ''
  }
  version.value = store.versionConfigOf()
  // 单 sheet 报表没有「按 sheet 设」的意义，直接写报表级，避免存一份多余的覆盖
  scope.value = multiSheet.value ? 'sheet' : 'all'
  tab.value = 'page'
  target.value = { part: 'header', section: 'center' }
})

/** 当前报表的主接口（含字段），没设主接口时为 null */
const primary = computed(() => store.datasetByCode(store.content.primaryDataset))
const primaryFields = computed(() => primary.value?.fields || [])
/**
 * 只有「主接口是集合型」才拆得动：分页型自己就在按页取数，再按行拆 sheet 是两套分页打架。
 */
const canSplit = computed(() => !!primary.value && primary.value.resultType !== 'page')
/**
 * 「每条数据一个 sheet」还多一条限制：**模板本身是多 sheet 时不许选**。
 * 它是把整份模板（M 张）复制 N 遍，出来 M×N 张 sheet，同一条数据的几张单据被打散在
 * 整个工作簿里；要一条数据一份得选 perRowPage（各模板各拼回一张，按页分开）。
 * 这条在 `stores/designer.js#setSheetSplit` 上也拦了一道，那边才是最终生效的地方。
 */
const canPerRow = computed(() => canSplit.value && !multiSheet.value)

/* ------------------------------ 版本 ------------------------------ */

const reportParams = computed(() => store.content.params || [])
/** 版本列表 + 推导出来的生效区间（只读展示，改在「版本管理」里） */
const versionRows = computed(() => versionIntervals(store.versions))
const defaultLabel = computed(() => {
  const d = versionRows.value.find((v) => v.isDefault)
  return d ? ` ${d.label}` : ''
})
/** 按条拆分时，版本是**逐行**选的（每张单据各按自己的日期） */
const splitByRow = computed(() => ['perRow', 'perRowPage'].includes(store.content.splitMode))

const printable = computed(() => printableSizeMm(form.value))

/** 页头页尾一共占掉多少纸张高度（含它们各自的边距） */
const reserveMm = computed(
  () => headerFooterReserveMm(form.value.header) + headerFooterReserveMm(form.value.footer)
)

const pageInfo = computed(() => {
  const pb = computePageBreaks(props.sheet, form.value)
  return { pages: pb.pages, cols: pb.colBreaks.size + 1, rows: pb.rowBreaks.size + 1 }
})

/** 一页宽的上限（px）；0 表示当前设置下不限宽 */
const limitPx = computed(() => Math.round(widthLimitPx(form.value)))
const totalWidth = computed(() => totalColumnsWidth(props.sheet, form.value))
const overflowPx = computed(() => widthOverflowPx(props.sheet, form.value))

const targetLabel = computed(() => {
  const part = target.value.part === 'header' ? '页头' : '页尾'
  return part + SECTIONS.find((s) => s.key === target.value.section)?.label
})

/** 弹窗里的水印预览：和浏览器打印用的是同一套 CSS 变换 */
const watermarkPreviewStyle = computed(() => {
  const wm = form.value.watermark
  return {
    color: wm.color,
    opacity: wm.opacity / 100,
    // 预览框比纸张小得多，字号按比例缩一下才看得出效果
    fontSize: `${Math.max(wm.fontSize / 3, 8)}px`,
    transform: `rotate(${-wm.rotation}deg)`,
    whiteSpace: 'nowrap'
  }
})

function insertToken(token) {
  const { part, section } = target.value
  form.value[part][section] = (form.value[part][section] || '') + token
}

function useSelection() {
  const range = props.getSelection?.()
  if (!range) {
    ElMessage.warning('请先在表格中框选一个区域')
    return
  }
  form.value.printArea = formatA1Range(range)
}

/** 撤掉当前 sheet 的单独设置，改回跟随报表级 */
function followReport() {
  store.clearPageConfig()
  form.value = normalizePageConfig(JSON.parse(JSON.stringify(store.pageConfig)))
  emit('applied')
  ElMessage.success(`「${sheetName.value}」已改回跟随报表设置`)
}

function onConfirm() {
  if (form.value.printArea && !parseA1Range(form.value.printArea)) {
    ElMessage.warning('打印区域格式不合法，应形如 A1:F30')
    return
  }
  store.setPageConfig(form.value, scope.value)
  // 输出方式是报表级的，跟「作用范围」无关，单独写
  store.setSheetSplit(canSplit.value ? split.value : { splitMode: 'single' })
  // 版本切换规则同样是报表级的，而且存在 report 上而不是 content 里
  store.setVersionConfig(version.value)
  emit('applied')
  visible.value = false
  ElMessage.success(
    scope.value === 'all' && multiSheet.value
      ? '打印设置已更新（全部工作表）'
      : `「${sheetName.value}」的打印设置已更新`
  )
}
</script>

<style scoped>
.margin-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  width: 100%;
}
.margin-grid :deep(.el-input-number) {
  width: 100%;
}
.unit {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
.area-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.limit-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.limit-row .text-muted {
  line-height: 1.4;
}
.scope-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}
.hf-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  width: 100%;
}
.style-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.style-row :deep(.el-input-number) {
  width: 110px;
}
.token-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.token {
  cursor: pointer;
}
.tips {
  padding: 0 0 4px 96px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.tips p {
  margin: 0;
}
.version-table {
  width: 100%;
}
.version-table :deep(.el-tag) {
  margin-left: 6px;
}
.wm-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 88px;
  overflow: hidden;
  border: 1px dashed var(--mz-border);
  border-radius: 4px;
  background: #fff;
}
.is-over {
  color: var(--el-color-warning);
}
.preview-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  padding: 8px 12px;
  border-radius: 4px;
  background: var(--mz-panel-bg);
  font-size: 12px;
  color: var(--el-text-color-regular);
}
</style>
