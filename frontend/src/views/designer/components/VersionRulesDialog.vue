<!--
  版本的**匹配条件**：时间之外的那几维（单据类型、区域…）。

  时间那一维（生效区间）只说得清「什么时候起换版式」，说不清
  「类型 A 走这版、类型 B 走那版」。所以每一版再挂一组条件，同一版内**多条是 AND**；
  渲染时**先按生效时间圈出候选，再按条件从候选里定唯一一份** —— 候选里照「起点更晚的优先、
  同起点版本号大的优先」逐个试，第一个条件满足的即命中，**没有「条件更具体的赢」这回事**
  （见 CONTRACT §4.1）。

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

      <!-- 「大于等于（数字）」这几项比原来的长，窄了会被省略号截掉 -->
      <el-table-column label="比较" width="180">
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
        <b>大于 / 大于等于 / 小于 / 小于等于</b>只认数字（金额、数量这类阈值分版）；
        取到的值<b>不是数字</b>（为空、是文本）时<b>跳过这一条</b>，那一版就由别的条件与生效时间定，
        不会因此整版落空。
      </p>
      <p>
        选版本是<b>先按生效时间圈候选、再按条件定唯一</b>：候选里照「起点更晚的优先、同起点版本号
        大的优先」逐个试，<b>第一个条件满足的就是它</b>。<b>条件更具体的不会天然优先</b>
        （「类型=A 且 区域=华东」并不压过「类型=A」）—— 想让它先被试到，把它放在更大的版本号上。
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
import { ElMessage } from 'element-plus'
import {
  RULE_OPS,
  RULE_SOURCES,
  isNumericOp,
  parseRules,
  ruleText,
  rulesText,
  versionLabel
} from '@/utils/version'

/** 「一百」这种填错的值：那条条件渲染时会被整个跳过，得在确定时就说 */
function isNumber(v) {
  const s = String(v ?? '').trim()
  return s !== '' && !Number.isNaN(Number(s))
}

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
  if (['in', 'notIn'].includes(row.op)) return '多个值用逗号分隔，如 华东,华南'
  if (isNumericOp(row.op)) return '一个数字，如 100000'
  return '要比对的值'
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
  // 大小比较配了个不是数字的值：那条在渲染时会被整个跳过、从来不生效（后端也拦，这里先说一声）
  const bad = keep.find((r) => isNumericOp(r.op) && !isNumber(r.value))
  if (bad) {
    ElMessage.warning(`条件「${ruleText(bad)}」的值不是数字，大于/小于这几个只能跟数字比`)
    return
  }
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
