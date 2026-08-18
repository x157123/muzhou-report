/**
 * 自动换行的行高计算。
 *
 * FortuneSheet **只在改字号(fs)时重算行高**，改「自动换行」(tb) 时不算
 * （见 @fortune-sheet/core 的 `updateFormatCell`：只有 `attr === "fs"` 那个分支写 rowlen）。
 * 所以在工具栏点了自动换行，格子里的文字虽然折行了，行却不会变高，多出来的行被压在下边界外面。
 *
 * 而且这不只是画布上的观感问题：行高最终以 `config.rowlen` 落进 content，
 * 导出的 xlsx 照着它写死行高（customHeight），**Excel 打开文件时不会重算行高**，
 * PDF / Word 又是照 xlsx 的行高分页的 —— 一路切到底。所以行高必须在这里补上。
 *
 * 量文字用画布的 `measureText`，字体串与 FortuneSheet 的 `getFontSet` 保持一致，
 * 不自己按「字数 × 字宽」推算。
 */
import { cellSizePx, colWidth, rowHeight, usedRange } from './print'
import { inlineStringText } from './sheet'
import { DEFAULT_FONT } from './fontList'

/** FortuneSheet 的默认字号（pt），见 core 的 defaultSettings */
const DEFAULT_FONT_SIZE = 10

/** 单元格左右 / 上下内边距合计（px），与 FortuneSheet 画文字时留的 space_width/height 一致 */
const PADDING_X = 4
const PADDING_Y = 3

/**
 * 行距系数。与后端 `ExcelExporter#LINE_HEIGHT` 是同一个值 ——
 * 两边算出同一个行高，画布上的分页位置才和导出的文件对得上。
 *
 * <p>1.35 照的是 Excel 的自动行高（11pt 一行占 15pt），比 measureText 量出来的字面高度略松：
 * 中文字体的 ascent/descent 比西文大，卡着字面算，Excel 里最后一行会被削掉。
 */
const LINE_HEIGHT = 1.35

/**
 * FortuneSheet 默认字体族，见 core 的 getFontSet。
 * 它是「fontarray[0] + 这一串兜底」，第一项由 utils/fontList.js 决定（当前是宋体），
 * 这里必须跟着，否则量出来的宽度与画布上实际画的不是同一款字体。
 */
const FONT_FALLBACK =
  '"Helvetica Neue", Helvetica, Arial, "PingFang SC", "Hiragino Sans GB", "Heiti SC", "Microsoft YaHei", "WenQuanYi Micro Hei", sans-serif'
const FONT_FAMILY = `"${DEFAULT_FONT}", ${FONT_FALLBACK}`

let measureCtx = null

/** 复用一个离屏画布量文字，别每次都新建 */
function ctx2d() {
  if (measureCtx) return measureCtx
  if (typeof document === 'undefined') return null
  measureCtx = document.createElement('canvas').getContext('2d')
  return measureCtx
}

/** 单元格的画布字体串，与 FortuneSheet 的 getFontSet 对齐 */
function fontOf(cell) {
  const italic = cell.it && Number(cell.it) !== 0 ? 'italic' : 'normal'
  const bold = cell.bl && Number(cell.bl) !== 0 ? 'bold' : 'normal'
  const size = Number(cell.fs) > 0 ? Math.ceil(Number(cell.fs)) : DEFAULT_FONT_SIZE
  // ff 是数字时指的是 FortuneSheet 内置字体表的下标，拿不到表就按默认字体量，
  // 差异只影响个别字体的宽度，不值得为它把内置字体表抄一份过来
  const family = cell.ff && !/^\d+$/.test(String(cell.ff)) ? `"${cell.ff}", ${FONT_FALLBACK}` : FONT_FAMILY
  return { font: `${italic} normal ${bold} ${size}pt ${family}`, size }
}

/**
 * 一段文字在给定宽度下折成几行。
 * 中文可以在任意字之间断开，西文优先在空格处断，与浏览器的表现一致。
 */
