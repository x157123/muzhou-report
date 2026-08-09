<template>
  <el-dialog
    :model-value="modelValue"
    title="导入 Excel 模板"
    width="620px"
    destroy-on-close
    @update:model-value="handleVisible"
  >
    <el-upload
      drag
      action="#"
      accept=".xlsx"
      :limit="1"
      :auto-upload="false"
      :file-list="fileList"
      :on-change="onFileChange"
      :on-remove="onFileRemove"
    >
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">把 <em>.xlsx</em> 拖到这里，或点击选择</div>
      <template #tip>
        <div class="el-upload__tip">
          带过来的是<b>版式</b>：文字、字体、底色、对齐、合并、行高列宽、边框，以及纸张 /
          页边距 / 打印区域 / 顶端标题行 / 页眉页脚。
          <b>数据绑定不猜</b> —— 导入之后照常在左侧建数据集、把字段拖到格子上。
          水印、条件格式、图片、图表带不过来，缺了什么会在下面逐条列出。
        </div>
      </template>
    </el-upload>

    <el-form label-width="96px" class="mode-form">
      <el-form-item label="导入方式">
        <el-radio-group v-model="mode">
          <el-radio value="append">追加到末尾</el-radio>
          <el-radio value="replace">替换整个工作簿</el-radio>
        </el-radio-group>
        <div class="mode-tip">{{ MODE_TIPS[mode] }}</div>
      </el-form-item>
    </el-form>

    <template v-if="result">
      <el-divider content-position="left">导入结果</el-divider>
      <div class="summary">
        <el-tag v-for="name in result.names" :key="name" size="small">{{ name }}</el-tag>
      </div>
      <div v-for="(w, i) in result.warnings" :key="i" class="warn">· {{ w }}</div>
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
// Excel 模板导入弹窗：选 xlsx → 选导入方式 → 后端解析成版式 → 交给设计器挂上画布。
// 后端只解析不落库（见 ReportController#parseExcel），用户在画布上看过、点「保存」才落库。
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { parseExcelTemplate } from '@/api/report'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
// imported：解析成功，把 { content, mode } 交给设计器去挂
const emit = defineEmits(['update:modelValue', 'imported'])

const file = ref(null)
const fileList = ref([])
const mode = ref('append')
const importing = ref(false)
const result = ref(null)

const MODE_TIPS = {
  append: '把 Excel 里的工作表加在现有工作表后面，原有的版式和绑定都不动',
  replace: '整个工作簿换成这份 Excel —— 现有的工作表与单元格绑定会被清掉（不点保存就不落库）'
}

// el-upload 只当选文件用（auto-upload 关了），真正的上传走 api 里那个 FormData
function onFileChange(uploadFile, files) {
  file.value = uploadFile.raw
  fileList.value = files.slice(-1)
  result.value = null
}

function onFileRemove() {
  file.value = null
  fileList.value = []
  result.value = null
}

async function handleImport() {
  if (!file.value) {
    ElMessage.warning('请先选择要导入的 Excel')
    return
  }
  // 替换是把现有版式整个换掉，先问一句（不点保存就不落库，所以问一次够了）
  if (mode.value === 'replace') {
    try {
      await ElMessageBox.confirm(
        '现有的工作表和单元格绑定会被这份 Excel 换掉，确定继续？',
        '替换整个工作簿',
        { type: 'warning', confirmButtonText: '替换', cancelButtonText: '取消' }
      )
    } catch (e) {
      return
    }
  }
  importing.value = true
  try {
    const res = await parseExcelTemplate(file.value)
    const sheets = res?.content?.sheets || []
    emit('imported', { content: res.content, mode: mode.value })
    const warnings = res?.warnings || []
    result.value = { names: sheets.map((s) => s.name), warnings }
    ElMessage.success(`已导入 ${sheets.length} 张工作表，记得点「保存」`)
    // 没什么要交代的就直接关掉，让用户看画布
    if (!warnings.length) handleVisible(false)
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
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.warn {
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.7;
}
</style>
