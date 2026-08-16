<!--
  打印设置弹窗：编辑当前 sheet 生效的打印设置的副本，点击确定才写回 store。
  设置同时作用于：设计器画布上的分页线、预览页浏览器打印、Excel / PDF / Word 导出的页面设置。
  作用范围三选一：「仅当前工作表」「全部工作表」（会清掉各表的独立设置）「跟随报表设置」——
  一份宽表和一份窄表可以各用各的纸张方向。
  多工作表报表另有一个「工作表」页签：一眼看全每张的纸张/独立设置/打印区域，
  并在那里逐张勾「只出一份」（拆分时不跟着拆的清单页）——它是跨表的一项，不该逼用户切表。
-->
<template>
  <el-dialog v-model="visible" :title="title" width="660px" :close-on-click-modal="false" destroy-on-close>
    <el-form v-if="multiSheet" label-width="96px" size="small">
      <el-form-item label="作用范围">
        <div class="scope-row">
          <el-radio-group v-model="scope">
            <el-radio-button value="sheet">仅「{{ sheetName }}」</el-radio-button>
            <el-radio-button value="all">全部工作表</el-radio-button>
            <!-- 本来就没有独立设置时这一项没有意义，灰掉，免得点了以为改了什么 -->
            <el-radio-button value="follow" :disabled="!store.hasOwnPageConfig">跟随报表设置</el-radio-button>
          </el-radio-group>
          <span v-if="!store.hasOwnPageConfig && scope !== 'all'" class="text-muted">
            当前跟随报表设置
          </span>
        </div>
      </el-form-item>
      <!-- 「全部工作表」会把各表的独立设置一起清掉，这事得在点确定之前就说清楚 -->
      <el-alert
        v-if="scope === 'all' && ownConfigSheets.length"
        type="warning"
        :closable="false"
        show-icon
        style="margin: -8px 0 12px"
        :title="`将清除 ${ownConfigSheets.length} 张工作表的独立设置：${ownConfigSheets.join('、')}`"
      />
      <el-alert
        v-if="scope === 'follow'"
        type="info"
        :closable="false"
        show-icon
        style="margin: -8px 0 12px"
        :title="`「${sheetName}」将改回跟随报表设置，下面「页面 / 页头页尾 / 水印」三项显示的就是报表级的值`"
      />
    </el-form>

    <el-tabs v-model="tab">
      <!-- ------------------------------ 页面 ------------------------------ -->
      <el-tab-pane label="页面" name="page">
        <el-form label-width="96px" size="small" :disabled="followMode">
          <el-form-item label="纸张">
            <el-select v-model="form.paperSize" style="width: 100%">
              <el-option v-for="p in PAPER_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
            </el-select>
          </el-form-item>

          <el-form-item label="方向">
            <el-radio-group v-model="form.orientation">
              <el-radio-button v-for="o in ORIENTATIONS" :key="o.value" :value="o.value">
                {{ o.label }}
              </el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="页边距(mm)">
            <div class="margin-grid">
              <el-input-number v-model="form.marginTop" :min="0" :max="100" :controls="false" size="small">
                <template #prefix><span class="unit">上</span></template>
              </el-input-number>
              <el-input-number v-model="form.marginBottom" :min="0" :max="100" :controls="false" size="small">
                <template #prefix><span class="unit">下</span></template>
              </el-input-number>
              <el-input-number v-model="form.marginLeft" :min="0" :max="100" :controls="false" size="small">
                <template #prefix><span class="unit">左</span></template>
              </el-input-number>
              <el-input-number v-model="form.marginRight" :min="0" :max="100" :controls="false" size="small">
                <template #prefix><span class="unit">右</span></template>
              </el-input-number>
            </div>
          </el-form-item>

          <el-form-item label="缩放">
            <el-input-number v-model="form.scale" :min="10" :max="400" :step="10" :disabled="form.fitToWidth" />
            <span class="text-muted" style="margin-left: 8px">%</span>
            <el-checkbox v-model="form.fitToWidth" style="margin-left: 16px">所有列打印在一页宽内</el-checkbox>
          </el-form-item>

          <el-form-item label="限制宽度">
            <div class="limit-row">
              <el-switch v-model="form.limitWidth" :disabled="form.fitToWidth" />
              <span class="text-muted">
                {{
                  form.fitToWidth
                    ? '已勾选「所有列打印在一页宽内」，打印时会自动压缩，无需限宽'
                    : `拖动列宽时，会被打印的那些列合计不超过一页可打印宽度（${limitPx} px）；关闭后可随意拖动`
                }}
              </span>
            </div>
          </el-form-item>

          <el-form-item label="打印区域">
            <div class="area-row">
              <el-input v-model="form.printArea" placeholder="留空=有内容的区域，例如 A1:F30" />
              <el-button @click="useSelection">取当前选区</el-button>
              <el-button :disabled="!form.printArea" @click="form.printArea = ''">清除</el-button>
            </div>
          </el-form-item>

          <el-form-item label="顶端标题行">
            <div class="area-row">
              <el-input v-model="form.titleRows" :placeholder="`留空=不重复，例如 ${titleRowFirst}:${titleRowFirst + 1}`" />
              <el-button @click="useSelectionRows">取当前选区行</el-button>
              <el-button :disabled="!form.titleRows" @click="form.titleRows = ''">清除</el-button>
            </div>
            <div class="text-muted" style="width: 100%; margin-top: 4px">
              这几行会在<b>每一页顶部重复</b>（导出的 Excel / PDF / Word 都生效）；
              只能是{{ form.printArea ? '打印区域' : '表格' }}最上面的连续若干行，也就是从第
              {{ titleRowFirst }} 行起。
            </div>
          </el-form-item>

          <el-form-item label="超高行">
            <el-radio-group v-model="form.rowOverflow">
              <el-radio-button value="slice">横切跨页</el-radio-button>
              <el-radio-button value="split">续行</el-radio-button>
            </el-radio-group>
            <div class="text-muted" style="width: 100%; margin-top: 4px">
              一行（比如长备注格）比一页还高时怎么出纸：<b>横切跨页</b>= 从中间切开接着印，
              切口两边的格子各缺一条横边；<b>续行</b>= 装不下的文字另起一行接着印，
              每页上都是边框闭合的完整格子，同一行里那些短格子也跟着留在第一页。
              <b>只对导出的 PDF / Word 生效</b> —— Excel 里一行就是一行，浏览器打印同理。
            </div>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ---------------------------- 页头页尾 ---------------------------- -->
      <el-tab-pane label="页头页尾" name="headerFooter">
        <el-form label-width="96px" size="small" :disabled="followMode">
          <el-form-item label="页头">
            <div class="hf-grid">
              <el-input
                v-for="s in SECTIONS"
                :key="`header-${s.key}`"
                v-model="form.header[s.key]"
                :placeholder="s.label"
                @focus="target = { part: 'header', section: s.key }"
              />
            </div>
          </el-form-item>
          <el-form-item label="页头样式">
            <div class="style-row">
              <el-input-number v-model="form.header.fontSize" :min="6" :max="72" size="small" />
              <span class="text-muted">pt，距纸张上边</span>
              <el-input-number v-model="form.header.margin" :min="0" :max="100" size="small" />
              <span class="text-muted">mm</span>
            </div>
          </el-form-item>

          <el-divider style="margin: 4px 0 12px" />

          <el-form-item label="页尾">
            <div class="hf-grid">
              <el-input
                v-for="s in SECTIONS"
                :key="`footer-${s.key}`"
                v-model="form.footer[s.key]"
                :placeholder="s.label"
                @focus="target = { part: 'footer', section: s.key }"
              />
            </div>
          </el-form-item>
          <el-form-item label="页尾样式">
            <div class="style-row">
              <el-input-number v-model="form.footer.fontSize" :min="6" :max="72" size="small" />
              <span class="text-muted">pt，距纸张下边</span>
              <el-input-number v-model="form.footer.margin" :min="0" :max="100" size="small" />
              <span class="text-muted">mm</span>
            </div>
          </el-form-item>

          <el-form-item label="插入占位符">
            <div class="token-row">
              <el-tag
                v-for="p in HEADER_PLACEHOLDERS"
                :key="p.token"
                class="token"
                type="info"
                effect="plain"
                @click="insertToken(p.token)"
              >
                {{ p.label }}
              </el-tag>
              <span class="text-muted">点击插入到「{{ targetLabel }}」</span>
            </div>
          </el-form-item>

          <div class="tips">
            <p>页头页尾画在页边距里，上/下页边距不够高时正文会自动往下让（和 Excel 一样）。</p>
            <p><b>页头页尾在导出的 Excel / PDF / Word 里生效</b>；预览页用浏览器打印时不出现 —— 浏览器画不到页边距里，也给不出页码。</p>
            <p>
              <b>没写页码的 sheet 不占页数</b>：封面、总结这类没放
              <code>${page}</code> / <code>${pages}</code> 的工作表整张跳过，正文那张从「第 1 页」数起、
              「共几页」也只数正文自己的页。按数据拆单据时两条叠加 ——
              一条数据出「封面 + 正文 + 总结」时，<b>「共几页」数的是这一条数据的正文页</b>。
              <b>PDF 两个数都准</b>（预览与「打印」走的就是它）；
              导出的 Excel 只能把正文的起始页号钉成 1，「共几页」是 Excel 自己数的整本页数，改不了。
            </p>
          </div>
        </el-form>
      </el-tab-pane>

      <!-- ------------------------------ 水印 ------------------------------ -->
      <el-tab-pane label="水印" name="watermark">
        <el-form label-width="96px" size="small" :disabled="followMode">
          <el-form-item label="水印文字">
            <el-input v-model="form.watermark.text" placeholder="留空 = 不加水印，例如 内部资料" clearable />
          </el-form-item>
          <el-form-item label="字号">
            <el-input-number v-model="form.watermark.fontSize" :min="8" :max="200" :step="10" size="small" />
            <span class="text-muted" style="margin-left: 8px">pt</span>
          </el-form-item>
          <el-form-item label="颜色">
            <el-color-picker v-model="form.watermark.color" />
            <span class="text-muted" style="margin-left: 12px">{{ form.watermark.color }}</span>
          </el-form-item>
          <el-form-item label="不透明度">
            <el-slider v-model="form.watermark.opacity" :min="0" :max="100" show-input size="small" />
          </el-form-item>
          <el-form-item label="倾斜角度">
            <el-slider v-model="form.watermark.rotation" :min="-90" :max="90" :step="5" show-input size="small" />
          </el-form-item>

          <el-form-item label="效果">
            <div class="wm-preview">
              <span v-if="form.watermark.text" :style="watermarkPreviewStyle">{{ form.watermark.text }}</span>
              <span v-else class="text-muted">没有水印</span>
            </div>
          </el-form-item>

          <div class="tips">
            <p>水印作用于 PDF / Word 导出和预览页的浏览器打印，每页都画一个。</p>
            <p><b>导出的 Excel 不带水印</b>：Excel 本身没有水印这个概念。Word 里的水印在正文下面，会被有底色的单元格挡住。</p>
          </div>
        </el-form>
      </el-tab-pane>

      <!-- ------------------------------ 输出 ------------------------------ -->
      <el-tab-pane label="输出" name="output">
        <el-form label-width="96px" size="small">
          <el-form-item label="输出方式">
            <el-radio-group v-model="split.splitMode" :disabled="!canSplit">
              <el-radio-button value="single">单 sheet 输出</el-radio-button>
              <el-radio-button value="perRowPage">多 sheet 输出</el-radio-button>
              <el-radio-button value="perRow">每条数据一个 sheet</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="主接口">
            <span v-if="primary" class="mono">{{ primary.name }}（{{ primary.code }}）</span>
            <span v-else class="text-muted">未设置 —— 在左侧数据集面板点 ☆ 指定一个</span>
          </el-form-item>

          <el-form-item v-if="split.splitMode !== 'single'" label="单据名">
            <el-select
              v-model="split.sheetNameField"
              placeholder="取主接口的哪个字段"
              clearable
              filterable
              style="width: 260px"
            >
              <el-option
                v-for="f in primaryFields"
                :key="f.fieldName"
                :label="`${f.fieldText || f.fieldName} (${f.fieldName})`"
                :value="f.fieldName"
              />
            </el-select>
            <span class="text-muted" style="margin-left: 8px">留空 = 第1条、第2条…</span>
          </el-form-item>
          <div v-if="split.splitMode === 'perRowPage'" class="tips" style="margin-top: -8px">
            <p>
              「多 sheet 输出」把 N 份拼回同一张 sheet，工作表名对每一份都一样 —— 这里配的名字给
              <b>页头页尾里的 <code>${sheet}</code></b> 用，每张纸印自己那一份的单号。
              <b>只有 PDF（含预览页的打印）逐份换名</b>，Excel / Word 里 <code>${sheet}</code> 恒是工作表名。
            </p>
          </div>
          <div v-if="split.splitMode === 'perRow'" class="tips" style="margin-top: -8px">
            <p>
              「每条数据一个 sheet」拿这里配的名字<b>当工作表名</b>（重名自动挂 <code>(2)</code>，
              多模板再补上模板名），页头页尾里的 <code>${sheet}</code> 三种格式都印得出来。
            </p>
          </div>

          <div class="tips">
            <p v-if="!primary">
              <b>要先设主接口</b>：拆分是按主接口返回的每一行拆的，没有主接口就不知道按谁拆。
            </p>
            <p v-else-if="primary.resultType === 'page'">
              <b>主接口「{{ primary.name }}」是分页型</b>，它已经在按页取数了，不再支持按行拆 sheet；
              要拆请把它的「返回结果」改成集合。
            </p>
            <p v-else>
              拆分是把当前这份模板整份复制 N 遍（N = 主接口的行数），第 i 份只喂第 i 行数据，
              <b>其它数据集每份都拿全量</b>。一条数据一张单据的报表就是这么出的。
            </p>
            <p v-if="split.splitMode === 'perRowPage'">
              <b>「多 sheet 输出」</b>= 复制出来的 N 份<b>首尾相接拼回同一张 sheet</b>，
              并在每份的开头打一个分页符。Excel 里是一张连续的表（能整体滚动、筛选），
              打印/导出 PDF 时<b>一条数据占一页</b>，不会两条挤在同一张纸上。
              打印设置不同的相邻份会断成几张 sheet，「多 sheet」由此而来 ——
              <b>它不是「一条数据一张工作表」</b>，要那样请选右边那项。
            </p>
            <p v-if="split.splitMode === 'perRow'">
              <b>「每条数据一个 sheet」</b>= 复制出来的 N 份<b>各自成一张工作表</b>，
              导出的 Excel 里就是 N 个标签页。模板有 M 张 sheet 时一共出 <b>M×N 张</b>，
              顺序是「数据1的模板1/2/3、数据2的模板1/2/3」—— 同一条数据的几张单据挨着，
              但工作表一多就不好翻，只要「一条数据一页纸」的话选左边的「多 sheet 输出」更省事。
            </p>
            <p v-if="canSplitSheet">
              <template v-if="onceSheetNames.length">
                <b>清单页：{{ onceSheetNames.join('、') }}</b> —— 这几张不跟着拆，各出一份、拿全量数据，
                <b>自成一份单据</b>（页头页尾里的 <code>${page}</code> 从 1 数起），
                <b>也不会被拼进单据那张 sheet 里</b>（哪怕打印设置一模一样）。
              </template>
              <template v-else>眼下每张模板都跟着拆。</template>
              要做「第一张清单列表 + 每条数据一份详情」，去
              <el-link type="primary" :underline="false" @click="tab = 'sheets'">「工作表」页签</el-link>
              把清单那张勾成「只出一份」。
            </p>
            <p>
              打印设置按模板的 sheet 走：模板第 2 张设成横向，拆出来每一份的第 2 张都是横向。
              最多 200 条，超了会直接报错 —— 先用报表参数把数据筛小。
            </p>
          </div>
        </el-form>
      </el-tab-pane>
      <!-- ------------------------------ 导出 ------------------------------ -->
      <el-tab-pane label="导出" name="export">
        <el-form label-width="96px" size="small">
          <el-form-item label="文件名">
            <el-checkbox v-model="exportCfg.withReportName">以报表名「{{ reportName }}」开头</el-checkbox>
          </el-form-item>

          <el-form-item label="拼接字段">
            <el-select
              v-model="exportCfg.fields"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="拼哪几段（可多选，按选中顺序拼）"
              style="width: 100%"
            >
              <el-option-group label="当前时间">
                <el-option label="当前时间（yyyyMMddHHmmss）" value="${now}" />
              </el-option-group>
              <el-option-group v-if="primaryParams.length" label="主接口参数">
                <el-option
                  v-for="p in primaryParams"
                  :key="p.paramName"
                  :label="`${p.paramText || p.paramName} (\${${p.paramName}})`"
                  :value="`\${${p.paramName}}`"
                />
              </el-option-group>
              <el-option-group v-if="primaryFields.length" label="主接口字段">
                <el-option
                  v-for="f in primaryFields"
                  :key="f.fieldName"
                  :label="`${f.fieldText || f.fieldName} (${f.fieldName})`"
                  :value="f.fieldName"
                />
              </el-option-group>
            </el-select>
            <span v-if="!primary" class="text-muted" style="margin-top: 4px">
              未设主接口 —— 在左侧数据集面板点 ☆ 指定一个，这里才有字段和参数可选（当前时间不受影响）
            </span>
          </el-form-item>

          <el-form-item label="连接符">
            <el-input v-model="exportCfg.separator" style="width: 120px" maxlength="8" />
            <span class="text-muted" style="margin-left: 8px">各段之间拼什么，默认下划线</span>
          </el-form-item>

          <el-form-item label="示例">
            <span class="mono">{{ exportNameSample }}.xlsx</span>
            <span class="text-muted" style="margin-left: 8px">
              （字段/参数位置这里用<b>名字</b>示意，实际是那一行的值；时间是真的当前时间）
            </span>
          </el-form-item>

          <div class="tips">
            <p>
              导出的 <b>Excel / PDF / Word</b> 都用这个名字。
              <b>字段值只在主接口恰好取到一条数据时才拼</b> —— 取回 200 条时第一条代表不了整份，
              拼出来的「销售出库单_SO-001」里其实装着 200 张单，名字反而误导，所以那几段整段跳过。
            </p>
            <p>
              <b>「当前时间」和「主接口参数」不看数据，多条数据时照样拼得出来</b> ——
              一批一批导出的报表靠它们分得清是哪次、哪个条件导的。参数取的是这次渲染实际用的值
              （报表参数、全局参数、地址栏透传的都算，没传值的参数整段跳过）；下拉里没有的参数名
              可以直接手打 <code>${参数名}</code> 回车。
            </p>
            <p>
              取不到值的字段整段跳过（不会留下空的连接符）；文件名里不能用的字符
              <code>\ / : * ? " &lt; &gt; |</code> 会被去掉。一段都拼不出来时退回报表名。
            </p>
            <p>
              预览页的「打印」走的是浏览器打印对话框，<b>那里的文件名由浏览器决定</b>，这里管不到。
            </p>
          </div>
        </el-form>
      </el-tab-pane>

      <!-- ---------------------------- 工作表一览 ---------------------------- -->
      <el-tab-pane v-if="multiSheet" label="工作表" name="sheets">
        <el-table :data="sheetRows" size="small" :border="false" max-height="360">
          <el-table-column label="工作表" min-width="130">
            <template #default="{ row }">
              <span :class="{ 'row-current': row.current }">{{ row.name }}</span>
              <el-tag v-if="row.current" size="small" type="info" style="margin-left: 6px">当前</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="纸张" prop="paper" width="100" />
          <el-table-column label="打印设置" width="100">
            <template #default="{ row }">
              <span :class="row.own ? '' : 'text-muted'">{{ row.own ? '单独设置' : '跟随报表' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="打印区域 / 标题行" min-width="140">
            <template #default="{ row }">
              <span v-if="row.printArea || row.titleRows" class="mono">
                {{ row.printArea || '—' }} / {{ row.titleRows || '—' }}
              </span>
              <span v-else class="text-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="拆分" width="120">
            <template #default="{ row }">
              <el-checkbox
                :model-value="row.once"
                :disabled="!canSplitSheet"
                @change="(v) => toggleOnce(row.index, v)"
              >
                只出一份
              </el-checkbox>
            </template>
          </el-table-column>
        </el-table>

        <div class="tips tips-flush">
          <p>
            纸张、页边距这些<b>只在这里看，不在这里改</b> —— 改还是切到那张工作表去改，
            两处都能改迟早对不齐。「打印设置」那列写「跟随报表」的，改报表级设置时会跟着变。
          </p>
          <p v-if="canSplitSheet">
            <b>「只出一份」= 这张不跟着拆</b>：整份只渲染一次、主接口给它<b>全量</b>数据（清单列表），
            其余的每条数据各出一份。出纸顺序照模板顺序走，
            「清单 + 详情」出来就是「清单、数据1详情、数据2详情…」。
            <b>点确定才生效</b>，和这个弹窗里其余的设置一样。
          </p>
          <p v-else-if="split.splitMode === 'single'" class="text-muted">
            「输出」页签里选了按数据拆分之后，才谈得上「哪几张跟着拆」。
          </p>
        </div>
      </el-tab-pane>
    </el-tabs>

    <div class="preview-summary">
      <span>可打印区域 <b class="mono">{{ printable.width.toFixed(0) }} × {{ printable.height.toFixed(0) }}</b> mm</span>
      <span v-if="reserveMm > 0">页头页尾占 <b class="mono">{{ reserveMm.toFixed(0) }}</b> mm</span>
      <span>当前内容 <b class="mono">{{ pageInfo.pages }}</b> 页</span>
      <span>整表切分 <b class="mono">{{ pageInfo.cols }}</b> 列 × <b class="mono">{{ pageInfo.rows }}</b> 行</span>
      <span :class="{ 'is-over': overflowPx > 1 }">
        打印列宽合计 <b class="mono">{{ Math.round(totalWidth) }}</b> px
        <template v-if="limitPx"> / 上限 <b class="mono">{{ limitPx }}</b> px</template>
      </span>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useDesignerStore } from '@/stores/designer'
import {
  PAPER_OPTIONS,
  ORIENTATIONS,
  HEADER_PLACEHOLDERS,
  normalizePageConfig,
  pageConfigOf,
  printableSizeMm,
  computePageBreaks,
  headerFooterReserveMm,
  parseA1Range,
  formatA1Range,
  parseRowRange,
  widthLimitPx,
  widthOverflowPx,
  totalColumnsWidth
} from '@/utils/print'
import { normalizeExportConfig, exportFileName } from '@/utils/sheet'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 取当前选区：() => {r1,c1,r2,c2} | null */
  getSelection: { type: Function, default: null },
  /** 当前正在设计的 sheet，用于估算页数 */
  sheet: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'applied'])