function countLines(text, measure, maxWidth) {
  let lines = 0
  for (const paragraph of String(text).split(/\r\n|\r|\n/)) {
    if (!paragraph) {
      lines += 1
      continue
    }
    if (measure(paragraph) <= maxWidth) {
      lines += 1
      continue
    }
    let cur = ''
    let lastSpace = -1
    for (const ch of paragraph) {
      cur += ch
      if (ch === ' ') lastSpace = cur.length - 1
      if (measure(cur) > maxWidth && cur.length > 1) {
        const cut = lastSpace > 0 ? lastSpace : cur.length - 1
        lines += 1
        cur = lastSpace > 0 ? cur.slice(cut + 1) : cur.slice(cut)
        lastSpace = -1
      }
    }
    lines += 1
  }
  return Math.max(lines, 1)
}

/**
 * 取单元格的读取器。
 * 工作簿实例给的是运行时二维 `data`，后端渲染结果给的是 `celldata`，两种形态都要能读；
 * celldata 先摊成 Map，免得每格都去线性查一遍。
 */
function cellReader(sheet) {
  if (Array.isArray(sheet.data)) {
    return (r, c) => sheet.data[r]?.[c]
  }
  const map = new Map()
  if (Array.isArray(sheet.celldata)) {
    sheet.celldata.forEach((cell) => {
      if (cell && cell.v) map.set(`${cell.r}_${cell.c}`, cell.v)
    })
  }
  return (r, c) => map.get(`${r}_${c}`)
}

/** 单元格文字（富文本格的文字只在 ct.s 里，见 `sheet.js#inlineStringText`） */
function textOf(cell) {
  if (cell == null) return ''
  if (typeof cell === 'string' || typeof cell === 'number') return String(cell)
  return inlineStringText(cell) ?? String(cell.m ?? cell.v ?? '')
}

/**
 * 算出该 sheet 里因自动换行需要长高的行。
 *
 * 只增不减：用户手动拖过的行高不会被改小，文字变短了也不回缩（与 Excel 的自动行高一致）。
 * 跨行合并的格子跳过 —— 高度该摊给哪一行没有确定答案，Excel 自己也不给合并区做自动行高。
 *
 * @returns {Object} `{ 行号: 高度px }`，没有要改的返回空对象
 */
export function computeWrapRowHeights(sheet) {
  const measureContext = ctx2d()
  if (!sheet || !measureContext) return {}
  const used = usedRange(sheet)
  if (!used) return {}
  const cellAt = cellReader(sheet)

  const heights = {}
  for (let r = used.r1; r <= used.r2; r += 1) {
    let need = 0
    for (let c = used.c1; c <= used.c2; c += 1) {
      const cell = cellAt(r, c)
      if (!cell || typeof cell !== 'object' || String(cell.tb) !== '2') continue
      const text = textOf(cell)
      if (!text) continue

      const size = cellSizePx(sheet, r, c)
      if (!size || size.rowSpan > 1) continue
      const usable = (size.width || colWidth(sheet, c)) - PADDING_X
      if (usable <= 0) continue

      const { font, size: fontSize } = fontOf(cell)
      measureContext.font = font
      const lines = countLines(text, (s) => measureContext.measureText(s).width, usable)
      // 只折了一行的格子按原样：它本来就放得下，撑高只会平白多出一截空白
      if (lines <= 1) continue
      const fontPx = (fontSize * 96) / 72
      need = Math.max(need, Math.ceil(lines * fontPx * LINE_HEIGHT + PADDING_Y))
    }
    if (need > rowHeight(sheet, r) + 1) heights[r] = need
  }
  return heights
}

/**
 * 把算出来的行高写回 `config.rowlen`（原地改）。
 *
 * <p>给「拿到现成的 Sheet[] 再挂载」的场景用（预览页）：渲染结果里的行高是**模板**的行高，
 * 扩展出来的数据行文字更长，不补一刀就会被切 —— 而导出的 Excel 是后端按真实文字算过的，
 * 不补的话「预览里被切、下载下来的却是对的」。
 */
export function applyWrapRowHeights(sheets) {
  ;(sheets || []).forEach((sheet) => {
    const heights = computeWrapRowHeights(sheet)
    if (!Object.keys(heights).length) return
    if (!sheet.config) sheet.config = {}
    sheet.config.rowlen = { ...(sheet.config.rowlen || {}), ...heights }
  })
  return sheets
}
