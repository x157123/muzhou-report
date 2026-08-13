/**
 * 给 @fortune-sheet/react 1.0.4 的工具栏打补丁。
 *
 * 边框菜单、字号清单这些东西全写死在 @fortune-sheet/react 的 Toolbar 里（内部组件不导出、
 * 也没有对应的 settings 项），Vue 桥接层碰不到，官方 1.0.4 已是最新版、这几个毛病没修。
 * 所以在**构建期改它的源码**：不动 node_modules（`npm ci` 之后照样生效），
 * 补丁本身在本仓库里看得见、改得动。
 *
 * ── 一、「边框」下拉菜单的三个 bug（纯修复，不改交互设计）
 *
 * ① **鼠标划到「边框颜色」「边框样式」上大概率不弹二级菜单**
 *    `showBorderSubMenu` 拿的是 `e.target`（指针实际落到的那个子元素）而不是 `e.currentTarget`
 *    （挂着 onMouseEnter 的那个选项 div）。选项里绝大部分面积被 `.fortune-toolbar-menu-line`
 *    与色块占着，从那儿进入时 `target.querySelector(".fortune-border-select-menu")` 找不到东西
 *    直接 return —— 只有正好从选项的 padding 上划进去才弹得出来，所以是「有时候能、多数不能」。
 *    顺带修掉左右翻转的判定：那里拿容器**宽度**去减选项的**视口右边界**，设计器左边有面板时
 *    这个差恒为负，二级菜单永远被翻到左边压在主菜单上；同文件的 `showSubMenu` 用的是
 *    `workbookContainerRect.right`，照它对齐。
 *
 * ② **再次打开边框菜单，不回显上次选的颜色/线型**
 *    颜色与线型有两份状态：Toolbar 里的 `customColor/customStyle`（真正拿去画边框的，常驻）
 *    与 CustomBorder 里的 `changeColor/changeStyle`（画预览用的）。CustomBorder 只在菜单展开时
 *    挂载，一关就卸，重开时后者复位成「黑色 + 1 号线」，于是色块、线型预览、取色器里的
 *    `<input type="color">` 全回到初始值。补丁把 Toolbar 的当前值传下去当初值。
 *
 * ③ **接着上一条：重开菜单后再选一次颜色，会把上次选的线型悄悄改回 1 号**
 *    这是 ② 的连带后果，也是最要命的一条 —— 它不只是显示问题。选颜色时执行的是
 *    `onPick(color, changeStyle)`，选线型时是 `onPick(changeColor, style)`，两个手都会把
 *    **另一维**的值一并写回 Toolbar；而重开后 `changeColor/changeStyle` 是复位过的默认值，
 *    于是「选完红色关掉菜单、重开再挑一种线型」得到的是黑色边框。② 把初值接上之后这条自然消失。
 *
 * ── 二、右键菜单的「行高」「列宽」对**选中的格子**也生效
 *
 * 这两项（`set-row-height` / `set-column-width`）官方本来就有，输入框、批量写回选区、
 * 校验都是现成的 —— 唯独**只在整行/整列选区上才渲染**（`row_select` / `column_select`，
 * 也就是必须先点行号或列标）。而报表设计时最顺手的是「框选一片格子 → 右键 → 把这几行调成同一高度」，
 * 那时两项都不出现，用户以为没有这个功能，只能一行行拖。
 *
 * 改法是把条件反过来：**整列选区不给「行高」、整行选区不给「列宽」**（Excel 也是这样：
 * 右键列标只给列宽），其余情况两项都给。整行/整列选区上的表现与改之前一字不差，
 * 多出来的只有「框选格子」这一种情况 —— 写回的范围就是选区覆盖的那几行/那几列。
 *
 * **输入框的默认值改成选区的平均行高/列宽**（`mzAvgLen`，四舍五入取整）。原文是「各行一样高就
 * 显示那个高度、只要有一行不一样就**显示空**」，而框选一片格子时高矮不齐才是常态，于是那个框
 * 十有八九是空的 —— 想微调（比如「都调到 30」）得先自己去别处看现在是多少。取平均至少给出一个
 * 有意义的起点，回车即用也不会把版式改乱多少。它判「齐不齐」用的 `height_move` 是**带缩放**的
 * 像素值，改用 `config.rowlen` / `columnlen` 直接算，与我们自己那份 `utils/print.js#rowHeight` 同源。
 *
 * 顺带两处：**输入框从 40px 放宽到 64px**（写死在行内 style 里，40px 装不下三位数 —— 默认列宽
 * 就是 73，一进去就看不全）；**「列宽」输入框的 `max`** 原文抄的是行高那份的 545，而它自己的
 * 校验放到 2038（见同一段的 `parseInt(targetColWidth, 10) > 2038`），于是宽表的列用微调按钮点不上去。
 *
 * ── 三、字号清单补上 6 / 7 / 8
 *
 * 工具栏字号下拉的那串数字是**写死在 JSX 里的字面量数组**（既不在 locale 里、也不是 settings 项，
 * 所以不像字体清单那样能从 `utils/fontList.js` 那条路换掉），最小只到 9。报表上的备注、
 * 落款、编号这类小字常要 6~8 号，只好把它一起打进来。`handleTextSize` 那边不做范围限制，
 * 自动换行的行高（`utils/wrapHeight.js` 与 `ExcelExporter#applyWrapRowHeights`）也是按字号算的，
 * 所以这一项只需要改清单本身。
 *
 * 补丁是按源码原文做的字符串替换，锚点找不到就**直接报错中断构建**（而不是静默跳过）——
 * 升级 @fortune-sheet/react 时会立刻暴露出来，比「装完发现边框菜单又坏了」强。
 * 升级后请逐条核对上游是否已经修掉，修掉的那条就从这里删。
 */