/** 页头页尾的三段 */
const SECTIONS = [
  { key: 'left', label: '左' },
  { key: 'center', label: '居中' },
  { key: 'right', label: '右' }
]

const store = useDesignerStore()
const form = ref(normalizePageConfig(null))
/**
 * 打印设置写到哪儿：`sheet` 只给当前工作表 | `all` 写成报表级并**清掉各表的独立设置** |
 * `follow` 撤掉当前工作表的独立设置、改回跟随报表级。
 *
 * 三个是互斥的一件事，所以做成一组单选而不是「两个单选 + 一个立即生效的链接」——
 * 原先那个「改回跟随报表设置」按钮点下去就写库了，和弹窗其余部分「点确定才写回」不是一套，
 * 点取消也撤不回来。现在三条路都是草稿，取消一律不留痕迹。
 */
const scope = ref('sheet')
/** 跟随报表时，「页面 / 页头页尾 / 水印」整块禁用 —— 那三项写的就是本表自己那份，改了也不会存 */
const followMode = computed(() => scope.value === 'follow')
/**
 * 输出方式（报表级，不按 sheet 分）：单 sheet / 多 sheet 输出（`perRowPage`，按主接口每条数据
 * 拆一份、拼回同一张 sheet、一份一页）/ 每条数据一个 sheet（`perRow`，同一套拆分不拼接，
 * 直接出 M×N 张 sheet）。
 * 和打印设置一样，编辑的是副本，点确定才写回 store。
 *
 * 这两项都是**报表级**的；「哪几张跟着拆」按 sheet 存，在 {@link splits} 里。
 */
