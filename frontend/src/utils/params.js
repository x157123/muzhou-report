/**
 * 报表参数相关的小工具。
 */

/**
 * **保留参数名**：这些 query 不是报表参数，不能透传给数据集。
 *
 * `versionId` 是地址上用来保持「现在看的是哪一版」的状态（`/designer/{id}?versionId=xxx`），
 * 它必须走渲染请求体里的 `versionId` 字段 —— 不剔除的话会被当成报表参数塞进 SQL 的
 * `${versionId}` 里去（见 CONTRACT §5）。
 *
 * `pageNo` / `pageSize` 同样是保留名，但它们本来就由分页条在提交前重新赋值，不必在这里剔。
 */
export const RESERVED_QUERY = ['versionId']

/**
 * 把路由 query 归一化成 { name: string } 的参数值。
 *
 * 预览/设计器地址上的 query 参数会**原样透传给数据集**（见 CONTRACT §5）：
 * 报表里声明过的用它做表单初值，没声明过的也照样传下去 —— SQL 的 `${id}`、
 * API 地址里的 `${id}` 都取得到，嵌入方（iframe / 门户跳转）因此不必先在报表里
 * 把参数声明一遍。保留参数名（见上）除外。
 *
 * 两种边角情况：同名重复（`?a=1&a=2`）取最后一个，无值（`?flag`）当空串 ——
 * 后端参数值一律按字符串收，给个 null 反而要在每处判空。
 */
export function queryParams(query) {
  const out = {}
  Object.entries(query || {}).forEach(([key, value]) => {
    if (RESERVED_QUERY.includes(key)) return
    if (Array.isArray(value)) {
      const last = value[value.length - 1]
      out[key] = last == null ? '' : String(last)
    } else {
      out[key] = value == null ? '' : String(value)
    }
  })
  return out
}

/** 地址上带的版本 id（保留参数名，不进报表参数） */
export function queryVersionId(query) {
  const v = query?.versionId
  const raw = Array.isArray(v) ? v[v.length - 1] : v
  return raw == null ? '' : String(raw)
}
