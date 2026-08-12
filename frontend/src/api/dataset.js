import request from './request'

/** 数据集管理页：只列公共数据集（内部数据集在各自报表的设计器里管理） */
export const pageDataset = (params) => request.get('/dataset/page', { params })
/**
 * 含 fields，用于设计器左侧字段树。
 *
 * @param reportId  当前报表 id —— 传了才拿得到该报表的内部数据集，不传只有公共数据集
 * @param versionId 当前设计的是哪一版 —— 传了才拿得到这一版自己的数据集（别的版本的不会回）
 */
export const listDataset = (reportId, versionId) =>
  request.get('/dataset/list', { params: { reportId, versionId } })
export const getDataset = (id) => request.get(`/dataset/${id}`)
export const createDataset = (data) => request.post('/dataset', data)
export const updateDataset = (data) => request.put('/dataset', data)
export const deleteDataset = (id) => request.delete(`/dataset/${id}`)
/** 解析 SQL / API / JSON，返回 { fields, params } */
export const parseDataset = (data) => request.post('/dataset/parse', data)
/** 预览数据，返回 { columns, records } */
export const previewDataset = (data) => request.post('/dataset/preview', data)
/** 按已保存的数据集取数 */
export const fetchDatasetData = (id, params) => request.post(`/dataset/${id}/data`, { params })
