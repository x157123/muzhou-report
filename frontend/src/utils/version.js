/**
 * 报表版本的小工具：显示名、匹配条件、生效区间与重叠提示。
 *
 * 生效区间**两端都由用户填**（`effectiveFrom` ~ `effectiveTo`，左闭右开，两端都可空 = 不限），
 * 所以这里不再推导右端。**允许多版共用同一段时间**，重叠时的胜负规则必须和后端
 * `ReportVersionResolver#covers` 一致：**起点更晚的赢，起点相同则版本号大的赢**；
 * **停用的版本不参与**。这份规则在界面上只用来提示「这一段其实归谁」——
 * 用户配得出重叠，就得看得见重叠。
 *
 * 版本还有**匹配条件**这一维（类型/区域这类离散维度，见 CONTRACT §4.1）：条件先筛、时间后判。
 * 所以**重叠只在「条件相同的那几版」之间算**（`versionIntervals` 按条件分组）——
 * 「类型 A 的 5 月版」和「类型 B 的 5 月版」时间上叠着，却根本不竞争，报重叠是误导。
 */

/** 条件的比较方式，与后端 `VersionMatchRuleDTO` 的 op 一一对应 */
export const RULE_OPS = [
  { value: 'eq', label: '等于', symbol: '=', needValue: true },
  { value: 'ne', label: '不等于', symbol: '≠', needValue: true },
  { value: 'in', label: '属于（逗号分隔）', symbol: '∈', needValue: true },
  { value: 'notIn', label: '不属于（逗号分隔）', symbol: '∉', needValue: true },
  { value: 'contains', label: '包含', symbol: '包含', needValue: true },
  { value: 'empty', label: '为空', symbol: '为空', needValue: false },
  { value: 'notEmpty', label: '不为空', symbol: '不为空', needValue: false }
]

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

/** 条件的指纹：条件相同的版本才在同一条时间轴上竞争（重叠也只在这几版之间算） */
function ruleKey(version) {
  return validRules(version.matchRules)
    .map((r) => `${r.source || 'field'}|${r.field}|${r.op || 'eq'}|${r.value ?? ''}`)
    .join('&&')
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

/** 重叠时谁赢：起点更晚的赢，起点相同则版本号大的赢（同后端 `decide` 的排序） */
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
 * 那就得告诉用户重叠那一段实际归谁。判断只在「匹配条件相同」的那几版之间做 ——
 * 条件不同的版本压根不竞争（后端也是先按条件筛出一批再在那批里判）。
 *
 * @param versions 版本列表（后端 /report/{id}/version 的返回）
 * @returns [{ ...version, label, from, to, enabled, isDefault, rules, condition, coveredBy, note }]，
 *          顺序同传入。`from` / `to` 是字符串（空 = 那一端不限），`coveredBy` 是盖住它的版本名
 */
export function versionIntervals(versions) {
  const list = Array.isArray(versions) ? versions : []
  const groups = new Map()
  list
    .filter((v) => v.status !== 0)
    .forEach((v) => {
      const key = ruleKey(v)
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key).push(v)
    })

  // 每一版被同组里哪几版盖过（重叠且对方赢）
  const covered = {}
  groups.forEach((group) => {
    group.forEach((v) => {
      const winners = group
        .filter((o) => o.id !== v.id && overlaps(v, o) && beats(o, v))
        .map(versionLabel)
      if (winners.length) covered[v.id] = winners
    })
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
          ? `重叠段被 ${winners.join('、')} 盖过（起点更晚的赢）`
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