const split = ref({ splitMode: 'single', sheetNameField: '' })
/**
 * 哪几张模板是清单页（`content.sheetSplits` 的草稿，key = 模板下标，值恒为 `once`）。
 *
 * **跨表的一项，所以不跟着「作用范围」走、也不该逼用户切表** —— 在「工作表」页签里
 * 一张一张勾，点确定整份写回。
 */
const splits = ref({})
/**
 * 导出设置（**报表级**）：导出的文件叫什么名字 —— 报表名 + 主接口若干字段值。
 * 同样是编辑副本，点确定才写回 store。
 */
const exportCfg = ref(normalizeExportConfig(null))
const tab = ref('page')
/** 占位符要插到哪个输入框：最后获得焦点的那个 */
const target = ref({ part: 'header', section: 'center' })

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const sheetCount = computed(() => store.content.sheets?.length || 0)
const multiSheet = computed(() => sheetCount.value > 1)
/** 眼下有自己那份打印设置的工作表名 —— 选「全部工作表」时它们会被清掉，先摆出来 */
const ownConfigSheets = computed(() =>
  Object.keys(store.content.pageConfigs || {})
    .map((k) => Number(k))
    .filter((i) => Number.isInteger(i) && i >= 0 && i < sheetCount.value)
    .sort((a, b) => a - b)
    .map((i) => store.content.sheets[i]?.name || `工作表${i + 1}`)
)
const sheetName = computed(() => props.sheet?.name || `工作表${store.sheetIndex + 1}`)
const title = computed(() => (multiSheet.value ? `打印设置 · ${sheetName.value}` : '打印设置'))

