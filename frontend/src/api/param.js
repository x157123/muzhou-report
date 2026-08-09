import request from './request'

/** 全局参数（mz_param），见 CONTRACT §3.5。所有报表共用，不属于任何一张报表 */
export const pageParam = (params) => request.get('/param/page', { params })
export const listParam = () => request.get('/param/list')
export const getParam = (id) => request.get(`/param/${id}`)
export const createParam = (data) => request.post('/param', data)
export const updateParam = (data) => request.put('/param', data)
export const deleteParam = (id) => request.delete(`/param/${id}`)
