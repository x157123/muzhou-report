/**
 * 打印/分页相关计算。
 *
 * FortuneSheet 的行高列宽单位是 96dpi 下的 CSS 像素，纸张与页边距用毫米，
 * 两者换算即可算出「一页装得下多少行/多少列」，进而得到分页线位置。
 */

import { colToLetter, letterToCol } from './sheet'

/** 常用纸张尺寸（mm，纵向摆放） */
export const PAPER_SIZES = {
  A3: { w: 297, h: 420 },
  A4: { w: 210, h: 297 },
  A5: { w: 148, h: 210 },
  B4: { w: 250, h: 353 },
  B5: { w: 176, h: 250 },
  Letter: { w: 215.9, h: 279.4 },
  Legal: { w: 215.9, h: 355.6 }
}

export const PAPER_OPTIONS = [
  { label: 'A4 (210×297mm)', value: 'A4' },
  { label: 'A3 (297×420mm)', value: 'A3' },
  { label: 'A5 (148×210mm)', value: 'A5' },
  { label: 'B4 (250×353mm)', value: 'B4' },
  { label: 'B5 (176×250mm)', value: 'B5' },
  { label: 'Letter (8.5×11in)', value: 'Letter' },
  { label: 'Legal (8.5×14in)', value: 'Legal' }
]

export const ORIENTATIONS = [
  { label: '纵向', value: 'portrait' },
  { label: '横向', value: 'landscape' }
]

/** 96dpi 下 1mm 对应的 CSS 像素 */
export const PX_PER_MM = 96 / 25.4

/** FortuneSheet 默认行高/列宽（见 @fortune-sheet/core defaultSettings） */
export const DEFAULT_COL_WIDTH = 73
export const DEFAULT_ROW_HEIGHT = 19

/**
 * 页头/页尾的行距系数。后端 `PageConfigDTO.LINE_HEIGHT`、`PdfExporter`、`WordExporter`
 * 用的是同一个数 —— 各处算出同一个「页头占多高」，分页位置才对得上。
 */
export const HEADER_LINE_HEIGHT = 1.35

/**
 * 页头/页尾里可以写的占位符。存的是这套写法，后端导出时才翻成 Excel 的页眉代码（`&P` 之类）。
 * 见 docs/CONTRACT.md §4。
 */
export const HEADER_PLACEHOLDERS = [
  { token: '${page}', label: '页码' },
  { token: '${pages}', label: '总页数' },
  { token: '${date}', label: '日期' },
  { token: '${time}', label: '时间' },
  { token: '${sheet}', label: '工作表名' }
]

/** 只有页码类占位符要靠分页才知道值，浏览器打印给不出来 */
const PAGE_TOKENS = ['${page}', '${pages}']

export function defaultHeaderFooter() {
  return {
    left: '',
    center: '',
    right: '',
    /** 字号（磅） */
    fontSize: 9,
    /** 页头距纸张上边 / 页尾距纸张下边，mm */
    margin: 5
  }
}

export function defaultWatermark() {
  return {
    /** 空 = 不画水印 */
    text: '',
    fontSize: 60,
    color: '#C0C0C0',
    /** 不透明度百分比 0~100 */
    opacity: 30,
    /** 旋转角度 -90~90，逆时针为正 */
    rotation: 45
  }
}

export function defaultPageConfig() {
  return {
    paperSize: 'A4',
    orientation: 'portrait',
    marginTop: 10,
    marginBottom: 10,
    marginLeft: 10,
    marginRight: 10,
    /** 打印缩放百分比，10~400 */
    scale: 100,
    /** 所有列压缩到一页宽（此时不产生纵向分页线） */
    fitToWidth: false,
    /** 限宽：整表列宽之和不得超过一页可打印宽度；关闭后列宽可随意拖动 */
    limitWidth: true,
    /** 打印区域，形如 "A1:F30"；空串表示整表 */
    printArea: '',
    /** 页头/页尾：画在页边距里，三段都空 = 没设置 */
    header: defaultHeaderFooter(),
    footer: defaultHeaderFooter(),
    /** 水印：只作用于 PDF / Word 导出与浏览器打印，xlsx 里没有水印这个概念 */
    watermark: defaultWatermark()
  }
}

