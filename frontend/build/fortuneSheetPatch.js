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
 * ── 二、字号清单补上 6 / 7 / 8
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
    name: 'mz-fortune-sheet-patch-v2',
    setup(build) {
      build.onLoad({ filter: /[\\/]@fortune-sheet[\\/]react[\\/]dist[\\/]index\.esm\.js$/ }, async (args) => {
        const { readFile } = await import('node:fs/promises')
        const code = await readFile(args.path, 'utf8')
        return { contents: patchFortuneSheetSource(code), loader: 'js' }
      })
    }
  }
}
