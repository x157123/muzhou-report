<!--
  版本管理弹窗：一张报表的几份**版式**（content）在这里管。

  版本化的是版式，不是数据集 —— 数据集是「取数」，跨版本共用。所以这里能改的是
  「哪一版在什么时间段生效」「哪一版是兜底的默认版本」，改 SQL 请去数据集面板。

  生效时间段两端都由用户填（左闭右开，两端可空 = 不限），**允许多版共用同一段时间**，
  所以每一行还要提示「这一段是不是被别人盖住了」—— 配得出重叠，就得看得见重叠。
-->
<template>
  <el-dialog v-model="visible" title="版本管理" width="1100px" :close-on-click-modal="false" destroy-on-close>
    <div class="tips">
      版本化的是<b>版式</b>：每一版持有一份完整的报表内容。用哪一版由<b>生效时间段</b>加
      <b>匹配条件</b>（类型/区域这类维度）两维决定 —— <b>先按时间圈出这一刻生效的那几版，
      再按匹配条件从中定唯一一份</b>，判定值从哪来在下面配。
      改<b>数据集</b>会同时影响所有版本（数据集不随版本走）。
    </div>

    <el-form label-width="96px" size="small" class="version-config-form">
      <el-form-item label="判定依据">
        <el-radio-group v-model="versionConfig.source" @change="applyVersionConfig">
          <el-radio-button value="field">主接口字段</el-radio-button>
          <el-radio-button value="param">报表参数</el-radio-button>
          <el-radio-button value="now">渲染当日</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="versionConfig.source === 'field'" label="字段">
        <el-select
          v-model="versionConfig.field"
          placeholder="取主接口的哪个字段（留空 = 只按条件选）"
          clearable
          style="width: 260px"
          @change="applyVersionConfig"
        >
          <el-option
            v-for="f in primaryFields"
            :key="f.fieldName"
            :label="`${f.fieldText || f.fieldName} (${f.fieldName})`"
            :value="f.fieldName"
          />
        </el-select>
        <span v-if="!primary" class="text-muted" style="margin-left: 8px">
          未设主接口 —— 在左侧数据集面板点 ☆ 指定一个
        </span>
      </el-form-item>

      <el-form-item v-else-if="versionConfig.source === 'param'" label="参数">
        <el-select
          v-model="versionConfig.field"
          placeholder="取哪个报表参数"
          clearable
          style="width: 260px"
          @change="applyVersionConfig"
        >
          <el-option
            v-for="p in reportParams"
            :key="p.name"
            :label="`${p.text || p.name} (${p.name})`"
            :value="p.name"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="无法判定时">
        <el-radio-group v-model="versionConfig.fallback" @change="applyVersionConfig">
          <el-radio-button value="default">用默认版本{{ defaultLabel }}</el-radio-button>
          <el-radio-button value="error">直接报错</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>

    <div class="tips">
      <p>
        选版本分<b>两步</b>：<b>先</b>用生效时间圈出这一刻生效的那几版（判定依据只管这一维，
        留空就是不按时间筛），<b>再</b>照「起点更晚的优先、同起点版本号大的优先」的次序
        逐个试匹配条件，<b>第一个条件满足的就是它</b>。没配条件 = 任何数据都满足，
        所以它是同一时间段里的兜底。
      </p>
      <p v-if="splitByRow">
        「每条数据一张/一页」时，<b>每条数据按自己那一行的字段值各选各的版式</b>（时间判定字段
        与匹配条件里取主接口字段的那些都逐行判）—— 跨期、跨类型的一批单据可以一次打完。
      </p>
      <p v-else-if="versionConfig.source === 'field' && versionConfig.field">
        非拆分报表取的是主接口<b>第一行</b>的字段值；汇总类报表第一行的日期未必代表整张表，
        建议改用「报表参数」或「渲染当日」。
      </p>
      <p>
        <b>判定依据是报表级设置</b>，改完记得点顶部工具栏的<b>保存</b>才会写回报表；
        下面各版本自己的匹配条件/生效时间/名称/启停改了立即生效，不必再按保存。
      </p>
    </div>

    <el-table :data="rows" size="small" v-loading="loading" border>
      <el-table-column label="版本" width="50">
        <template #default="{ row }">
          <span class="mono" v-if="row.id !== currentId">{{ row.label }}</span>
          <span class="mono select-version" v-if="row.id === currentId" >{{ row.label }}</span>
        </template>
      </el-table-column>

      <el-table-column label="匹配条件" min-width="200">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="editRules(row)">
            <span v-if="row.condition" class="cond">{{ row.condition }}</span>
            <span v-else class="text-muted">无条件（兜底）</span>
          </el-button>
        </template>
      </el-table-column>

      <!--
        两个独立的日期框而不是一个 datetimerange：range 选择器只能整段填或整段清，
        配不出「从 5/1 起、右端不限」这种最常见的写法。
      -->
      <el-table-column label="生效时间段" width="300">
        <template #default="{ row }">
          <div class="range">
            <el-date-picker
              :model-value="row.effectiveFrom"
              type="datetime"
              size="small"
              placeholder="开始（空=不限）"
              value-format="YYYY-MM-DD HH:mm:ss"
              @update:model-value="(v) => saveMeta(row, { effectiveFrom: v || null })"
            />
            <span class="sep">~</span>
            <el-date-picker
              :model-value="row.effectiveTo"
              type="datetime"
              size="small"
              placeholder="结束（空=不限）"
              value-format="YYYY-MM-DD HH:mm:ss"
              @update:model-value="(v) => saveMeta(row, { effectiveTo: v || null })"
            />
          </div>
