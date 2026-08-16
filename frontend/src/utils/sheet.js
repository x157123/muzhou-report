/**
 * FortuneSheet 数据结构与单元格配置的公共工具。
 * 设计器、预览页共用，请勿在各自页面里重复实现。
 */

/** cellConfigs 的 key 规则，见 docs/CONTRACT.md §4 */
export function cellKey(sheetIndex, r, c) {
  return `${sheetIndex}_${r}_${c}`
}

/** 列号 -> 字母（0 -> A, 26 -> AA） */
export function colToLetter(c) {
  let s = ''
  let n = c
  while (n >= 0) {
    s = String.fromCharCode((n % 26) + 65) + s
    n = Math.floor(n / 26) - 1
  }
  return s
}

/** 字母 -> 列号 */
export function letterToCol(letters) {
  let n = 0
  for (const ch of letters.toUpperCase()) {
    n = n * 26 + (ch.charCodeAt(0) - 64)
  }
  return n - 1
}

/** (r,c) -> "A1" */
export function toA1(r, c) {
  return `${colToLetter(c)}${r + 1}`
}

/** 生成一张空白工作表 */
export function createEmptySheet(index = 0) {
  return {
    name: index === 0 ? 'Sheet1' : `Sheet${index + 1}`,
    id: `sheet_${index + 1}`,
    order: index,
    status: index === 0 ? 1 : 0,
    row: 60,
    column: 20,
    celldata: [],
    config: { merge: {}, rowlen: {}, columnlen: {}, borderInfo: [] },
    scrollLeft: 0,
    scrollTop: 0
  }
}

/**
 * 导出设置（报表级）：导出的 Excel / PDF / Word 叫什么名字 —— 报表名 + 主接口若干字段值，
 * 用 `separator` 拼起来。见 CONTRACT §4，后端那份是 `dto/ExportConfigDTO`。
 *
 * 老报表没有这一项（等于「就叫报表名」），这里补全字段，弹窗里就不用到处判空。
 */
export function normalizeExportConfig(cfg) {
  const c = cfg || {}
  return {
    withReportName: c.withReportName !== false,
    fields: Array.isArray(c.fields) ? c.fields.filter((f) => !!f) : [],
    separator: typeof c.separator === 'string' ? c.separator : '_'
  }
}

/**
 * 照 `exportConfig` 拼一个文件名（不含扩展名）—— 设计器里那个「示例」用它现算，
 * 后端 `ExportConfigDTO#resolve` 是同一套规则（含洗掉 `\/:*?"<>|` 这些字符）。
 *
 * @param values 字段名 -> 值；设计器里没有真实数据，传字段的显示名当示意
 */
