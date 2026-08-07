<!--
  报表参数配置弹窗：编辑 store.content.params 的副本，点击确定才写回 store。
-->
<template>
  <el-dialog v-model="visible" title="参数配置" width="900px" :close-on-click-modal="false" destroy-on-close>
    <div class="text-muted" style="margin-bottom: 12px">
      参数可在单元格中用 <span class="mono">${参数名}</span> 引用，也会自动按同名传给数据集参数。
    </div>

    <el-table :data="rows" border size="small" max-height="420">
      <el-table-column label="参数名" width="150">
        <template #default="{ row }">
          <el-input v-model="row.name" placeholder="paramName" />
        </template>
      </el-table-column>
      <el-table-column label="显示名" width="130">
        <template #default="{ row }">
          <el-input v-model="row.text" placeholder="显示名" />
        </template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-select v-model="row.type" style="width: 100%">
            <el-option v-for="t in PARAM_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="控件" width="120">
        <template #default="{ row }">
          <el-select v-model="row.widget" style="width: 100%">
            <el-option v-for="t in PARAM_WIDGETS" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="默认值" width="130">
        <template #default="{ row }">
          <el-input v-model="row.defaultValue" placeholder="默认值" />
        </template>
      </el-table-column>
      <el-table-column label="必填" width="70" align="center">
        <template #default="{ row }">
          <el-switch v-model="row.required" />
        </template>
      </el-table-column>
      <el-table-column label="选项（下拉专用）" min-width="180">
        <template #default="{ row }">
          <el-input
            v-if="row.widget === 'select'"
            v-model="row.optionsText"
            placeholder="值1:标签1,值2:标签2"
            @change="syncOptions(row)"
          />
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70" align="center">
        <template #default="{ $index }">
          <el-button link type="danger" :icon="Delete" @click="removeRow($index)" />
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 12px">
      <el-button type="primary" plain :icon="Plus" @click="addRow">新增参数</el-button>
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
import { Plus, Delete } from '@element-plus/icons-vue'
import { PARAM_TYPES, PARAM_WIDGETS, deepClone } from '@/utils/sheet'
import { useDesignerStore } from '@/stores/designer'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const store = useDesignerStore()
const rows = ref([])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

/** 打开时，从 store 拷贝一份副本用于编辑，避免中途关闭污染原数据 */
watch(visible, (v) => {
  if (v) {
    rows.value = deepClone(store.content.params || []).map((p) => ({
      ...p,
      required: !!p.required,
      optionsText: (p.options || []).map((o) => `${o.value}:${o.label}`).join(',')
    }))
  }
})

function addRow() {
  rows.value.push({
    name: '',
    text: '',
    type: 'string',
    widget: 'input',
    defaultValue: '',
    required: false,
    options: [],
    optionsText: ''
  })
}

function removeRow(index) {
  rows.value.splice(index, 1)
}

/** 解析 "值1:标签1,值2:标签2" -> [{value,label}] */
function syncOptions(row) {
  row.options = (row.optionsText || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const [value, label] = s.split(':')
      return { value: (value || '').trim(), label: (label || value || '').trim() }
    })
}

function onConfirm() {
  const names = new Set()
  for (const row of rows.value) {
    if (!row.name || !/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(row.name)) {
      ElMessage.warning(`参数名「${row.name}」不合法，须以字母或下划线开头，仅含字母数字下划线`)
      return
    }
    if (names.has(row.name)) {
      ElMessage.warning(`参数名「${row.name}」重复`)
      return
    }
    names.add(row.name)
    if (row.widget === 'select') syncOptions(row)
  }
  const cleaned = rows.value.map(({ optionsText, ...rest }) => rest)
  store.setParams(cleaned)
  visible.value = false
  ElMessage.success('参数已更新')
}
</script>

<style scoped></style>
