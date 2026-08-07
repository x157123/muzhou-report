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
        新建报表
      </el-button>
    </div>

    <el-table v-if="tableData.length || loading" v-loading="loading" :data="tableData" border stripe>
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
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="goDesign(row)">设计</el-button>
          <el-button link type="primary" @click="goPreview(row)">预览</el-button>
          <el-button link type="primary" @click="handleCopy(row)">复制</el-button>
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
  </div>
</template>

<script setup>
// 报表列表页
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageReport, deleteReport, copyReport } from '@/api/report'
import ReportEditDialog from './ReportEditDialog.vue'

const router = useRouter()

const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchName = ref('')

const dialogVisible = ref(false)
const editingId = ref('')

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
