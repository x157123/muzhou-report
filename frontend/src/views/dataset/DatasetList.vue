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
      <span class="text-muted scope-hint">
        这里维护的是公共数据集，所有报表都能用；只给某一张报表用的内部数据集在该报表的设计器里建
      </span>
      <div class="flex-spacer" />
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon>
        新建数据集
      </el-button>
    </div>

    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="编码" min-width="160">
        <template #default="{ row }">
          <el-tooltip content="单元格中用 #{code.字段} 引用" placement="top">
            <span class="mono">{{ row.code }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="type" label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="typeTagType(row.type)">{{ row.type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="返回结果" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.resultType === 'page'" size="small" type="warning" effect="plain">分页</el-tag>
          <span v-else class="text-muted">集合</span>
        </template>
      </el-table-column>
      <el-table-column label="所属数据源" min-width="140">
        <template #default="{ row }">{{ datasourceName(row.datasourceId) }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column prop="updateTime" label="更新时间" width="160" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="handleCopyCode(row)">复制code</el-button>
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

    <DatasetEditDialog v-model="dialogVisible" :dataset-id="editingId" @saved="loadData" />
  </div>
</template>

<script setup>
// 数据集管理列表页
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageDataset, deleteDataset } from '@/api/dataset'
import { listDatasource } from '@/api/datasource'
import DatasetEditDialog from './DatasetEditDialog.vue'

const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchName = ref('')

const dialogVisible = ref(false)
const editingId = ref('')

const datasourceMap = ref({})

function typeTagType(type) {
  if (type === 'sql') return 'primary'
  if (type === 'api') return 'success'
  if (type === 'json') return 'warning'
  return 'info'
}

function datasourceName(id) {
  if (!id) return '-'
  return datasourceMap.value[id] || id
}

async function loadDatasourceMap() {
  try {
    const list = await listDatasource()
    const map = {}
    for (const item of list || []) {
      map[item.id] = item.name
    }
    datasourceMap.value = map
  } catch (e) {
    // 已由拦截器提示
  }
}

async function loadData() {
  loading.value = true
  try {
    const res = await pageDataset({ pageNo: pageNo.value, pageSize: pageSize.value, name: searchName.value })
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

async function handleCopyCode(row) {
  try {
    await navigator.clipboard.writeText(row.code)
    ElMessage.success('编码已复制')
  } catch (e) {
    ElMessage.warning('复制失败，请手动复制：' + row.code)
  }
}

function handleDelete(row) {
  ElMessageBox.confirm(`确定删除数据集「${row.name}」吗？`, '提示', { type: 'warning' })
    .then(async () => {
      try {
        await deleteDataset(row.id)
        ElMessage.success('删除成功')
        loadData()
      } catch (e) {
        // 已由拦截器提示
      }
    })
    .catch(() => {})
}

onMounted(() => {
  loadDatasourceMap()
  loadData()
})
</script>

<style scoped>
.scope-hint {
  font-size: 12px;
}
.mz-pagination {
  margin-top: 12px;
  justify-content: flex-end;
  display: flex;
}
</style>