/** 打开时从当前 sheet 生效的设置拷副本，取消不污染原数据 */
watch(visible, (v) => {
  if (!v) return
  // 深拷 header / footer / watermark：浅拷会让弹窗里的输入直接改到 store 上，取消也撤不回来
  form.value = normalizePageConfig(JSON.parse(JSON.stringify(store.pageConfig)))
  // 认不得的值（老报表写坏的、手写 content）退回 single —— 不退的话单选框一个都不亮，
  // 用户看着像「没设过」，点确定却又把那个野值原样存回去
  const stored = store.content.splitMode
  split.value = {
    splitMode: ['perRow', 'perRowPage'].includes(stored) ? stored : 'single',
    sheetNameField: store.content.sheetNameField || ''
  }
  // 哪几张是清单页：整份拷一份当草稿（它是跨表的一项，在「工作表」页签里逐张勾）
  splits.value = { ...(store.content.sheetSplits || {}) }
  exportCfg.value = normalizeExportConfig(store.content.exportConfig)
  // 单 sheet 报表没有「按 sheet 设」的意义，直接写报表级，避免存一份多余的覆盖
  scope.value = multiSheet.value ? 'sheet' : 'all'
  tab.value = 'page'
  target.value = { part: 'header', section: 'center' }
})

/**
 * 切到「跟随报表设置」时，把报表级那份装进表单 —— 那三张表单同时被禁用，
 * 于是用户看到的就是确定之后本表会变成的样子，而不是自己那份已经作废的设置。
 * 切回来再装回本表生效的那份。
 */
