<!--
  设计器内免保存预览抽屉：填参数 -> 调用 /api/render/preview -> 只读 FortuneSheet 展示结果。
-->
<template>
  <el-drawer v-model="visible" title="报表预览" size="80%" :close-on-click-modal="false" destroy-on-close>
    <div class="preview-drawer-body" v-loading="loading">
      <div class="preview-toolbar">
        <ParamForm ref="paramFormRef" :params="params" v-model="paramValues">
          <template #actions>
            <el-button type="primary" :icon="Refresh" @click="doRender">渲染</el-button>
          </template>
        </ParamForm>
        <div class="flex-spacer"></div>
        <span v-if="elapsed !== null" class="text-muted">耗时 {{ elapsed }} ms</span>
      </div>

      <!--
        没挂全时说一句：这里只是预览挂不下，**导出的文件是完整的**。
        不说的话就是「我配了每条数据一个 sheet，怎么只出 10 张」。
      -->
      <div v-if="sheetTruncated" class="sheet-limit-tip">
        共 {{ sheetTotal }} 张工作表，预览只显示前 {{ PREVIEW_MAX_SHEETS }} 张（全挂上会卡住浏览器）；
        导出的文件仍是完整的 {{ sheetTotal }} 张。
      </div>

      <div class="preview-sheet-wrap">
        <el-result v-if="errorMsg" icon="error" :title="'渲染失败'" :sub-title="errorMsg" />
        <FortuneSheet
          v-else
          ref="sheetRef"
          :data="renderedSheets"
          :allow-edit="false"
          :show-toolbar="false"
          :show-formula-bar="false"
        />
      </div>

      <!-- 主接口是分页型数据集时才有分页条，规则同预览页 -->
      <div v-if="total !== null" class="preview-pager">
        <el-pagination
          v-model:current-page="pageNo"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[20, 50, 100, 200]"
          :disabled="loading"
          layout="total, sizes, prev, pager, next"
          @size-change="doRender"
          @current-change="doRender"
        />
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import FortuneSheet from '@/components/FortuneSheet.vue'
import ParamForm from '@/components/ParamForm.vue'
import { renderPreview } from '@/api/render'
import { applyCellImages } from '@/utils/cellImage'
import { limitPreviewSheets, PREVIEW_MAX_SHEETS } from '@/utils/sheet'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 当前报表 id：内部数据集按报表隔离，后端要靠它解析 #{code.字段} */
  reportId: { type: String, default: '' },
  /** 报表 content（ReportContent JSON 对象） */
  content: { type: Object, default: () => ({}) },
  /** 参数定义 [{name,text,type,widget,defaultValue,required,options}] */
  params: { type: Array, default: () => [] },
  /** 设计器地址上带来的外部参数（`/designer/{id}?id=11233`），表单里的同名值优先 */
  externalParams: { type: Object, default: () => ({}) },
  /** 当前设计的是哪一版：预览渲染的就是传过去的这份 content，这里只用来在标题上说清是哪一版 */
  versionId: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const sheetRef = ref(null)
const paramFormRef = ref(null)
const paramValues = ref({})
const renderedSheets = ref([])
const elapsed = ref(null)
/** 主接口自报的总条数，null = 这张报表不分页 */
const total = ref(null)
const pageNo = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const errorMsg = ref('')
/** 这次渲染一共几张工作表 / 预览是不是没挂全，见 utils/sheet.js#limitPreviewSheets */
const sheetTotal = ref(0)
const sheetTruncated = ref(false)

watch(visible, (v) => {
  if (v) doRender()
})

async function doRender() {
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await renderPreview(
      props.reportId,
      props.content,
      {
        ...props.externalParams,
        ...paramValues.value,
        pageNo: pageNo.value,
        pageSize: pageSize.value
      },
      props.versionId
    )
    total.value = res.total ?? null
    // 先截断再摊图片：几百张 sheet 逐格遍历一遍就够把页面卡住了（拆分模式下一条数据一张）
    const limited = limitPreviewSheets(res.sheets)
    sheetTotal.value = limited.total
    sheetTruncated.value = limited.truncated
    // 图片单元格要摊成 FortuneSheet 的浮动图片才画得出来，见 utils/cellImage.js
    renderedSheets.value = applyCellImages(limited.sheets)
    elapsed.value = res.elapsed ?? null
    await sheetRef.value?.reload(renderedSheets.value)
  } catch (e) {
    errorMsg.value = e?.message || '渲染失败'
  } finally {
    loading.value = false
  }
}

defineExpose({ doRender })
</script>

<style scoped>
.preview-drawer-body {
  height: calc(100vh - 100px);
  display: flex;
  flex-direction: column;
}
.preview-toolbar {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--mz-border);
  margin-bottom: 8px;
}
.preview-sheet-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.sheet-limit-tip {
  padding: 6px 10px;
  margin-bottom: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-radius: 4px;
}
.preview-pager {
  display: flex;
  justify-content: flex-end;
  padding-top: 8px;
  border-top: 1px solid var(--mz-border);
}
</style>
