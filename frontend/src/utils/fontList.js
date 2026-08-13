/**
 * FortuneSheet 的字体清单 = 一份内置清单 + 用户上传的那些（CONTRACT §3.6）。
 *
 * 官方 zh 语言包里那份 `fontarray` 是「Times New Roman, Arial, Tahoma, Verdana, 微软雅黑, 宋体, …」
 * —— 四个英文字体排在最前面。中文报表用不上，所以这里整份换掉：中文在前、英文收到最后。
 *
 * **第一项就是默认字体**：单元格没设 `ff` 时，core 的 `getFontSet` 取的是 `fontarray[0]`，
 * 所以把宋体放在第一位既是下拉里的第一项，也是画布上的默认字体。**上传字体一律接在内置清单
 * 后面**，正是为了不动这一项 —— 传一款字体不该把所有老报表的默认字体换掉。
 *
 * 内置清单本身没有增删，只是换了顺序 —— 老报表里存的 `ff` 是字体名（工具栏写入的就是名字），
 * 顺序变了不影响它们。
 *
 * 改这份清单时注意 `utils/wrapHeight.js` 也引了 `DEFAULT_FONT`：
 * 它按同一套字体量文字算行高，两边必须是同一个默认值，否则行高与画布对不上。
 */
import { locale } from '@fortune-sheet/core'
import { listFont, fontFileUrl } from '@/api/font'

/** 内置字体清单：装了 Office 的 Windows 上基本都有，选了也印得出（PDF 那头见 export/PdfFonts） */
export const BUILTIN_FONTS = [
  '宋体',
  '黑体',
  '微软雅黑',
  '楷体',
  '仿宋',
  '新宋体',
  '华文新魏',
  '华文行楷',
  '华文隶书',
  'Times New Roman',
  'Arial',
  'Tahoma',
  'Verdana'
]

/** 没设 ff 的单元格用的字体，等于清单第一项 */
export const DEFAULT_FONT = BUILTIN_FONTS[0]

/** 已加载的上传字体，形如 `[{ id, fontName, fontFormat }]`，由 loadCustomFonts 填 */
let customFonts = []

/** `<style>` 节点的 id：重复加载时整块换掉，不往 head 里堆 */
const STYLE_ID = 'mz-custom-fonts'

/**
 * 等字体真下载完最多等这么久（毫秒）。
 *
 * 不等的话首屏那次量文字（wrapHeight 的 measureText）用的是兜底字体的字宽，算出来的行高
 * 与随后画出来的不是一回事；等太久又变成白屏 —— 一款中文字体十几 MB，头一回加载是要点时间的
 * （之后走浏览器缓存，接口那头挂了 30 天的 Cache-Control）。
 */
const FONT_READY_TIMEOUT = 5000

/** 当前完整的字体清单：内置 + 上传（同名的只留一份） */
export function fontNames() {
  return [...new Set([...BUILTIN_FONTS, ...customFonts.map((f) => f.fontName)])]
}

/**
 * 把清单装进 core 的语言包。
 *
 * `locale(ctx)` 返回的就是语言包对象本身（不是拷贝），改它即改全局；
 * 工具栏的字体下拉与 `getFontSet` 都是每次现取，所以**后来再改一次也是有效的** ——
 * loadCustomFonts 拿到列表后会再调一遍，此时已经挂载的工作簿下次打开下拉就能看到新字体。
 */
export function applyFontList(lang = 'zh') {
  const pack = locale({ lang })
  if (pack) pack.fontarray = fontNames()
}

/** ttf / otf 在 `@font-face` 里的 format() 名字，跟后缀名不是一回事 */
function faceFormat(format) {
  return format === 'otf' ? 'opentype' : 'truetype'
}

/**
 * 把上传字体注册成 `@font-face`，画布才画得出它们。
 *
 * **.ttc 跳过**：字体集是给桌面软件和 PDF 引擎用的，浏览器的 `@font-face` 不收它，
 * 硬写进去只会是一条静默失败的规则。所以 .ttc 字体在 PDF 里印得出、设计器画布上仍退回默认字体，
 * 字体管理页上对这种字体标了一句提示。
 */
function injectFaces(fonts) {
  const css = fonts
    .filter((f) => f.fontFormat !== 'ttc')
    .map(
      (f) =>
        `@font-face{font-family:"${f.fontName}";` +
        `src:url("${fontFileUrl(f.id)}") format("${faceFormat(f.fontFormat)}");font-display:swap;}`
    )
    .join('\n')
  let style = document.getElementById(STYLE_ID)
  if (!style) {
    style = document.createElement('style')
    style.id = STYLE_ID
    document.head.appendChild(style)
  }
  style.textContent = css
}

/**
 * 拉一次上传字体：并进字体清单、注册 `@font-face`、等浏览器真把它们下载下来。
 *
 * 挂在 `main.js` 的挂载之前 —— 设计器和预览页都要用，而且必须赶在第一次量文字之前
 * （见 FONT_READY_TIMEOUT 那段）。**失败不拦启动**：字体接口挂了顶多是少几款可选字体，
 * 不该连报表都打不开。
 */
export async function loadCustomFonts() {
  try {
    customFonts = (await listFont()) || []
  } catch (e) {
    // 已由拦截器提示；清单退回只有内置字体
    customFonts = []
    return
  }
  applyFontList()
  if (!customFonts.length || typeof document === 'undefined' || !document.fonts) return
  injectFaces(customFonts)

  // allSettled：一款字体的文件丢了不该拖住其余几款
  const loads = Promise.allSettled(
    customFonts
      .filter((f) => f.fontFormat !== 'ttc')
      .map((f) => document.fonts.load(`12pt "${f.fontName}"`))
  )
  await Promise.race([loads, new Promise((resolve) => setTimeout(resolve, FONT_READY_TIMEOUT))])
}
