import request from './request'

export const pageReport = (params) => request.get('/report/page', { params })

/**
 * 报表详情。
 *
 * @param versionId 要哪一版的版式，省略 = 默认版本。响应里的 versionId 说明拿到的是哪一版
 */
export const getReport = (id, versionId) =>
  request.get(`/report/${id}`, { params: versionId ? { versionId } : {} })

export const createReport = (data) => request.post('/report', data)

/** 更新报表；data 里带 versionId 表示把 content 写进那一版（省略 = 默认版本） */
export const updateReport = (data) => request.put('/report', data)

export const deleteReport = (id) => request.delete(`/report/${id}`)
export const copyReport = (id) => request.post(`/report/${id}/copy`)

/* ------------------------------ 导入导出 ------------------------------ */

/**
 * 导出报表包（一张或多张），返回 axios 原始 response（blob）。
 *
 * 包里是报表定义 + 全部版式 + 内部数据集 + 引用到的公共数据集，**按 code 引用不带主键**，
 * 所以能直接带进另一个环境。数据源不进包（口令导不出来，两个环境的连接本来就不一样）。
 */
export const exportReports = (ids) => request.post('/report/export', ids, { responseType: 'blob' })

/**
 * 导入报表包。
 *
 * @param mode 目标环境已有同编码报表时怎么办：skip 跳过 / overwrite 覆盖 / copy 另建一张
 * @returns { created, updated, skipped, failed, items:[{name, code, action, message, warnings}] }
 */
export const importReports = (file, mode) => {
  const form = new FormData()
  form.append('file', file)
  // 大报表包传得慢，别用默认的 120s 卡在半路
  return request.post('/report/import', form, { params: { mode }, timeout: 180000 })
}

/**
 * 把一份 xlsx 解析成报表版式（**只解析、不落库**）。
 *
 * 只做静态版式：返回的 content 里 cellConfigs 恒为空，占位符与取数由用户在设计器里手工配。
 *
 * @returns { content, warnings } —— warnings 是「哪些东西没带过来」，要原样给用户看
 */
export const parseExcelTemplate = (file) => {
  const form = new FormData()
  form.append('file', file)
  // 大表解析比普通请求慢，别用默认的 120s 卡在半路
  return request.post('/report/parse-excel', form, { timeout: 180000 })
}

/* ------------------------------ 版本 ------------------------------ */

/** 版本列表（不含 content），按 versionNo 升序 */
export const listVersion = (id) => request.get(`/report/${id}/version`)

/** 从某一版复制出新版本（停用、生效时间段清空），返回新版本 id */
export const copyVersion = (id, versionId) => request.post(`/report/${id}/version/${versionId}/copy`)

/** 改版本元信息 { id, name, effectiveFrom, effectiveTo, matchRules, status, remark }；两端为 null = 不限 */
export const updateVersion = (data) => request.put('/report/version', data)

/** 设为默认（基准）版本 */
export const setDefaultVersion = (id, versionId) =>
  request.post(`/report/${id}/version/${versionId}/default`)

/** 删除版本（默认版本、最后一个启用版本不许删） */
export const deleteVersion = (versionId) => request.delete(`/report/version/${versionId}`)

/** 体检某一版：引用的数据集/字段是否还在、与默认版本之间那几项设置是否一致 */
export const checkVersion = (id, versionId) => request.get(`/report/${id}/version/${versionId}/check`)
