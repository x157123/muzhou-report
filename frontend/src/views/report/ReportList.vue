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
      <el-button :loading="exporting" :disabled="!selectedIds.length" @click="handleExportSelected">
        <el-icon><Download /></el-icon>
        导出{{ selectedIds.length ? `(${selectedIds.length})` : '' }}
      </el-button>
      <el-button @click="importVisible = true">
        <el-icon><Upload /></el-icon>
        导入
      </el-button>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新建报表
      </el-button>
    </div>

    <el-table
      v-if="tableData.length || loading"
      v-loading="loading"
      :data="tableData"
      border
      stripe
      row-key="id"
      @selection-change="onSelectionChange"
    >
      <!-- reserve-selection：翻页之后勾选还在，批量导出常常要跨页挑 -->
      <el-table-column type="selection" width="46" reserve-selection />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="编码" width="140">
        <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-switch :model-value="row.status === 1" disabled />
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="160" />
      <el-table-column prop="updateTime" label="更新时间" width="160" />
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="goDesign(row)">设计</el-button>
          <el-button link type="primary" @click="goPreview(row)">预览</el-button>
          <el-button link type="primary" @click="handleCopy(row)">复制</el-button>
          <el-button link type="primary" @click="handleExportRow(row)">导出</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑信息</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-else description="还没有报表，点击右上角新建">
      <el-button type="primary" @click="openCreate">新建报表</el-button>
    </el-empty>

    <el-pagination
      v-if="tableData.length"
      class="mz-pagination"
      v-model:current-page="pageNo"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      @size-change="loadData"
      @current-change="loadData"
    />

    <ReportEditDialog v-model="dialogVisible" :report-id="editingId" @saved="loadData" />
    <ReportImportDialog v-model="importVisible" @imported="loadData" />
  </div>
</template>

<script setup>
// 报表列表页
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageReport, deleteReport, copyReport, exportReports } from '@/api/report'
import { downloadBlob } from '@/utils/sheet'
import ReportEditDialog from './ReportEditDialog.vue'
import ReportImportDialog from './ReportImportDialog.vue'

const router = useRouter()

const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchName = ref('')

const dialogVisible = ref(false)
const editingId = ref('')

const importVisible = ref(false)
const exporting = ref(false)
const selectedIds = ref([])

async function loadData() {
  loading.value = true
  try {
    const res = await pageReport({ pageNo: pageNo.value, pageSize: pageSize.value, name: searchName.value })
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
  editingId.value = ''
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  dialogVisible.value = true
}

function goDesign(row) {
  router.push('/designer/' + row.id)
}

function goPreview(row) {
  router.push('/preview/' + row.id)
}

async function handleCopy(row) {
  try {
    await copyReport(row.id)
    ElMessage.success('复制成功')
    loadData()
  } catch (e) {
    // 已由拦截器提示
  }
}

function onSelectionChange(rows) {
  selectedIds.value = rows.map((r) => r.id)
}

/** 单张导出：不动勾选状态，文件名用报表名 */
function handleExportRow(row) {
  doExport([row.id], row.name || 'report')
}

/** 批量导出：勾选的那几张打成一个包 */
function handleExportSelected() {
  if (!selectedIds.value.length) {
    ElMessage.warning('请先勾选要导出的报表')
    return
  }
  doExport(selectedIds.value, `报表导出_${timestamp()}`)
}

async function doExport(ids, fileName) {
  exporting.value = true
  try {
    const res = await exportReports(ids)
    downloadBlob(res.data, `${fileName}.mzreport.json`)
    ElMessage.success(`已导出 ${ids.length} 张报表`)
  } catch (e) {
    // 已由拦截器提示
  } finally {
    exporting.value = false
  }
}

function timestamp() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

function handleDelete(row) {
  ElMessageBox.confirm(`确定删除报表「${row.name}」吗？`, '提示', { type: 'warning' })
    .then(async () => {
      try {
        await deleteReport(row.id)
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
