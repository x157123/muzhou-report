/**
 * 报表版本的小工具：显示名、匹配条件、生效区间与重叠提示。
 *
 * 生效区间**两端都由用户填**（`effectiveFrom` ~ `effectiveTo`，左闭右开，两端都可空 = 不限），
 * 所以这里不再推导右端。**允许多版共用同一段时间**，谁先被试到的规则必须和后端
 * `ReportVersionResolver#BY_TIME_PRECEDENCE` 一致：**起点更晚的先，起点相同则版本号大的先**；
 * **停用的版本不参与**。
 *
 * 选版本是**先按生效时间圈候选、再按匹配条件定唯一一份**（CONTRACT §4.1）。所以
 * **「先轮到」不等于「赢」**：排在前面那一版的条件不满足时，仍会轮到被它盖住的那一版。
 * 于是重叠提示只在**盖住它的那一版是「无条件」**（任何数据都满足，后面的永远轮不到）时才报
 * —— 盖住它的那版自己也带条件的话，被盖的那版仍有机会，报重叠是误导。
 * 早先是「按条件指纹分组、只在同组内算重叠」，那是「条件先筛 + 特异度优先」时代的规则，
 * 与现在的算法对不上了。
 */

/**
 * 条件的比较方式，与后端 `VersionMatchRuleDTO` 的 op 一一对应。
 *
 * `numeric` 那四个（大于/大于等于/小于/小于等于）**只认数字**：两边任一不是数字时后端
 * 跳过这一条（视为满足），界面上的说明也要照这个讲。
 */
export const RULE_OPS = [
  { value: 'eq', label: '等于', symbol: '=', needValue: true },
  { value: 'ne', label: '不等于', symbol: '≠', needValue: true },
  { value: 'in', label: '属于（逗号分隔）', symbol: '∈', needValue: true },
  { value: 'notIn', label: '不属于（逗号分隔）', symbol: '∉', needValue: true },
  { value: 'contains', label: '包含', symbol: '包含', needValue: true },
  { value: 'gt', label: '大于（数字）', symbol: '>', needValue: true, numeric: true },
  { value: 'ge', label: '大于等于（数字）', symbol: '≥', needValue: true, numeric: true },
  { value: 'lt', label: '小于（数字）', symbol: '<', needValue: true, numeric: true },
  { value: 'le', label: '小于等于（数字）', symbol: '≤', needValue: true, numeric: true },
  { value: 'empty', label: '为空', symbol: '为空', needValue: false },
  { value: 'notEmpty', label: '不为空', symbol: '不为空', needValue: false }
]

/** 这个 op 是不是只认数字的那四个（界面上的提示与占位符按它分岔） */
export function isNumericOp(op) {
  return !!(RULE_OPS.find((o) => o.value === op) || {}).numeric
}

/** 条件里那个值从哪来 */
export const RULE_SOURCES = [
  { value: 'field', label: '主接口字段' },
  { value: 'param', label: '报表参数' }
]

/** 匹配条件存的是 JSON 串（同 report.versionConfig）；解析不了当作没配，别让界面白屏 */
export function parseRules(matchRules) {
  if (!matchRules) return []
  if (Array.isArray(matchRules)) return matchRules
  try {
    const list = JSON.parse(matchRules)
    return Array.isArray(list) ? list : []
  } catch (e) {
    return []
  }
}

/** 字段名还空着的那几行是没填完的，不算数（后端 `VersionMatchRuleDTO#isValid` 同款） */
export function validRules(rules) {
  return parseRules(rules).filter((r) => r && r.field && String(r.field).trim())
}

/** 一条条件的人话：`order_type=A`、`area∈华东,华南`（与后端 describe 保持一致） */
export function ruleText(rule) {
  if (!rule) return ''
  const op = RULE_OPS.find((o) => o.value === (rule.op || 'eq')) || RULE_OPS[0]
  const field = rule.field || ''
  const value = rule.value == null ? '' : String(rule.value).trim()
  if (!op.needValue) return `${field}${op.symbol}`
  return `${field}${op.symbol}${value}`
}

/** 一组条件连成一句：`order_type=A 且 area∈华东,华南`，没配条件就是空串 */
export function rulesText(rules) {
  return validRules(rules)
    .map(ruleText)
    .join(' 且 ')
}

/** 无条件 = 任何数据都匹配：它一旦排在前面，后面同段的版本就永远轮不到了 */
function unconditional(version) {
  return validRules(version.matchRules).length === 0
}