<!--          <div v-if="!row.enabled" class="hint text-muted">{{ row.note }}</div>-->
<!--          <div v-else-if="row.coveredBy.length" class="hint warn">{{ row.note }}</div>-->
<!--          <div v-else class="hint text-muted">{{ intervalText(row) }}</div>-->
        </template>
      </el-table-column>

      <el-table-column label="名称" min-width="130">
        <template #default="{ row }">
          <el-input
            v-model="rawVersion(row.id).name"
            size="small"
            :placeholder="`留空显示 v${row.versionNo}`"
            @change="(v) => saveMeta(row, { name: v })"
          />
        </template>
      </el-table-column>

      <el-table-column label="状态" width="70">
        <template #default="{ row }">

          <el-switch
              :model-value="row.enabled"
              :disabled="row.isDefault"
              active-text="启用"
              inactive-text="停用"
              inline-prompt
              @update:model-value="(v) => saveMeta(row, { status: v ? 1 : 0 })"
          />
        </template>
      </el-table-column>

<!--      <el-table-column label="更新时间" width="160" prop="updateTime" />-->

      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="emitOpen(row)">打开</el-button>
          <el-button link type="primary" size="small" @click="doCopy(row)">复制</el-button>
          <el-button
            link
            type="primary"
            size="small"
            :disabled="row.isDefault"
            @click="doSetDefault(row)"
          >
            设为默认
          </el-button>
<!--          <el-button link type="primary" size="small" @click="doCheck(row)">校验</el-button>-->
          <el-button link type="danger" size="small" :disabled="row.isDefault" @click="doRemove(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="tips foot">
      <p>
        <b>生效时间段两端都可以留空</b>（= 那一端不限），区间<b>左闭右开</b> ——
        结束填 8/1 表示 7/31 当天还用它、8/1 起不再用。两端都空 = 全时段。
      </p>
      <p>
        <b>允许多版共用同一段时间</b>：重叠那一段<b>先轮到起点更晚</b>的那一版（起点相同则版本号
        大的先）。但「先轮到」不等于「一定用它」—— 它的匹配条件不满足时就轮到下一版，
        所以只有被<b>无条件</b>的版本盖住时才真的永远轮不到，表格里标的就是这种。
        <b>条件更具体的版本不会天然优先</b>，想让它先被试到就把它排在更大的版本号上（后建的天然如此）。
      </p>
      <p>
        谁也没圈进来的那一段（比如某一版到期后没有下一版接上），或者圈进来了却没有哪一版的条件
        满足，都落回<b>默认版本</b>。
      </p>
      <p>
        <b>停用</b>不参与自动选择，它那段会被前一版吞掉（临时回滚版式就靠这个）；
        <b>默认版本</b>是判定值取不到时的兜底，不能停用、不能删除。
      </p>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>

    <VersionRulesDialog v-model="rulesVisible" :version="editing" @confirm="onRulesConfirm" />
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listVersion,
  updateVersion,
  copyVersion,
  setDefaultVersion,
  deleteVersion,
  checkVersion
} from '@/api/report'
import { versionIntervals, intervalText } from '@/utils/version'
import { useDesignerStore } from '@/stores/designer'
import VersionRulesDialog from './VersionRulesDialog.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  reportId: { type: String, default: '' },
  /** 设计器当前打开的是哪一版 */
  currentId: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'open', 'changed'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const store = useDesignerStore()

const versions = ref([])
const loading = ref(false)

/** 带上推导出来的生效区间 */
const rows = computed(() => versionIntervals(versions.value))

/**
 * 「名称」输入框专用：`rows` 是 `versionIntervals` 每次都重新 `{...v}` 展开出来的普通对象，
 * 绑给它的 `el-input` 每敲一个字都会被 Element Plus 自己的 `setNativeInputValue` 拉回旧值
 * （modelValue 没有跟着变，见 input.vue 的 handleInput）—— 这就是"打不出字"的根因。
 * 必须直接把 v-model 接回 `versions` 里那个响应式对象本身。
 */
function rawVersion(id) {
  return versions.value.find((v) => v.id === id) || {}
}

