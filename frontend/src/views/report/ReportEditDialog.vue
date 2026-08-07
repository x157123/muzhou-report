<template>
  <el-dialog
    :model-value="modelValue"
    :title="form.id ? '编辑报表信息' : '新建报表'"
    width="520px"
    destroy-on-close
    @update:model-value="(val) => emit('update:modelValue', val)"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" placeholder="请输入报表名称" />
      </el-form-item>
      <el-form-item label="编码" prop="code">
        <el-input v-model="form.code" placeholder="英文字母开头，字母数字下划线" :disabled="!!form.id" />
      </el-form-item>
      <el-form-item label="备注" prop="remark">
        <el-input v-model="form.remark" type="textarea" :rows="3" placeholder="备注" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
// 报表基础信息新建/编辑弹窗；新建成功后直接跳转设计器
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getReport, createReport, updateReport } from '@/api/report'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  reportId: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const router = useRouter()
const formRef = ref(null)
const saving = ref(false)

const emptyForm = () => ({
  id: '',
  name: '',
  code: '',
  remark: '',
  status: 1,
  type: 'sheet',
  content: ''
})

const form = reactive(emptyForm())

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入编码', trigger: 'blur' },
    { pattern: /^[a-zA-Z][a-zA-Z0-9_]*$/, message: '需以字母开头，仅含字母数字下划线', trigger: 'blur' }
  ]
}

async function openDialog() {
  if (props.reportId) {
    try {
      const detail = await getReport(props.reportId)
      Object.assign(form, emptyForm(), detail || {})
    } catch (e) {
      // 已由拦截器提示
    }
  } else {
    Object.assign(form, emptyForm())
  }
}

watch(
  () => props.modelValue,
  (val) => {
    if (val) openDialog()
  }
)

async function handleSave() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await updateReport({ ...form })
      ElMessage.success('保存成功')
      emit('saved')
      emit('update:modelValue', false)
    } else {
      const newId = await createReport({
        name: form.name,
        code: form.code,
        remark: form.remark,
        status: 1,
        type: 'sheet',
        content: ''
      })
      ElMessage.success('创建成功')
      emit('saved')
      emit('update:modelValue', false)
      router.push('/designer/' + newId)
    }
  } catch (e) {
    // 已由拦截器提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped></style>
