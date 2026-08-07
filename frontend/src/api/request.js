import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 120000
})

request.interceptors.request.use(
  (config) => config,
  (error) => Promise.reject(error)
)

const isBinary = (config) =>
  config?.responseType === 'blob' || config?.responseType === 'arraybuffer'

/**
 * 导出接口失败时，后端返回的仍是 HTTP 200 + Result JSON（见 GlobalExceptionHandler），
 * 但 responseType 是 blob，body 就是一个装着 JSON 的 Blob。不识别出来的话，
 * 这段错误 JSON 会被当成文件下载，用户拿到一个打不开的 .xlsx / .pdf / .docx 而看不到原因
 * （典型场景：报表太大或字体缺失，导出 PDF 失败）。
 *
 * @returns 错误信息；不是错误结构（即正常的文件流）时返回 null
 */
async function readErrorMsg(data) {
  let text = null
  if (data instanceof Blob) {
    // 只有 JSON 才读成文本，避免把几十兆的 xlsx 二进制解码一遍
    if (!String(data.type || '').includes('json')) return null
    text = await data.text()
  } else if (data instanceof ArrayBuffer) {
    text = new TextDecoder('utf-8').decode(data)
  } else if (data && typeof data === 'object') {
    return 'code' in data && data.code !== 0 ? data.msg || '请求失败' : null
  }
  if (!text) return null
  try {
    const body = JSON.parse(text)
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      return body.msg || '请求失败'
    }
  } catch (e) {
    // 解析不了就是正常的文件流
  }
  return null
}

/**
 * 统一解包 { code, msg, data }：成功返回 data，失败弹提示并 reject。
 */
request.interceptors.response.use(
  async (response) => {
    // 二进制流（导出）直接返回原始 response，但要先排除「200 里装着错误结构」的情况
    if (isBinary(response.config)) {
      const msg = await readErrorMsg(response.data)
      if (msg) {
        ElMessage.error(msg)
        return Promise.reject(new Error(msg))
      }
      return response
    }
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return body
  },
  async (error) => {
    // 非 2xx 时 blob 请求的 error.response.data 同样是 Blob，直接取 .msg 拿不到东西
    const msg =
      (isBinary(error.config) ? await readErrorMsg(error.response?.data) : null) ||
      error.response?.data?.msg ||
      error.message ||
      '网络异常'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
