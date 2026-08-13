import request from './request'

/**
 * 上传字体（mz_font），见 CONTRACT §3.6。系统级、所有报表共用。
 *
 * 设计器能选的字体是一份写死的清单（utils/fontList.js），而三条出纸的路各要各的东西：
 * 画布要浏览器有这款字体、xlsx/docx 里只写得进**字体名**（打开文件的机器得有），
 * **PDF 必须把字体文件本身内嵌进去**。所以服务器上没有的字体传一份上来，三条路才齐。
 */
export const pageFont = (params) => request.get('/font/page', { params })
export const listFont = () => request.get('/font/list')
export const updateFont = (data) => request.put('/font', data)
export const deleteFont = (id) => request.delete(`/font/${id}`)

/**
 * 传一款字体。
 *
 * **批量上传就是把它串行调多遍**（见 views/font/FontList.vue）—— 接口本身保持一款一次是有意的：
 * 每款都要在服务端真用 PDF 引擎加载一遍才收（FontServiceImpl#checkLoadable），一批里混进一款
 * 禁止嵌入的商业字体时，该报的是「这一款不行」而不是整批回滚。并发也不合适：一款十几 MB，
 * 十款一起发能把带宽和服务端内存一起打满。
 *
 * @param file     字体文件（.ttf / .otf / .ttc）
 * @param fontName 字体名，就是单元格样式里 ff 存的那个字符串，全局唯一
 * @param ttcIndex .ttc 字体集里用第几款，其余格式忽略
 * @param remark   备注
 * @param silent   true = 失败时不弹全局提示，由调用方自己显示（批量时逐行标在各自那一行上）
 */
export const uploadFont = ({ file, fontName, ttcIndex, remark, silent = false }) => {
  const form = new FormData()
  form.append('file', file)
  // 字体文件动辄十几 MB，别用默认的 120s 卡在半路
  return request.post('/font/upload', form, {
    params: { fontName, ttcIndex, remark },
    timeout: 180000,
    silent
  })
}

/**
 * 字体文件的地址，给 `@font-face` 的 `src` 用。
 *
 * **不走 axios** —— 这个地址是浏览器自己按 CSS 里的 URL 去拉的，所以要把 baseURL 拼进去
 * （axios 实例上那个 baseURL 只对经过它的请求生效）。
 */
export const fontFileUrl = (id) => `${request.defaults.baseURL || '/api'}/font/${id}/file`
