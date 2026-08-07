/**
 * FortuneSheet 的字体清单。
 *
 * 官方 zh 语言包里那份 `fontarray` 是「Times New Roman, Arial, Tahoma, Verdana, 微软雅黑, 宋体, …」
 * —— 四个英文字体排在最前面。中文报表用不上，所以这里整份换掉：中文在前、英文收到最后。
 *
 * **第一项就是默认字体**：单元格没设 `ff` 时，core 的 `getFontSet` 取的是 `fontarray[0]`，
 * 所以把宋体放在第一位既是下拉里的第一项，也是画布上的默认字体。
 * 清单本身没有增删，只是换了顺序 —— 老报表里存的 `ff` 是字体名（工具栏写入的就是名字），
 * 顺序变了不影响它们。
 *
 * 改这份清单时注意 `utils/wrapHeight.js` 也引了 `DEFAULT_FONT`：
 * 它按同一套字体量文字算行高，两边必须是同一个默认值，否则行高与画布对不上。
 */
import { locale } from '@fortune-sheet/core'

export const FONT_LIST = [
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
export const DEFAULT_FONT = FONT_LIST[0]

/**
 * 把清单装进 core 的语言包。
 *
 * `locale(ctx)` 返回的就是语言包对象本身（不是拷贝），改它即改全局；
 * 工具栏的字体下拉与 `getFontSet` 都是每次现取，所以在挂载工作簿之前调一次就够了。
 */
export function applyFontList(lang = 'zh') {
  const pack = locale({ lang })
  if (pack) pack.fontarray = FONT_LIST.slice()
}