/** 界面上的名字：没起名就是 v3 */
export function versionLabel(v) {
  if (!v) return ''
  return v.name && v.name.trim() ? v.name : `v${v.versionNo}`
}

/** 只留日期那一段：生效时间基本都是 00:00:00，整串显示反而看不清 */
export function shortTime(t) {
  if (!t) return ''
  const s = String(t)
  return s.endsWith(' 00:00:00') ? s.slice(0, 10) : s
}

/** 两个左闭右开区间叠着没有（两端空 = 那一端不限） */
function overlaps(a, b) {
  const aFrom = a.effectiveFrom || ''
  const aTo = a.effectiveTo || ''
  const bFrom = b.effectiveFrom || ''
  const bTo = b.effectiveTo || ''
  if (aTo && bFrom && aTo <= bFrom) return false
  if (bTo && aFrom && bTo <= aFrom) return false
  return true
}

/** 重叠时谁先被试到：起点更晚的先，起点相同则版本号大的先（同后端 `BY_TIME_PRECEDENCE`） */
function beats(a, b) {
  const av = a.effectiveFrom || ''
  const bv = b.effectiveFrom || ''
  if (av !== bv) {
    if (!av) return false
    if (!bv) return true
    return av > bv
  }
  return (a.versionNo || 0) > (b.versionNo || 0)
}

/**
 * 给每一版算出界面上要显示的那几样：区间、条件、以及**被谁盖住了**。
 *
 * 区间不再推导（两端都是用户填的），这里做的是**重叠提示**：允许多版共用同一段时间，
 * 那就得告诉用户重叠那一段实际归谁。选版本是「时间圈候选 + 条件定唯一」，所以
 * **只有排在前面的那一版是「无条件」时，被盖的那一版才真的永远轮不到** ——
 * 对方也带条件的话，它的条件不满足时仍会轮到这一版，报重叠是误导。
 *
 * @param versions 版本列表（后端 /report/{id}/version 的返回）
 * @returns [{ ...version, label, from, to, enabled, isDefault, rules, condition, coveredBy, note }]，
 *          顺序同传入。`from` / `to` 是字符串（空 = 那一端不限），`coveredBy` 是盖住它的版本名
 */
export function versionIntervals(versions) {
  const list = Array.isArray(versions) ? versions : []
  const active = list.filter((v) => v.status !== 0)

  // 每一版被哪几版真正挡死了：时间上叠着、对方先被试到、且对方无条件（任何数据都满足）
  const covered = {}
  active.forEach((v) => {
    const winners = active
      .filter((o) => o.id !== v.id && overlaps(v, o) && beats(o, v) && unconditional(o))
      .map(versionLabel)
    if (winners.length) covered[v.id] = winners
  })

  return list.map((v) => {
    const isEnabled = v.status !== 0
    const rules = validRules(v.matchRules)
    const winners = isEnabled ? covered[v.id] : null
    return {
      ...v,
      label: versionLabel(v),
      enabled: isEnabled,
      isDefault: v.isDefault === 1,
      rules,
      condition: rulesText(rules),
      from: shortTime(v.effectiveFrom),
      to: shortTime(v.effectiveTo),
      coveredBy: winners || [],
      note: isEnabled
        ? winners
          ? `重叠段被无条件的 ${winners.join('、')} 挡住（它起点更晚、又匹配任何数据）`
          : ''
        : '已停用，不参与自动选择'
    }
  })
}

/** 「2026-05-01 ~ 2026-08-01」这样的一句话，两端都空就是「全时段」 */
export function intervalText(item) {
  if (!item) return ''
  if (!item.enabled) return item.note
  if (!item.from && !item.to) return '全时段'
  return `${item.from || ''} ~ ${item.to || ''}`
}

/**
 * 「这一版什么时候用」的一句话（两维一起说），给只有一行位置的地方用（设计器顶栏的版本下拉）。
 *
 * 表格里两维各有一列，不必用这个。只配了条件、没配生效时间的版本单说「全时段」是误导 ——
 * 它并不是「什么时候都用」，而是「满足这个条件时才用」。
 */
export function scopeText(item) {
  if (!item) return ''
  if (!item.enabled) return item.note
  const range = intervalText(item)
  if (!item.condition) return range
  return range === '全时段' ? item.condition : `${item.condition}｜${range}`
}
