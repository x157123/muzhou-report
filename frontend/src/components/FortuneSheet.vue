<!--
  FortuneSheet Vue 桥接组件。

  FortuneSheet 官方只发布了 @fortune-sheet/core（无 UI 的内核）与 @fortune-sheet/react（UI 层），
  没有 Vue 包。这里用一个极薄的 React 根挂载做桥接：Vue 负责外层布局与状态，
  React 只负责把 <Workbook /> 渲染进这个 div。对外暴露的是纯 Vue 的 props / emits / expose。

  暴露方法（通过 ref 调用）：
    getAllSheets()                    -> Sheet[]      当前全部工作表（含用户编辑）
    getSheet()                        -> Sheet        当前激活工作表
    getSelection()                    -> Range[]      当前选区
    getActiveCell()                   -> {r, c} | null 当前选区左上角单元格
    getSelectionRange()               -> {r1,c1,r2,c2} | null 当前选区范围
    repaint()                                        强制画布重绘（渲染钩子依赖的外部状态变化时用）
    setCellValue(r, c, value)                        写入单元格（value 可为字符串或 {v,m,ct,...}）
    setCellFormat(r, c, attr, value)                 设置单元格样式
    setColumnWidth({ [col]: px })                    批量设置列宽
    insertRowOrColumn(type, index, count)            在 index 之后插入若干行/列
    reload(sheets)                                   用新数据整体重建工作簿
    instance                          -> WorkbookInstance 原生实例（逃生通道）
-->
<template>
  <div ref="wrapRef" class="fortune-sheet-wrap">
    <div ref="containerRef" class="fortune-sheet-container"></div>
    <!-- 叠加层：浮在工作簿之上，用于画分页线等标记；不拦截鼠标 -->
    <slot name="overlay" :rect="canvasRect" />
  </div>
</template>

