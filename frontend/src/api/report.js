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

/* ------------------------------ 版本 ------------------------------ */

/** 版本列表（不含 content），按 versionNo 升序 */
export const listVersion = (id) => request.get(`/report/${id}/version`)

/** 从某一版复制出新版本（停用、无生效时间），返回新版本 id */
export const copyVersion = (id, versionId) => request.post(`/report/${id}/version/${versionId}/copy`)

/** 改版本元信息 { id, name, effectiveFrom, status, remark } */
export const updateVersion = (data) => request.put('/report/version', data)

/** 设为默认（基准）版本 */
export const setDefaultVersion = (id, versionId) =>
  request.post(`/report/${id}/version/${versionId}/default`)

/** 删除版本（默认版本、最后一个启用版本不许删） */
export const deleteVersion = (versionId) => request.delete(`/report/version/${versionId}`)

/** 体检某一版：引用的数据集/字段是否还在、与默认版本之间那几项设置是否一致 */
export const checkVersion = (id, versionId) => request.get(`/report/${id}/version/${versionId}/check`)
