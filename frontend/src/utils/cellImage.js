/**
 * 图片单元格的显示。
 *
 * 后端把图片地址放在单元格的 `v.mzImg.src` 上（见 CONTRACT §4 与 `GridCell#image`），
 * 但 **FortuneSheet 的单元格画不了图片** —— 它只有「浮动图片」这一种形态
 * （`sheet.images`，一组绝对定位的 `<img>`，坐标是内容坐标系里的像素）。
 * 所以这里把「哪一格有图」翻译成「图片摆在哪个像素位置、多大」：
 * 左上角 = 该格左上角（行高列宽累加），框 = 该格（合并区则是整块）。
 *
 * 注意这里给的是**框**，不是图片的最终大小：FortuneSheet 会把 `<img>` 的宽高写死成这个框，
 * 照单渲染就是拉伸变形。图片在框里等比例居中靠的是 `styles/index.css` 里那条
 * `.luckysheet-modal-dialog-image img { object-fit: contain }`，与导出侧的
 * `export/ImageFit#contain` 是同一条规则 —— 改一处就要改另一处。
 *
 * 要放在**行高定下来之后**再调（预览页是 `applyWrapRowHeights` 之后）：
 * 自动换行撑高过的行会让下面所有图片整体下移。
 */
import { colWidth, mergeSpanAt, rowHeight } from './print'

/** 从单元格值里取图片地址；不是图片单元格返回空 */
function srcOf(v) {
  if (!v || typeof v !== 'object') return ''
  const src = v.mzImg?.src
  return typeof src === 'string' ? src.trim() : ''
}

/** 遍历 sheet 里的图片单元格（celldata 与运行时二维 data 两种形态都支持） */
function forEachImageCell(sheet, fn) {
  if (Array.isArray(sheet?.celldata)) {
    sheet.celldata.forEach((cell) => {
      if (!cell) return
      const src = srcOf(cell.v)
      if (src) fn(cell.r, cell.c, src)
    })
    return
  }
  if (Array.isArray(sheet?.data)) {
    sheet.data.forEach((row, r) => {
      if (!Array.isArray(row)) return
      row.forEach((v, c) => {
        const src = srcOf(v)
        if (src) fn(r, c, src)
      })
    })
  }
}

/**
 * 算出该 sheet 的浮动图片列表（FortuneSheet 的 `sheet.images`）。
 *
 * @returns {Array} `[{id,src,left,top,width,height}]`，没有图片返回空数组
 */
export function computeSheetImages(sheet) {
  const images = []
  if (!sheet) return images

  // 左上角坐标要按行高列宽累加，逐格重算是 O(n²)，先把前缀和摊出来
  const left = []
  const top = []
  forEachImageCell(sheet, (r, c, src) => {
    const span = mergeSpanAt(sheet, r, c)
    // 合并区里只有锚点那一格出图（其余格子是被盖住的空格）
    if (span.r !== r || span.c !== c) return

    while (left.length <= span.c) {
      left.push(left.length === 0 ? 0 : left[left.length - 1] + colWidth(sheet, left.length - 1))
    }
    while (top.length <= span.r) {
      top.push(top.length === 0 ? 0 : top[top.length - 1] + rowHeight(sheet, top.length - 1))
    }

    let width = 0
    for (let i = span.c; i < span.c + span.cs; i += 1) width += colWidth(sheet, i)
    let height = 0
    for (let i = span.r; i < span.r + span.rs; i += 1) height += rowHeight(sheet, i)
    if (width <= 0 || height <= 0) return

    images.push({
      // id 要稳定：FortuneSheet 用它做 key，重新渲染时同一格还是同一张图
      id: `mzimg_${span.r}_${span.c}`,
      src,
      left: left[span.c],
      top: top[span.r],
      width,
      height
    })
  })
  return images
}

/**
 * 把渲染结果里的图片单元格摊成 FortuneSheet 的浮动图片（原地改 sheet.images）。
 *
 * 报表自己没有别的浮动图片，所以直接整份覆盖 —— 重复渲染时不会越积越多。
 */
export function applyCellImages(sheets) {
  ;(sheets || []).forEach((sheet) => {
    const images = computeSheetImages(sheet)
    if (images.length || Array.isArray(sheet.images)) sheet.images = images
  })
  return sheets
}
