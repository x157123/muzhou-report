import request from './request'

/**
 * 渲染已保存报表 -> { sheets, elapsed, message, total, versionId, versionNo, versionName, versionMatch }
 *
 * @param versionId 用哪一版版式，省略 = 按报表的版本切换规则自动选。
 *                  **只能走请求体**：挂在地址栏上会被 utils/params.js#queryParams
 *                  当成报表参数透传给数据集（CONTRACT §5）
 */
export const renderReport = (id, params, versionId) =>
  request.post(`/render/report/${id}`, { params, versionId })

/**
 * 设计器免保存预览：直接把当前 content 发给后端渲染。
 *
 * reportId 不能省 —— 内部数据集按报表隔离，后端得知道是谁在预览才解析得出 `#{code.字段}`。
 * versionId 只是回显（渲染的就是传过去的这份 content，不走版本选择）。
 */
export const renderPreview = (reportId, content, params, versionId) =>
  request.post('/render/preview', { reportId, content, params, versionId })

/** 报表参数定义 */
export const getReportParams = (id) => request.get(`/render/report/${id}/params`)

/** 导出 Excel，返回 axios 原始 response（blob） */
export const exportExcel = (id, params, versionId) =>
  request.post(`/render/report/${id}/export/excel`, { params, versionId }, { responseType: 'blob' })

/**
 * 导出 PDF，返回 axios 原始 response（blob）。
 *
 * 后端先出 xlsx 再转 PDF，大报表渲染慢，所以单独放宽超时，不用默认的 120s。
 *
 * @param sheetIndex 只要**渲染结果**里的第几张 sheet，省略 = 整本。
 *
 * 预览页看到的就是这份 PDF（默认视图，整本、不传 sheetIndex），「打印」按钮打的也是它；
 * 切到表格视图后打印才带上当前那张工作表的下标。
 */
export const exportPdf = (id, params, sheetIndex, versionId) =>
  request.post(
    `/render/report/${id}/export/pdf`,
    { params, sheetIndex, versionId },
    { responseType: 'blob', timeout: 180000 }
  )

/**
 * 导出 Word(.docx)，返回 axios 原始 response（blob）。
 *
 * 与 PDF 同一条路（先出 xlsx 再转），版式一致，超时也一样放宽。
 */
export const exportWord = (id, params, versionId) =>
  request.post(`/render/report/${id}/export/word`, { params, versionId }, { responseType: 'blob', timeout: 180000 })
