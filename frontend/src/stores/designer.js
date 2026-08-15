import { defineStore } from 'pinia'
import {
  cellKey,
  createEmptyContent,
  defaultCellConfig,
  defaultFormatPattern,
  deepClone,
  normalizeExportConfig,
  normalizeRowColOp,
  normalizeSheets,
  normalizeSheetSplits,
  pruneEmptyCellConfigs,
  pruneSheetConfigs,
  remapCellConfigs,
  remapPageConfigs,
  sheetIndexRemap,
  shiftCellConfigs,
  toCelldataSheets
} from '@/utils/sheet'
import { normalizePageConfig, pageConfigOf, shiftPrintArea } from '@/utils/print'

/**
 * 撤销一次行/列删除时，要把当时丢掉的单元格配置放回去，所以按后进先出攒着。
 * 攒的份数有上限，免得长时间设计攒出一大堆（撤销栈本身也不会无限长）。
 */
const MAX_DROPPED_STASH = 50

/** 追加工作表时避开已有的 id（id 只要唯一，不必好看） */
function uniqueSheetId(taken, index) {
  let id = `sheet_${index + 1}`
  while (taken.has(id)) id = `sheet_${index + 1}_${Math.random().toString(36).slice(2, 7)}`
  taken.add(id)
  return id
}

/** 追加工作表时重名的挂 (2)(3)，同 Excel */
function uniqueSheetName(taken, name) {
  let out = name
  let n = 2
  while (taken.has(out)) out = `${name}(${n++})`
  taken.add(out)
  return out
}

/**
 * 设计器状态：当前报表、单元格配置、选中单元格、可用数据集。
 */
