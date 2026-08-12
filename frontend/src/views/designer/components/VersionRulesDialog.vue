<!--
  版本的**匹配条件**：时间之外的那几维（单据类型、区域…）。

  时间那一维（生效起始推出来的区间）只说得清「什么时候起换版式」，说不清
  「类型 A 走这版、类型 B 走那版」。所以每一版再挂一组条件，同一版内**多条是 AND**；
  渲染时**条件先筛、时间后推**，多版同时匹配时**条件更具体的压过更宽泛的**（见 CONTRACT §4.1）。

  字段下拉给的是主接口的字段，但**允许手输**（allow-create）—— 报表可能还没设主接口，
  或者用的是接口返回里没被「解析字段」收进来的那一个。
-->
<template>
  <el-dialog v-model="visible" :title="`匹配条件 · ${label}`" width="720px" :close-on-click-modal="false">
    <div class="tips">
      这一版在什么情况下生效。多条之间是<b>「并且」</b>；一条都不配 = <b>无条件</b>（任何数据都匹配，
      是别的版本都不匹配时的兜底）。
    </div>

    <el-table :data="rules" size="small" border empty-text="没有条件 —— 这一版任何数据都匹配">
      <el-table-column label="取值" width="130">
        <template #default="{ row }">
          <el-select v-model="row.source" size="small">
            <el-option v-for="s in RULE_SOURCES" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="字段 / 参数" min-width="180">
        <template #default="{ row }">
          <el-select
            v-model="row.field"
            size="small"
            filterable
            allow-create
            default-first-option
            placeholder="选一个，也可以直接输入"
            style="width: 100%"
          >
            <el-option
              v-for="o in optionsOf(row)"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="比较" width="160">
        <template #default="{ row }">
          <el-select v-model="row.op" size="small">
            <el-option v-for="o in RULE_OPS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </template>
      </el-table-column>

      <el-table-column label="值" min-width="180">
        <template #default="{ row }">
          <el-input
            v-if="needValue(row)"
            v-model="row.value"
            size="small"
            :placeholder="placeholderOf(row)"
          />
          <span v-else class="text-muted">—</span>
        </template>
      </el-table-column>

      <el-table-column label="" width="60" fixed="right">
        <template #default="{ $index }">
          <el-button link type="danger" size="small" @click="rules.splice($index, 1)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="ops">
      <el-button size="small" @click="add">添加条件</el-button>
      <span class="text-muted">{{ preview }}</span>
    </div>

    <div class="tips foot">
      <p>
        值比较<b>忽略大小写与首尾空格</b>，两边都是数字时按数值比（<code>1</code> 与
        <code>1.0</code> 相等）；<b>属于/不属于</b>的值写成 <code>华东,华南</code> 这样一串。
      </p>
      <p>
        多版同时匹配时<b>条件更具体的赢</b>（「类型=A 且 区域=华东」压过「类型=A」，都压过无条件的那版）；
        条件一样的几版之间才按<b>生效时间</b>分先后。
      </p>
      <p v-if="splitByRow">
        按数据拆单据时条件是<b>逐条数据</b>判的 —— 一批单据里类型 A 和类型 B 各自出自己那一版。
      </p>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useDesignerStore } from '@/stores/designer'
import { RULE_OPS, RULE_SOURCES, parseRules, rulesText, versionLabel } from '@/utils/version'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 正在编辑哪一版（版本管理页那一行） */
  version: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const store = useDesignerStore()
const rules = ref([])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})
const label = computed(() => versionLabel(props.version))
const preview = computed(() => rulesText(rules.value) || '无条件（任何数据都匹配）')
const splitByRow = computed(() => ['perRow', 'perRowPage'].includes(store.content.splitMode))

/** 主接口的字段；没设主接口时为空，靠 allow-create 手输 */
const primaryFields = computed(() => store.datasetByCode(store.content.primaryDataset)?.fields || [])
const reportParams = computed(() => store.content.params || [])

// 编辑的是副本，取消不污染那一行
watch(visible, (v) => {
  if (!v) return
  rules.value = parseRules(props.version?.matchRules).map((r) => ({
    source: r.source || 'field',
    field: r.field || '',
    op: r.op || 'eq',
    value: r.value == null ? '' : String(r.value)
  }))
})

function optionsOf(row) {
  if (row.source === 'param') {
    return reportParams.value.map((p) => ({ value: p.name, label: `${p.text || p.name} (${p.name})` }))
  }
  return primaryFields.value.map((f) => ({
    value: f.fieldName,
    label: `${f.fieldText || f.fieldName} (${f.fieldName})`
  }))
}

function needValue(row) {
  return (RULE_OPS.find((o) => o.value === row.op) || RULE_OPS[0]).needValue
}

function placeholderOf(row) {
  return ['in', 'notIn'].includes(row.op) ? '多个值用逗号分隔，如 华东,华南' : '要比对的值'
}

function add() {
  rules.value.push({ source: 'field', field: '', op: 'eq', value: '' })
}

function onConfirm() {
  // 字段名空的那几行是没填完的，丢掉（后端 normalizeRules 也会再丢一次）；
  // 一条不剩就还空串 —— 「无条件匹配」，不是「留着上次那份」
  const keep = rules.value
    .filter((r) => r.field && r.field.trim())
    .map((r) => ({ source: r.source || 'field', field: r.field.trim(), op: r.op || 'eq', value: r.value || '' }))
  emit('confirm', keep.length ? JSON.stringify(keep) : '')
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
.ops {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  font-size: 12px;
}
</style>
