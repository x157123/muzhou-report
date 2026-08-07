<!--
  表达式语法帮助弹窗：静态说明，来自 docs/CONTRACT.md §5、§6。
-->
<template>
  <el-dialog v-model="visible" title="表达式帮助" width="760px" :close-on-click-modal="false">
    <h4>单元格表达式语法</h4>
    <el-table :data="syntaxRows" border size="small" style="margin-bottom: 20px">
      <el-table-column prop="syntax" label="语法" width="220" class-name="mono" />
      <el-table-column prop="desc" label="含义" />
    </el-table>

    <h4>Aviator 表达式可用上下文</h4>
    <el-descriptions border :column="1" size="small" style="margin-bottom: 20px">
      <el-descriptions-item label="params">Map，报表参数</el-descriptions-item>
      <el-descriptions-item label="row">当前展开行的数据 Map（仅在扩展带内可用）</el-descriptions-item>
      <el-descriptions-item label="ds">Map&lt;datasetCode, List&lt;Map&gt;&gt; 全量数据</el-descriptions-item>
    </el-descriptions>

    <h4>内置函数</h4>
    <el-table :data="functionRows" border size="small" style="margin-bottom: 20px">
      <el-table-column prop="fn" label="函数" width="220" class-name="mono" />
      <el-table-column prop="desc" label="说明" />
    </el-table>

    <h4>示例</h4>
    <ul class="example-list">
      <li><span class="mono">#{orders.amount}</span> — 引用数据集 orders 的 amount 字段</li>
      <li><span class="mono">${startDate}</span> — 引用报表参数 startDate</li>
      <li><span class="mono">!{SUM("B2:B10")}</span> — 对渲染后网格 B2:B10 区间求和</li>
      <li><span class="mono">!{IF(row.amount &gt; 100, "大额", "普通")}</span> — 按当前行数据判断</li>
      <li><span class="mono">=SUM(B2:B5)</span> — 原生 Excel 公式，扩展时自动偏移引用</li>
    </ul>

    <template #footer>
      <el-button type="primary" @click="visible = false">我知道了</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const syntaxRows = [
  { syntax: '#{datasetCode.fieldName}', desc: '数据单元格，按 cellConfig 的 expandType 扩展' },
  { syntax: '${paramName}', desc: '报表参数替换（文本内）' },
  { syntax: '!{aviator 表达式}', desc: '服务端 Aviator 计算，可用函数见下表' },
  { syntax: '=SUM(B2:B5)', desc: '原生公式，扩展时自动偏移引用' },
  { syntax: '纯文本', desc: '原样输出' }
]

const functionRows = [
  { fn: 'SUM(range)', desc: '区间求和，如 SUM("A2:A10")' },
  { fn: 'AVG(range)', desc: '区间平均值' },
  { fn: 'MAX(range) / MIN(range)', desc: '区间最大 / 最小值' },
  { fn: 'COUNT(range)', desc: '区间计数' },
  { fn: 'IF(c, a, b)', desc: '条件判断' },
  { fn: 'ROUND(n, d)', desc: '四舍五入，d 为小数位数' },
  { fn: 'CONCAT(...)', desc: '字符串拼接' },
  { fn: 'DATEFMT(date, pattern)', desc: '日期格式化' },
  { fn: 'NUMFMT(num, pattern)', desc: '数值格式化' },
  { fn: 'NVL(a, b)', desc: 'a 为空时取 b' },
  { fn: 'ABS(n)', desc: '绝对值' },
  { fn: 'PERCENT(a, b)', desc: 'a/b 的百分比' }
]
</script>

<style scoped>
h4 {
  margin: 12px 0 8px;
}
.example-list {
  margin: 0;
  padding-left: 20px;
  line-height: 2;
}
</style>