watch(scope, (v, old) => {
  if (!visible.value || v === old) return
  if (v === 'follow') {
    form.value = normalizePageConfig(JSON.parse(JSON.stringify(store.content.pageConfig)))
  } else if (old === 'follow') {
    form.value = normalizePageConfig(JSON.parse(JSON.stringify(store.pageConfig)))
  }
})

/** 当前报表的主接口（含字段），没设主接口时为 null */
const primary = computed(() => store.datasetByCode(store.content.primaryDataset))
const primaryFields = computed(() => primary.value?.fields || [])
/** 主接口自己声明的参数 —— 导出文件名可以拼 `${参数名}`，那一段不看数据、多条时照样在 */
const primaryParams = computed(() => primary.value?.params || [])
/**
 * 只有「主接口是集合型」才拆得动：分页型自己就在按页取数，再按行拆 sheet 是两套分页打架。
 */
const canSplit = computed(() => !!primary.value && primary.value.resultType !== 'page')
/**
 * 「哪几张跟着拆」只在**多模板 + 真的在拆**时才有意义：单模板报表把唯一那张标成清单页，
 * 等于把拆分整个关掉，那不是一个说得通的选择。
 */
const canSplitSheet = computed(() => canSplit.value && split.value.splitMode !== 'single' && multiSheet.value)
/* ------------------------------ 工作表一览 ------------------------------ */