export function normalizePageConfig(cfg) {
  const merged = { ...defaultPageConfig(), ...(cfg || {}) }
  merged.scale = clamp(numOr(merged.scale, 100), 10, 400)
  merged.fitToWidth = !!merged.fitToWidth
  // 老报表的 pageConfig 里没有这一项，缺省即开启
  merged.limitWidth = merged.limitWidth == null ? true : !!merged.limitWidth
  merged.printArea = String(merged.printArea || '').trim().toUpperCase()
  ;['marginTop', 'marginBottom', 'marginLeft', 'marginRight'].forEach((k) => {
    merged[k] = clamp(numOr(merged[k], 10), 0, 100)
  })
  if (!PAPER_SIZES[merged.paperSize]) merged.paperSize = 'A4'
  if (merged.orientation !== 'landscape') merged.orientation = 'portrait'
  // 老报表里这三项不存在，补全后各处就可以直接读
  merged.header = normalizeHeaderFooter(merged.header)
  merged.footer = normalizeHeaderFooter(merged.footer)
  merged.watermark = normalizeWatermark(merged.watermark)
  return merged
}

function normalizeHeaderFooter(hf) {
  const merged = { ...defaultHeaderFooter(), ...(hf || {}) }
  ;['left', 'center', 'right'].forEach((k) => {
    merged[k] = String(merged[k] ?? '')
  })
  merged.fontSize = clamp(numOr(merged.fontSize, 9), 6, 72)
  merged.margin = clamp(numOr(merged.margin, 5), 0, 100)
  return merged
}

