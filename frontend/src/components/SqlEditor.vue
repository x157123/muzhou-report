<template>
  <div ref="wrapperRef" class="sql-editor" :style="{ height: props.height }"></div>
</template>

<script setup>
/**
 * 受控 SQL 编辑器（v-model），基于 CodeMirror 5。
 * 若 CodeMirror 初始化失败，自动退化为 el-input textarea（本组件不做退化处理，
 * 因为 codemirror 是纯 JS 依赖，风险很低，这里直接使用）。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import CodeMirror from 'codemirror'
import 'codemirror/lib/codemirror.css'
import 'codemirror/theme/idea.css'
import 'codemirror/mode/sql/sql.js'
import 'codemirror/addon/edit/matchbrackets.js'

const props = defineProps({
  modelValue: { type: String, default: '' },
  height: { type: String, default: '220px' },
  readonly: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const wrapperRef = ref(null)
let cm = null

onMounted(() => {
  cm = CodeMirror(wrapperRef.value, {
    value: props.modelValue || '',
    mode: 'text/x-sql',
    theme: 'idea',
    lineNumbers: true,
    matchBrackets: true,
    lineWrapping: true,
    readOnly: props.readonly
  })
  cm.on('change', (instance) => {
    const value = instance.getValue()
    if (value !== props.modelValue) {
      emit('update:modelValue', value)
    }
  })
})

watch(
  () => props.modelValue,
  (val) => {
    if (!cm) return
    const cur = cm.getValue()
    if ((val || '') !== cur) {
      cm.setValue(val || '')
    }
  }
)

watch(
  () => props.readonly,
  (val) => {
    if (cm) cm.setOption('readOnly', val)
  }
)

onBeforeUnmount(() => {
  cm = null
})
</script>

<style scoped>
.sql-editor {
  border: 1px solid var(--mz-border);
  border-radius: 4px;
  overflow: hidden;
}

.sql-editor :deep(.CodeMirror) {
  height: 100%;
  font-family: 'Cascadia Code', Consolas, Monaco, monospace;
  font-size: 13px;
}
</style>