export function exportFileName(cfg, reportName, values = {}) {
  const c = normalizeExportConfig(cfg)
  const clean = (s) =>
    String(s ?? '')
      // 与后端 ExportConfigDTO#sanitize 一致：洗掉文件名里不能用的那几个字符，
      // 换行/制表折成空格（后端连控制字符一并丢掉，效果相同）
      .replace(/[\\\/:*?"<>|]/g, '')
      .replace(/\s+/g, ' ')
      .trim()
      .replace(/\.+$/, '')
      .trim()
  const parts = []
  if (c.withReportName) parts.push(clean(reportName))
  c.fields.forEach((f) => parts.push(clean(values[f] ?? f)))
  const name = parts.filter((p) => p).join(clean(c.separator))
  return name || clean(reportName) || 'report'
}

/** 生成一份空白报表 content，见 docs/CONTRACT.md §4 */
export function createEmptyContent() {
  return {
    version: 1,
    sheets: [createEmptySheet(0)],
    cellConfigs: {},
    params: [],
    /** 主接口：驱动本报表分页/拆分的数据集 code，一张报表只有一个，空 = 不分页不拆分 */
    primaryDataset: '',
    /**
     * 输出方式：single 单 sheet 输出 / perRowPage「多 sheet 输出」（界面上的名字）——
     * 按主接口每条数据拆一份、首尾相接拼在同一张 sheet 里，一份一页 /
     * perRow「每条数据一个 sheet」—— 同一套拆分不拼接，直接出 M×N 张 sheet。
     */
    splitMode: 'single',
    /** 拆分时单据名取主接口的哪个字段（perRow 当 sheet 名，perRowPage 当 ${sheet} 的值） */
    sheetNameField: '',
    /**
     * 哪几张模板**跟着拆**，key = 模板下标，值 `once` = 不跟着拆（整份只渲染一次、拿全量数据）。
     * 缺省即跟着拆 —— 老报表一项都没有，行为不变。
     *
     * splitMode 是报表级的总开关（拆不拆），这一项按模板决定谁参与：
     * 「第一张是清单列表、第二张是每条数据的详情」就靠它。
     */
    sheetSplits: {},
    /** 父子关联（子接口查询）：[{name, master, child, mappings:[{param, field}]}] */
    datasetLinks: [],
    /** 导出设置：下载下来的文件叫什么名字，见 normalizeExportConfig */
    exportConfig: normalizeExportConfig(null),
    pageConfig: {
      paperSize: 'A4',
      orientation: 'portrait',
      marginTop: 10,
      marginBottom: 10,
      marginLeft: 10,
      marginRight: 10,
      limitWidth: true
    },
    /** 按 sheet 单独设的打印设置，key = sheet 下标；没有的 sheet 用上面的 pageConfig */
    pageConfigs: {}
  }
}

/**
 * 归一 `sheetSplits`（哪几张模板跟着拆）：**只留标了 `once` 的那些**。
 *
 * 跟着拆是缺省，存一堆 `perRow` 只会让 content 平白多出一截，也让「这张有没有特殊设置」
 * 不好判断（同 `pageConfigs` 只存改过的那些）。与后端 `ReportContentDTO#splitsSheet`
 * 是同一条规则：认不得的值一律当「跟着拆」。
 */
export function normalizeSheetSplits(splits) {
  const out = {}
  Object.entries(splits || {}).forEach(([idx, v]) => {
    if (v === 'once') out[String(idx)] = 'once'
  })
  return out
}

/** 默认单元格配置 */
export function defaultCellConfig(sheetIndex, r, c) {
  return {
    sheetIndex,
    r,
    c,
    type: 'text',
    datasetCode: '',
    field: '',
    expandType: 'none',
    groupType: 'list',
    aggregate: 'none',
    formatType: 'text',
    formatPattern: '',
    expression: '',
    /** 图片格没出成图时改用哪个字段的值当文字显示，空 = 出空白 */
    fallbackField: ''
  }
}

/**
 * 取某个单元格对象。
 * FortuneSheet 运行时把 celldata 展开成二维 data 并删除 celldata，
 * 因此工作簿实例返回的 sheet 只有 data；持久化的 sheet 只有 celldata。两种形态都要能读。
 */
export function findCell(sheet, r, c) {
  if (!sheet) return null
  if (Array.isArray(sheet.data)) {
    const v = sheet.data[r]?.[c]
    return v === null || v === undefined ? null : { r, c, v }
  }
  if (Array.isArray(sheet.celldata)) {
    return sheet.celldata.find((x) => x.r === r && x.c === c) || null
  }
  return null
}

/** 单元格值对象 -> 显示文本（与后端 TemplateParser 一样，先 m 后 v） */
function cellDisplayText(v) {
  if (v === null || v === undefined) return ''
  if (typeof v === 'string' || typeof v === 'number') return String(v)
  return String(v.m ?? v.v ?? '')
}

/** 读取单元格显示文本 */
export function getCellText(sheet, r, c) {
  const cell = findCell(sheet, r, c)
  if (!cell) return ''
  return cellDisplayText(cell.v)
}

/**
 * 从数据集面板拖字段到画布时，拖拽负载用的自定义 MIME。
 * 不用 `text/plain` 当主通道 —— 那个格子谁都能写（拖拽库、浏览器默认行为），
 * 被覆盖后落格那头 `JSON.parse` 一抛错就是「拖过去什么都没有」。
 */
export const FIELD_DRAG_MIME = 'application/x-mz-field'

/** 数据集字段 -> 单元格占位符 `#{code.field}` */
export function fieldToken(datasetCode, field) {
  return `#{${datasetCode}.${field}}`
}

/**
 * 三类占位符的匹配规则，与后端 TemplateParser 的 DATA/FORMULA/PARAM_TOKEN **逐字对应**
 * —— `autoBindCellConfigs` 照它推断出来的配置必须和后端 `resolveConfig` 推断的是同一份，
 * 否则面板上显示的是一回事、出纸又是另一回事。捕获组是那边推断要用的（数据集/字段/表达式/参数名）。
 */
const TOKEN_PATTERNS = {
  data: /#\{\s*([A-Za-z_]\w*)\.([A-Za-z_][\w$]*)\s*\}/,
  formula: /!\{([\s\S]+?)\}/,
  param: /\$\{\s*([A-Za-z_]\w*)\s*\}/
}

/** 文本里有没有占位符的粗筛（每次改动要扫全表，先挡掉绝大多数普通格子） */
const HAS_TOKEN = /[#!$]\{/

/** 图片单元格的绑定写法与数据格一样，都是 `#{code.field}` */
const TOKEN_TYPE_ALIAS = { img: 'data', base64: 'data' }

/**
 * 把单元格文本里已有的占位符换成新的，**用户手写在占位符前后的文字原样留着**
 * （`#{orders.amount}元` 改个格式化方式，不该把「元」一起冲掉）。
 * 文本里还没有该类占位符时，直接返回占位符本身。
 *
 * 替换用**函数**而不是字符串：正则带了捕获组（见 TOKEN_PATTERNS），字符串形态下新占位符里
 * 万一出现 `$1`（表达式里写得出来）会被当成「回填第一个捕获组」，换出来的东西面目全非。
 */
export function replaceToken(text, type, token) {
  const re = TOKEN_PATTERNS[TOKEN_TYPE_ALIAS[type] || type]
  const old = String(text ?? '')
  return re && re.test(old) ? old.replace(re, () => token) : token
}

/** 解析 `#{code.field}`，返回 {datasetCode, field} 或 null */
export function parseFieldToken(text) {
  if (!text) return null
  const m = String(text).match(/^#\{\s*([\w$]+)\.([\w$]+)\s*\}$/)
  return m ? { datasetCode: m[1], field: m[2] } : null
}

/** 值来自单元格里那串占位符（`#{}` / `!{}` / `${}`）的配置类型（图片/条码格的绑定写法同 data） */
const BOUND_TYPES = new Set(['data', 'formula', 'param', 'img', 'base64', 'barcode', 'qrcode'])

const isBlankValue = (x) => x === null || x === undefined || String(x) === ''

/**
 * 单元格是不是空的（没值、没显示文本、也没公式）。
 * 注意 Delete 键留下的是一个空对象 `{}` 而不是 null，所以不能只判 `!cell`。
 */
export function isCellEmpty(sheet, r, c) {
  const cell = findCell(sheet, r, c)
  const v = cell?.v
  if (v === null || v === undefined) return true
  if (typeof v === 'object') return isBlankValue(v.v) && isBlankValue(v.m) && isBlankValue(v.f)
  return isBlankValue(v)
}

/**
 * 丢掉「格子已经清空、绑定却还留着」的单元格配置。
 *
 * 拖字段落格时写的是**两处**：格子里的占位符 `#{code.field}`，与 `cellConfigs` 里的一条绑定。
 * 而用户按 Delete 删格子内容时，FortuneSheet 只是把该格置成 `{}`
 * （见 `@fortune-sheet/core` 的 `deleteSelectedCellText`），既不删 celldata 条目，
 * 也没有「哪个格被清空了」这种钩子，业务层的 `cellConfigs` 原封不动。
 * 偏偏后端 `TemplateParser#resolveConfig` 是**优先信 cellConfigs** 的（有显式配置就不看文本），
 * 于是画布上明明删干净了，一预览/导出数据照旧出来，甚至凭空多出一条行带 ——
 * 空格子上的 `expandType=down` 绑定同样会被 `ExpandProcessor#findBandDataset` 认成扩展行带。
 *
 * 只回收 data / formula / param 这三类**值来自占位符**的配置：占位符没了就等于绑定没了。
 * `text` 类配置（只有格式化）留着 —— 用户可能先设格式再填内容。
 *
 * @returns 新的 cellConfigs；没有要丢的则原样返回传入对象
 */
export function pruneEmptyCellConfigs(cellConfigs, sheets) {
  const entries = Object.entries(cellConfigs || {})
  if (!entries.length || !Array.isArray(sheets)) return cellConfigs
  const out = {}
  let dropped = false
  for (const [key, cfg] of entries) {
    const [si, r, c] = key.split('_').map(Number)
    const sheet = sheets[si]
    const addressed = [si, r, c].every(Number.isInteger)
    if (sheet && addressed && BOUND_TYPES.has(cfg?.type) && isCellEmpty(sheet, r, c)) {
      dropped = true
      continue
    }
    out[key] = cfg
  }
  return dropped ? out : cellConfigs
}

/** 遍历一张 sheet 的每个非空格子（celldata 与运行时二维 data 两种形态都认，同 findCell） */
function forEachCellText(sheet, fn) {
  if (Array.isArray(sheet?.celldata)) {
    for (const cell of sheet.celldata) {
      if (cell && Number.isInteger(cell.r) && Number.isInteger(cell.c)) fn(cell.r, cell.c, cellDisplayText(cell.v))
    }
    return
  }
  if (Array.isArray(sheet?.data)) {
    sheet.data.forEach((row, r) => {
      if (!Array.isArray(row)) return
      row.forEach((v, c) => {
        if (v !== null && v !== undefined) fn(r, c, cellDisplayText(v))
      })
    })
  }
}

/**
 * 照格子文本推断一条单元格配置，**规则与后端 `TemplateParser#resolveConfig` 一致**：
 * data 先于 formula 先于 param，手写的数据格默认纵向扩展。认不出来返回 null。
 *
 * 格式化类型跟着字段类型走（同拖拽落格的 `bindField`）；字段类型不知道时按文本，
 * 反正后端推断出来的也是 `formatType=text`。
 */
function inferCellConfig(sheetIndex, r, c, text, fieldTypeOf) {
  const base = defaultCellConfig(sheetIndex, r, c)
  const dm = TOKEN_PATTERNS.data.exec(text)
  if (dm) {
    const fieldType = fieldTypeOf ? fieldTypeOf(dm[1], dm[2]) : ''
    const formatType = fieldType === 'number' ? 'number' : fieldType === 'date' ? 'date' : 'text'
    return {
      ...base,
      type: 'data',
      datasetCode: dm[1],
      field: dm[2],
      expandType: 'down',
      formatType,
      formatPattern: defaultFormatPattern(formatType)
    }
  }
  const fm = TOKEN_PATTERNS.formula.exec(text)
  if (fm) return { ...base, type: 'formula', expression: fm[1] }
  const pm = TOKEN_PATTERNS.param.exec(text)
  // 参数格的值走文本替换（后端 ExpandProcessor#substitute），field 只是给面板显示/回写用
  if (pm) return { ...base, type: 'param', field: pm[1] }
  return null
}

/**
 * `pruneEmptyCellConfigs` 相反的那一半：格子里手写了 `#{code.field}` / `!{表达式}` / `${param}`
 * 却没有配置时，照文本自动补一条绑定。
 *
 * 手写占位符本来就渲染得出来（后端 `TemplateParser#resolveConfig` 没有显式配置就按文本推断），
 * 但**设计器右侧面板读的是 `cellConfigs`** —— 不补的话手写的那格在面板上显示成「文本」，
 * 扩展方式、分组、格式化都无从配起，看着就是「没绑上」。所以这里把后端那份推断提前落成配置，
 * 面板上看到的与出纸的是同一份。规则必须与后端一致，见 `inferCellConfig`。
 *
 * **只补没有配置的格子**，已有配置一律不动：那是用户在面板上明确设过的，包括「就是要把这串
 * 占位符当普通文字印出来」（改成 text 类型之后不该被这里又绑回去）。
 *
 * @param fieldTypeOf 可选，`(datasetCode, field) => 'number'|'date'|...`，用来定格式化类型
 * @returns 新的 cellConfigs；没有要补的则原样返回传入对象
 */
export function autoBindCellConfigs(cellConfigs, sheets, fieldTypeOf) {
  if (!Array.isArray(sheets) || !sheets.length) return cellConfigs
  const base = cellConfigs || {}
  let out = null
  sheets.forEach((sheet, sheetIndex) => {
    forEachCellText(sheet, (r, c, text) => {
      if (!text || !HAS_TOKEN.test(text)) return
      const key = cellKey(sheetIndex, r, c)
      if ((out || base)[key]) return
      const cfg = inferCellConfig(sheetIndex, r, c, text, fieldTypeOf)
      if (!cfg) return
      if (!out) out = { ...base }
      out[key] = cfg
    })
  })
  return out || cellConfigs
}

/** 深拷贝（结构化克隆，退化到 JSON） */
export function deepClone(obj) {
  if (obj === null || obj === undefined) return obj
  try {
    return structuredClone(obj)
  } catch (e) {
    return JSON.parse(JSON.stringify(obj))
  }
}

/** FortuneSheet 运行时的二维 data -> 持久化用的 celldata */
export function dataToCelldata(data) {
  const celldata = []
  if (!Array.isArray(data)) return celldata
  data.forEach((row, r) => {
    if (!Array.isArray(row)) return
    row.forEach((v, c) => {
      if (v !== null && v !== undefined) celldata.push({ r, c, v })
    })
  })
  return celldata
}

/**
 * 把工作簿实例返回的 sheets 统一成 celldata 形态。
 * getAllSheets() / onChange 拿到的是运行时结构（二维 data，无 celldata），
 * 而持久化契约与后端 TemplateParser 都只认 celldata —— 直接存运行时结构会导致
 * 保存下来的模板后端解析为空（预览/导出空白），且体积巨大。
 */
export function toCelldataSheets(sheets) {
  if (!Array.isArray(sheets)) return []
  return sheets.map((s) => {
    const { data, ...rest } = s
    return {
      ...rest,
      celldata: Array.isArray(data) ? dataToCelldata(data) : Array.isArray(s.celldata) ? s.celldata : []
    }
  })
}

/**
 * 规范化后端返回或本地保存的 sheets，保证 FortuneSheet 需要的字段齐全。
 * 同时兼容历史上误存成运行时二维 data 的报表（转回 celldata）。
 */
export function normalizeSheets(sheets) {
  if (!Array.isArray(sheets) || !sheets.length) return [createEmptySheet(0)]
  return toCelldataSheets(sheets).map((s, i) => ({
    ...createEmptySheet(i),
    ...s,
    order: s.order ?? i,
    config: {
      merge: {},
      rowlen: {},
      columnlen: {},
      borderInfo: [],
      ...(s.config || {})
    },
    celldata: Array.isArray(s.celldata) ? s.celldata : []
  }))
}

/* ------------------------- 按下标寻址的配置跟随 sheet ------------------------- */

/**
 * 按 sheet 的 id 算出「旧下标 -> 新下标」的映射。
 *
 * `cellConfigs` 的 key 是 `${sheetIndex}_${r}_${c}`、`pageConfigs` 的 key 就是 sheetIndex，
 * 说的都是「第几张 sheet」。**删除工作表时 FortuneSheet 是把它从数组里 splice 掉的**
 * （见 @fortune-sheet/core 的 `deleteSheet`），后面每张 sheet 的下标都往前挪一位 ——
 * 不跟着搬 key，删掉中间一张之后所有配置就都对到别人身上去了：
 * 单元格绑定跑到隔壁表的同坐标上，打印设置也是。
 *
 * 拖动标签重排只改各 sheet 的 `order` 字段、不动数组顺序，本来不需要搬；
 * 但这里按 id 对齐，顺序真变了也一样正确。
 *
 * @param prevSheets 变更前的 sheets
 * @param nextSheets 变更后的 sheets
 * @returns {Map<number, number>|null} 旧下标 -> 新下标（-1 = 该 sheet 已删除）；
 *          下标没动、或 id 不全没法对齐时返回 null（宁可原样不动，也不能把配置搬错）
 */
export function sheetIndexRemap(prevSheets, nextSheets) {
  if (!Array.isArray(prevSheets) || !Array.isArray(nextSheets) || !prevSheets.length) return null
  const nextIndexById = new Map()
  nextSheets.forEach((s, i) => {
    if (s?.id != null && s.id !== '') nextIndexById.set(s.id, i)
  })
  if (nextIndexById.size !== nextSheets.length) return null

  const remap = new Map()
  const seen = new Set()
  let moved = false
  let matched = 0
  for (let i = 0; i < prevSheets.length; i += 1) {
    const id = prevSheets[i]?.id
    // id 缺失或重复都没法一一对应（FortuneSheet 初始化时会给重复 id 重新发号），别猜
    if (id == null || id === '' || seen.has(id)) return null
    seen.add(id)
    const to = nextIndexById.has(id) ? nextIndexById.get(id) : -1
    if (to >= 0) matched += 1
    remap.set(i, to)
    if (to !== i) moved = true
  }
  // 一张都对不上：不是删了 sheet，而是 id 被整体换过了（或压根不是同一份报表）。
  // 此时按 remap 搬会把所有配置当成「sheet 已删」清空，宁可不动。
  if (!matched) return null
  return moved ? remap : null
}

/**
 * 按下标映射搬 `cellConfigs` 的 key，值里冗余的 `sheetIndex` 字段一起改。
 * 所属 sheet 已被删除的条目直接丢掉。
 */
export function remapCellConfigs(cellConfigs, remap) {
  const out = {}
  Object.entries(cellConfigs || {}).forEach(([key, cfg]) => {
    const parts = key.split('_')
    const from = Number(parts[0])
    if (!Number.isInteger(from) || !remap.has(from)) {
      out[key] = cfg
      return
    }
    const to = remap.get(from)
    if (to < 0) return
    out[[to, ...parts.slice(1)].join('_')] = to === from ? cfg : { ...cfg, sheetIndex: to }
  })
  return out
}

/**
 * 按下标映射搬 `pageConfigs` 的 key（key 本身就是下标）；sheet 已删的条目丢掉。
 *
 * **`sheetSplits`（哪几张模板跟着拆）共用这个函数** —— 两者是同一套寻址，值原样搬。
 */
export function remapPageConfigs(pageConfigs, remap) {
  const out = {}
  Object.entries(pageConfigs || {}).forEach(([key, cfg]) => {
    const from = Number(key)
    if (!Number.isInteger(from) || !remap.has(from)) {
      out[key] = cfg
      return
    }
    const to = remap.get(from)
    if (to < 0) return
    out[String(to)] = cfg
  })
  return out
}

/* ------------------------- 按行列寻址的配置跟随插入/删除 ------------------------- */

/**
 * 把 FortuneSheet 的一次行/列增删归一成「从第 at 行(列)起，增/删 count 个」。
 *
 * 两种 op 的载荷形状不一样（见 @fortune-sheet/core 的 `PatchOptions`）：
 * 插入是 `{type, index, count, direction}`，`direction=rightbottom` 意为「在 index **之后**插」，
 * 新行的起点要再往后挪一格；删除是 `{type, start, end}`，闭区间。
 *
 * @returns {{type:'row'|'column', at:number, count:number, remove:boolean}|null} 认不出来返回 null
 */
export function normalizeRowColOp(op) {
  const v = op?.value
  if (v?.type !== 'row' && v?.type !== 'column') return null
  if (op.op === 'insertRowCol') {
    const index = Number(v.index)
    const count = Number(v.count)
    if (!Number.isInteger(index) || !Number.isInteger(count) || count <= 0 || index < 0) return null
    return { type: v.type, at: index + (v.direction === 'rightbottom' ? 1 : 0), count, remove: false }
  }
  if (op.op === 'deleteRowCol') {
    const start = Number(v.start)
    const end = Number(v.end)
    if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start) return null
    return { type: v.type, at: start, count: end - start + 1, remove: true }
  }
  return null
}

/**
 * 行/列增删之后，把 `cellConfigs` 的坐标跟着挪。
 *
 * 与「删除工作表要搬 sheetIndex」是同一类问题，只是换成了行列这一维：**插入/删除一行时
 * FortuneSheet 只挪自己那份数据**（celldata、merge、rowlen、borderInfo 都由它自己维护），
 * 业务层按 `${sheetIndex}_${r}_${c}` 寻址的 `cellConfigs` 原地不动 —— 删掉顶上一行之后，
 * 数据绑定就整体错位一行：原来在第 5 行的 `#{orders.amount}` 文本挪到了第 4 行，配置还留在第 5 行，
 * 于是画布上那个格子成了「有占位符没配置」，而它下面那个空格子凭空多出一条扩展行带。
 *
 * `change` 事件只给结果、看不出「哪一行被删了」，所以只能从 `op` 事件里认（见 normalizeRowColOp）。
 *
 * 删除时落在被删区间里的配置直接丢掉，但一并返回 —— 撤销那一步要拿它们放回去（见 store 的
 * `applyRowColChange`）。别的 sheet 与形状不对的 key 原样留着。
 *
 * @returns {{cellConfigs: object, dropped: object}} dropped 的 key 是**挪之前**的坐标
 */
export function shiftCellConfigs(cellConfigs, sheetIndex, change) {
  const out = {}
  const dropped = {}
  // key = `${sheetIndex}_${r}_${c}`，行走下标 1、列走下标 2
  const axis = change.type === 'row' ? 1 : 2
  const end = change.at + change.count
  Object.entries(cellConfigs || {}).forEach(([key, cfg]) => {
    const nums = key.split('_').map(Number)
    if (nums.length !== 3 || !nums.every(Number.isInteger) || nums[0] !== sheetIndex || nums[axis] < change.at) {
      out[key] = cfg
      return
    }
    if (change.remove) {
      if (nums[axis] < end) {
        dropped[key] = cfg
        return
      }
      nums[axis] -= change.count
    } else {
      nums[axis] += change.count
    }
    // 值里冗余存了一份 r/c（属性面板与后端都读它），跟着一起改
    out[cellKey(nums[0], nums[1], nums[2])] = { ...cfg, r: nums[1], c: nums[2] }
  })
  return { cellConfigs: out, dropped }
}

/**
 * 丢掉指向已不存在的 sheet 的配置（下标越界的那些）。
 *
 * 历史版本删除工作表时不会搬 key，老报表里可能留着这种孤儿配置；留着的话
 * 后面新增一张 sheet 就会莫名其妙「继承」到已删 sheet 的打印设置和单元格绑定。
 * 就地修改并返回传入的 content。
 */
export function pruneSheetConfigs(content) {
  if (!content) return content
  const count = Array.isArray(content.sheets) ? content.sheets.length : 0
  const dead = new Map()
  const mark = (idx) => {
    if (Number.isInteger(idx) && (idx < 0 || idx >= count)) dead.set(idx, -1)
  }
  Object.keys(content.cellConfigs || {}).forEach((k) => mark(Number(k.split('_')[0])))
  Object.keys(content.pageConfigs || {}).forEach((k) => mark(Number(k)))
  Object.keys(content.sheetSplits || {}).forEach((k) => mark(Number(k)))
  if (!dead.size) return content
  content.cellConfigs = remapCellConfigs(content.cellConfigs, dead)
  content.pageConfigs = remapPageConfigs(content.pageConfigs, dead)
  // 同一套寻址，同一个坑：留着越界的条目，新增一张 sheet 就会莫名其妙「继承」到清单页的标记
  content.sheetSplits = remapPageConfigs(content.sheetSplits, dead)
  return content
}

/**
 * 从响应头 `Content-Disposition` 里取文件名 —— **导出下载一律用它**：
 * 文件名由后端按 `content.exportConfig` 拼（报表名 + 主接口若干字段值，见 CONTRACT §4），
 * 前端手上没有那份数据，自己拼只会拼成另一个名字。
 *
 * 认两种写法：`filename*=UTF-8''%E2%80%A6`（后端发的这种，中文名要它）与 `filename="x.xlsx"`。
 * 读不到就退回 `fallback`（跨域部署时后端没放出这个响应头的话就会走到这一步）。
 */
export function fileNameFromResponse(res, fallback) {
  const raw = res?.headers?.['content-disposition'] || res?.headers?.get?.('content-disposition') || ''
  const star = /filename\*=UTF-8''([^;]+)/i.exec(raw)
  if (star) {
    try {
      return decodeURIComponent(star[1].trim())
    } catch (e) {
      // 编码坏了就当没有，退回 fallback
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(raw)
  return plain ? plain[1].trim() : fallback
}

/** 触发浏览器下载 */
export function downloadBlob(blob, filename) {
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

/**
 * 单元格类型，见 CONTRACT §4。
 *
 * `img` / `base64` / `barcode` / `qrcode` 的取数配置与 `data` 完全一样（数据集 + 字段 + 扩展方式），
 * 区别只在于取到的值是当图片画还是当文字写：地址去下载、base64 直接解、条码则拿那串字现编成一张图。
 * 四者都等比例装进格子并居中。
 */
export const CELL_TYPES = [
  { label: '文本', value: 'text' },
  { label: '数据', value: 'data' },
  { label: '公式', value: 'formula' },
  { label: '参数', value: 'param' },
  { label: '图片（地址）', value: 'img' },
  { label: '图片（Base64）', value: 'base64' },
  { label: '条形码', value: 'barcode' },
  { label: '二维码', value: 'qrcode' }
]

/** 值当条码画的单元格类型（值是要编成码的那串字，后端出图） */
export const BARCODE_TYPES = ['barcode', 'qrcode']

/** 值当图片渲染的单元格类型 */
export const IMAGE_TYPES = ['img', 'base64', ...BARCODE_TYPES]

/** 按数据集字段取值的单元格类型（data 与几种图片类型的取数配置一致） */
export const DATA_BOUND_TYPES = ['data', ...IMAGE_TYPES]

/**
 * 一维条形码的码制。名字是 ZXing 的 `BarcodeFormat`，后端照着它出图。
 *
 * 各码制能编什么是**有硬限制**的（EAN_13 只收 13 位数字、CODE_39 不认小写字母…），
 * 编不出来的那一格后端记 warn 后当空格处理，不会让整份渲染失败。
 */
export const BARCODE_FORMATS = [
  { label: 'Code 128（字母数字，默认）', value: 'CODE_128' },
  { label: 'Code 39（大写字母 + 数字）', value: 'CODE_39' },
  { label: 'Code 93', value: 'CODE_93' },
  { label: 'EAN-13（13 位数字，商品条码）', value: 'EAN_13' },
  { label: 'EAN-8（8 位数字）', value: 'EAN_8' },
  { label: 'UPC-A（12 位数字）', value: 'UPC_A' },
  { label: 'ITF（偶数位数字）', value: 'ITF' },
  { label: 'Codabar', value: 'CODABAR' }
]

/** 二维码的码制 */
export const QRCODE_FORMATS = [
  { label: 'QR Code（默认）', value: 'QR_CODE' },
  { label: 'DataMatrix（小面积）', value: 'DATA_MATRIX' },
  { label: 'PDF417（长条形）', value: 'PDF_417' },
  { label: 'Aztec', value: 'AZTEC' }
]

/** 二维码纠错级别：越高越抗污损，同样面积能编的内容越少 */
export const QR_LEVELS = [
  { label: 'L　低（约 7%）', value: 'L' },
  { label: 'M　中（约 15%，默认）', value: 'M' },
  { label: 'Q　较高（约 25%）', value: 'Q' },
  { label: 'H　高（约 30%）', value: 'H' }
]

export const EXPAND_TYPES = [
  { label: '不扩展', value: 'none' },
  { label: '纵向扩展', value: 'down' },
  { label: '横向扩展', value: 'right' }
]

export const AGGREGATES = [
  { label: '无', value: 'none' },
  { label: '求和 SUM', value: 'sum' },
  { label: '平均 AVG', value: 'avg' },
  { label: '最大 MAX', value: 'max' },
  { label: '最小 MIN', value: 'min' },
  { label: '计数 COUNT', value: 'count' }
]

export const GROUP_TYPES = [
  { label: '列表（每行都显示）', value: 'list' },
  { label: '分组（相同值合并）', value: 'group' }
]

export const FORMAT_TYPES = [
  { label: '文本', value: 'text' },
  { label: '数值', value: 'number' },
  { label: '金额', value: 'currency' },
  { label: '百分比', value: 'percent' },
  { label: '日期', value: 'date' }
]

/**
 * 金额中文大写的模板值，与后端 CellFormatter.CN_UPPER 是同一个串。
 * 出的是一段文字而不是数字，所以后端把 ct 标成文本（fa=@ / t=s）。
 */
export const CN_UPPER_PATTERN = '[中文大写]'

/**
 * 金额模板：**货币符号写在模板里**，后端不再另外拼前缀
 * —— 拼前缀的话符号只进得了显示文本，进不了 ct.fa，导出的 Excel 里就没有符号了。
 */
export const CURRENCY_PATTERNS = [
  { label: '¥1,234.56', value: '¥#,##0.00' },
  { label: '¥1,235（无小数）', value: '¥#,##0' },
  { label: '$1,234.56', value: '$#,##0.00' },
  { label: '1,234.56（无符号）', value: '#,##0.00' },
  { label: '中文大写', value: CN_UPPER_PATTERN }
]

/** 日期模板：下拉里是常用的几个，也可以直接输入自定义格式（Java 的日期格式串） */
export const DATE_PATTERNS = [
  { label: '2024-01-31', value: 'yyyy-MM-dd' },
  { label: '2024/01/31', value: 'yyyy/MM/dd' },
  { label: '2024年01月31日', value: 'yyyy年MM月dd日' },
  { label: '2024-01-31 15:04:05', value: 'yyyy-MM-dd HH:mm:ss' },
  { label: '2024-01-31 15:04', value: 'yyyy-MM-dd HH:mm' },
  { label: '2024-01', value: 'yyyy-MM' },
  { label: '01-31', value: 'MM-dd' },
  { label: '15:04:05', value: 'HH:mm:ss' }
]

/**
 * 各格式化类型的默认模板。
 *
 * 面板上只让配金额与日期的模板（数值/百分比/文本没什么可配的），
 * 所以切换类型时要顺手把模板换成该类型的默认值 —— 留着上一个类型的模板，
 * 界面上看不见、渲染时却在生效，就成了「怎么改都不对」的怪事。
 */
export function defaultFormatPattern(formatType) {
  switch (formatType) {
    case 'number':
      return '#,##0.00'
    case 'currency':
      return '¥#,##0.00'
    case 'percent':
      return '0.00'
    case 'date':
      return 'yyyy-MM-dd'
    default:
      return ''
  }
}

export const PARAM_TYPES = [
  { label: '字符串', value: 'string' },
  { label: '数字', value: 'number' },
  { label: '日期', value: 'date' },
  { label: '布尔', value: 'boolean' }
]

export const PARAM_WIDGETS = [
  { label: '输入框', value: 'input' },
  { label: '数字框', value: 'number' },
  { label: '日期选择', value: 'date' },
  { label: '日期范围', value: 'daterange' },
  { label: '下拉选择', value: 'select' }
]
