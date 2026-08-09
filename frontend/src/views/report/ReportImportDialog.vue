<template>
  <el-dialog
    :model-value="modelValue"
    title="导入报表"
    width="640px"
    destroy-on-close
    @update:model-value="handleVisible"
  >
    <el-upload
      drag
      action="#"
      accept=".json"
      :limit="1"
      :auto-upload="false"
      :file-list="fileList"
      :on-change="onFileChange"
      :on-remove="onFileRemove"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">把导出的 <em>.mzreport.json</em> 拖到这里，或点击选择</div>
      <template #tip>
        <div class="el-upload__tip">
          一个文件可以装多张报表；包里含版式、内部数据集和引用到的公共数据集。
          <b>数据源不在包里</b>，目标环境缺哪个会在结果里列出来，导完去数据源管理页补上即可。
        </div>
      </template>
    </el-upload>

    <el-form label-width="96px" class="mode-form">
      <el-form-item label="同编码报表">
        <el-radio-group v-model="mode">
          <el-radio value="skip">跳过</el-radio>
          <el-radio value="overwrite">覆盖</el-radio>
          <el-radio value="copy">另建一张</el-radio>
        </el-radio-group>
        <div class="mode-tip">{{ modeTip }}</div>
      </el-form-item>
    </el-form>

    <template v-if="result">
      <el-divider content-position="left">导入结果</el-divider>
      <div class="summary">
        <el-tag type="success">新建 {{ result.created }}</el-tag>
        <el-tag type="warning">覆盖 {{ result.updated }}</el-tag>
        <el-tag type="info">跳过 {{ result.skipped }}</el-tag>
        <el-tag type="danger">失败 {{ result.failed }}</el-tag>
      </div>
      <el-table :data="result.items || []" border size="small" max-height="260">
        <el-table-column prop="name" label="报表" min-width="120" show-overflow-tooltip />
        <el-table-column label="编码" width="140">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="actionType(row.action)" size="small">{{ actionText(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="200">
          <template #default="{ row }">
            <div v-if="row.message" class="msg">{{ row.message }}</div>
            <div v-for="(w, i) in row.warnings || []" :key="i" class="warn">{{ w }}</div>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <template #footer>
      <el-button @click="handleVisible(false)">关闭</el-button>
      <el-button type="primary" :loading="importing" :disabled="!file" @click="handleImport">
        开始导入
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
// 报表导入弹窗：选包 → 选同编码报表的处理策略 → 导入，结果逐张列出来
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { importReports } from '@/api/report'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
// imported：至少导进去一张（新建或覆盖），列表页据此刷新
const emit = defineEmits(['update:modelValue', 'imported'])

const file = ref(null)
const fileList = ref([])
const mode = ref('skip')
const importing = ref(false)
const result = ref(null)

const MODE_TIPS = {
  skip: '目标环境已有同编码的报表时原样保留，什么都不动',
  overwrite: '用包里这份覆盖它：报表 id 不变（外部系统的链接照旧），版式与内部数据集整套替换',
  copy: '不管有没有同编码的，一律新建一张；编码撞了自动加 _import_时间戳 后缀'
}
const modeTip = computed(() => MODE_TIPS[mode.value])

// el-upload 只当选文件用（auto-upload 关了），真正的上传走 api 里那个 FormData
function onFileChange(uploadFile, files) {
  file.value = uploadFile.raw
  fileList.value = files.slice(-1)
  result.value = null
}

function onFileRemove() {
  file.value = null
  fileList.value = []
}

function actionText(action) {
  return { created: '新建', updated: '覆盖', skipped: '跳过', failed: '失败' }[action] || action
}

function actionType(action) {
  return { created: 'success', updated: 'warning', skipped: 'info', failed: 'danger' }[action] || 'info'
}

async function handleImport() {
  if (!file.value) {
    ElMessage.warning('请先选择要导入的文件')
    return
  }
  importing.value = true
  try {
    const res = await importReports(file.value, mode.value)
    result.value = res
    const done = (res.created || 0) + (res.updated || 0)
    if (res.failed) {
      ElMessage.warning(`导入完成：成功 ${done} 张，失败 ${res.failed} 张`)
    } else {
      ElMessage.success(`导入完成：成功 ${done} 张`)
    }
    if (done) emit('imported')
  } catch (e) {
    // 已由拦截器提示
  } finally {
    importing.value = false
  }
}

function handleVisible(val) {
  emit('update:modelValue', val)
}

// 关掉时清干净，下次打开不带着上一次的文件和结果
watch(
  () => props.modelValue,
  (val) => {
    if (!val) {
      file.value = null
      fileList.value = []
      result.value = null
    }
  }
)
</script>

<style scoped>
.mode-form {
  margin-top: 16px;
}

.mode-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.summary {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.msg {
  color: var(--el-text-color-regular);
}

.warn {
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.6;
}
</style>