/**
 * 每张工作表一行：名字 + 生效的纸张方向 + 是不是自己那份设置 + 是不是清单页。
 *
 * 有了它才不必「切标签 → 开弹窗 → 看一眼 → 关掉 → 再切下一张」把三张模板逐个翻一遍；
 * **清单页那一列直接在这里勾**（草稿，点确定才写回），它本来就是跨表的一项。
 * 纸张/页边距这些仍然是「谁生效改谁」，所以只读 —— 要改还是切到那张去改，
 * 否则这张表就变成第二个打印设置界面了，两处说同一件事迟早对不齐。
 *
 * **按 `order` 排，不是数组顺序**：拖动标签重排只改 `order`（标签栏与出纸顺序都认它，
 * 见后端 `TemplateParser#parse`），照数组顺序列的话这张表和标签栏对不上，
 * 而「清单页」勾的是连续的模板段（清单在最前面还是夹在中间），看错顺序就配错了。
 * `index` 仍是**数组下标** —— 打印设置与清单页标记都按它寻址。
 */
const sheetRows = computed(() =>
  (store.content.sheets || [])
    .map((s, i) => {
      const cfg = pageConfigOf(store.content, i)
      return {
        index: i,
        order: Number.isFinite(Number(s.order)) ? Number(s.order) : i,
        name: s.name || `工作表${i + 1}`,
        current: i === store.sheetIndex,
        own: !!store.content.pageConfigs?.[String(i)],
        paper: `${cfg.paperSize} ${cfg.orientation === 'landscape' ? '横向' : '纵向'}`,
        printArea: cfg.printArea || '',
        titleRows: cfg.titleRows || '',
        once: splits.value[String(i)] === 'once'
      }
    })
    .sort((a, b) => a.order - b.order || a.index - b.index)
)

