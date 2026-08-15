<!--
  设计器右侧面板：当前选中单元格的配置表单。
  任何字段变更都通过 emit('update', patch) 通知父组件写入 store。
-->
<template>
  <div class="mz-panel cell-property-panel">
    <div class="mz-panel-title">单元格属性</div>

    <el-empty v-if="!cell" description="请选择一个单元格" :image-size="80" />

    <div v-else class="panel-body">
      <div class="cell-coord mono">{{ coordText }}</div>

      <el-form label-position="top" size="small" class="cell-form">
        <el-form-item label="单元格类型">
          <el-select
            :model-value="config?.type || 'text'"
            style="width: 100%"
            @change="onTypeChange"
          >
            <el-option v-for="t in CELL_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>

        <!-- 图片/条码单元格取数方式与数据格完全一致，只是取到的值当图片画 -->
        <div v-if="isImage" class="text-muted type-hint">
          {{ typeHint }}<br />
          图片等比例装进格子并居中（不拉伸），要显示得更大就拖高这一行或合并几格
        </div>

        <template v-if="isDataBound">
          <el-form-item label="数据集">
            <el-select
              :model-value="config?.datasetCode"
              placeholder="请选择数据集"
              filterable
              style="width: 100%"
              @change="onChange('datasetCode', $event)"
            >
              <!-- 内部数据集（reportId 非空）标一下，跟公共的区分开 -->
              <el-option
                v-for="ds in datasets"
                :key="ds.code"
                :label="`${ds.name} (${ds.code})${ds.reportId ? ' · 本报表' : ''}`"
                :value="ds.code"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="字段">
            <el-select
              :model-value="config?.field"
              placeholder="请选择字段（可搜字段名或中文名）"
              filterable
              :filter-method="(q) => (fieldQuery = q)"
              style="width: 100%"
              @change="onChange('field', $event)"
              @visible-change="onFieldVisible"
            >
              <el-option
                v-for="f in filteredFields"
                :key="f.fieldName"
                :label="f.fieldText || f.fieldName"
                :value="f.fieldName"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="扩展方式">
            <el-select :model-value="config?.expandType" style="width: 100%" @change="onChange('expandType', $event)">
              <el-option v-for="t in EXPAND_TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <!-- 出不了图时的兜底：改用同一行另一个字段的值当文字写，别让格子整块空白 -->
          <template v-if="isImage">
            <el-form-item label="兜底字段">
              <el-select
                :model-value="config?.fallbackField || ''"
                placeholder="不兜底（出空白）"
                clearable
                filterable
                :filter-method="(q) => (fallbackQuery = q)"
                style="width: 100%"
                @change="onChange('fallbackField', $event || '')"
                @visible-change="onFallbackVisible"
              >
                <el-option
                  v-for="f in filteredFallbackFields"
                  :key="f.fieldName"
                  :label="f.fieldText || f.fieldName"
                  :value="f.fieldName"
                />
              </el-select>
            </el-form-item>
            <div class="text-muted format-hint">{{ fallbackHint }}</div>
          </template>
          <!-- 分组合并按「值相同」判定，图片格没有文字值，后端也不给它做合并 -->
          <el-form-item v-if="!isImage" label="分组方式">
            <el-select :model-value="config?.groupType" style="width: 100%" @change="onChange('groupType', $event)">
              <el-option v-for="t in GROUP_TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <!-- 汇总是「把一列数字算成一个值」，图片没有这回事 -->
          <el-form-item v-if="!isImage" label="汇总方式">
            <el-select :model-value="config?.aggregate" style="width: 100%" @change="onChange('aggregate', $event)">
              <el-option v-for="t in AGGREGATES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
        </template>

        <!-- 条码由服务端出图，预览与导出的三种格式看到的是同一张 -->
        <template v-if="isBarcode">
          <el-form-item label="码制">
            <el-select
              :model-value="config?.barcodeFormat || defaultBarcodeFormat"
              style="width: 100%"
              @change="onChange('barcodeFormat', $event)"
            >
              <el-option
                v-for="f in barcodeFormats"
                :key="f.value"
                :label="f.label"
                :value="f.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="isQrLevel" label="纠错级别">
            <el-select
              :model-value="config?.qrLevel || 'M'"
              style="width: 100%"
              @change="onChange('qrLevel', $event)"
            >
              <el-option v-for="l in QR_LEVELS" :key="l.value" :label="l.label" :value="l.value" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="config?.type === 'barcode'" label="条码下方印出原文">
            <el-switch
              :model-value="config?.barcodeText !== false"
              @change="onChange('barcodeText', $event)"
            />
          </el-form-item>
        </template>

        <template v-if="config?.type === 'formula'">
          <el-form-item label="表达式">
            <el-input
              type="textarea"
              :rows="3"
              :model-value="config?.expression"
              placeholder='例如 !{SUM("B2:B10")}'
              @change="onChange('expression', $event)"
            />
          </el-form-item>
          <div class="text-muted formula-hint">
            可用函数：SUM / AVG / MAX / MIN / COUNT / IF / ROUND / CONCAT / DATEFMT / NUMFMT / NVL / ABS / PERCENT
          </div>
        </template>

        <template v-if="config?.type === 'param'">
          <el-form-item label="参数名">
            <el-input
              :model-value="config?.field"
              placeholder="参数名（不含 ${} ）"
              @change="onChange('field', $event)"
            />
          </el-form-item>
        </template>

        <el-divider style="margin: 12px 0" />

        <!-- 格式化是给文字用的，图片格没有文字 -->
        <el-form-item v-if="!isImage" label="格式化类型">
          <el-select :model-value="config?.formatType" style="width: 100%" @change="onFormatTypeChange">
            <el-option v-for="t in FORMAT_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <!-- 模板只有金额与日期可配：数值/百分比/文本按各自的默认模板出，没什么可选的 -->
        <template v-if="!isImage && isCurrency">
          <el-form-item label="金额样式">
            <el-select
              :model-value="config?.formatPattern || '¥#,##0.00'"
              style="width: 100%"
              @change="onChange('formatPattern', $event)"
            >
              <el-option v-for="p in CURRENCY_PATTERNS" :key="p.value" :label="p.label" :value="p.value" />
            </el-select>
          </el-form-item>
          <!-- 大写出的是一段文字，Excel 里不再是能参与计算的数值，先说清楚 -->
          <div v-if="isChineseUpper" class="text-muted format-hint">
            1234.56 出成「壹仟贰佰叁拾肆元伍角陆分」；四舍五入到分，导出到 Excel 是文本
          </div>
        </template>
        <template v-if="!isImage && isDate">
          <el-form-item label="日期格式">
            <!-- allow-create：下拉里没有的格式直接敲进去 -->
            <el-select
              :model-value="config?.formatPattern || 'yyyy-MM-dd'"
              filterable
              allow-create
              default-first-option
              placeholder="yyyy-MM-dd"
              style="width: 100%"
              @change="onChange('formatPattern', $event)"
            >
              <el-option v-for="p in DATE_PATTERNS" :key="p.value" :label="`${p.label}　${p.value}`" :value="p.value" />
            </el-select>
          </el-form-item>
          <div class="text-muted format-hint">
            可直接输入自定义格式，如 <code>yyyy年MM月dd日</code>；
            yyyy 年、MM 月、dd 日、HH 时、mm 分、ss 秒
          </div>
        </template>
      </el-form>

      <div v-if="size" class="cell-size">
        <div class="size-title">单元格尺寸</div>
        <div class="size-row">
          <span class="size-label">宽</span>
          <el-input-number
            :model-value="Math.round(size.width)"
            :min="minSize(size.colSpan)"
            :max="maxWidth(size.colSpan)"
            :step="5"
            :controls="false"
            size="small"
            class="size-input"
            @change="onResize('width', $event)"
          />
          <span class="size-unit">px</span>
          <span class="size-mm">≈ {{ pxToMm(size.width).toFixed(1) }} mm</span>
        </div>
        <div class="size-row">
          <span class="size-label">高</span>
          <el-input-number
            :model-value="Math.round(size.height)"
            :min="minSize(size.rowSpan)"
            :max="maxHeight(size.rowSpan)"
            :step="5"
            :controls="false"
            size="small"
            class="size-input"
            @change="onResize('height', $event)"
          />
          <span class="size-unit">px</span>
          <span class="size-mm">≈ {{ pxToMm(size.height).toFixed(1) }} mm</span>
        </div>
        <div v-if="size.rowSpan > 1 || size.colSpan > 1" class="text-muted size-merge">
          合并区域 {{ size.rowSpan }} 行 × {{ size.colSpan }} 列，上面是整块的尺寸，
          改了按现有比例摊给这几行几列
        </div>
      </div>

      <el-button type="danger" plain size="small" style="width: 100%" @click="$emit('clear')">
        清除该单元格配置
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import {
  toA1,
  CELL_TYPES,
  DATA_BOUND_TYPES,
  IMAGE_TYPES,
  BARCODE_TYPES,
  BARCODE_FORMATS,
  QRCODE_FORMATS,
  QR_LEVELS,
  EXPAND_TYPES,
  AGGREGATES,
  GROUP_TYPES,
  FORMAT_TYPES,
  CURRENCY_PATTERNS,
  DATE_PATTERNS,
  CN_UPPER_PATTERN,
  defaultFormatPattern
} from '@/utils/sheet'
import { pxToMm, MIN_CELL_SIZE, MAX_ROW_HEIGHT, MAX_COL_WIDTH } from '@/utils/print'

