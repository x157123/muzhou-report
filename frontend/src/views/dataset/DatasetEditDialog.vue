<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="1100px"
    top="5vh"
    destroy-on-close
    @update:model-value="(val) => emit('update:modelValue', val)"
  >
    <el-alert v-if="isInternal" type="info" :closable="false" show-icon style="margin-bottom: 12px">
      内部数据集：只有当前这张报表能用它，不会出现在其它报表的字段树里；报表被删除时一并删除。
      需要多张报表共用请到「数据集管理」里建公共数据集。
      <template v-if="form.versionId">
        本条只属于{{ versionLabel }}，别的版本看不见它，那些版本会用同编码的共用/公共数据集。
      </template>
    </el-alert>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" placeholder="请输入名称" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="编码" prop="code">
            <el-input v-model="form.code" placeholder="供 #{code.字段} 引用" :disabled="!!form.id" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="类型" prop="type">
            <el-radio-group v-model="form.type">
              <el-radio-button value="sql">SQL</el-radio-button>
              <el-radio-button value="api">API</el-radio-button>
              <el-radio-button value="json">JSON</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="返回结果" prop="resultType">
            <el-radio-group v-model="form.resultType" :disabled="form.type === 'sql'">
              <el-radio-button value="list">集合</el-radio-button>
              <el-radio-button value="page">分页</el-radio-button>
            </el-radio-group>
            <el-tooltip placement="top">
              <template #content>
                集合：整份数据一次取回。<br />
                分页：响应里带数据数组 + 总数（total / totalCount / count 等自动识别），<br />
                翻页靠接口地址里的 ${pageNo} / ${pageSize}。<br />
                只有被报表设为「主接口」的分页数据集，才会驱动预览页的分页条。<br />
                SQL 数据集恒为集合。
              </template>
              <el-icon class="hint-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
        </el-col>
        <el-col :span="10">
          <el-form-item label="备注" prop="remark">
            <el-input v-model="form.remark" placeholder="备注" />
          </el-form-item>
        </el-col>
        <el-col :span="6">
          <el-form-item label="状态" prop="status">
            <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
          </el-form-item>
        </el-col>
      </el-row>

      <!--
        内部数据集的第二维作用范围。只在设计器里建/改时给得出（数据集管理页没有「当前版本」可言）。
        「本版本」是默认：不同版本接口可能不一样，正是版本级数据集存在的理由；
        真要几版共用就切到「全版本共用」。同 code 时窄的那个盖住宽的，模板里的绑定不用动。
      -->
      <el-row v-if="isInternal && versionId" :gutter="16">
        <el-col :span="18">
          <el-form-item label="作用范围">
            <el-radio-group v-model="form.versionId">
              <el-radio-button :value="versionId">只属于{{ versionLabel }}</el-radio-button>
              <el-radio-button value="">本报表全版本共用</el-radio-button>
            </el-radio-group>
            <el-tooltip placement="top">
              <template #content>
                只属于这一版：换到别的版本就看不见它了，那一版会用同 code 的共用/公共数据集。<br />
                本报表全版本共用：这张报表的每一版都能用（改它会同时影响所有版本）。<br />
                复制版本时，「只属于这一版」的那些会跟着复制一份到新版本上。
              </template>
              <el-icon class="hint-icon"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
        </el-col>
      </el-row>

      <!-- SQL 类型 -->
      <template v-if="form.type === 'sql'">
        <el-form-item label="数据源" prop="datasourceId">
          <el-select v-model="form.datasourceId" placeholder="请选择数据源" style="width: 320px">
            <el-option v-for="ds in datasourceList" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="SQL">
          <div style="width: 100%">
            <SqlEditor v-model="form.sqlText" height="220px" />
            <div class="text-muted" style="margin-top: 6px">
              参数用 <span class="mono">${参数名}</span>，会以 JDBC 占位符安全绑定；
              <span class="mono">$!{参数名}</span> 为直接拼接（仅用于动态表名/排序）。
            </div>
          </div>
        </el-form-item>
      </template>

      <!-- API 类型 -->
      <template v-else-if="form.type === 'api'">
        <el-form-item label="请求方法" prop="apiMethod">
          <el-select v-model="form.apiMethod" style="width: 140px">
            <el-option label="GET" value="GET" />
            <el-option label="POST" value="POST" />
          </el-select>
        </el-form-item>
        <el-form-item label="URL" prop="apiUrl">
          <div style="width: 100%">
            <el-input v-model="form.apiUrl" placeholder="请求地址，支持 ${参数名}，例如 /api/user?id=${id}" />
            <div class="text-muted" style="margin-top: 6px">
              地址里的 <span class="mono">${参数名}</span> 会做 URL 编码后替换，点「解析字段」可自动列出来。
              取值优先用下方参数页签里的值，<b>报表预览地址上的同名 query 参数会覆盖它</b> ——
              例如预览页打开 <span class="mono">/preview/{报表id}?id=11233</span>，这里的
              <span class="mono">${id}</span> 拿到的就是 11233。
            </div>
          </div>
        </el-form-item>
        <el-form-item label="请求头">
          <el-input v-model="form.apiHeaders" type="textarea" :rows="3" placeholder='JSON 格式，例如 {"Content-Type":"application/json"}' />
        </el-form-item>
      </template>

      <!-- JSON 类型 -->
      <template v-else-if="form.type === 'json'">
        <el-form-item label="JSON 内容">
          <el-input v-model="form.jsonText" type="textarea" :rows="8" class="mono" placeholder="请输入 JSON 数据或 JSON 数组" />
        </el-form-item>
      </template>
    </el-form>

    <div class="page-toolbar">
      <el-button :loading="parsing" @click="handleParse">解析字段</el-button>
      <el-button :loading="previewing" @click="handlePreview">数据预览</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <!-- 字段 -->
      <el-tab-pane label="字段" name="fields">
        <div class="page-toolbar">
          <span class="text-muted">共 {{ fields.length }} 个字段</span>
        </div>
        <el-table :data="fields" border size="small" max-height="320">
          <el-table-column prop="fieldName" label="字段名" width="160">
            <template #default="{ row }"><span class="mono">{{ row.fieldName }}</span></template>
          </el-table-column>
          <el-table-column label="显示名" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.fieldText" size="small" placeholder="显示名" />
            </template>
          </el-table-column>
          <el-table-column label="类型" width="130">
            <template #default="{ row }">
              <el-select v-model="row.fieldType" size="small">
                <el-option label="字符串" value="string" />
                <el-option label="数字" value="number" />
                <el-option label="日期" value="date" />
                <el-option label="布尔" value="boolean" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="排序" width="90">
            <template #default="{ row }">
              <el-input-number v-model="row.sortNo" :min="0" size="small" controls-position="right" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ $index }">
              <el-button link type="danger" @click="fields.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 参数 -->
      <el-tab-pane label="参数" name="params">
        <div class="page-toolbar">
          <el-button size="small" @click="addParam">
            <el-icon><Plus /></el-icon>
            新增参数
          </el-button>
        </div>
        <el-table :data="params" border size="small" max-height="320">
          <el-table-column label="参数名" width="150">
            <template #default="{ row }">
              <el-input v-model="row.paramName" size="small" placeholder="参数名" />
            </template>
          </el-table-column>
          <el-table-column label="显示名" min-width="130">
            <template #default="{ row }">
              <el-input v-model="row.paramText" size="small" placeholder="显示名" />
            </template>
          </el-table-column>
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <el-select v-model="row.paramType" size="small">
                <el-option v-for="t in PARAM_TYPES" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="默认值" min-width="130">
            <template #default="{ row }">
              <el-input v-model="row.defaultValue" size="small" placeholder="默认值" />
            </template>
          </el-table-column>
          <el-table-column label="必填" width="80">
            <template #default="{ row }">
              <el-switch v-model="row.required" :active-value="1" :inactive-value="0" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ $index }">
              <el-button link type="danger" @click="params.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 数据预览 -->
      <el-tab-pane label="数据预览" name="preview">
        <div v-if="previewColumns.length" class="page-toolbar">
          <span class="text-muted">
            取回 {{ previewRecords.length }} 条
            <template v-if="previewTotal !== null"> · 接口自报共 {{ previewTotal }} 条</template>
            <template v-else-if="form.resultType === 'page'"> · 未取到总数</template>
          </span>
        </div>
        <el-table v-loading="previewing" :data="previewRecords" border size="small" max-height="360">
          <el-table-column v-for="col in previewColumns" :key="col" :prop="col" :label="col" min-width="120">
            <template #default="{ row }">{{ formatCell(row[col]) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!previewing && !previewColumns.length" description="暂无预览数据，点击上方「数据预览」按钮获取" />
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
// 数据集新建/编辑弹窗：支持 SQL / API / JSON 三种类型，解析字段、数据预览、保存
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getDataset, createDataset, updateDataset, parseDataset, previewDataset } from '@/api/dataset'
import { listDatasource } from '@/api/datasource'
import { QuestionFilled } from '@element-plus/icons-vue'
import { PARAM_TYPES } from '@/utils/sheet'
import SqlEditor from '@/components/SqlEditor.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  datasetId: { type: String, default: '' },
  /**
   * 归属报表 id。传了就是在报表设计器里建/改**内部数据集**（只有该报表可用）；
   * 不传就是数据集管理页的**公共数据集**（所有报表可用）。
   * 编辑已有数据集时以库里存的作用范围为准，这个 prop 只影响新建。
   */
  reportId: { type: String, default: '' },
  /**
   * 设计器当前打开的是哪一版。新建内部数据集时**默认归属这一版**（不同版本接口可能不一样），
   * 用户可以在「作用范围」里改成全版本共用。数据集管理页不传（公共数据集没有版本可言）。
   */
  versionId: { type: String, default: '' },
  /** 「只属于 v2」这种话里的那个版本名，只用于文案 */
  versionLabel: { type: String, default: '当前版本' }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const formRef = ref(null)
