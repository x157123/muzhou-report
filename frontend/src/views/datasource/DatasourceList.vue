<template>
  <div class="page-container">
    <div class="page-toolbar">
      <el-input
        v-model="searchName"
        placeholder="按名称搜索"
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
        新建数据源
      </el-button>
    </div>

    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="code" label="编码" min-width="120">
        <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column prop="dbType" label="类型" width="110">
        <template #default="{ row }">
          <el-tag size="small">{{ row.dbType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="url" label="URL" min-width="240" show-overflow-tooltip />
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-switch :model-value="row.status === 1" disabled />
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="160" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="handleTest(row)">测试连接</el-button>
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

    <!-- 新建/编辑 弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑数据源' : '新建数据源'" width="600px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入显示名称" />
        </el-form-item>
        <el-form-item label="编码" prop="code">
          <el-input v-model="form.code" placeholder="英文字母开头，字母数字下划线" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="数据库类型" prop="dbType">
          <el-select v-model="form.dbType" placeholder="请选择" style="width: 100%" @change="handleDbTypeChange">
            <el-option v-for="(tpl, key) in DB_TEMPLATES" :key="key" :label="tpl.label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="驱动类" prop="driverClassName">
          <el-input v-model="form.driverClassName" placeholder="driverClassName" />
        </el-form-item>
        <el-form-item label="URL" prop="url">
          <el-input v-model="form.url" type="textarea" :rows="2" placeholder="JDBC URL" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <!-- 后端不再回传口令（MzDatasource#password 是 WRITE_ONLY），所以编辑时这里恒为空，
               留空提交 = 不修改（DatasourceServiceImpl#keepOldPassword） -->
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="form.id ? '留空表示不修改' : '密码'"
          />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="备注" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleTest(form)" :loading="testing">测试连接</el-button>
        <div class="flex-spacer" />
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 数据源管理列表页
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageDatasource,
  createDatasource,
  updateDatasource,
  deleteDatasource,
  testDatasource,
  DB_TEMPLATES
} from '@/api/datasource'

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const tableData = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchName = ref('')

const dialogVisible = ref(false)
const formRef = ref(null)

const emptyForm = () => ({
  id: '',
  name: '',
  code: '',
  dbType: 'mysql',
  driverClassName: '',
  url: '',
  username: '',
  password: '',
  remark: '',
  status: 1
})

const form = reactive(emptyForm())
// 记录上一次自动填充所用的模板值，用于判断用户是否手动改动过
let lastTplDriver = ''
let lastTplUrl = ''

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入编码', trigger: 'blur' },
    { pattern: /^[a-zA-Z][a-zA-Z0-9_]*$/, message: '需以字母开头，仅含字母数字下划线', trigger: 'blur' }
  ],
  url: [{ required: true, message: '请输入 URL', trigger: 'blur' }]
}

async function loadData() {
  loading.value = true
  try {
    const res = await pageDatasource({ pageNo: pageNo.value, pageSize: pageSize.value, name: searchName.value })
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
  lastTplDriver = ''
  lastTplUrl = ''
  dialogVisible.value = true
}

function openEdit(row) {
  Object.assign(form, emptyForm(), row)
  lastTplDriver = ''
  lastTplUrl = ''
  dialogVisible.value = true
}

// 切换数据库类型时自动填充模板，仅当当前值为空或等于上一次模板值时才覆盖
function handleDbTypeChange(dbType) {
  const tpl = DB_TEMPLATES[dbType]
  if (!tpl) return
  if (!form.driverClassName || form.driverClassName === lastTplDriver) {
    form.driverClassName = tpl.driverClassName
  }
  if (!form.url || form.url === lastTplUrl) {
    form.url = tpl.url
  }
  lastTplDriver = tpl.driverClassName
  lastTplUrl = tpl.url
}

async function handleTest(row) {
  testing.value = true
  try {
    const ok = await testDatasource(row)
    if (ok) ElMessage.success('连接成功')
  } catch (e) {
    // 已由拦截器提示
  } finally {
    testing.value = false
  }
}

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
      await updateDatasource({ ...form })
      ElMessage.success('保存成功')
    } else {
      await createDatasource({ ...form })
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
  ElMessageBox.confirm(`确定删除数据源「${row.name}」吗？`, '提示', { type: 'warning' })
    .then(async () => {
      try {
        await deleteDatasource(row.id)
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
</style>