const props = defineProps({
  /** store.activeConfig，可能为 null */
  config: { type: Object, default: null },
  /** {r,c} 或 null */
  cell: { type: Object, default: null },
  /** 归一化后的数据集列表 */
  datasets: { type: Array, default: () => [] },
  /** 当前单元格尺寸 {width,height,rowSpan,colSpan}，见 utils/print.js#cellSizePx */
  size: { type: Object, default: null }
})

const emit = defineEmits(['update', 'clear', 'resize'])

const coordText = computed(() => (props.cell ? toA1(props.cell.r, props.cell.c) : ''))

/** 值当图片渲染（img / base64 / barcode / qrcode）：没有格式化、没有汇总 */
const isImage = computed(() => IMAGE_TYPES.includes(props.config?.type))
/** 值是要编成码的那串字（后端出图） */
const isBarcode = computed(() => BARCODE_TYPES.includes(props.config?.type))
/** 按数据集字段取值：数据格与几种图片格共用同一套绑定配置 */
const isDataBound = computed(() => DATA_BOUND_TYPES.includes(props.config?.type))

const TYPE_HINTS = {
  img: '字段值是图片地址（http/https）。导出时由服务端去下载，内网地址要服务器也能访问',
  base64: '字段值是图片的 Base64（带不带 data: 前缀都行）',
  barcode: '字段值就是要编进条码的那串字。编不出来（位数/字符不合码制）的那一格出空白，不影响其它格',
  qrcode: '字段值就是要编进二维码的那串字，支持中文。内容越长码越密，扫码距离越近'
}
const typeHint = computed(() => TYPE_HINTS[props.config?.type] || '')