const saving = ref(false)
const parsing = ref(false)
const previewing = ref(false)
const activeTab = ref('fields')

const datasourceList = ref([])

const emptyForm = () => ({
  id: '',
  name: '',
  code: '',
  /** 空 = 公共数据集；非空 = 该报表的内部数据集 */
  reportId: '',
  /** 空 = 不限版本（公共集恒为空，内部集则是全版本共用）；非空 = 只属于那一版 */
  versionId: '',
  datasourceId: '',
  type: 'sql',
  /** list 集合 / page 分页；SQL 恒为 list */
  resultType: 'list',
  sqlText: '',
  apiUrl: '',
  apiMethod: 'GET',
  apiHeaders: '',
  jsonText: '',
  remark: '',
  status: 1
})

const form = reactive(emptyForm())
/** 当前编辑的是不是内部数据集（新建时看 prop，编辑时看回填的 reportId） */
const isInternal = computed(() => !!form.reportId)
const dialogTitle = computed(() => {
  const scope = isInternal.value ? '内部数据集' : '数据集'
  return form.id ? `编辑${scope}` : `新建${scope}`
})
const fields = ref([])
const params = ref([])
const previewColumns = ref([])
const previewRecords = ref([])
/** 分页型数据集里接口自报的总条数 */
const previewTotal = ref(null)

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入编码', trigger: 'blur' },
    { pattern: /^[a-zA-Z][a-zA-Z0-9_]*$/, message: '需以字母开头，仅含字母数字下划线', trigger: 'blur' }
  ]
}

