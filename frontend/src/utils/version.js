/**
 * 报表版本的小工具：显示名与「生效区间」的推导。
 *
 * 用户配的是**起点**（这一版从哪天起生效），脑子里想的却是**区间**。区间由排序推出来，
 * 左闭右开 —— 存两端必然出现重叠和空洞，且改一处要改两处。这份推导必须和后端
 * `ReportVersionResolver` 一致：**停用的版本不参与**（它那段被前一版吞掉），
 * `effectiveFrom` 为空的那一版是「最早的那一版」（区间左端 -∞）。
 */

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

/**
 * 推导每一版的生效区间。
 *
 * @param versions 版本列表（后端 /report/{id}/version 的返回）
 * @returns [{ ...version, label, from, to, enabled, isDefault, note }]，顺序同传入
 *          `from` / `to` 是字符串（`to` 为空表示 +∞），停用的版本 note 说明它不参与
 */
export function versionIntervals(versions) {
  const list = Array.isArray(versions) ? versions : []
  const enabled = list
    .filter((v) => v.status !== 0)
    // 排序规则同后端：effectiveFrom 空的排最前，其次按时间，再按版本号
    .slice()
    .sort((a, b) => {
      const av = a.effectiveFrom || ''
      const bv = b.effectiveFrom || ''
      if (av !== bv) {
        if (!av) return -1
        if (!bv) return 1
        return av < bv ? -1 : 1
      }
      return (a.versionNo || 0) - (b.versionNo || 0)
    })

  const to = {}
  enabled.forEach((v, i) => {
    // 区间右端 = 下一个启用版本的起点（左闭右开），最后一个是 +∞
    to[v.id] = enabled[i + 1]?.effectiveFrom || ''
  })

  return list.map((v) => {
    const isEnabled = v.status !== 0
    return {
      ...v,
      label: versionLabel(v),
      enabled: isEnabled,
      isDefault: v.isDefault === 1,
      from: isEnabled ? shortTime(v.effectiveFrom) : '',
      to: isEnabled ? shortTime(to[v.id]) : '',
      note: isEnabled ? '' : '已停用，不参与自动选择'
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