export const useDesignerStore = defineStore('designer', {
  state: () => ({
    /** 报表主体（不含 content）；versionConfig 是**报表级**的版本切换规则，见 CONTRACT §4 */
    report: { id: '', name: '', code: '', remark: '', type: 'sheet', status: 1, versionConfig: '' },
    /** 报表内容，见 CONTRACT §4。**它是被版本化的那个东西** —— 画布上是哪一版就是哪一版的 content */
    content: createEmptyContent(),
    /** 版本列表（不含 content），见 utils/version.js */
    versions: [],
    /** 当前正在设计哪一版 */
    versionId: '',
    /** 当前激活的 sheet 下标 */
    sheetIndex: 0,
    /** 当前选中单元格 { r, c } */
    activeCell: null,
    /** 数据集列表（含 fields） */
    datasets: [],
    /** 是否有未保存修改 */
    dirty: false,
    loading: false,
    /**
     * 行/列删除时被丢掉的单元格配置，供撤销那一步放回去，见 applyRowColChange。
     * 纯粹的编辑期状态，不进 content、不参与保存。
     */
    droppedCellConfigs: []
  }),

  getters: {
    /** 当前选中单元格的配置（无则返回默认值） */
    activeConfig(state) {
      if (!state.activeCell) return null
      const key = cellKey(state.sheetIndex, state.activeCell.r, state.activeCell.c)
      return state.content.cellConfigs[key] || defaultCellConfig(state.sheetIndex, state.activeCell.r, state.activeCell.c)
    },
    /** 当前 sheet 生效的打印设置（没单独设过则是报表级的那份） */
    pageConfig(state) {
      return pageConfigOf(state.content, state.sheetIndex)
    },
    /** 当前 sheet 是否有自己的打印设置（用于在 UI 上区分「跟随报表」和「已单独设置」） */
    hasOwnPageConfig(state) {
      return !!state.content.pageConfigs?.[String(state.sheetIndex)]
    },
    /** 当前 sheet 是不是「清单页」（拆分时不跟着拆，整份只渲染一次、拿全量数据） */
    sheetSplitOnce(state) {
      return state.content.sheetSplits?.[String(state.sheetIndex)] === 'once'
    },
    /** 报表中实际用到的数据集 code */
    usedDatasetCodes(state) {
      const set = new Set()
      Object.values(state.content.cellConfigs || {}).forEach((cfg) => {
        if (cfg.datasetCode) set.add(cfg.datasetCode)
      })
      return [...set]
    },
    /**
     * 按 code 找数据集（含 fields）。
     *
     * 接口返回的是 DatasetDetailDTO（{dataset, fields, params}），也兼容已经拍平的形态 ——
     * 两种都认，调用方不必关心 store 里存的是哪一种。
     */
    datasetByCode(state) {
      return (code) => {
        if (!code) return null
        for (const item of state.datasets) {
          const ds = item?.dataset || item
          if (ds?.code === code) return { ...ds, fields: item?.fields || ds.fields || [] }
        }
        return null
      }
    }
  },

  actions: {
    setReport(report) {
      this.report = {
        id: report.id || '',
        name: report.name || '',
        code: report.code || '',
        remark: report.remark || '',
        type: report.type || 'sheet',
        status: report.status ?? 1,
        // 版本切换规则是报表级的（不进 content —— content 本身就是被版本化的那个东西）
        versionConfig: report.versionConfig || ''
      }
      // 后端回填的 versionId 说明这份 content 是哪一版
      this.versionId = report.versionId || ''
      let content = createEmptyContent()
      if (report.content) {
        try {
          const parsed = typeof report.content === 'string' ? JSON.parse(report.content) : report.content
          content = { ...content, ...parsed }
        } catch (e) {
          console.warn('[designer] content 解析失败，使用空白模板', e)
        }
      }
      content.sheets = normalizeSheets(content.sheets)
      content.cellConfigs = content.cellConfigs || {}
      content.params = content.params || []
      content.primaryDataset = content.primaryDataset || ''
      content.splitMode = content.splitMode || 'single'
      content.sheetNameField = content.sheetNameField || ''
      content.sheetSplits = normalizeSheetSplits(content.sheetSplits)
      content.datasetLinks = Array.isArray(content.datasetLinks) ? content.datasetLinks : []
      content.exportConfig = normalizeExportConfig(content.exportConfig)
      content.pageConfig = normalizePageConfig(content.pageConfig)
      // 按 sheet 单独设的那些也逐个补全字段，后面各处就可以直接用
      const perSheet = {}
      Object.entries(content.pageConfigs || {}).forEach(([idx, cfg]) => {
        perSheet[String(idx)] = normalizePageConfig(cfg)
      })
      content.pageConfigs = perSheet
      // 老报表里可能留着指向已删 sheet 的孤儿配置，清掉，别让新增的 sheet 继承过去
      pruneSheetConfigs(content)
      this.content = content
      this.sheetIndex = 0
      this.activeCell = null
      this.droppedCellConfigs = []
      this.dirty = false
    },

    setDatasets(list) {
      this.datasets = list || []
    },

    setVersions(list) {
      this.versions = Array.isArray(list) ? list : []
    },

    /**
     * 写版本切换规则（判定依据 / 字段 / 取不到时怎么办）。
     *
     * 存的是 `report.versionConfig` 的 JSON 串，**不是 content 的一部分** ——
     * 规则放进 content 就成了「每个版本各有一套怎么选自己」，逻辑成环。
     * 和别的报表属性一样，要点「保存」才落库。
     */
    setVersionConfig(cfg) {
      this.report.versionConfig = cfg ? JSON.stringify(cfg) : ''
      this.dirty = true
    },

    /** 解析出来的版本切换规则（没配过给一份默认的） */
    versionConfigOf() {
      const fallback = { source: 'field', field: '', fallback: 'default' }
      if (!this.report.versionConfig) return fallback
      try {
        return { ...fallback, ...JSON.parse(this.report.versionConfig) }
      } catch (e) {
        return fallback
      }
    },

    setActiveCell(cell) {
      this.activeCell = cell
    },

    /**
     * 工作簿实例给的是运行时结构（二维 data），统一转成 celldata 后再入库。
     *
     * @returns {{dropped: string[]}} 被删掉的工作表里**带过配置**的那几张的名字 ——
     *   它们的单元格绑定/打印设置/清单页标记跟着没了，而这件事在画布上一点痕迹都没有，
     *   调用方据此提示一句（store 里不弹 UI）。
     */
    setSheets(sheets) {
      if (!Array.isArray(sheets) || !sheets.length) return { dropped: [] }
      const next = toCelldataSheets(sheets)
      const dropped = []
      // 删掉一张 sheet 会让它后面每张的下标往前挪，而 cellConfigs / pageConfigs / sheetSplits
      // 都是按下标寻址的 —— 必须按 sheet id 把 key 跟着搬过去，否则配置会对到别的 sheet 身上
      // （sheetSplits 搬错的后果尤其难查：清单页的标记跑到详情模板上，详情就只出一份了）
      const remap = sheetIndexRemap(this.content.sheets, next)
      if (remap) {
        remap.forEach((to, from) => {
          if (to >= 0) return
          const key = String(from)
          const had = !!this.content.pageConfigs?.[key] || !!this.content.sheetSplits?.[key]
            || Object.keys(this.content.cellConfigs || {}).some((k) => k.startsWith(`${key}_`))
          if (had) dropped.push(this.content.sheets[from]?.name || `工作表${from + 1}`)
        })
        this.content.cellConfigs = remapCellConfigs(this.content.cellConfigs, remap)
        this.content.pageConfigs = remapPageConfigs(this.content.pageConfigs, remap)
        this.content.sheetSplits = remapPageConfigs(this.content.sheetSplits, remap)
        const to = remap.get(this.sheetIndex)
        if (to != null && to !== this.sheetIndex) {
          // 当前这张被删了（to<0）：先退到第一张，随后的 sheet-activate 会给出真正激活的那张
          this.sheetIndex = to < 0 ? 0 : to
          if (to < 0) this.activeCell = null
        }
      }
      this.content.sheets = next
      // 删掉格子内容不会通知业务层（FortuneSheet 只是把格子置成 {}），绑定只能在这里
      // 对着最新的 sheets 回收 —— 否则格子空了数据照样渲染出来
      this.content.cellConfigs = pruneEmptyCellConfigs(this.content.cellConfigs, next)
      this.dirty = true
      return { dropped }
    },

    /**
     * 把导入的 Excel 版式装进当前报表。
     *
     * **不走 setSheets**：那个方法收的是工作簿回传的运行时结构、只管把按下标寻址的配置跟着搬；
     * 这里是往报表里塞新 sheet，还得把它们各自的打印设置一起安顿好，两件事不一样。
     * 调用方改完必须 `sheetRef.reload()` 重挂工作簿（`data` prop 只在挂载时读一次）。
     *
     * @param imported 后端解析出来的 content：{ sheets, pageConfig, pageConfigs }
     * @param mode     `replace` 整个工作簿换掉 / `append` 追加到末尾
     *
     * `replace` 会把 `cellConfigs` 整个清掉 —— 版式都换了，老绑定说的是旧坐标，留着就是一堆
     * 指向别处的配置，空格子上还会凭空多出扩展行带。
     *
     * `append` 只往后面加，前面每张的下标纹丝不动，所以不需要搬 key。导入那份的**报表级**打印
     * 设置落到新 sheet 各自的 `pageConfigs` 上 —— 本报表的报表级设置是老 sheet 在用的，
     * 不能被导入的那份顶掉。
     */
    importSheets(imported, mode = 'replace') {
      const incoming = normalizeSheets(imported?.sheets)
      const perSheetIn = imported?.pageConfigs || {}
      const reportLevelIn = imported?.pageConfig

      if (mode === 'append') {
        const base = this.content.sheets.length
        const ids = new Set(this.content.sheets.map((s) => s.id))
        const names = new Set(this.content.sheets.map((s) => s.name))
        incoming.forEach((sheet, i) => {
          const index = base + i
          sheet.id = uniqueSheetId(ids, index)
          sheet.name = uniqueSheetName(names, sheet.name || `Sheet${index + 1}`)
          sheet.order = index
          sheet.status = 0
          const own = perSheetIn[String(i)] || reportLevelIn
          if (own) this.content.pageConfigs[String(index)] = normalizePageConfig(own)
        })
        this.content.sheets = [...this.content.sheets, ...incoming]
      } else {
        this.content.sheets = incoming
        this.content.cellConfigs = {}
        // 版式整个换掉了，「哪张是清单页」说的是老模板，一起清掉
        this.content.sheetSplits = {}
        this.content.pageConfig = normalizePageConfig(reportLevelIn)
        const perSheet = {}
        Object.entries(perSheetIn).forEach(([i, cfg]) => {
          perSheet[String(i)] = normalizePageConfig(cfg)
        })
        this.content.pageConfigs = perSheet
        this.sheetIndex = 0
        this.activeCell = null
        this.droppedCellConfigs = []
      }
      this.dirty = true
    },

    /**
     * 行/列插入或删除后，把按坐标寻址的配置跟着挪。
     *
     * `cellConfigs` 的 key 是 `${sheetIndex}_${r}_${c}`、打印区域是 A1 串，说的都是**模板坐标**。
     * FortuneSheet 插入/删除行列时只挪它自己那份数据（celldata / merge / rowlen / borderInfo），
     * 业务层这两处原地不动 —— 删掉顶上一行之后绑定就整体错位一行：占位符文本上移了、配置没动，
     * 于是那个格子成了「有占位符没配置」，而它下面的空格子凭空多出一条扩展行带。
     *
     * `change` 事件只给结果、认不出「哪一行被删了」，所以走 `op` 事件（见 FortuneSheet.vue 的 @op）。
     * op 在 change 之前发出，因此这里挪完，随后的 `setSheets` 正好对着新坐标回收空绑定。
     *
     * 撤销一次删除时 FortuneSheet 会发出一条**恰好相反**的插入 op（`inverseRowColOptions`），
     * 位置和行数与栈顶那次删除完全对得上，就把当时丢掉的配置放回去 —— 否则「删错了、Ctrl+Z」
     * 回来的是一片没有绑定的格子。对不上说明用户又做了别的增删，攒着的坐标不再作数，整个清掉。
     *
     * @param sheetId 发生增删的工作表 id（op 里带的是 id 不是下标）
     * @param op      FortuneSheet 的 insertRowCol / deleteRowCol op
     */
    applyRowColChange(sheetId, op) {
      const change = normalizeRowColOp(op)
      if (!change) return
      const sheetIndex = this.content.sheets.findIndex((s) => s.id === sheetId)
      if (sheetIndex < 0) return

      const { cellConfigs, dropped } = shiftCellConfigs(this.content.cellConfigs, sheetIndex, change)
      this.content.cellConfigs = cellConfigs
      const target = this.printAreaTarget(sheetIndex)
      const printArea = target?.printArea ?? null
      if (target) target.printArea = shiftPrintArea(target.printArea, change)

      if (change.remove) {
        // 没丢配置也要记一笔，撤销时才对得上号（栈是严格后进先出的）
        this.droppedCellConfigs.push({ sheetIndex, change, dropped, printArea })
        if (this.droppedCellConfigs.length > MAX_DROPPED_STASH) this.droppedCellConfigs.shift()
      } else {
        const last = this.droppedCellConfigs[this.droppedCellConfigs.length - 1]
        const undoing =
          last &&
          last.sheetIndex === sheetIndex &&
          last.change.type === change.type &&
          last.change.at === change.at &&
          last.change.count === change.count
        if (undoing) {
          // 坐标已被上面那一挪还原成删除之前的样子，按原 key 放回去即可
          this.content.cellConfigs = { ...this.content.cellConfigs, ...last.dropped }
          // 打印区域被整块删光时挪不回来（已经退成了空串=整表），照原样写回去
          if (target && last.printArea != null) target.printArea = last.printArea
          this.droppedCellConfigs.pop()
        } else {
          this.droppedCellConfigs = []
        }
      }
      this.dirty = true
    },

    /**
     * 该 sheet 的打印区域存在哪份 pageConfig 里。
     *
     * 单独设过就是它自己那份；没设过时生效的是报表级那份，但那份被所有 sheet 共用 ——
     * 多 sheet 报表里跟着某一张的行列增删去挪它，会把别的 sheet 的打印区域一起挪错，
     * 所以只有单 sheet 报表才动它。
     */
    printAreaTarget(sheetIndex) {
      const own = this.content.pageConfigs?.[String(sheetIndex)]
      if (own) return own
      if ((this.content.sheets?.length || 0) === 1) return this.content.pageConfig || null
      return null
    },

    /** 写入/合并单元格配置 */
    setCellConfig(r, c, patch) {
      const key = cellKey(this.sheetIndex, r, c)
      const base = this.content.cellConfigs[key] || defaultCellConfig(this.sheetIndex, r, c)
      this.content.cellConfigs[key] = { ...base, ...patch, sheetIndex: this.sheetIndex, r, c }
      this.dirty = true
    },

    removeCellConfig(r, c) {
      delete this.content.cellConfigs[cellKey(this.sheetIndex, r, c)]
      this.dirty = true
    },

    /** 拖拽字段落到单元格时调用：写入配置并返回应显示的占位符文本 */
    bindField(r, c, datasetCode, field, fieldType = 'string') {
      const formatType = fieldType === 'number' ? 'number' : fieldType === 'date' ? 'date' : 'text'
      this.setCellConfig(r, c, {
        type: 'data',
        datasetCode,
        field,
        expandType: 'down',
        groupType: 'list',
        aggregate: 'none',
        formatType,
        formatPattern: defaultFormatPattern(formatType)
      })
      return `#{${datasetCode}.${field}}`
    },

    setParams(params) {
      this.content.params = params || []
      this.dirty = true
    },

    /**
     * 设/取消主接口（驱动分页的那个数据集）。
     *
     * 单值存储，「一张报表只能有一个」这条约束是结构本身保证的，不需要额外校验；
     * 再点一次当前主接口就是取消。
     */
    setPrimaryDataset(code) {
      this.content.primaryDataset = this.content.primaryDataset === code ? '' : code || ''
      this.dirty = true
    },

    /**
     * 写入页面/打印设置。
     *
     * @param cfg      设置项
     * @param scope    'sheet' 只作用于当前 sheet；'all' 作用于整个报表
     *
     * scope='all' 会连同已有的按 sheet 覆盖一起清掉 —— 用户选「全部工作表」的意思就是
     * 「所有 sheet 都按这份出纸」，留着旧的覆盖会让其中几张纹丝不动，看起来像没生效。
     */
    setPageConfig(cfg, scope = 'sheet') {
      const normalized = normalizePageConfig(cfg)
      if (scope === 'all') {
        this.content.pageConfig = normalized
        this.content.pageConfigs = {}
      } else {
        this.content.pageConfigs[String(this.sheetIndex)] = normalized
      }
      this.dirty = true
    },

    /**
     * 新增/覆盖一条父子关联（子接口查询）。
     *
     * @param link  { name, master, child, mappings:[{param, field}] }
     * @param index 要覆盖的下标，< 0 表示新增
     *
     * 一个子表只能挂一个主表 —— 挂两个的话「这份子表数据怎么来的」有两种解释，
     * 后端 `LinkedDataFetcher.indexByChild` 会直接报错，所以这里先拦住。
     */
    saveDatasetLink(link, index = -1) {
      const dup = this.content.datasetLinks.findIndex((l, i) => i !== index && l.child === link.child)
      if (dup >= 0) {
        throw new Error(`子表「${link.child}」已经挂在「${this.content.datasetLinks[dup].master}」下了`)
      }
      if (index >= 0) {
        this.content.datasetLinks.splice(index, 1, link)
      } else {
        this.content.datasetLinks.push(link)
      }
      this.dirty = true
    },

    removeDatasetLink(index) {
      this.content.datasetLinks.splice(index, 1)
      this.dirty = true
    },

    /**
     * 输出方式（含单据名取哪个字段）：
     * - `single` 单 sheet 输出；
     * - `perRowPage`「多 sheet 输出」：按主接口每条数据拆一份，**首尾相接拼回同一张 sheet**、
     *   每份开头打一个行分页符 —— Excel 里是一张连续的表，打印时一条数据一页；
     * - `perRow`「每条数据一个 sheet」：**同一套拆分不拼接**，直接出 M×N 张 sheet
     *   （M = 跟着拆的模板张数），导出的 Excel 里就是那么多标签页。
     *
     * 跟打印设置一起在打印设置弹窗里配，但它是**报表级**的 —— 决定的是整个工作簿有几张 sheet，
     * 不是某一张 sheet 怎么出纸，所以不进 pageConfigs。
     *
     * 两个拆分模式共用 `sheetNameField`，只是那个值落在哪儿不一样：`perRow` 拿它当 sheet 名，
     * `perRowPage` 拼成一张之后 sheet 名说不了话（整张只有一个名字），它改落在 `mzDocNames` 上
     * 当页头页尾里 `${sheet}` 的值（每张纸印自己那一份的单号）。`single` 用不上，清掉。
     *
     * `sheetSplits` 是**哪几张模板跟着拆**（`{下标: 'once'}` = 那张不跟着拆，是清单页），
     * 整份替换 —— 它是跨表的一项，在打印设置弹窗的「工作表」页签里一张一张勾好再一起写回，
     * 所以不跟「作用范围」走。`single` 时整份清掉：没拆分就没有「跟不跟着拆」这回事。
     */
    setSheetSplit({ splitMode, sheetNameField, sheetSplits }) {
      const mode = ['perRow', 'perRowPage'].includes(splitMode) ? splitMode : 'single'
      this.content.splitMode = mode
      this.content.sheetNameField = mode === 'single' ? '' : sheetNameField || ''
      if (mode === 'single') {
        this.content.sheetSplits = {}
      } else if (sheetSplits) {
        this.content.sheetSplits = normalizeSheetSplits(sheetSplits)
      }
      this.dirty = true
    },

    /**
     * 导出设置（**报表级**）：导出的文件叫什么名字 —— 报表名 + 主接口若干字段值。
     * 后端拼名字时取的是主接口第一行，见 `dto/ExportConfigDTO`。
     */
    setExportConfig(cfg) {
      this.content.exportConfig = normalizeExportConfig(cfg)
      this.dirty = true
    },

    /** 让当前 sheet 回到「跟随报表级设置」 */
    clearPageConfig() {
      delete this.content.pageConfigs[String(this.sheetIndex)]
      this.dirty = true
    },

    markClean() {
      this.dirty = false
    },

    /** 导出用于保存的 content 字符串 */
    serializeContent() {
      return JSON.stringify(deepClone(this.content))
    },

    reset() {
      this.report = { id: '', name: '', code: '', remark: '', type: 'sheet', status: 1, versionConfig: '' }
      this.content = createEmptyContent()
      this.versions = []
      this.versionId = ''
      this.sheetIndex = 0
      this.activeCell = null
      this.droppedCellConfigs = []
      this.dirty = false
    }
  }
})