function resetPreview() {
  previewColumns.value = []
  previewRecords.value = []
  previewTotal.value = null
}

async function loadDatasourceList() {
  try {
    datasourceList.value = await listDatasource()
  } catch (e) {
    // 已由拦截器提示
  }
}

async function openDialog() {
  activeTab.value = 'fields'
  resetPreview()
  await loadDatasourceList()
  if (props.datasetId) {
    try {
      const detail = await getDataset(props.datasetId)
      Object.assign(form, emptyForm(), detail.dataset || {})
      fields.value = (detail.fields || []).map((f) => ({ ...f }))
      params.value = (detail.params || []).map((p) => ({ ...p }))
    } catch (e) {
      // 已由拦截器提示
    }
  } else {
    // 新建：在设计器里建的默认只属于当前这一版（版本级），公共数据集那头 versionId 恒为空
    Object.assign(form, emptyForm(), {
      reportId: props.reportId,
      versionId: props.reportId ? props.versionId : ''
    })
    fields.value = []
    params.value = []
  }
}

watch(
  () => props.modelValue,
  (val) => {
    if (val) openDialog()
  }
)

// SQL 恒为集合型（分页要数据库端 limit/offset 再加一条 count），切到 SQL 就把选择收回来
watch(
  () => form.type,
  (type) => {
    if (type === 'sql') form.resultType = 'list'
  }
)