function normalizeWatermark(wm) {
  const merged = { ...defaultWatermark(), ...(wm || {}) }
  merged.text = String(merged.text ?? '')
  merged.fontSize = clamp(numOr(merged.fontSize, 60), 8, 200)
  merged.opacity = clamp(numOr(merged.opacity, 30), 0, 100)
  merged.rotation = clamp(numOr(merged.rotation, 45), -90, 90)
  if (!/^#[0-9a-fA-F]{6}$/.test(merged.color || '')) merged.color = '#C0C0C0'
  return merged
}

/* ------------------------- 页头页尾 ------------------------- */

/** 三段都没文字 = 没设置页头/页尾 */
export function isHeaderFooterBlank(hf) {
  return !hf || !['left', 'center', 'right'].some((k) => String(hf[k] || '').trim())
}

/**
 * 页头/页尾占掉的高度（mm）：从纸张边缘算到文字的另一侧。没设置返回 0。
 * 与后端 `PageConfigDTO#headerReserveMm` 是同一个公式。
 */
export function headerFooterReserveMm(hf) {
  if (isHeaderFooterBlank(hf)) return 0
  const { fontSize, margin } = normalizeHeaderFooter(hf)
  return margin + (fontSize * HEADER_LINE_HEIGHT) / 72 * 25.4
}

/**
 * 正文实际的上/下页边距（mm）。
 *
 * 页头页尾画在页边距里，页边距不够高时正文会被压到 —— 这时把正文推到页头下面，
 * 与 Excel / 导出的 PDF 表现一致（后端 `PageConfigDTO#effectiveMarginTopMm` 是同一个规则）。
 */
export function effectiveMarginsMm(cfg) {
  const c = normalizePageConfig(cfg)
  return {
    top: Math.max(c.marginTop, headerFooterReserveMm(c.header)),
    bottom: Math.max(c.marginBottom, headerFooterReserveMm(c.footer))
  }
}

/** 文字里是否用到了页码类占位符（浏览器打印算不出页码，靠这个判断要不要渲染那一段） */
export function hasPageNumber(text) {
  return PAGE_TOKENS.some((token) => String(text || '').includes(token))
}

/**
 * 把页头/页尾文字里的占位符换成实际值。
 *
 * @param text 段文字
 * @param ctx  { page, pages, sheetName, now } —— now 省略则取当前时间
 */
export function expandHeaderFooter(text, ctx = {}) {
  const now = ctx.now instanceof Date ? ctx.now : new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return String(text || '')
    .replaceAll('${page}', String(ctx.page ?? ''))
    .replaceAll('${pages}', String(ctx.pages ?? ''))
    .replaceAll('${date}', `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`)
    .replaceAll('${time}', `${pad(now.getHours())}:${pad(now.getMinutes())}`)
    .replaceAll('${sheet}', String(ctx.sheetName ?? ''))
}

/**
 * 取某张 sheet 实际生效的打印设置。
 *
 * 打印设置可以按 sheet 单独设：`content.pageConfigs[sheetIndex]` 是该 sheet 的覆盖值，
 * 没设过就退回报表级的 `content.pageConfig`（老报表只有后者）。
 */
export function pageConfigOf(content, sheetIndex) {
  const own = content?.pageConfigs?.[String(sheetIndex)]
  return normalizePageConfig(own || content?.pageConfig)
}

/**
 * 取**渲染结果**里这张 sheet 生效的打印设置。
 *
 * 结果 sheet 与模板 sheet 不是一一对应的（拆分是 N 倍、拼接又会把几份合成一张），而
 * pageConfigs 是按**模板**下标存的，所以每张结果 sheet 自己带着 `mzTemplateIndex` 说明
 * 出自哪张模板 —— 有就认它。与后端 `ReportContentDTO#pageConfigOfSheet` 是同一条规则，
 * 改一处要改另一处。
 *
 * 没有这个标记时（老结果）退回按下标推算：perRow 拆出来的结果是模板的 N 倍，取模映射回去；
 * perRowPage 拼过之后这个推算不成立，所以这里判的是 perRow 而不是「拆没拆」。
 *
 * @param sheet 渲染结果里的那张 sheet，可不传
 */
/**
 * 取渲染结果里这张 sheet 生效的打印设置，**优先认后端随结果给的那份**
 * （`RenderResult.sheetPageConfigs`，与 sheets 一一对应）。
 *
 * 结果 sheet 与模板不是一一对应的（拆分 N 倍、拼接又合并），逐行选版本时更是各出自不同版本的
 * 模板 —— 只有渲染引擎说得清哪张该用哪份。老结果没有这一项时才退回 `pageConfigOfRendered`
 * 那条按 `mzTemplateIndex` 推算的路（后端 `RenderServiceImpl#pageConfigAt` 是同一条规则）。
 *
 * @param result 渲染结果 { sheets, sheetPageConfigs }
 * @param index  结果里的第几张
 * @param content 报表 content，兜底推算时用
 */
export function pageConfigOfResult(result, index, content) {
  const own = result?.sheetPageConfigs?.[index]
  if (own) return normalizePageConfig(own)
  return pageConfigOfRendered(content, index, result?.sheets?.[index])
}

export function pageConfigOfRendered(content, renderedIndex, sheet) {
  const t = sheet?.mzTemplateIndex
  if (typeof t === 'number') {
    return pageConfigOf(content, t)
  }
  const templateCount = content?.sheets?.length || 0
  const split = content?.splitMode === 'perRow' && !!content?.primaryDataset
  const index = split && templateCount > 0 ? renderedIndex % templateCount : renderedIndex
  return pageConfigOf(content, index)
}

/** 纸张实际摆放尺寸（mm，已按方向旋转） */
export function paperSizeMm(cfg) {
  const c = normalizePageConfig(cfg)
  const paper = PAPER_SIZES[c.paperSize]
  return c.orientation === 'landscape' ? { width: paper.h, height: paper.w } : { width: paper.w, height: paper.h }
}

/**
 * 去掉页边距后的可打印尺寸（mm）。
 *
 * 高度按**生效的**上下页边距算（页边距装不下页头页尾时正文会被推下去，见
 * `effectiveMarginsMm`），所以设了页头之后画布上的分页线会跟着上移 —— 和导出的 PDF 一致。
 */
export function printableSizeMm(cfg) {
  const c = normalizePageConfig(cfg)
  const paper = paperSizeMm(c)
  const margins = effectiveMarginsMm(c)
  return {
    width: Math.max(paper.width - c.marginLeft - c.marginRight, 10),
    height: Math.max(paper.height - margins.top - margins.bottom, 10)
  }
}

/** 一页能容纳的内容尺寸（px，已计入打印缩放） */
export function printablePageSizePx(cfg) {
  const c = normalizePageConfig(cfg)
  const mm = printableSizeMm(c)
  const scale = c.scale / 100
  return { width: (mm.width * PX_PER_MM) / scale, height: (mm.height * PX_PER_MM) / scale }
}

/* ------------------------- 行高列宽 ------------------------- */

export function colWidth(sheet, c) {
  if (sheet?.config?.colhidden && sheet.config.colhidden[c] === 0) return 0
  const len = sheet?.config?.columnlen?.[c]
  return numOr(len, DEFAULT_COL_WIDTH)
}

export function rowHeight(sheet, r) {
  if (sheet?.config?.rowhidden && sheet.config.rowhidden[r] === 0) return 0
  const len = sheet?.config?.rowlen?.[r]
  return numOr(len, DEFAULT_ROW_HEIGHT)
}

/** px -> mm（96dpi） */
export function pxToMm(px) {
  return px / PX_PER_MM
}

/**
 * 单元格在设计器画布上的尺寸（px）。
 * 落在合并区域里时给的是整个合并块的尺寸，而不是它自己那一格。
 *
 * @returns {{width:number, height:number, rowSpan:number, colSpan:number}|null}
 */
export function cellSizePx(sheet, r, c) {
  if (!sheet || r == null || c == null) return null
  const span = mergeSpanAt(sheet, r, c)
  let width = 0
  for (let i = span.c; i < span.c + span.cs; i += 1) width += colWidth(sheet, i)
  let height = 0
  for (let i = span.r; i < span.r + span.rs; i += 1) height += rowHeight(sheet, i)
  return { width, height, rowSpan: span.rs, colSpan: span.cs }
}

/** (r,c) 所属的合并区域；未合并则返回它自己 */
export function mergeSpanAt(sheet, r, c) {
  const merge = sheet?.config?.merge
  if (merge) {
    for (const m of Object.values(merge)) {
      if (!m) continue
      const rs = numOr(m.rs, 1) > 0 ? numOr(m.rs, 1) : 1
      const cs = numOr(m.cs, 1) > 0 ? numOr(m.cs, 1) : 1
      if (r >= m.r && r < m.r + rs && c >= m.c && c < m.c + cs) return { r: m.r, c: m.c, rs, cs }
    }
  }
  return { r, c, rs: 1, cs: 1 }
}

/**
 * 整个网格的范围。
 * 分页线要按整张表算，而不是只按有内容的区域 —— 否则内容不满一页时一条线都画不出来，
 * 而「纸张边界在哪」恰恰是排版时最需要看到的东西。
 */
export function gridRange(sheet) {
  const used = usedRange(sheet)
  const rows = Math.max(numOr(sheet?.row, 60), used ? used.r2 + 1 : 0)
  const cols = Math.max(numOr(sheet?.column, 20), used ? used.c2 + 1 : 0)
  return { r1: 0, c1: 0, r2: Math.max(rows - 1, 0), c2: Math.max(cols - 1, 0) }
}

/** 有内容的最大行列（celldata 与运行时二维 data 两种形态都支持），空表返回 null */
export function usedRange(sheet) {
  let maxR = -1
  let maxC = -1
  if (Array.isArray(sheet?.celldata)) {
    sheet.celldata.forEach((cell) => {
      if (!cell || !hasValue(cell.v)) return
      if (cell.r > maxR) maxR = cell.r
      if (cell.c > maxC) maxC = cell.c
    })
  } else if (Array.isArray(sheet?.data)) {
    sheet.data.forEach((row, r) => {
      if (!Array.isArray(row)) return
      row.forEach((v, c) => {
        if (!hasValue(v)) return
        if (r > maxR) maxR = r
        if (c > maxC) maxC = c
      })
    })
  }
  if (maxR < 0 || maxC < 0) return null
  return { r1: 0, c1: 0, r2: maxR, c2: maxC }
}

/* ------------------------- 宽度限制 ------------------------- */

/**
 * 限宽后列宽的下限（px）。
 * FortuneSheet 自身拖动的下限是 10px，这里略放宽一点，免得被压成看不清的一条缝。
 */
export const MIN_COL_WIDTH = 16

/**
 * 整表列宽之和的上限（px，sheet 坐标系，即未乘画布缩放）。
 * 返回 0 表示不限制（未开启限宽，或「所有列压缩到一页宽」已经代劳）。
 */
export function widthLimitPx(cfg) {
  const c = normalizePageConfig(cfg)
  if (!c.limitWidth || c.fitToWidth) return 0
  return printablePageSizePx(c).width
}

/**
 * 真正会被打印出来的列范围：设了打印区域就用它，否则用有内容的区域。空表返回 null。
 *
 * 限宽必须按这个范围算，而不是按整张表 —— 网格默认就有 20 列，尾部那些从没用过的空列
 * 光默认列宽就有十几个 73px，按整表算的话任何报表一打开就报「超出纸张宽度」，
 * 而它们根本不会被打印（Excel 与浏览器都只排有内容的区域）。
 * 分页线是另一回事，那里刻意按整表算，见 computePageBreaks。
 */
export function printedColRange(sheet, pageConfig) {
  if (!sheet) return null
  const area = parseA1Range(normalizePageConfig(pageConfig).printArea)
  if (area) return { c1: area.c1, c2: area.c2 }
  const used = usedRange(sheet)
  return used ? { c1: 0, c2: used.c2 } : null
}

/** 会被打印的那些列的可见列宽之和 */
export function totalColumnsWidth(sheet, pageConfig) {
  const range = printedColRange(sheet, pageConfig)
  if (!range) return 0
  let sum = 0
  for (let c = range.c1; c <= range.c2; c += 1) sum += colWidth(sheet, c)
  return sum
}

/** 超出一页宽多少 px；未超出（或未限宽）返回 0 */
export function widthOverflowPx(sheet, pageConfig) {
  const limit = widthLimitPx(pageConfig)
  if (!sheet || limit <= 0) return 0
  return Math.max(totalColumnsWidth(sheet, pageConfig) - limit, 0)
}

/**
 * 把「这次拖宽多出来的、越过一页宽的那部分」还回去。
 *
 * FortuneSheet 没有列宽变更的前置钩子，只能在变更落地后按快照比对再纠正。
 * 规则：
 *   1. 超出量先由被拖动列右侧的列让出 —— 于是纸张右边界不动，符合「表格只有一页宽」；
 *   2. 右侧让无可让时，才把本次的增量本身砍掉（拖不动了）；
 *   3. 表格原本就超宽（比如后来把纸张改小了）时不做整体压缩，只保证「不会更宽」。
 *      真要压回一页宽用 shrinkColumnsToWidth。
 * 因为 (3)，回收量取 total - max(limit, prevTotal)：表格已超宽时它退化为本次增量。
 *
 * @param {Object} sheet 变更后的工作表
 * @param {Object} pageConfig
 * @param {Record<number, number>} prevWidths 变更前的列宽（列号 -> px）
 * @returns {{widths: Record<number, number>, capped: boolean}|null} 无需调整时返回 null
 */
export function clampColumnGrowth(sheet, pageConfig, prevWidths) {
  const limit = widthLimitPx(pageConfig)
  if (!sheet || limit <= 0 || !prevWidths) return null

  const range = printedColRange(sheet, pageConfig)
  if (!range) return null
  const last = range.c2
  const cur = {}
  const grown = []
  let total = 0
  let prevTotal = 0
  for (let c = range.c1; c <= last; c += 1) {
    const w = colWidth(sheet, c)
    cur[c] = w
    total += w
    const p = prevWidths[c]
    prevTotal += p === undefined ? w : p
    if (p !== undefined && w > p) grown.push(c)
  }
  if (!grown.length) return null

  const excess = total - Math.max(limit, prevTotal)
  if (excess <= 0.5) return null

  const out = {}
  // 1) 右侧的列让出空间
  const anchor = grown[grown.length - 1]
  const tail = []
  for (let c = anchor + 1; c <= last; c += 1) {
    if (cur[c] > 0) tail.push(c)
  }
  const remain = shrink(cur, tail, excess, out)
  // 2) 右侧让不出来，把本次增量砍掉；下限是变更前的宽度，所以只会「拖不动」，不会倒缩
  const capped = remain > 0.5
  if (capped) shrink(cur, grown, remain, out, (c) => prevWidths[c])

  const widths = roundWidths(out, cur)
  return widths ? { widths, capped } : null
}

/**
 * 把整表列宽等比压缩到一页宽内（未超宽时不动）。
 * 用于「压缩到一页宽」这一显式动作 —— 例如把纸张从 A3 改成 A4 之后。
 *
 * @returns {Record<number, number>|null} 需要写回的列宽
 */
export function shrinkColumnsToWidth(sheet, pageConfig) {
  const limit = widthLimitPx(pageConfig)
  if (!sheet || limit <= 0) return null

  const range = printedColRange(sheet, pageConfig)
  if (!range) return null
  const cur = {}
  const cols = []
  let total = 0
  for (let c = range.c1; c <= range.c2; c += 1) {
    const w = colWidth(sheet, c)
    cur[c] = w
    total += w
    if (w > 0) cols.push(c)
  }
  if (!cols.length || total - limit <= 0.5) return null

  const out = {}
  shrink(cur, cols, total - limit, out)
  return roundWidths(out, cur)
}

/**
 * 在 cols 上按各列「可让出的空间」等比削减 need px。
 * 结果累加进 out（已经在 out 里的列以 out 的值为准，便于分两轮削减）。
 *
 * @param {(c:number)=>number} [floorOf] 每列的下限，默认 MIN_COL_WIDTH；
 *        本来就比下限还窄的列不参与削减，也不会被「撑宽」
 * @returns 没能削掉的剩余量
 */
function shrink(cur, cols, need, out, floorOf) {
  if (need <= 0 || !cols.length) return Math.max(need, 0)
  const floorAt = (c) => Math.min(numOr(floorOf?.(c), MIN_COL_WIDTH), cur[c])

  let room = 0
  cols.forEach((c) => {
    room += Math.max((out[c] ?? cur[c]) - floorAt(c), 0)
  })
  if (room <= 0) return need

  // 削减量与各自的余量成正比，一轮即可削满：ratio<1 时正好削掉 need，ratio=1 时全部触底
  const ratio = Math.min(need / room, 1)
  let cut = 0
  cols.forEach((c) => {
    const w = out[c] ?? cur[c]
    const d = Math.max(w - floorAt(c), 0) * ratio
    if (d <= 0) return
    out[c] = w - d
    cut += d
  })
  return Math.max(need - cut, 0)
}

/**
 * 列宽取整，并剔除没实际变化的列。
 *
 * 逐列独立取整会让误差累积（20 列最多差 20px，表现为压缩过头、右边留白），
 * 所以把每列的零头进位给下一列，总宽度误差始终在 1px 以内。
 */
function roundWidths(map, cur) {
  const out = {}
  let changed = false
  let carry = 0
  Object.keys(map)
    .map(Number)
    .sort((a, b) => a - b)
    .forEach((c) => {
      const exact = map[c] + carry
      const v = Math.max(Math.round(exact), 1)
      carry = exact - v
      if (v === Math.round(cur[c])) return
      out[c] = v
      changed = true
    })
  return changed ? out : null
}

/* ------------------------- A1 区域 ------------------------- */

/** "A1:F30" -> {r1,c1,r2,c2}；非法返回 null */
export function parseA1Range(text) {
  if (!text) return null
  const m = String(text).trim().toUpperCase().match(/^([A-Z]+)(\d+)(?::([A-Z]+)(\d+))?$/)
  if (!m) return null
  const c1 = letterToCol(m[1])
  const r1 = Number(m[2]) - 1
  const c2 = m[3] ? letterToCol(m[3]) : c1
  const r2 = m[4] ? Number(m[4]) - 1 : r1
  if ([r1, c1, r2, c2].some((n) => !Number.isFinite(n) || n < 0)) return null
  return {
    r1: Math.min(r1, r2),
    c1: Math.min(c1, c2),
    r2: Math.max(r1, r2),
    c2: Math.max(c1, c2)
  }
}

/** {r1,c1,r2,c2} -> "A1:F30" */
export function formatA1Range(range) {
  if (!range) return ''
  const { r1, c1, r2, c2 } = range
  return `${colToLetter(c1)}${r1 + 1}:${colToLetter(c2)}${r2 + 1}`
}

/**
 * 行/列增删之后跟着挪打印区域（`utils/sheet.js#shiftCellConfigs` 的同一件事，换成 A1 串）。
 *
 * 打印区域是**模板坐标**，删掉它上面一行、它就该整体上移一行 —— 不挪的话「设好的 A1:F30」
 * 会连着下移一行的内容一起漂，最后一行被切在纸外面。整块被删光时返回空串（= 整表）。
 *
 * @param area   A1 区域串，如 "A1:F30"；空串/非法原样返回
 * @param change {type,at,count,remove}，见 `utils/sheet.js#normalizeRowColOp`
 */
export function shiftPrintArea(area, change) {
  const parsed = parseA1Range(area)
  if (!parsed || !change) return area || ''
  const [head, tail] = change.type === 'row' ? ['r1', 'r2'] : ['c1', 'c2']
  const next = { ...parsed }
  if (change.remove) {
    const end = change.at + change.count
    // 与被删区间无交集时只需按落在它前后决定挪不挪；有交集则两端各自往回收
    next[head] = parsed[head] >= end ? parsed[head] - change.count : Math.min(parsed[head], change.at)
    next[tail] = parsed[tail] >= end ? parsed[tail] - change.count : Math.min(parsed[tail], change.at - 1)
    if (next[tail] < next[head]) return ''
  } else {
    if (parsed[head] >= change.at) next[head] = parsed[head] + change.count
    // 在区域内部插入：区域跟着变长（与 Excel 一致）
    if (parsed[tail] >= change.at) next[tail] = parsed[tail] + change.count
  }
  return formatA1Range(next)
}

/* ------------------------- 分页计算 ------------------------- */

/**
 * 计算纸张边界（分页线）位置。
 *
 * 扫描范围是整张表（设了打印区域则只扫区域内），所以内容不满一页时也能看到纸张边界。
 * 边界有可能落在最后一列/行之外（网格比一页还窄），此时用 colTail/rowTail 给出
 * 「最后一列右侧再往外多少 px」，由调用方外推绘制。
 *
 * @returns {{
 *   area: {r1,c1,r2,c2}|null,   显式设置的打印区域（未设置为 null）
 *   range: {r1,c1,r2,c2}|null,  参与分页的范围
 *   colBreaks: Set<number>,     这些列的左边界是一条纵向纸张边界
 *   rowBreaks: Set<number>,     这些行的上边界是一条横向纸张边界
 *   lastCol: number, lastRow: number,
 *   colTail: number, rowTail: number,  边界落在网格外时，距最后一列/行的 px（sheet 坐标）
 *   pages: number,              内容（或打印区域）实际占用的页数
 *   signature: string           指纹，用于判断是否需要重绘
 * }}
 */
export function computePageBreaks(sheet, pageConfig) {
  const cfg = normalizePageConfig(pageConfig)
  const empty = {
    area: null,
    range: null,
    colBreaks: new Set(),
    rowBreaks: new Set(),
    lastCol: -1,
    lastRow: -1,
    colTail: 0,
    rowTail: 0,
    pages: 0,
    signature: 'empty'
  }
  if (!sheet) return empty

  const area = parseA1Range(cfg.printArea)
  const range = area || gridRange(sheet)
  if (!range || range.c2 < range.c1 || range.r2 < range.r1) return empty

  const page = printablePageSizePx(cfg)
  // 「压缩到一页宽」时没有纵向分页，纸张右边界也就无从谈起
  const cols = cfg.fitToWidth
    ? { breaks: [], tail: 0 }
    : scan(range.c1, range.c2, (c) => colWidth(sheet, c), page.width)
  const rows = scan(range.r1, range.r2, (r) => rowHeight(sheet, r), page.height)

  // 页数按实际内容（或打印区域）算，整张空表不该报一堆页
  const content = area || usedRange(sheet)
  const pages = content
    ? pageSpan(cols.breaks, content.c1, content.c2) * pageSpan(rows.breaks, content.r1, content.r2)
    : 0

  return {
    area,
    range,
    colBreaks: new Set(cols.breaks),
    rowBreaks: new Set(rows.breaks),
    lastCol: range.c2,
    lastRow: range.r2,
    colTail: cols.tail,
    rowTail: rows.tail,
    pages,
    signature: [
      cfg.printArea,
      formatA1Range(range),
      cols.breaks.join(','),
      rows.breaks.join(','),
      Math.round(cols.tail),
      Math.round(rows.tail)
    ].join('|')
  }
}

/**
 * 让纸张边界能落在网格内所需的最小行列数。
 *
 * FortuneSheet 的网格是有限的（默认 60 行 20 列），纸张比网格还大时，
 * 边界落在网格之外，那里没有单元格可画、也没有可滚动的画布空间 —— 线就看不见。
 * 所以要看到边界，网格必须比一页多出至少一行/一列。
 */
export function requiredGridForPage(sheet, pageConfig) {
  const cfg = normalizePageConfig(pageConfig)
  const page = printablePageSizePx(cfg)
  // 限宽模式下整表本来就在一页宽内，补列只会把它顶出上限，故不补列
  const skipCols = cfg.fitToWidth || cfg.limitWidth
  return {
    rows: countToExceed((r) => rowHeight(sheet, r), page.height),
    cols: skipCols ? 0 : countToExceed((c) => colWidth(sheet, c), page.width)
  }
}

/** 从 0 开始累加，返回「累计超过 limit」时的格数（即首页格数 + 1） */
function countToExceed(sizeOf, limit) {
  let used = 0
  let i = 0
  // 上限兜底：纸张再大也不至于要几千行，避免尺寸异常时死循环
  while (i < 5000) {
    const size = sizeOf(i)
    used += size > 0 ? size : DEFAULT_ROW_HEIGHT
    i++
    if (used > limit) break
  }
  return i
}

/**
 * 从 from 累加到 to，超过 limit 的那一格作为新一页的起点。
 * 单格本身就超过一页宽/高时独占一页，避免死循环。
 * tail = 最后一页剩余的空间，>0 说明纸张边界落在 to 之后。
 */
function scan(from, to, sizeOf, limit) {
  const breaks = []
  let used = 0
  for (let i = from; i <= to; i++) {
    const size = sizeOf(i)
    if (size <= 0) continue
    if (used > 0 && used + size > limit) {
      breaks.push(i)
      used = size
    } else {
      used += size
    }
  }
  return { breaks, tail: Math.max(limit - used, 0) }
}

/** [from,to] 跨了几页 */
function pageSpan(breaks, from, to) {
  let count = 1
  breaks.forEach((b) => {
    if (b > from && b <= to) count++
  })
  return count
}

/* ------------------------- 杂项 ------------------------- */

function hasValue(v) {
  if (v === null || v === undefined) return false
  if (typeof v === 'object') return v.v !== null && v.v !== undefined && v.v !== '' ? true : !!v.m
  return v !== ''
}

function numOr(v, fallback) {
  const n = Number(v)
  return Number.isFinite(n) ? n : fallback
}

function clamp(n, min, max) {
  return Math.min(Math.max(n, min), max)
}
