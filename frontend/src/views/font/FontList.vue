<!--
  字体管理页（CONTRACT §3.6）。

  为什么要有这一页：设计器工具栏能选的字体是一份内置清单（utils/fontList.js），
  而三条出纸的路各要各的东西 —— 画布要**浏览器**装了这款字体、xlsx / docx 里只写得进
  **字体名**（打开文件的那台机器得有它）、**PDF 必须把字体文件本身内嵌进去**。
  所以服务器上没有的字体（Linux 上几乎没有楷体、仿宋），从前在 PDF 里是印不出来的；
  在这里传一份上来，三条路就都齐了。

  字体是**系统级**的，所有报表共用，所以这里没有「属于哪张报表」这一维。
-->
<template>
  <div class="page-container">
    <div class="page-toolbar">
      <el-input
        v-model="searchName"
        placeholder="按字体名搜索"
        clearable
        style="width: 240px"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="handleSearch">搜索</el-button>
      <div class="flex-spacer" />
      <el-button type="primary" @click="openUpload">
        <el-icon><Upload /></el-icon>
        上传字体
      </el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
      传上来的字体会出现在设计器的<b>字体下拉</b>里，所有报表都能选。
      导出 <b>PDF</b> 时字体文件会内嵌进去，出纸就是这款字体；
      导出 <b>Excel / Word</b> 只写得进字体名，<b>打开文件的那台机器得装了它</b>才显示得出来
      —— 要精确出纸请走 PDF。
    </el-alert>

    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="fontName" label="字体名" min-width="140">
        <template #default="{ row }"><span class="mono">{{ row.fontName }}</span></template>
      </el-table-column>
      <el-table-column label="字样" min-width="200">
        <!-- 用这款字体本身画一行样张：.ttc 浏览器加载不了，这一列会退回默认字体，所以标一句 -->
        <template #default="{ row }">
          <span :style="{ fontFamily: familyOf(row.fontName) }" class="sample">{{ SAMPLE }}</span>
          <el-tooltip v-if="row.fontFormat === 'ttc'" content="浏览器不支持 .ttc 字体集，这里显示的是默认字体；PDF 导出不受影响">
            <el-icon class="ttc-hint"><WarningFilled /></el-icon>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="fileName" label="文件" min-width="160" show-overflow-tooltip />
      <el-table-column prop="fontFormat" label="格式" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.fontFormat === 'ttc' ? 'warning' : 'info'">
            {{ row.fontFormat }}<template v-if="row.fontFormat === 'ttc'"> #{{ row.ttcIndex || 0 }}</template>
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="fileSize" label="大小" width="100">
        <template #default="{ row }">{{ sizeText(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="90">
        <!-- 停用 = 字体清单里不再出现它，已经用了它的格子退回默认字体 -->
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

    <!-- 上传：文件与字体名一起提交，字体名默认取文件名（去掉后缀） -->
    <el-dialog v-model="uploadVisible" title="上传字体" width="560px" destroy-on-close>
      <el-form ref="uploadFormRef" :model="uploadForm" :rules="rules" label-width="90px">
        <el-form-item label="字体文件" required>
          <el-upload
            :auto-upload="false"
            :limit="1"
            :show-file-list="false"
            accept=".ttf,.otf,.ttc"
            :on-change="onFileChange"
          >
            <el-button>选择文件</el-button>
          </el-upload>
          <div v-if="uploadForm.file" class="text-muted form-tip">
            {{ uploadForm.file.name }}（{{ sizeText(uploadForm.file.size) }}）
          </div>
          <div v-else class="text-muted form-tip">
            支持 .ttf / .otf / .ttc，最大 30MB。
            <b>字体本身要允许嵌入</b>（不少商业中文字体在文件里禁止嵌入，传上来会被退回并说明原因）
          </div>
        </el-form-item>
        <el-form-item label="字体名" prop="fontName">
          <el-input v-model="uploadForm.fontName" placeholder="设计器字体下拉里显示的名字" />
          <div class="text-muted form-tip">
            单元格记的就是这个名字，<b>传上来之后不建议再改</b>（改了老报表里绑着旧名字的格子会退回默认字体）
          </div>
        </el-form-item>
        <el-form-item v-if="isTtc" label="字体集序号" prop="ttcIndex">
          <el-input-number v-model="uploadForm.ttcIndex" :min="0" :max="20" />
          <span class="text-muted" style="margin-left: 8px">.ttc 里含多款字体，用第几款（宋体是 0、新宋体是 1）</span>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="uploadForm.remark" type="textarea" :rows="2" placeholder="比如授权范围、来源" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleUpload">上传</el-button>
      </template>
    </el-dialog>

    <!-- 编辑：只改元信息。换文件走「删掉重传」，见 FontService#update -->
    <el-dialog v-model="editVisible" title="编辑字体" width="480px" destroy-on-close>
      <el-form ref="editFormRef" :model="editForm" :rules="rules" label-width="90px">
        <el-form-item label="字体名" prop="fontName">
          <el-input v-model="editForm.fontName" />
          <div class="text-muted form-tip">
            改名之后，老报表里绑着旧名字的格子会退回默认字体
          </div>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="editForm.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-switch v-model="editForm.status" :active-value="1" :inactive-value="0" />
          <span class="text-muted" style="margin-left: 8px">停用后字体下拉里不再出现它</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleEditSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Upload, WarningFilled } from '@element-plus/icons-vue'