const barcodeFormats = computed(() =>
  props.config?.type === 'qrcode' ? QRCODE_FORMATS : BARCODE_FORMATS
)
const defaultBarcodeFormat = computed(() =>
  props.config?.type === 'qrcode' ? 'QR_CODE' : 'CODE_128'
)
/** 纠错级别只有 QR 收：Aztec / PDF417 的「纠错」是另一套取值，DataMatrix 压根不给配 */
const isQrLevel = computed(
  () =>
    props.config?.type === 'qrcode' && (props.config?.barcodeFormat || 'QR_CODE') === 'QR_CODE'
)

const currentFields = computed(() => {
  const ds = props.datasets.find((d) => d.code === props.config?.datasetCode)
  return ds ? ds.fields : []
})

/** 兜底字段取自同一数据集的同一行，绑图的那个字段自己排除掉（它没值才轮得到兜底） */
const fallbackFields = computed(() =>
  currentFields.value.filter((f) => f.fieldName !== props.config?.field)
)

/**
 * 字段下拉的搜索：**字段名与中文名都能搜到**。
 *
 * 这个面板窄，选项标签只写 `fieldText || fieldName`（别的地方是
 * `中文名 (field_name)`，宽度够）—— 而 el-select 自带的过滤只认标签文本，
 * 于是设了中文名的字段就再也搜不到它的英文名了，偏偏手写 `#{code.field}` 时
 * 记住的往往正是英文名。所以自己接管过滤，两样都比对。
 */
function matchFields(list, keyword) {
  const kw = String(keyword || '').trim().toLowerCase()
  if (!kw) return list
  return list.filter((f) =>
    `${f.fieldName || ''} ${f.fieldText || ''}`.toLowerCase().includes(kw)
  )
}

const fieldQuery = ref('')
const fallbackQuery = ref('')
const filteredFields = computed(() => matchFields(currentFields.value, fieldQuery.value))
const filteredFallbackFields = computed(() => matchFields(fallbackFields.value, fallbackQuery.value))

/**
 * 下拉收起时把关键字清掉：el-select 自己会清输入框，但过滤用的是我们这份状态，
 * 不清的话下次展开看到的还是上次搜剩下的那几条。
 */
function onFieldVisible(visible) {
  if (!visible) fieldQuery.value = ''
}

function onFallbackVisible(visible) {
  if (!visible) fallbackQuery.value = ''
}

