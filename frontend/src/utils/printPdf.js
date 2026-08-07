/**
 * 打印一份 PDF（后端出的那份），而不是打浏览器里的网页。
 *
 * 报表的出纸规则（纸张 / 方向 / 页边距 / 缩放 / 打印区域 / 页头页尾 / 水印）只有导出那条路
 * 是完整实现的 —— 浏览器打印给不出页头页尾和页码，表格又是一整张 canvas，分页位置也只能靠
 * `@page` 大致对齐。所以「打印」= 取一份后端 PDF 再交给浏览器打印，与导出的 PDF 一模一样。
 *
 * 做法是把 blob 挂到一个隐藏 iframe 上再 `contentWindow.print()`：
 * 打印的是 iframe 里的 PDF 文档本身，页面上的 `@page` / DOM 都不参与。
 */

/** 上一次打印用的 iframe 与它的 blob URL —— 打印对话框还开着时不能回收，只能等下一次打印或离开页面 */
let frame = null
let frameUrl = ''

/**
 * 打印一份 PDF blob。
 *
 * @param {Blob} blob PDF 字节流
 * @returns {Promise<void>} 打印对话框已弹出即 resolve；打不开（Safari 等不允许打 iframe 里的
 *          PDF）则 reject，由调用方兜底（例如改为在新标签页里打开）
 */
export function printPdfBlob(blob) {
  disposePrintFrame()
  const url = URL.createObjectURL(blob)
  const iframe = document.createElement('iframe')
  // 不能用 display:none —— 那样 Chrome 根本不会去加载 PDF 阅读器，print() 打出来是一片空白。
  // 尺寸置 0 + visibility:hidden 才既看不见、又实实在在渲染了一遍
  iframe.style.cssText =
    'visibility:hidden;position:absolute;left:0;top:0;width:0;height:0;border:0'
  iframe.setAttribute('aria-hidden', 'true')
  frame = iframe
  frameUrl = url

  return new Promise((resolve, reject) => {
    iframe.onload = () => {
      try {
        // 不 focus 的话 Firefox 会把打印命令发给外层页面
        iframe.contentWindow.focus()
        iframe.contentWindow.print()
        resolve()
      } catch (e) {
        reject(e)
      }
    }
    iframe.onerror = () => reject(new Error('PDF 加载失败'))
    iframe.src = url
    document.body.appendChild(iframe)
  })
}

/**
 * 回收上一次打印用的 iframe。
 *
 * 打印对话框是模态的，`print()` 之后 URL 一旦被 revoke，用户在对话框里改设置重排就没内容了 ——
 * 所以只在「开始下一次打印」和「离开页面」这两个时机回收。
 */
export function disposePrintFrame() {
  if (frame) {
    frame.remove()
    frame = null
  }
  if (frameUrl) {
    URL.revokeObjectURL(frameUrl)
    frameUrl = ''
  }
}