/** 待打补丁的文件（@fortune-sheet/react 的 ESM 产物，dev 预构建与 build 都走它） */
const TARGET = '@fortune-sheet/react/dist/index.esm.js'

const REPLACEMENTS = [
  {
    name: '二级菜单：用 currentTarget 找菜单节点',
    from: `    var target = e.target;
    var menuItemRect = target.getBoundingClientRect();`,
    to: `    var target = e.currentTarget;
    var menuItemRect = target.getBoundingClientRect();`
  },
  {
    name: '二级菜单：左右翻转按容器右边界判定',
    from: `    if (workbookContainerRect.width - menuItemRect.right > parseFloat(subMenuItem.style.width.replace("px", ""))) {`,
    to: `    if (workbookContainerRect.right - menuItemRect.right > parseFloat(subMenuItem.style.width.replace("px", ""))) {`
  },
  {
    name: '回显：CustomBorder 收下上层传来的当前颜色/线型',
    from: `var CustomBorder = function CustomBorder(_ref) {
  var onPick = _ref.onPick;`,
    to: `function mzBorderPreviewOf(text) {
  var found = size.find(function (s) {
    return s.Text === text;
  });
  return found || {
    strokeWidth: "1",
    strokeDasharray: "1,0"
  };
}
var CustomBorder = function CustomBorder(_ref) {
  var onPick = _ref.onPick;
  var mzColor = _ref.borderColor || "#000000";
  var mzStyle = _ref.borderStyle || "1";`
  },
  {
    name: '回显：颜色/线型状态的初值',
    from: `  var _useState = useState("#000000"),
    _useState2 = _slicedToArray(_useState, 2),
    changeColor = _useState2[0],
    setchangeColor = _useState2[1];
  var _useState3 = useState("1"),`,
    to: `  var _useState = useState(mzColor),
    _useState2 = _slicedToArray(_useState, 2),
    changeColor = _useState2[0],
    setchangeColor = _useState2[1];
  var _useState3 = useState(mzStyle),`
  },
  {
    name: '回显：线型预览线的初值',
    from: `  var _useState5 = useState(""),
    _useState6 = _slicedToArray(_useState5, 2),
    previewWith = _useState6[0],
    setPreviewWith = _useState6[1];
  var _useState7 = useState(""),`,
    to: `  var _useState5 = useState(mzBorderPreviewOf(mzStyle).strokeWidth),
    _useState6 = _slicedToArray(_useState5, 2),
    previewWith = _useState6[0],
    setPreviewWith = _useState6[1];
  var _useState7 = useState(mzBorderPreviewOf(mzStyle).strokeDasharray),`
  },
  {
    name: '回显：取色器的 <input type="color"> 跟着当前颜色',
    from: `  }, /*#__PURE__*/React.createElement(CustomColor, {
    onCustomPick: function onCustomPick(color) {
      onPick(color, changeStyle);`,
    to: `  }, /*#__PURE__*/React.createElement(CustomColor, {
    value: changeColor,
    onCustomPick: function onCustomPick(color) {
      onPick(color, changeStyle);`
  },
  {
    // CustomColor 是字体色/背景色共用的，不传 value 时行为不变
    name: '回显：CustomColor 接受 value 作为输入框初值',
    from: `var CustomColor = function CustomColor(_ref) {
  var onCustomPick = _ref.onCustomPick,
    onColorPick = _ref.onColorPick;
  var _useContext = useContext(WorkbookContext),
    context = _useContext.context;
  var _locale = locale(context),
    toolbar = _locale.toolbar,
    sheetconfig = _locale.sheetconfig,
    button = _locale.button;
  var _useState = useState("#000000"),`,
    to: `var CustomColor = function CustomColor(_ref) {
  var onCustomPick = _ref.onCustomPick,
    onColorPick = _ref.onColorPick;
  var _useContext = useContext(WorkbookContext),
    context = _useContext.context;
  var _locale = locale(context),
    toolbar = _locale.toolbar,
    sheetconfig = _locale.sheetconfig,
    button = _locale.button;
  var _useState = useState(_ref.value || "#000000"),`
  },
  {
    name: '回显：Toolbar 把常驻的颜色/线型传进 CustomBorder',
    from: `        }), /*#__PURE__*/React.createElement(CustomBorder, {
          onPick: function onPick(color, style) {`,
    to: `        }), /*#__PURE__*/React.createElement(CustomBorder, {
          borderColor: customColor,
          borderStyle: customStyle,
          onPick: function onPick(color, style) {`
  },
  {
    name: '右键菜单：选中格子时也给出「行高」',
    from: `      return ((_context$luckysheet_s9 = context.luckysheet_select_save) === null || _context$luckysheet_s9 === void 0 ? void 0 : _context$luckysheet_s9.some(function (section) {
        return section.row_select;
      })) ? (/*#__PURE__*/React.createElement(Menu, {
        key: "set-row-height",`,
    to: `      return !(context.luckysheet_select_save || []).some(function (section) {
        return section.column_select;
      }) ? (/*#__PURE__*/React.createElement(Menu, {
        key: "set-row-height",`
  },
  {
    name: '右键菜单：选中格子时也给出「列宽」',
    from: `      return ((_context$luckysheet_s1 = context.luckysheet_select_save) === null || _context$luckysheet_s1 === void 0 ? void 0 : _context$luckysheet_s1.some(function (section) {
        return section.column_select;
      })) ? (/*#__PURE__*/React.createElement(Menu, {`,
    to: `      return !(context.luckysheet_select_save || []).some(function (section) {
        return section.row_select;
      }) ? (/*#__PURE__*/React.createElement(Menu, {`
  },
  {
    name: '右键菜单：算选区平均行高/列宽的辅助函数',
    from: `var ContextMenu = function ContextMenu() {`,
    to: `/**
 * 选区覆盖的那几行（那几列）的平均行高（列宽），四舍五入取整。
 * lens 是当前工作表的 config.rowlen / config.columnlen，没记过的行列按默认值算。
 */
function mzAvgLen(sections, key, lens, fallback) {
  var sum = 0;
  var count = 0;
  (sections || []).forEach(function (section) {
    var range = section[key];
    if (!range) return;
    for (var i = range[0]; i <= range[1]; i += 1) {
      var v = lens == null ? undefined : lens[i];
      sum += typeof v === "number" && isFinite(v) ? v : fallback;
      count += 1;
    }
  });
  return count > 0 ? Math.round(sum / count) : fallback;
}
var ContextMenu = function ContextMenu() {`
  },
  {
    name: '右键菜单：「行高」的默认值取选中各行的平均值',
    from: `      var rowHeight = (selection === null || selection === void 0 ? void 0 : selection.height) || context.defaultrowlen;
      var shownRowHeight = ((_context$luckysheet_s8 = context.luckysheet_select_save) === null || _context$luckysheet_s8 === void 0 ? void 0 : _context$luckysheet_s8.some(function (section) {
        return section.height_move !== (rowHeight + 1) * (section.row[1] - section.row[0] + 1) - 1;
      })) ? "" : rowHeight;`,
    to: `      var shownRowHeight = mzAvgLen(context.luckysheet_select_save, "row", context.config && context.config.rowlen, context.defaultrowlen);`
  },
  {
    name: '右键菜单：「列宽」的默认值取选中各列的平均值',
    from: `      var colWidth = (selection === null || selection === void 0 ? void 0 : selection.width) || context.defaultcollen;
      var shownColWidth = ((_context$luckysheet_s0 = context.luckysheet_select_save) === null || _context$luckysheet_s0 === void 0 ? void 0 : _context$luckysheet_s0.some(function (section) {
        return section.width_move !== (colWidth + 1) * (section.column[1] - section.column[0] + 1) - 1;
      })) ? "" : colWidth;`,
    to: `      var shownColWidth = mzAvgLen(context.luckysheet_select_save, "column", context.config && context.config.columnlen, context.defaultcollen);`
  },
  {
    name: '右键菜单：「行高」输入框加宽',
    from: `        defaultValue: shownRowHeight,
        style: {
          width: "40px"
        }`,
    to: `        defaultValue: shownRowHeight,
        style: {
          width: "64px"
        }`
  },
  {
    name: '右键菜单：「列宽」输入框加宽',
    from: `        defaultValue: shownColWidth,
        style: {
          width: "40px"
        }`,
    to: `        defaultValue: shownColWidth,
        style: {
          width: "64px"
        }`
  },
  {
    // getMenuElement 是 useCallback 记住的，deps 里没有 config：拖过行高之后不换选区直接右键，
    // 拿到的会是上一份 config，平均值按老行高算。原文只读 selection 所以碰不到，我们读了就得补上。
    name: '右键菜单：getMenuElement 的依赖补上 config',
    from: `  }, [context.currentSheetId, context.lang, context.luckysheet_select_save, context.defaultrowlen, context.defaultcollen, rightclick, info, setContext, showAlert, showDialog, drag, generalDialog]);`,
    to: `  }, [context.currentSheetId, context.lang, context.config, context.luckysheet_select_save, context.defaultrowlen, context.defaultcollen, rightclick, info, setContext, showAlert, showDialog, drag, generalDialog]);`
  },
  {
    name: '右键菜单：「列宽」输入框的上限按列宽算（原文抄的是行高的 545）',
    from: `}, rightclick.column, rightclick.width, /*#__PURE__*/React.createElement("input", {
        onClick: function onClick(e) {
          return e.stopPropagation();
        },
        onKeyDown: function onKeyDown(e) {
          return e.stopPropagation();
        },
        tabIndex: 0,
        type: "number",
        min: 1,
        max: 545,`,
    to: `}, rightclick.column, rightclick.width, /*#__PURE__*/React.createElement("input", {
        onClick: function onClick(e) {
          return e.stopPropagation();
        },
        onKeyDown: function onKeyDown(e) {
          return e.stopPropagation();
        },
        tabIndex: 0,
        type: "number",
        min: 1,
        max: 2038,`
  },
  {
    name: '字号清单补上 6 / 7 / 8',
    from: `[9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72].map(`,
    to: `[6, 7, 8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72].map(`
  }
]

