<!--
  父子关联（子接口查询）编辑弹窗：先查主表，再拿主表返回的字段值去查子表。

  编辑的是一份副本，点「确定」才写回 store（同 ParamConfigDialog）。
  取数语义见 docs/CONTRACT.md §4 `datasetLinks`：主表 N 行 = 子表被调用 N 次，
  结果拼成一份，主表字段合进每条子行。
-->
<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑父子关联' : '新增父子关联'"
    width="640px"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <el-form :model="form" label-width="110px">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" placeholder="给这条关联起个名字，只用于这里认人" />
      </el-form-item>

      <el-form-item label="主表数据源" required>
        <el-select v-model="form.master" placeholder="请选择" filterable style="width: 100%">
          <el-option
            v-for="ds in datasets"
            :key="ds.code"
            :label="`${ds.name}(${ds.code})`"
            :value="ds.code"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="子表数据源" required>
        <el-select v-model="form.child" placeholder="请选择" filterable style="width: 100%">
          <el-option
            v-for="ds in datasets"
            :key="ds.code"
            :label="`${ds.name}(${ds.code})`"
            :value="ds.code"
            :disabled="ds.code === form.master"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <div class="section-title">参数传递</div>
    <div class="text-muted section-hint">
      主表每返回一行，就把下面这些字段的值填进子表参数查一次子表；子表在模板里照常拖字段扩展。
    </div>

    <el-button type="primary" :icon="Plus" @click="addMapping">新增</el-button>

    <el-table :data="form.mappings" border size="small" style="margin-top: 8px" empty-text="还没有配参数传递">
      <el-table-column label="子表参数" min-width="200">
        <template #default="{ row }">
          <!-- allow-create：参数不一定在数据集里声明过，SQL/接口地址里的 ${xx} 直接写也认 -->
          <el-select
            v-model="row.param"
            placeholder="请选择"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
          >
            <el-option
              v-for="p in childParams"
              :key="p.paramName"
              :label="p.paramText && p.paramText !== p.paramName ? `${p.paramText}(${p.paramName})` : p.paramName"
              :value="p.paramName"
            />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="主表字段" min-width="200">
        <template #default="{ row }">
          <el-select
            v-model="row.field"
            placeholder="请选择"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
          >
            <el-option
              v-for="f in masterFields"
              :key="f.fieldName"
              :label="f.fieldText && f.fieldText !== f.fieldName ? `${f.fieldText}(${f.fieldName})` : f.fieldName"
              :value="f.fieldName"
            />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70" align="center">
        <template #default="{ $index }">
          <el-button link type="danger" :icon="Delete" @click="form.mappings.splice($index, 1)" />
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="onConfirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import { deepClone } from '@/utils/sheet'
import { useDesignerStore } from '@/stores/designer'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 归一化后的数据集列表 [{code,name,fields,params}] */
  datasets: { type: Array, default: () => [] },
  /** 正在编辑第几条关联，-1 = 新增 */
  index: { type: Number, default: -1 }
})
const emit = defineEmits(['update:modelValue'])

const store = useDesignerStore()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const isEdit = computed(() => props.index >= 0)

const form = ref(emptyForm())

function emptyForm() {
  return { name: '', master: '', child: '', mappings: [] }
}

/** 打开时载入待编辑的那条（新增则是空表单） */
watch(visible, (v) => {
  if (!v) return
  const src = props.index >= 0 ? store.content.datasetLinks[props.index] : null
  form.value = src ? { mappings: [], ...deepClone(src) } : emptyForm()
})

const datasetByCode = (code) => props.datasets.find((ds) => ds.code === code) || null
const masterFields = computed(() => datasetByCode(form.value.master)?.fields || [])
const childParams = computed(() => datasetByCode(form.value.child)?.params || [])

function addMapping() {
  form.value.mappings.push({ param: '', field: '' })
}

function onConfirm() {
  const link = {
    name: (form.value.name || '').trim(),
    master: form.value.master,
    child: form.value.child,
    // 两头都填了才算数：留着半条的话渲染时那个参数会被填成空值，子表反而查不出东西
    mappings: form.value.mappings
      .map((m) => ({ param: (m.param || '').trim(), field: (m.field || '').trim() }))
      .filter((m) => m.param && m.field)
  }
  if (!link.name) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!link.master || !link.child) {
    ElMessage.warning('请选择主表和子表数据源')
    return
  }
  if (link.master === link.child) {
    ElMessage.warning('主表和子表不能是同一个数据源')
    return
  }
  if (!link.mappings.length) {
    ElMessage.warning('请至少配置一条参数传递，否则子表每行查的都是同一份数据')
    return
  }
  try {
    store.saveDatasetLink(link, props.index)
  } catch (e) {
    // 同一个子表挂两个主表：取数规则有歧义，后端也会拒
    ElMessage.warning(e.message)
    return
  }
  visible.value = false
  ElMessage.success(isEdit.value ? '关联已更新' : '关联已新增')
}
</script>

<style scoped>
.section-title {
  margin-bottom: 4px;
  font-weight: 600;
}
.section-hint {
  margin-bottom: 8px;
  font-size: 12px;
  line-height: 1.6;
}
</style>