/** 草稿里哪几张是清单页（在「输出」「工作表」两个页签里都要回显） */
const onceSheetNames = computed(() =>
  !canSplitSheet.value ? [] : sheetRows.value.filter((r) => r.once).map((r) => r.name)
)

/** 勾/取消「清单页」：只存标了的那些，取消就删掉 key（跟着拆是缺省） */
function toggleOnce(index, once) {
  const next = { ...splits.value }
  if (once) next[String(index)] = 'once'
  else delete next[String(index)]
  splits.value = next
}

/* ------------------------------ 导出 ------------------------------ */

const reportName = computed(() => store.report.name || '报表')
/**
 * 文件名示例：字段位置拿**字段名**示意（设计器里没有真实数据），
 * 拼法与后端 `ExportConfigDTO#resolve` 是同一套（utils/sheet.js#exportFileName）。
 */
const exportNameSample = computed(() => exportFileName(exportCfg.value, reportName.value))

const printable = computed(() => printableSizeMm(form.value))

/** 页头页尾一共占掉多少纸张高度（含它们各自的边距） */
const reserveMm = computed(
  () => headerFooterReserveMm(form.value.header) + headerFooterReserveMm(form.value.footer)
)

const pageInfo = computed(() => {
  const pb = computePageBreaks(props.sheet, form.value)
  return { pages: pb.pages, cols: pb.colBreaks.size + 1, rows: pb.rowBreaks.size + 1 }
})