function addParam() {
  params.value.push({
    paramName: '',
    paramText: '',
    paramType: 'string',
    defaultValue: '',
    required: 0,
    sortNo: params.value.length
  })
}

// 构建解析/预览接口所需的公共请求体
function buildParseBody() {
  return {
    datasourceId: form.datasourceId,
    type: form.type,
    resultType: form.resultType,
    sqlText: form.sqlText,
    apiUrl: form.apiUrl,
    apiMethod: form.apiMethod,
    apiHeaders: form.apiHeaders,
    jsonText: form.jsonText,
    params: params.value
  }
}

async function handleParse() {
  if (form.type === 'sql' && !form.datasourceId) {
    ElMessage.warning('请先选择数据源')
    return
  }
  parsing.value = true
  try {
    const res = await parseDataset(buildParseBody())
    fields.value = (res.fields || []).map((f) => ({ ...f }))
    // 参数合并：已存在同名参数保留原配置，否则新增
    const existByName = {}
    for (const p of params.value) {
      if (p.paramName) existByName[p.paramName] = p
    }
    const merged = []
    for (const p of res.params || []) {
      if (existByName[p.paramName]) {
        merged.push(existByName[p.paramName])
      } else {
        merged.push({ ...p })
      }
    }
    // 保留解析结果之外、用户手动添加的参数
    const parsedNames = new Set((res.params || []).map((p) => p.paramName))
    for (const p of params.value) {
      if (p.paramName && !parsedNames.has(p.paramName)) {
        merged.push(p)
      }
    }
    params.value = merged
    ElMessage.success('解析成功')
    activeTab.value = 'fields'
  } catch (e) {
    // 已由拦截器提示
  } finally {
    parsing.value = false
  }
}

async function handlePreview() {
  if (form.type === 'sql' && !form.datasourceId) {
    ElMessage.warning('请先选择数据源')
    return
  }
  previewing.value = true
  activeTab.value = 'preview'
  try {
    const res = await previewDataset({ ...buildParseBody(), pageNo: 1, pageSize: 100 })
    previewColumns.value = res.columns || []
    previewRecords.value = res.records || []
    previewTotal.value = res.total ?? null
  } catch (e) {
    // 已由拦截器提示
  } finally {
    previewing.value = false
  }
}

function formatCell(val) {
  if (val === null || val === undefined || val === '') return '-'
  if (typeof val === 'object') return JSON.stringify(val)
  return val
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
    const payload = { ...form, fields: fields.value, params: params.value }
    if (form.id) {
      await updateDataset(payload)
      ElMessage.success('保存成功')
    } else {
      await createDataset(payload)
      ElMessage.success('创建成功')
    }
    emit('saved')
    emit('update:modelValue', false)
  } catch (e) {
    // 已由拦截器提示
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.hint-icon {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  cursor: help;
}
</style>
