<!--
  全局参数管理页（CONTRACT §3.5）。

  参数是**系统级**的一份定义，所有报表共用 —— 渲染时并进每张报表的参数里，
  同名时报表自己声明的那条整条覆盖它（CONTRACT §5）。所以这里没有「属于哪张报表」这一维，
  就是一张普通的 CRUD 表；报表专有的查询条件仍在各自的报表内容里，不该往这儿放。
-->
<template>
  <div class="page-container">
    <div class="page-toolbar">
      <el-input
        v-model="searchName"
        placeholder="按参数名 / 显示名搜索"
        clearable
        style="width: 240px"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="handleSearch">搜索</el-button>
      <div class="flex-spacer" />
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新建参数
      </el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
      这里的参数<b>所有报表都能用</b>：单元格与数据集 SQL 里写
      <span class="mono">${参数名}</span> 即可取到，不必在每张报表里各声明一遍。
      渲染时报表自己声明的同名参数优先。
    </el-alert>

    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="paramName" label="参数名" min-width="140">
        <template #default="{ row }"><span class="mono">{{ row.paramName }}</span></template>
      </el-table-column>
      <el-table-column prop="paramText" label="显示名" min-width="120" />
      <el-table-column prop="paramType" label="类型" width="90">
        <template #default="{ row }">{{ labelOf(PARAM_TYPES, row.paramType) }}</template>
      </el-table-column>
      <el-table-column prop="widget" label="控件" width="100">
        <template #default="{ row }">{{ labelOf(PARAM_WIDGETS, row.widget) }}</template>
      </el-table-column>
      <el-table-column prop="defaultValue" label="默认值" min-width="120" show-overflow-tooltip />
      <el-table-column prop="required" label="必填" width="70" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.required === 1" type="danger" size="small">必填</el-tag>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="90">
        <!-- 停用 = 不参与合并，等于这个参数不存在（CONTRACT §5） -->
        <template #default="{ row }">
          <el-switch :model-value="row.status === 1" disabled />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="mz-pagination"
      v-model:current-page="pageNo"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @size-change="loadData"
      @current-change="loadData"
    />

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑参数' : '新建参数'" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="参数名" prop="paramName">
          <el-input v-model="form.paramName" placeholder="字母或下划线开头，仅含字母数字下划线" />
          <div class="text-muted form-tip">单元格与 SQL 里写 <span class="mono">{{ paramToken }}</span></div>
        </el-form-item>
        <el-form-item label="显示名" prop="paramText">
          <el-input v-model="form.paramText" placeholder="参数表单上的标签" />
        </el-form-item>
        <el-form-item label="类型" prop="paramType">
          <el-select v-model="form.paramType" style="width: 100%">
            <el-option v-for="t in PARAM_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="控件" prop="widget">
          <el-select v-model="form.widget" style="width: 100%">
            <el-option v-for="t in PARAM_WIDGETS" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.widget === 'select'" label="选项" prop="optionsText">
          <el-input v-model="form.optionsText" placeholder="值1:标签1,值2:标签2" />
        </el-form-item>
        <el-form-item label="默认值" prop="defaultValue">
          <el-input v-model="form.defaultValue" placeholder="留空表示没有默认值" />
        </el-form-item>
        <el-form-item label="必填" prop="required">
          <el-switch v-model="form.required" :active-value="1" :inactive-value="0" />
          <span class="text-muted" style="margin-left: 8px">缺值时渲染直接报错</span>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
          <span class="text-muted" style="margin-left: 8px">停用后所有报表都取不到它</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { pageParam, createParam, updateParam, deleteParam } from '@/api/param'
import { PARAM_TYPES, PARAM_WIDGETS } from '@/utils/sheet'

const loading = ref(false)
const saving = ref(false)
const tableData = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchName = ref('')

const dialogVisible = ref(false)
const formRef = ref(null)

const emptyForm = () => ({
  id: '',
  paramName: '',
  paramText: '',
  paramType: 'string',
  widget: 'input',
  defaultValue: '',
  required: 0,
  // 表里存的是 JSON 数组文本，界面上按 "值:标签,值:标签" 编辑（与设计器那份参数表同一种写法）
  optionsText: '',
  remark: '',
  status: 1
})

const form = reactive(emptyForm())

const rules = {
  paramName: [
    { required: true, message: '请输入参数名', trigger: 'blur' },
    { pattern: /^[a-zA-Z_][a-zA-Z0-9_]*$/, message: '需以字母或下划线开头，仅含字母数字下划线', trigger: 'blur' }
  ]
}

/** 拼在模板里会和 Vue 的插值语法打架，放到这里拼 */
const paramToken = computed(() => '${' + (form.paramName || '参数名') + '}')

function labelOf(list, value) {
  return list.find((t) => t.value === value)?.label || value || '-'
}

/** JSON 数组文本 -> "值:标签,值:标签"；存坏了就当没有选项，别把整页卡住 */
function optionsToText(json) {
  if (!json) return ''
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr.map((o) => `${o.value}:${o.label}`).join(',') : ''
  } catch (e) {
    return ''
  }
}

/** "值:标签,值:标签" -> JSON 数组文本；空串存 null，免得库里堆一片 "[]" */
function textToOptions(text) {
  const arr = (text || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const [value, label] = s.split(':')
      return { value: (value || '').trim(), label: (label || value || '').trim() }
    })
  return arr.length ? JSON.stringify(arr) : null
}

async function loadData() {
  loading.value = true
  try {
    const res = await pageParam({ pageNo: pageNo.value, pageSize: pageSize.value, name: searchName.value })
    tableData.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    // 已由拦截器提示
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pageNo.value = 1
  loadData()
}

function openCreate() {
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

function openEdit(row) {
  Object.assign(form, emptyForm(), row, { optionsText: optionsToText(row.options) })
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  const { optionsText, ...rest } = form
  const payload = { ...rest, options: form.widget === 'select' ? textToOptions(optionsText) : null }
  saving.value = true
  try {
    if (form.id) {
      await updateParam(payload)
      ElMessage.success('保存成功')
    } else {
      await createParam(payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
    // 已由拦截器提示
  } finally {
    saving.value = false
  }
}

function handleDelete(row) {
  ElMessageBox.confirm(
    `确定删除参数「${row.paramName}」吗？引用了它的报表将取不到这个参数的值。`,
    '提示',
    { type: 'warning' }
  )
    .then(async () => {
      try {
        await deleteParam(row.id)
        ElMessage.success('删除成功')
        loadData()
      } catch (e) {
        // 已由拦截器提示
      }
    })
    .catch(() => {})
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.mz-pagination {
  margin-top: 12px;
  justify-content: flex-end;
  display: flex;
}

.form-tip {
  margin-top: 4px;
  font-size: 12px;
}
</style>
