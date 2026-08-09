<!--
  纸张边界叠加层：浮在工作簿画布之上，用 DOM 画完整的分页线与打印区域框。

  为什么不直接画在 canvas 上：逐格绘制时线由每个单元格的一小段拼成，
  一旦某些格没渲染（合并格、超出网格）线就断；而且线会被后续绘制覆盖。
  叠加层是独立元素，永远完整、永远在表格之上，拖动/编辑表格都不影响它。

  坐标不自己算：行高列宽、滚动位置、缩放三者叠加后极易差几个像素，
  改由渲染钩子把「这一列/行在画布上的实际起止像素」采样出来（见设计器 sampleCellGeometry），
  这里只做换算与绘制，因此位置与 FortuneSheet 自身的绘制完全一致。
-->
<template>
  <div class="print-overlay" :style="overlayStyle">
    <div v-for="(line, i) in lines" :key="i" :class="line.cls" :style="line.style">
      <span v-if="line.label" class="print-overlay-label">{{ line.label }}</span>
    </div>
    <div v-if="areaBox" class="print-overlay-area" :style="areaBox"></div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** FortuneSheet 画布相对其容器的位置尺寸 */
  rect: { type: Object, default: () => ({ left: 0, top: 0, width: 0, height: 0 }) },
  /** computePageBreaks 的结果 */
  breaks: { type: Object, default: null },
  /** 渲染钩子采样出的画布坐标：{ cols: {idx:[x1,x2]}, rows: {idx:[y1,y2]}, zoomX, zoomY } */
  geometry: { type: Object, default: null },
  /** 纸张说明，画在第一条纵向线旁边 */
  paperLabel: { type: String, default: '' }
})

/** 行号列宽 / 列头高度，取自 @fortune-sheet/core 的默认设置（本项目未覆盖） */
const ROW_HEADER_W = 46
const COL_HEADER_H = 20

/** 叠加层盖住网格区域（不盖行号列头，否则线会压在表头上） */
const overlayStyle = computed(() => ({
  left: `${props.rect.left + ROW_HEADER_W}px`,
  top: `${props.rect.top + COL_HEADER_H}px`,
  width: `${Math.max(props.rect.width - ROW_HEADER_W, 0)}px`,
  height: `${Math.max(props.rect.height - COL_HEADER_H, 0)}px`
}))

const lines = computed(() => {
  const pb = props.breaks
  const geo = props.geometry
  const out = []
  if (!pb || !geo) return out

  const toX = (x) => x - ROW_HEADER_W
  const toY = (y) => y - COL_HEADER_H

  // 纵向：新一页起始列的左边界
  let firstVertical = true
  pb.colBreaks.forEach((c) => {
    const seg = geo.cols?.[c]
    if (!seg) return
    out.push({
      cls: 'print-overlay-v',
      style: { left: `${toX(seg[0])}px` },
      label: firstVertical ? props.paperLabel : ''
    })
    firstVertical = false
  })

  // 纵向：纸张比网格还宽时，边界落在最后一列之外，按剩余宽度外推
  if (pb.colTail > 0) {
    const seg = geo.cols?.[pb.lastCol]
    if (seg) {
      out.push({
        cls: 'print-overlay-v',
        style: { left: `${toX(seg[1] + pb.colTail * (geo.zoomX || 1))}px` },
        label: firstVertical ? props.paperLabel : ''
      })
    }
  }

  pb.rowBreaks.forEach((r) => {
    const seg = geo.rows?.[r]
    if (seg) out.push({ cls: 'print-overlay-h', style: { top: `${toY(seg[0])}px` } })
  })

  if (pb.rowTail > 0) {
    const seg = geo.rows?.[pb.lastRow]
    if (seg) {
      out.push({
        cls: 'print-overlay-h',
        style: { top: `${toY(seg[1] + pb.rowTail * (geo.zoomY || 1))}px` }
      })
    }
  }

  return out
})

/** 显式设置了打印区域时，用实线框标出来 */
const areaBox = computed(() => {
  const pb = props.breaks
  const geo = props.geometry
  if (!pb?.area || !geo) return null
  const left = geo.cols?.[pb.area.c1]
  const right = geo.cols?.[pb.area.c2]
  const top = geo.rows?.[pb.area.r1]
  const bottom = geo.rows?.[pb.area.r2]
  if (!left || !right || !top || !bottom) return null
  return {
    left: `${left[0] - ROW_HEADER_W}px`,
    top: `${top[0] - COL_HEADER_H}px`,
    width: `${right[1] - left[0]}px`,
    height: `${bottom[1] - top[0]}px`
  }
})
</script>

<style scoped>
.print-overlay {
  position: absolute;
  overflow: hidden;
  pointer-events: none;
  z-index: 2;
}

.print-overlay-v {
  position: absolute;
  top: 0;
  bottom: 0;
  border-left: 1px dashed var(--mz-primary, #409eff);
}

.print-overlay-h {
  position: absolute;
  left: 0;
  right: 0;
  border-top: 1px dashed var(--mz-primary, #409eff);
}

.print-overlay-area {
  position: absolute;
  border: 2px solid #f56c6c;
}

/* 标签放在线的左侧（纸内那一侧）：线常常紧贴画布右缘，放右边会被容器裁掉 */
.print-overlay-label {
  position: absolute;
  top: 2px;
  right: 4px;
  padding: 0 4px;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
  color: #fff;
  background: var(--mz-primary, #409eff);
  border-radius: 2px;
  opacity: 0.85;
}
</style>
