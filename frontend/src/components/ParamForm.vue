<!--
  报表参数表单：根据参数定义（widget）动态渲染表单项。
  设计器预览抽屉、预览页均复用此组件。
-->
<template>
  <el-form :model="form" inline label-position="left" class="param-form" @submit.prevent>
    <el-form-item
      v-for="p in params"
      :key="p.name"
      :label="p.text || p.name"
      :required="!!p.required"
      class="param-form-item"
    >
      <!-- 输入框 -->
      <el-input
        v-if="p.widget === 'input'"
        v-model="form[p.name]"
        :placeholder="`请输入${p.text || p.name}`"
        clearable
        style="width: 180px"
        @change="emitChange"
      />
      <!-- 数字框 -->
      <el-input-number
        v-else-if="p.widget === 'number'"
        v-model="form[p.name]"
        :placeholder="`请输入${p.text || p.name}`"
        style="width: 180px"
        controls-position="right"
        @change="emitChange"
      />
      <!-- 日期 -->
      <el-date-picker
        v-else-if="p.widget === 'date'"
        v-model="form[p.name]"
        type="date"
        value-format="YYYY-MM-DD"
        :placeholder="`请选择${p.text || p.name}`"
        style="width: 180px"
        @change="emitChange"
      />
      <!-- 日期范围 -->
      <el-date-picker
        v-else-if="p.widget === 'daterange'"
        v-model="form[p.name]"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        style="width: 240px"
        @change="onDateRangeChange(p.name, $event)"
      />
      <!-- 下拉选择 -->
      <el-select
        v-else-if="p.widget === 'select'"
        v-model="form[p.name]"
        :placeholder="`请选择${p.text || p.name}`"
        clearable
        filterable
        style="width: 180px"
        @change="emitChange"
      >
        <el-option v-for="opt in p.options || []" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <!-- 兜底：未知 widget 按输入框处理 -->
      <el-input v-else v-model="form[p.name]" style="width: 180px" @change="emitChange" />
    </el-form-item>
    <el-form-item class="param-form-actions">
      <slot name="actions"></slot>
    </el-form-item>
  </el-form>
</template>

<script setup>
import { reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  /** [{name,text,type,widget,defaultValue,required,options}] */
  params: { type: Array, default: () => [] },
  modelValue: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['update:modelValue'])

/** 表单内部状态，初始化时用 defaultValue 填充 */
const form = reactive({})

function initForm() {
  Object.keys(form).forEach((k) => delete form[k])
  props.params.forEach((p) => {
    const existing = props.modelValue ? props.modelValue[p.name] : undefined
    if (existing !== undefined) {
      form[p.name] = existing
    } else if (p.widget === 'daterange') {
      form[p.name] = p.defaultValue ? String(p.defaultValue).split(',') : []
    } else {
      form[p.name] = p.defaultValue ?? ''
    }
  })
}

initForm()
watch(() => props.params, initForm)

function emitChange() {
  emit('update:modelValue', { ...form })
}

function onDateRangeChange(name, val) {
  const result = { ...form, [name]: val }
  if (Array.isArray(val) && val.length === 2) {
    result[`${name}_start`] = val[0]
    result[`${name}_end`] = val[1]
  } else {
    result[`${name}_start`] = ''
    result[`${name}_end`] = ''
  }
  Object.assign(form, result)
  emit('update:modelValue', { ...form })
}

/** 校验必填项，供父组件调用 */
function validate() {
  for (const p of props.params) {
    if (!p.required) continue
    const v = form[p.name]
    const empty = v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)
    if (empty) {
      ElMessage.warning(`请填写参数「${p.text || p.name}」`)
      return false
    }
  }
  return true
}

defineExpose({ validate, form })
</script>

<style scoped>
.param-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}
.param-form-item {
  margin-bottom: 8px;
  margin-right: 12px;
}
.param-form-actions {
  margin-bottom: 8px;
}
</style>