/** 一页宽的上限（px）；0 表示当前设置下不限宽 */
const limitPx = computed(() => Math.round(widthLimitPx(form.value)))
const totalWidth = computed(() => totalColumnsWidth(props.sheet, form.value))
const overflowPx = computed(() => widthOverflowPx(props.sheet, form.value))

const targetLabel = computed(() => {
  const part = target.value.part === 'header' ? '页头' : '页尾'
  return part + SECTIONS.find((s) => s.key === target.value.section)?.label
})

/** 弹窗里的水印预览：和浏览器打印用的是同一套 CSS 变换 */
const watermarkPreviewStyle = computed(() => {
  const wm = form.value.watermark
  return {
    color: wm.color,
    opacity: wm.opacity / 100,
    // 预览框比纸张小得多，字号按比例缩一下才看得出效果
    fontSize: `${Math.max(wm.fontSize / 3, 8)}px`,
    transform: `rotate(${-wm.rotation}deg)`,
    whiteSpace: 'nowrap'
  }
})

function insertToken(token) {
  const { part, section } = target.value
  form.value[part][section] = (form.value[part][section] || '') + token
}

function useSelection() {
  const range = props.getSelection?.()
  if (!range) {
    ElMessage.warning('请先在表格中框选一个区域')
    return
  }
  form.value.printArea = formatA1Range(range)
}

/** 顶端标题行必须从这一行起：设了打印区域就是区域的第一行，否则是第 1 行 */
const titleRowFirst = computed(() => (parseA1Range(form.value.printArea)?.r1 ?? 0) + 1)

function useSelectionRows() {
  const range = props.getSelection?.()
  if (!range) {
    ElMessage.warning('请先在表格中框选要重复的那几行')
    return
  }
  form.value.titleRows = `${range.r1 + 1}:${range.r2 + 1}`
}

function onConfirm() {
  if (form.value.printArea && !parseA1Range(form.value.printArea)) {
    ElMessage.warning('打印区域格式不合法，应形如 A1:F30')
    return
  }
  if (form.value.titleRows) {
    const rows = parseRowRange(form.value.titleRows)
    if (!rows) {
      ElMessage.warning('顶端标题行格式不合法，应形如 1:3')
      return
    }
    // 落在中间的标题行三条导出路都还原不了（后端会忽略），不如在这里就拦住
    if (rows.r1 + 1 !== titleRowFirst.value) {
      ElMessage.warning(`顶端标题行只能从第 ${titleRowFirst.value} 行起，例如 ${titleRowFirst.value}:${rows.r2 + 1}`)
      return
    }
  }
  // 三条路互斥：写本表 / 推给全部（顺带清掉各表的独立设置）/ 撤掉本表的独立设置
  if (scope.value === 'follow') {
    store.clearPageConfig()
  } else {
    store.setPageConfig(form.value, scope.value)
  }
  // 输出方式与导出文件名是报表级的，跟「作用范围」无关；
  // 「哪几张是清单页」按 sheet 存，整份草稿一起写回（在「工作表」页签里勾的）
  store.setSheetSplit(
    canSplit.value
      ? { ...split.value, sheetSplits: canSplitSheet.value ? splits.value : {} }
      : { splitMode: 'single' }
  )
  store.setExportConfig(exportCfg.value)
  emit('applied')
  visible.value = false
  ElMessage.success(
    scope.value === 'all' && multiSheet.value
      ? '打印设置已更新（全部工作表）'
      : scope.value === 'follow'
        ? `「${sheetName.value}」已改回跟随报表设置`
        : `「${sheetName.value}」的打印设置已更新`
  )
}
</script>

<style scoped>
.margin-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  width: 100%;
}
.margin-grid :deep(.el-input-number) {
  width: 100%;
}
.unit {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}
.area-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.limit-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.limit-row .text-muted {
  line-height: 1.4;
}
.scope-row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}
.hf-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  width: 100%;
}
.style-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.style-row :deep(.el-input-number) {
  width: 110px;
}
.token-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.token {
  cursor: pointer;
}
.tips {
  padding: 0 0 4px 96px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.tips p {
  margin: 0;
}
/* 「工作表」页签里没有 label 那一列，tips 不必跟着缩进 96px */
.tips-flush {
  padding-left: 0;
  padding-top: 8px;
}
.row-current {
  font-weight: 600;
}
.wm-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 88px;
  overflow: hidden;
  border: 1px dashed var(--mz-border);
  border-radius: 4px;
  background: #fff;
}
.is-over {
  color: var(--el-color-warning);
}
.preview-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  padding: 8px 12px;
  border-radius: 4px;
  background: var(--mz-panel-bg);
  font-size: 12px;
  color: var(--el-text-color-regular);
}
</style>
