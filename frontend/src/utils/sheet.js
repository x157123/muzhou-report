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
     * 输出方式：single 单 sheet / perRow 主接口每条数据一个 sheet /
     * perRowPage 每条数据一页、拼在同一张 sheet 里
     */
    splitMode: 'single',
    /** perRow 时 sheet 名取主接口的哪个字段 */
    sheetNameField: '',
    /** 父子关联（子接口查询）：[{name, master, child, mappings:[{param, field}]}] */
    datasetLinks: [],
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
    expression: ''
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

/** 读取单元格显示文本 */
export function getCellText(sheet, r, c) {
  const cell = findCell(sheet, r, c)
  if (!cell || !cell.v) return ''
  const v = cell.v
  if (typeof v === 'string' || typeof v === 'number') return String(v)
  return String(v.m ?? v.v ?? '')
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

/** 三类占位符的匹配规则，与后端 TemplateParser 的 DATA/FORMULA/PARAM_TOKEN 对应 */
const TOKEN_PATTERNS = {
  data: /#\{\s*[A-Za-z_]\w*\.[\w$]+\s*\}/,
  formula: /!\{[\s\S]+?\}/,
  param: /\$\{\s*[A-Za-z_]\w*\s*\}/
}

/** 图片单元格的绑定写法与数据格一样，都是 `#{code.field}` */
const TOKEN_TYPE_ALIAS = { img: 'data', base64: 'data' }

/**
 * 把单元格文本里已有的占位符换成新的，**用户手写在占位符前后的文字原样留着**
 * （`#{orders.amount}元` 改个格式化方式，不该把「元」一起冲掉）。
 * 文本里还没有该类占位符时，直接返回占位符本身。
 */
export function replaceToken(text, type, token) {
  const re = TOKEN_PATTERNS[TOKEN_TYPE_ALIAS[type] || type]
  const old = String(text ?? '')
  return re && re.test(old) ? old.replace(re, token) : token
}

/** 解析 `#{code.field}`，返回 {datasetCode, field} 或 null */
export function parseFieldToken(text) {
  if (!text) return null
  const m = String(text).match(/^#\{\s*([\w$]+)\.([\w$]+)\s*\}$/)
  return m ? { datasetCode: m[1], field: m[2] } : null
}

/** 值来自单元格里那串占位符（`#{}` / `!{}` / `${}`）的配置类型（img/base64 的绑定写法同 data） */
const BOUND_TYPES = new Set(['data', 'formula', 'param', 'img', 'base64'])

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

/** 按下标映射搬 `pageConfigs` 的 key（key 本身就是下标）；sheet 已删的条目丢掉 */
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
  if (!dead.size) return content
  content.cellConfigs = remapCellConfigs(content.cellConfigs, dead)
  content.pageConfigs = remapPageConfigs(content.pageConfigs, dead)
  return content
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
 * `img` / `base64` 的取数配置与 `data` 完全一样（数据集 + 字段 + 扩展方式），
 * 区别只在于取到的值是当图片画还是当文字写；图片按格子的宽高铺满。
 */
export const CELL_TYPES = [
  { label: '文本', value: 'text' },
  { label: '数据', value: 'data' },
  { label: '公式', value: 'formula' },
  { label: '参数', value: 'param' },
  { label: '图片（地址）', value: 'img' },
  { label: '图片（Base64）', value: 'base64' }
]

/** 值当图片渲染的单元格类型 */
export const IMAGE_TYPES = ['img', 'base64']

/** 按数据集字段取值的单元格类型（data 与两种图片类型的取数配置一致） */
export const DATA_BOUND_TYPES = ['data', ...IMAGE_TYPES]

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