/**
 * `img` 的兜底只在「字段本身没值」时管用：地址取不回来是导出时（服务端下载）才知道的，
 * 渲染时那一格已经当成有图处理了。这条差别要在界面上说清楚，否则就是「配了兜底却没出来」的报障。
 */
const fallbackHint = computed(() =>
  props.config?.type === 'img'
    ? '字段值为空时改印这个字段的文字。注意：地址填了但图片下载不下来时兜不了底（那是导出时才知道的）'
    : props.config?.type === 'base64'
      ? '字段值为空（如这单还没签字）时，改印这个字段的文字，例如签字人姓名'
      : '字段值为空、或这串字编不出码时，改印这个字段的文字'
)

const isCurrency = computed(() => props.config?.formatType === 'currency')
const isDate = computed(() => props.config?.formatType === 'date')
const isChineseUpper = computed(() => props.config?.formatPattern === CN_UPPER_PATTERN)

function onChange(field, value) {
  emit('update', { [field]: value })
}

/* ------------------------- 尺寸 ------------------------- */

/**
 * 合并块的取值范围要按跨了几行几列放大 —— 每一行/列各自受上下限约束，整块自然是它们的 n 倍。
 */
function minSize(span) {
  return MIN_CELL_SIZE * Math.max(span || 1, 1)
}
function maxWidth(span) {
  return MAX_COL_WIDTH * Math.max(span || 1, 1)
}
function maxHeight(span) {
  return MAX_ROW_HEIGHT * Math.max(span || 1, 1)
}

/**
 * 手输宽高。清空输入框时 el-input-number 给的是 null，那是「没改」不是「设成 0」，直接忽略。
 * 真正摊到哪几行几列由父组件按合并区算（utils/print.js#resizeCellPlan）。
 */
function onResize(dim, value) {
  if (value == null || !Number.isFinite(value)) return
  if (Math.round(value) === Math.round(props.size?.[dim] ?? 0)) return
  emit('resize', { [dim]: Math.round(value) })
}

/**
 * 换单元格类型时，把不属于新类型的码制一并清掉。
 *
 * 条形码与二维码各有一套码制名，留着 `CODE_128` 切到二维码就是「下拉框空着、后端照旧出一维码」。
 * 清成空串而不是填上新默认值 —— 空串的语义就是「按类型给默认」（见 CellConfigDTO#barcodeFormat）。
 * 兜底字段同理：换成非图片类型之后它在界面上就没了，留着只会在下次换回图片格时冒出来。
 */
function onTypeChange(value) {
  const patch = { type: value }
  const formats = value === 'qrcode' ? QRCODE_FORMATS : BARCODE_FORMATS
  const current = props.config?.barcodeFormat
  if (current && !formats.some((f) => f.value === current)) {
    patch.barcodeFormat = ''
  }
  if (!IMAGE_TYPES.includes(value) && props.config?.fallbackField) {
    patch.fallbackField = ''
  }
  emit('update', patch)
}

/** 换格式化类型时模板一起换成该类型的默认值，见 utils/sheet.js#defaultFormatPattern */
function onFormatTypeChange(value) {
  emit('update', { formatType: value, formatPattern: defaultFormatPattern(value) })
}
</script>

<style scoped>
.cell-property-panel {
  display: flex;
  flex-direction: column;
}
.panel-body {
  padding: 12px;
  overflow: auto;
}
.cell-coord {
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--mz-primary);
}
.formula-hint {
  margin-bottom: 12px;
  line-height: 1.6;
}
.type-hint {
  margin: -4px 0 12px;
  line-height: 1.6;
}
.format-hint {
  margin: -6px 0 12px;
  line-height: 1.6;
}
.format-hint code {
  padding: 0 2px;
  background: var(--mz-panel-bg);
}
.cell-form :deep(.el-form-item) {
  margin-bottom: 12px;
}
.cell-size {
  margin-bottom: 12px;
  padding: 8px 10px;
  border-radius: 4px;
  background: var(--mz-panel-bg);
  font-size: 12px;
}
.size-title {
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.size-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
  line-height: 1.8;
}
.size-label {
  width: 20px;
  color: var(--el-text-color-secondary);
}
/* 输入框只放三四位数，别把右边的 mm 换算挤没了 */
.size-input {
  width: 72px;
}
.size-input :deep(.el-input__inner) {
  text-align: left;
  font-family: 'Cascadia Code', Consolas, Monaco, monospace;
}
.size-unit {
  color: var(--el-text-color-secondary);
}
.size-mm {
  margin-left: auto;
  color: var(--el-text-color-placeholder);
}
.size-merge {
  margin-top: 4px;
  line-height: 1.5;
}
</style>