/**
 * 版本切换规则（判定依据 / 字段 / 取不到时怎么办）：**报表级**设置，原来放在打印设置弹窗的
 * 「版本」页签，和这里的版本列表分散两处不好找，合并到版本管理里一起改。
 * 绑的是 `store.report.versionConfig` 而不是 content —— content 本身就是被版本化的那个东西，
 * 规则放进去就成了「每个版本各有一套怎么选自己」，逻辑成环；和报表名一样，改了要点顶部工具栏
 * 的保存才落库，所以这里只改本地 store、不单独调接口。
 */
const versionConfig = ref({ source: 'field', field: '', fallback: 'default' })
const primary = computed(() => store.datasetByCode(store.content.primaryDataset))
const primaryFields = computed(() => primary.value?.fields || [])
const reportParams = computed(() => store.content.params || [])
const splitByRow = computed(() => ['perRow', 'perRowPage'].includes(store.content.splitMode))
const defaultLabel = computed(() => {
  const d = rows.value.find((v) => v.isDefault)
  return d ? ` ${d.label}` : ''
})

function applyVersionConfig() {
  store.setVersionConfig(versionConfig.value)
}

watch(visible, (v) => {
  if (!v) return
  load()
  versionConfig.value = store.versionConfigOf()
})

async function load() {
  if (!props.reportId) return
  loading.value = true
  try {
    versions.value = await listVersion(props.reportId)
    emit('changed', versions.value)
  } catch (e) {
    // 错误已由 axios 拦截器提示
  } finally {
    loading.value = false
  }
}

/** 正在编辑匹配条件的那一行 */
const rulesVisible = ref(false)
const editing = ref(null)

function editRules(row) {
  editing.value = row
  rulesVisible.value = true
}

/** 条件弹窗点确定：空串 = 无条件（后端 set() 会把这一列清掉） */
function onRulesConfirm(matchRules) {
  if (editing.value) saveMeta(editing.value, { matchRules })
}

/** 改元信息：几个字段一起提交（后端走 set()，生效区间两端 / matchRules 才清得掉） */
async function saveMeta(row, patch) {
  try {
    await updateVersion({
      id: row.id,
      name: row.name,
      effectiveFrom: row.effectiveFrom,
      effectiveTo: row.effectiveTo,
      matchRules: row.matchRules,
      status: row.enabled ? 1 : 0,
      remark: row.remark,
      ...patch
    })
    ElMessage.success('已保存')
    await load()
  } catch (e) {
    await load() // 失败就把界面拉回库里的真实状态
  }
}

async function doCopy(row) {
  try {
    await copyVersion(props.reportId, row.id)
    ElMessage.success(`已从 ${row.label} 复制出新版本（停用中，配好生效时间再启用）`)
    await load()
  } catch (e) {
    // 错误已提示
  }
}

async function doSetDefault(row) {
  try {
    await setDefaultVersion(props.reportId, row.id)
    ElMessage.success(`${row.label} 已设为默认版本`)
    await load()
  } catch (e) {
    // 错误已提示
  }
}

function doRemove(row) {
  ElMessageBox.confirm(`确定删除 ${row.label} 吗？这一版的版式会一并删除。`, '提示', { type: 'warning' })
    .then(async () => {
      try {
        await deleteVersion(row.id)
        ElMessage.success('已删除')
        await load()
      } catch (e) {
        // 错误已提示
      }
    })
    .catch(() => {})
}

async function doCheck(row) {
  try {
    const problems = await checkVersion(props.reportId, row.id)
    if (!problems || !problems.length) {
      ElMessage.success(`${row.label} 没查出问题`)
      return
    }
    ElMessageBox.alert(problems.map((p) => `· ${p}`).join('\n'), `${row.label} 的体检结果`, {
      type: 'warning',
      customClass: 'mz-pre-wrap'
    })
  } catch (e) {
    // 错误已提示
  }
}

function emitOpen(row) {
  emit('open', row.id)
  visible.value = false
}
</script>

<style scoped>
.tips {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.tips.foot {
  margin: 8px 0 0;
}
.tips p {
  margin: 0;
}
.version-config-form {
  margin-bottom: 4px;
}
.mono {
  margin-right: 6px;
}

.select-version {
  color: #0350ea;
}

/* 两个日期框挤在一列里：各占一半，中间留一个 ~ */
.range {
  display: flex;
  align-items: center;
  gap: 4px;
}
.range :deep(.el-date-editor) {
  flex: 1;
  min-width: 0;
}
.sep {
  color: var(--el-text-color-secondary);
}
.hint {
  font-size: 12px;
  line-height: 1.4;
  margin-top: 2px;
}
.hint.warn {
  color: var(--el-color-warning);
}
/* 条件可能挺长，允许换行显示（默认的 el-button 是不换行的） */
.cond {
  white-space: normal;
  text-align: left;
  line-height: 1.4;
}
</style>

<style>
/* 体检结果是多行文本，弹框里要保留换行 */
.mz-pre-wrap .el-message-box__message {
  white-space: pre-wrap;
}
</style>