/**
 * 对 index.esm.js 的源码做替换。锚点缺失或出现多处都抛错 —— 前者说明上游改了实现，
 * 后者说明锚点不够唯一，两种情况都不能靠猜。
 */
export function patchFortuneSheetSource(code) {
  return REPLACEMENTS.reduce((acc, { name, from, to }) => {
    const first = acc.indexOf(from)
    if (first < 0) {
      throw new Error(
        `[fortune-sheet-patch] 找不到锚点「${name}」：@fortune-sheet/react 的实现已变，` +
          '请到 frontend/build/fortuneSheetPatch.js 逐条核对（上游修掉的就删掉那一条）。'
      )
    }
    if (acc.indexOf(from, first + 1) >= 0) {
      throw new Error(`[fortune-sheet-patch] 锚点「${name}」匹配到多处，需要更长的上下文。`)
    }
    return acc.slice(0, first) + to + acc.slice(first + from.length)
  }, code)
}

function isTarget(path) {
  return path.replace(/\\/g, '/').endsWith(TARGET)
}

/** vite build（rollup）那条路：普通插件的 transform 就能改到 node_modules 里的文件 */
export function fortuneSheetPatchPlugin() {
  return {
    name: 'mz-fortune-sheet-patch',
    enforce: 'pre',
    transform(code, id) {
      if (!isTarget(id)) return null
      return { code: patchFortuneSheetSource(code), map: null }
    }
  }
}

/**
 * vite dev 那条路：@fortune-sheet/react 在 optimizeDeps.include 里，会被 esbuild 预构建，
 * **预构建不走 Vite 插件的 transform**，所以同一份补丁还要以 esbuild 插件的形式挂一遍。
 *
 * 注意：预构建缓存的 hash 只认插件的 name（见 vite 的 getConfigHash），
 * **改了补丁内容一定要把下面的版本号加一**，否则 dev 会继续用旧缓存。
 */
export function fortuneSheetPatchEsbuildPlugin() {
  return {
    name: 'mz-fortune-sheet-patch-v5',
    setup(build) {
      build.onLoad({ filter: /[\\/]@fortune-sheet[\\/]react[\\/]dist[\\/]index\.esm\.js$/ }, async (args) => {
        const { readFile } = await import('node:fs/promises')
        const code = await readFile(args.path, 'utf8')
        return { contents: patchFortuneSheetSource(code), loader: 'js' }
      })
    }
  }
}
