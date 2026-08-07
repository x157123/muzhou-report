<!--
  版本管理弹窗：一张报表的几份**版式**（content）在这里管。

  版本化的是版式，不是数据集 —— 数据集是「取数」，跨版本共用。所以这里能改的是
  「哪一版从什么时候起生效」「哪一版是兜底的默认版本」，改 SQL 请去数据集面板。

  用户配的是**起点**，脑子里想的是**区间**，所以每一行都把推导出来的区间摆出来
  （左闭右开，停用的版本不参与推导，被前一版吞掉）。
-->
<template>
  <el-dialog v-model="visible" title="版本管理" width="900px" :close-on-click-modal="false" destroy-on-close>
    <div class="tips">
      版本化的是<b>版式</b>：每一版持有一份完整的报表内容。用哪一版由「打印设置 → 版本」里配的规则决定，
      改<b>数据集</b>会同时影响所有版本（数据集不随版本走）。
    </div>

    <el-table :data="rows" size="small" v-loading="loading" border>
      <el-table-column label="版本" width="120">
        <template #default="{ row }">
          <span class="mono">{{ row.label }}</span>
          <el-tag v-if="row.id === currentId" size="small" type="primary" effect="plain">当前</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="生效起始" width="190">
        <template #default="{ row }">
          <el-date-picker
            :model-value="row.effectiveFrom"
            type="datetime"
            size="small"
            style="width: 100%"
            placeholder="留空 = 最早的那一版"
            value-format="YYYY-MM-DD HH:mm:ss"
            @update:model-value="(v) => saveMeta(row, { effectiveFrom: v || null })"
          />
        </template>
      </el-table-column>

      <el-table-column label="生效区间（推导）" width="200">
        <template #default="{ row }">
          <span :class="{ 'text-muted': !row.enabled }">{{ intervalText(row) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="名称" min-width="130">
        <template #default="{ row }">
          <el-input
            :model-value="row.name"
            size="small"
            :placeholder="`留空显示 v${row.versionNo}`"
            @change="(v) => saveMeta(row, { name: v })"
          />
        </template>
      </el-table-column>

      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            :disabled="row.isDefault"
            active-text="启用"
            inactive-text="停用"
            inline-prompt
            @update:model-value="(v) => saveMeta(row, { status: v ? 1 : 0 })"
          />
          <el-tag v-if="row.isDefault" size="small" type="success" effect="plain">默认</el-tag>
        </template>
      </el-table-column>

      <el-table-column label="更新时间" width="160" prop="updateTime" />

      <el-table-column label="操作" width="230" fixed="right">
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
          <el-button link type="primary" size="small" @click="doCheck(row)">校验</el-button>
          <el-button link type="danger" size="small" :disabled="row.isDefault" @click="doRemove(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="tips foot">
      <p>
        <b>区间是推导出来的</b>：只存起点，右端是下一个启用版本的起点，<b>左闭右开</b> ——
        5 月 1 号那天走的是「从 5/1 起」的那一版。
      </p>
      <p>
        <b>停用</b>不参与自动选择，它那段会被前一版吞掉（临时回滚版式就靠这个）；
        <b>默认版本</b>是判定值取不到时的兜底，不能停用、不能删除。
      </p>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
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

const versions = ref([])
const loading = ref(false)

/** 带上推导出来的生效区间 */
const rows = computed(() => versionIntervals(versions.value))

watch(visible, (v) => {
  if (v) load()
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

/** 改元信息：三个字段一起提交（后端走 set()，effectiveFrom 才清得掉） */
async function saveMeta(row, patch) {
  try {
    await updateVersion({
      id: row.id,
      name: row.name,
      effectiveFrom: row.effectiveFrom,
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
.mono {
  margin-right: 6px;
}
</style>

<style>
/* 体检结果是多行文本，弹框里要保留换行 */
.mz-pre-wrap .el-message-box__message {
  white-space: pre-wrap;
}
</style>
