<!--
  数据集字段列表：可拖到工作簿单元格上落格（内部/公共两组数据集共用这一份）。

  用原生 HTML5 拖放，**不要再套 vuedraggable / Sortable**：Sortable 在 dragstart 里会执行
  `dataTransfer.setData('Text', dragEl.textContent)`（默认的 setData 选项），而它的监听挂在列表
  根节点上，冒泡到那儿时晚于挂在条目上的我们，后写的赢 —— text/plain 于是变成「订单号string」
  这样的可见文字，落格那头 `JSON.parse` 直接抛错，拖过去什么都没有（占位符、表头名称全没了）。
  这份列表本来也不排序、不接收外部条目（`sort:false` + `put:false`），Sortable 只剩下副作用。

  拖拽负载放在**自定义 MIME** 里（`text/plain` 只作兜底），谁再往 text/plain 上写都伤不到它。
-->
<template>
  <div class="field-list">
    <el-tooltip
      v-for="field in dataset.fields || []"
      :key="field.fieldName"
      :content="`#{${dataset.code}.${field.fieldName}}`"
      placement="right"
    >
      <div
        class="field-item"
        draggable="true"
        @dragstart="onDragStart($event, field)"
        @dragend="onDragEnd"
      >
        <el-icon><Document /></el-icon>
        <span>{{ field.fieldText || field.fieldName }}</span>
        <span class="field-type">{{ field.fieldType }}</span>
      </div>
    </el-tooltip>
  </div>
</template>

<script setup>
import { Document } from '@element-plus/icons-vue'
import { FIELD_DRAG_MIME } from '@/utils/sheet'

const props = defineProps({
  /** 归一化后的数据集 { code, fields:[{fieldName,fieldText,fieldType}] } */
  dataset: { type: Object, required: true }
})

function onDragStart(e, field) {
  const payload = JSON.stringify({
    datasetCode: props.dataset.code,
    field: field.fieldName,
    // 落格时用它在上一格写表头，所以这里必须把「名称」一起带上
    fieldText: field.fieldText || field.fieldName,
    fieldType: field.fieldType
  })
  e.dataTransfer.setData(FIELD_DRAG_MIME, payload)
  e.dataTransfer.setData('text/plain', payload)
  e.dataTransfer.effectAllowed = 'copy'
  e.currentTarget.classList.add('dragging')
}

function onDragEnd(e) {
  e.currentTarget.classList.remove('dragging')
}
</script>