import { pageFont, uploadFont, updateFont, deleteFont } from '@/api/font'
import { loadCustomFonts } from '@/utils/fontList'

/** 字样那一列画的样张，中英数字各来一点，一眼看出是不是那款字体 */
const SAMPLE = '木舟报表 Report 0123'

const loading = ref(false)
const saving = ref(false)
const tableData = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(10)
const searchName = ref('')

const uploadVisible = ref(false)
const editVisible = ref(false)
const uploadFormRef = ref(null)
const editFormRef = ref(null)

const uploadForm = reactive({ file: null, fontName: '', ttcIndex: 0, remark: '' })
const editForm = reactive({ id: '', fontName: '', remark: '', status: 1 })

const isTtc = computed(() => /\.ttc$/i.test(uploadForm.file?.name || ''))

const rules = {
  fontName: [
    { required: true, message: '请填写字体名', trigger: 'blur' },
    { max: 32, message: '不能超过 32 个字符', trigger: 'blur' },
    // 这个名字要同时当 CSS 的 font-family、xlsx 的字体名用，引号逗号在那两处都是语法。
    // 后端 FontServiceImpl#checkName 是同一条规则，这里先拦一道少跑一趟
    { pattern: /^[^"',<>\\/;{}]+$/, message: '不能包含 " \' , < > \\ / ; { } 这些字符', trigger: 'blur' }
  ]
}

/** 字样那一列的 font-family。写在这儿而不是模板里：模板属性是双引号包的，里面再嵌双引号解析不了 */
function familyOf(name) {
  return `"${name}"`
}

function sizeText(bytes) {
  if (!bytes) return '-'
  const mb = bytes / 1024 / 1024
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`
}

async function loadData() {
  loading.value = true
  try {
    const res = await pageFont({ pageNo: pageNo.value, pageSize: pageSize.value, name: searchName.value })
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

function openUpload() {
  Object.assign(uploadForm, { file: null, fontName: '', ttcIndex: 0, remark: '' })
  uploadVisible.value = true
}

/** 选中文件时顺手把字体名填成文件名（去后缀），大多数时候就是想要的那个 */
function onFileChange(file) {
  uploadForm.file = file.raw
  if (!uploadForm.fontName) {
    uploadForm.fontName = (file.name || '').replace(/\.[^.]+$/, '')
  }
}

async function handleUpload() {
  if (!uploadForm.file) {
    ElMessage.warning('请选择字体文件')
    return
  }
  try {
    await uploadFormRef.value?.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    await uploadFont(uploadForm.file, uploadForm.fontName, isTtc.value ? uploadForm.ttcIndex : 0, uploadForm.remark)
    ElMessage.success('上传成功')
    uploadVisible.value = false
    await refresh()
  } catch (e) {
    // 已由拦截器提示（字体文件 PDF 引擎读不了也是在这里报出来的）
  } finally {
    saving.value = false
  }
}

function openEdit(row) {
  Object.assign(editForm, { id: row.id, fontName: row.fontName, remark: row.remark, status: row.status })
  editVisible.value = true
}

async function handleEditSave() {
  try {
    await editFormRef.value?.validate()
  } catch (e) {
    return
  }
  saving.value = true
  try {
    await updateFont({ ...editForm })
    ElMessage.success('保存成功')
    editVisible.value = false
    await refresh()
  } catch (e) {
    // 已由拦截器提示
  } finally {
    saving.value = false
  }
}

function handleDelete(row) {
  ElMessageBox.confirm(
    `确定删除字体「${row.fontName}」吗？服务器上的字体文件会一并删掉，` +
      '用了这款字体的报表会退回默认字体。',
    '提示',
    { type: 'warning' }
  )
    .then(async () => {
      try {
        await deleteFont(row.id)
        ElMessage.success('删除成功')
        await refresh()
      } catch (e) {
        // 已由拦截器提示
      }
    })
    .catch(() => {})
}

/**
 * 改完之后要重拉一次字体：字体清单（设计器下拉）和 `@font-face`（本页的字样列）都是启动时
 * 灌进去的那一份，不重拉的话新传的字体这一页自己都画不出来。
 */
async function refresh() {
  await Promise.all([loadData(), loadCustomFonts()])
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

.sample {
  font-size: 16px;
}

.ttc-hint {
  margin-left: 6px;
  color: var(--el-color-warning);
  vertical-align: middle;
}
</style>