<script setup>
import { ref, shallowRef, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import React from 'react'
import { createRoot } from 'react-dom/client'
import { Workbook } from '@fortune-sheet/react'
import { applyFontList } from '@/utils/fontList'

// 换掉 core 语言包里的字体清单（中文在前、默认宋体）。挂在这里是因为所有用到工作簿的页面
// 都要经过本组件，改的又是 core 的全局语言包对象，只需在挂载工作簿之前执行一次。
applyFontList()

const props = defineProps({
  /** FortuneSheet Sheet[] 初始数据 */
  data: { type: Array, default: () => [] },
  allowEdit: { type: Boolean, default: true },
  showToolbar: { type: Boolean, default: true },
  showFormulaBar: { type: Boolean, default: true },
  showSheetTabs: { type: Boolean, default: true },
  lang: { type: String, default: 'zh' },
  /** 透传给 Workbook 的其它配置 */
  options: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['change', 'op', 'selection-change', 'sheet-activate', 'ready'])

const wrapRef = ref(null)
const containerRef = ref(null)
/** 工作簿画布相对本组件的位置尺寸，叠加层据此定位 */
const canvasRect = ref({ left: 0, top: 0, width: 0, height: 0 })
const instance = shallowRef(null)
const reactRoot = shallowRef(null)
const renderKey = ref(0)
// reload() 时用它替换 props.data，避免直接改父组件数据
const dataOverride = shallowRef(null)

function currentData() {
  const d = dataOverride.value || props.data
  return Array.isArray(d) && d.length ? d : [defaultSheet()]
}

function defaultSheet() {
  return {
    name: 'Sheet1',
    id: 'sheet_1',
    order: 0,
    status: 1,
    row: 60,
    column: 20,
    celldata: [],
    config: {}
  }
}

/**
 * Workbook 初始化完成时会立刻回调一次 onChange，内容就是刚传进去的 data。
 * 这不是用户编辑，抛给父组件会让报表一打开就变成「未保存」，故吞掉第一次。
 * reload() 重建工作簿时重新置位。
 */
let pendingInitialChange = true

// 保持回调引用稳定：Workbook 内部以 onChange 为 effect 依赖，
// 每次 render 传新函数会重复触发一次 change。
function handleChange(sheets) {
  if (pendingInitialChange) {
    pendingInitialChange = false
    return
  }
  emit('change', sheets)
  // 删除工作表也是直接改 currentSheetId、不触发钩子的，这里兜一次
  // （放在 change 之后，外面拿到的工作表列表已经是删完的了）
  syncActiveSheet()
}

function handleOp(ops) {
  emit('op', ops)
}

/* ------------------------- 激活的工作表 ------------------------- */

/**
 * 当前激活工作表的 id。
 *
 * `afterActivateSheet` 钩子只有 core 的 `changeSheet()` 会触发，而 React 层点击工作表标签
 * 是直接改 `context.currentSheetId` 的（见 @fortune-sheet/react 的 SheetTab / SheetList），
 * 根本不走 `changeSheet` —— 光挂钩子的话，用户点标签切换工作表时外面永远收不到通知，
 * 会一直以为停在第一张（按 sheet 生效的打印设置就全写到第一张上去了）。
 * 所以再盯一层标签栏的点击：点完比对一次 id，变了就补发 sheet-activate。
 */
let activeSheetId = null

/** 标签栏与工作表列表弹层，只有点这些地方才可能切换工作表 */
const SHEET_TAB_SELECTOR = '.luckysheet-sheet-area, .fortune-sheet-list-item'

function currentSheetId() {
  try {
    return instance.value?.getSheet?.()?.id || null
  } catch (e) {
    return null
  }
}

/** id 变了才发事件；不传 id 时自己去实例上取 */
function syncActiveSheet(id) {
  const next = id || currentSheetId()
  if (!next || next === activeSheetId) return
  activeSheetId = next
  emit('sheet-activate', next)
}

/** React 的状态更新要等事件回调结束才落地，所以下一个宏任务里再读 id */
function onTabClick(e) {
  if (!e.target?.closest?.(SHEET_TAB_SELECTOR)) return
  setTimeout(() => syncActiveSheet(), 0)
}

/**
 * 合并内置钩子与外部传入的钩子：同名钩子两个都调用，而不是后者把前者顶掉
 * （否则外部传一个 afterSelectionChange 就会把桥接自己的事件转发弄没了）。
 * before* 钩子返回 false 表示中止，任一方否决即中止。
 */
function mergeHooks(base, extra) {
  const merged = { ...base }
  Object.entries(extra || {}).forEach(([name, fn]) => {
    const prev = merged[name]
    merged[name] = prev
      ? (...args) => {
          const a = prev(...args)
          const b = fn(...args)
          return a === false || b === false ? false : b
        }
      : fn
  })
  return merged
}

function renderWorkbook() {
  if (!reactRoot.value) return
  const element = React.createElement(Workbook, {
    key: `wb-${renderKey.value}`,
    ref: (inst) => {
      instance.value = inst
      if (inst) {
        emit('ready', inst)
        // 画布此时才进 DOM，叠加层要等它出现后才能对齐
        nextTick(() => {
          measureCanvas()
          // 打开的报表可能激活在第二张工作表上，外面别默认停在第一张
          syncActiveSheet()
        })
      }
    },
    data: currentData(),
    lang: props.lang,
    allowEdit: props.allowEdit,
    showToolbar: props.showToolbar,
    showFormulaBar: props.showFormulaBar,
    showSheetTabs: props.showSheetTabs,
    onChange: handleChange,
    onOp: handleOp,
    hooks: mergeHooks(
      {
        afterSelectionChange: (sheetId, selection) => {
          // 切换工作表也会走这里（新工作表里点了格子），顺手兜一次
          syncActiveSheet(sheetId)
          emit('selection-change', { sheetId, selection })
        },
        // 只有 changeSheet() 会触发它（新增工作表、超链接跳转），点标签不会，见 syncActiveSheet
        afterActivateSheet: (sheetId) => syncActiveSheet(sheetId)
      },
      props.options.hooks
    ),
    ...stripHooks(props.options)
  })
  reactRoot.value.render(element)
}

function stripHooks(opts) {
  const { hooks, ...rest } = opts || {}
  return rest
}

/** 量出工作簿画布的位置，供叠加层对齐（画布上方还有工具栏与编辑栏） */
function measureCanvas() {
  const wrap = wrapRef.value
  const canvas = wrap?.querySelector('canvas')
  if (!wrap || !canvas) return
  const wr = wrap.getBoundingClientRect()
  const cr = canvas.getBoundingClientRect()
  const next = {
    left: cr.left - wr.left,
    top: cr.top - wr.top,
    width: cr.width,
    height: cr.height
  }
  const cur = canvasRect.value
  if (cur.left !== next.left || cur.top !== next.top || cur.width !== next.width || cur.height !== next.height) {
    canvasRect.value = next
  }
}

let resizeObserver = null

onMounted(() => {
  reactRoot.value = createRoot(containerRef.value)
  renderWorkbook()
  // 画布尺寸随窗口/面板变化，叠加层要跟着重新对齐
  resizeObserver = new ResizeObserver(() => measureCanvas())
  resizeObserver.observe(wrapRef.value)
  // 点标签切换工作表不触发任何钩子，只能自己盯着，见 syncActiveSheet
  wrapRef.value.addEventListener('click', onTabClick)
  setTimeout(measureCanvas, 0)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  wrapRef.value?.removeEventListener('click', onTabClick)
  const root = reactRoot.value
  reactRoot.value = null
  instance.value = null
  // React 要求在当前渲染周期之外 unmount
  if (root) setTimeout(() => root.unmount(), 0)
})

// 非 reload 的 props 变化只需重新 render（Workbook 内部状态保留）
watch(
  () => [props.allowEdit, props.showToolbar, props.showFormulaBar, props.showSheetTabs],
  () => renderWorkbook()
)

/* ------------------------- 对外 API ------------------------- */

function getAllSheets() {
  try {
    return instance.value?.getAllSheets?.() || []
  } catch (e) {
    console.warn('[FortuneSheet] getAllSheets 失败', e)
    return []
  }
}

function getSheet() {
  try {
    return instance.value?.getSheet?.() || null
  } catch (e) {
    return null
  }
}

function getSelection() {
  try {
    return instance.value?.getSelection?.() || []
  } catch (e) {
    return []
  }
}

/** 当前选区左上角单元格坐标 */
function getActiveCell() {
  const sel = getSelection()
  if (!sel || !sel.length) return null
  const s = sel[0]
  const r = s.row?.[0] ?? s.r
  const c = s.column?.[0] ?? s.c
  if (r === undefined || c === undefined || r === null || c === null) return null
  return { r, c }
}

/** 当前选区范围 {r1,c1,r2,c2}（含起止），无选区返回 null */
function getSelectionRange() {
  const sel = getSelection()
  if (!sel || !sel.length) return null
  const s = sel[0]
  const r1 = s.row?.[0] ?? s.r
  const r2 = s.row?.[1] ?? s.r
  const c1 = s.column?.[0] ?? s.c
  const c2 = s.column?.[1] ?? s.c
  if ([r1, r2, c1, c2].some((n) => n === undefined || n === null)) return null
  return { r1: Math.min(r1, r2), c1: Math.min(c1, c2), r2: Math.max(r1, r2), c2: Math.max(c1, c2) }
}

/**
 * 强制画布重绘。
 * FortuneSheet 没有对外的 refresh API，但 Sheet 组件监听了 window resize 并在回调里
 * 重新计算画布上下文，派发一次 resize 是官方组件自身也走的重绘路径。
 * 仅在渲染钩子依赖的外部状态（如分页线配置）变化时调用。
 */
function repaint() {
  if (!instance.value) return
  window.dispatchEvent(new Event('resize'))
}

function setCellValue(r, c, value, options) {
  instance.value?.setCellValue?.(r, c, value, options)
}

function setCellFormat(r, c, attr, value, options) {
  instance.value?.setCellFormat?.(r, c, attr, value, options)
}

/**
 * 批量设置列宽，columnInfo 形如 { 0: 80, 1: 120 }。
 * 第三个参数 custom=true 标记为「用户指定宽度」，与拖动列边界的效果一致，
 * 否则 FortuneSheet 后续可能按内容自动重算把它覆盖掉。
 */
function setColumnWidth(columnInfo, options) {
  instance.value?.setColumnWidth?.(columnInfo, options, true)
}

/**
 * 批量设置行高，rowInfo 形如 { 0: 19, 2: 76 }。
 * 同样传 custom=true，与拖动行边界等价，写进 config.rowlen 后不会被内部逻辑覆盖。
 */
function setRowHeight(rowInfo, options) {
  instance.value?.setRowHeight?.(rowInfo, options, true)
}

/** 插入行/列：type 'row'|'column'，在 index 之后（direction 默认 rightbottom）插入 count 个 */
function insertRowOrColumn(type, index, count, direction = 'rightbottom') {
  instance.value?.insertRowOrColumn?.(type, index, count, direction)
}

function setSelection(range, options) {
  instance.value?.setSelection?.(range, options)
}

function calculateFormula() {
  try {
    instance.value?.calculateSheetFormula?.()
  } catch (e) {
    /* ignore */
  }
}

/**
 * 用新的 Sheet[] 整体重建工作簿。
 * 注意：props.data 只在挂载时被 Workbook 读取一次，之后修改 data 不会生效——
 * 异步加载出来的内容必须通过本方法（或让组件在数据就绪后才挂载）才能回填到画布。
 */
async function reload(sheets) {
  dataOverride.value = Array.isArray(sheets) && sheets.length ? sheets : [defaultSheet()]
  pendingInitialChange = true
  // 工作簿要重建，激活的工作表按新数据重新确定
  activeSheetId = null
  renderKey.value += 1
  await nextTick()
  renderWorkbook()
}

defineExpose({
  instance,
  canvasRect,
  measureCanvas,
  getAllSheets,
  getSheet,
  getSelection,
  getActiveCell,
  getSelectionRange,
  repaint,
  setCellValue,
  setCellFormat,
  setColumnWidth,
  setRowHeight,
  insertRowOrColumn,
  setSelection,
  calculateFormula,
  reload
})
</script>

<style scoped>
.fortune-sheet-wrap {
  position: relative;
  width: 100%;
  height: 100%;
}

.fortune-sheet-container {
  width: 100%;
  height: 100%;
  min-height: 300px;
  overflow: hidden;
}
</style>
