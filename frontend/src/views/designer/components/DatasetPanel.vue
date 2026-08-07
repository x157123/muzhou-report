<!--
  设计器左侧面板：数据集/字段列表，支持拖拽字段到工作簿、搜索过滤、插入全部字段、刷新。

  数据集分两类（见 CONTRACT §3.2）：
  - **内部数据集**（dataset.reportId = 当前报表）：在这个面板里直接建，只有本报表能用，可改可删；
  - **公共数据集**（reportId 为空）：在「数据集管理」里建，所有报表都能用，这里只读。
-->
<template>
  <div class="mz-panel dataset-panel">
    <div class="mz-panel-title">
      数据集
      <span class="title-actions">
        <el-tooltip content="为当前报表新建内部数据集" placement="top">
          <el-button link :icon="Plus" size="small" @click="$emit('create')" />
        </el-tooltip>
        <el-tooltip content="刷新数据集列表" placement="top">
          <el-button link :icon="Refresh" size="small" @click="$emit('refresh')" />
        </el-tooltip>
      </span>
    </div>

    <div class="panel-search">
      <el-input v-model="keyword" placeholder="搜索字段" clearable size="small" :prefix-icon="Search" />
    </div>

    <div class="dataset-scroll">
      <!-- 内部数据集 -->
      <div class="group-title">
        本报表数据集
        <el-tooltip content="只有当前报表能用，随报表一起删除/复制" placement="top">
          <el-icon class="group-hint"><InfoFilled /></el-icon>
        </el-tooltip>
      </div>
      <div v-if="!internalDatasets.length" class="group-empty text-muted">
        暂无，点右上角
        <el-icon><Plus /></el-icon>
        新建只属于本报表的数据集
      </div>
      <el-collapse v-model="activeInternal" class="dataset-collapse">
        <el-collapse-item v-for="ds in internalDatasets" :key="ds.id || ds.code" :name="ds.id || ds.code">
          <template #title>
            <el-tooltip :content="primaryTip(ds)" placement="top">
              <el-button
                class="ds-star"
                link
                :type="ds.code === primaryDataset ? 'success' : 'info'"
                :icon="ds.code === primaryDataset ? StarFilled : Star"
                size="small"
                @click.stop="$emit('set-primary', ds)"
              />
            </el-tooltip>
            <span class="ds-title" :title="`${ds.name}（${ds.code}）`">
              {{ ds.name }} <span class="text-muted">({{ ds.code }})</span>
            </span>
            <el-tag v-if="ds.resultType === 'page'" class="ds-tag" size="small" type="warning" effect="plain">
              分页
            </el-tag>
            <span class="ds-actions">
              <el-tooltip content="编辑数据集" placement="top">
                <el-button link :icon="Edit" size="small" @click.stop="$emit('edit', ds)" />
              </el-tooltip>
              <el-tooltip content="删除数据集" placement="top">
                <el-button link type="danger" :icon="Delete" size="small" @click.stop="$emit('remove', ds)" />
              </el-tooltip>
            </span>
          </template>

          <div class="ds-body-actions">
            <el-tooltip content="将全部字段插入当前选中单元格所在行" placement="top">
              <el-button link type="primary" size="small" @click="$emit('insert-all', ds)">插入全部</el-button>
            </el-tooltip>
          </div>
          <FieldList :dataset="ds" />
        </el-collapse-item>
      </el-collapse>

      <!-- 公共数据集 -->
      <div class="group-title">
        公共数据集
        <el-tooltip content="在「数据集管理」里维护，所有报表共用" placement="top">
          <el-icon class="group-hint"><InfoFilled /></el-icon>
        </el-tooltip>
      </div>
      <div v-if="!publicDatasets.length" class="group-empty text-muted">暂无，请到数据集管理创建</div>
      <el-collapse v-model="activePublic" class="dataset-collapse">
        <el-collapse-item v-for="ds in publicDatasets" :key="ds.id || ds.code" :name="ds.id || ds.code">
          <template #title>
            <el-tooltip :content="primaryTip(ds)" placement="top">
              <el-button
                class="ds-star"
                link
                :type="ds.code === primaryDataset ? 'success' : 'info'"
                :icon="ds.code === primaryDataset ? StarFilled : Star"
                size="small"
                @click.stop="$emit('set-primary', ds)"
              />
            </el-tooltip>
            <span class="ds-title" :title="`${ds.name}（${ds.code}）`">
              {{ ds.name }} <span class="text-muted">({{ ds.code }})</span>
            </span>
            <el-tag v-if="ds.resultType === 'page'" class="ds-tag" size="small" type="warning" effect="plain">
              分页
            </el-tag>
          </template>

          <div class="ds-body-actions">
            <el-tooltip content="将全部字段插入当前选中单元格所在行" placement="top">
              <el-button link type="primary" size="small" @click="$emit('insert-all', ds)">插入全部</el-button>
            </el-tooltip>
          </div>
          <FieldList :dataset="ds" />
        </el-collapse-item>
      </el-collapse>
    </div>

    <!--
      父子关联（子接口查询）：主表取回的每一行，都拿它的字段值去查一遍子表。
      放在数据集列表下面 —— 它配的就是「这几个数据集之间怎么串」，离数据集越近越好找。
    -->
    <div class="link-section">
      <div class="group-title">
        父子关联
        <el-tooltip content="先查主表，再用主表返回的字段值查子表（子接口查询）" placement="top">
          <el-icon class="group-hint"><InfoFilled /></el-icon>
        </el-tooltip>
        <span class="title-actions">
          <el-tooltip content="新增父子关联" placement="top">
            <el-button link :icon="Plus" size="small" @click="$emit('link-create')" />
          </el-tooltip>
        </span>
      </div>
      <div v-if="!links.length" class="group-empty text-muted">
        暂无，点右侧
        <el-icon><Plus /></el-icon>
        配置主子表取数
      </div>
      <div class="link-list">
        <div v-for="(link, i) in links" :key="i" class="link-item">
          <div class="link-main" :title="linkTip(link)">
            <div class="link-name">{{ link.name || '未命名关联' }}</div>
            <div class="link-flow text-muted">{{ link.master }} → {{ link.child }}</div>
          </div>
          <span class="link-actions">
            <el-tooltip content="编辑" placement="top">
              <el-button link :icon="Edit" size="small" @click="$emit('link-edit', i)" />
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button link type="danger" :icon="Delete" size="small" @click="$emit('link-remove', i)" />
            </el-tooltip>
          </span>
        </div>
      </div>
    </div>

    <div class="panel-footer">
      <el-link type="primary" :underline="false" @click="goDataset">去数据集管理 &gt;</el-link>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Refresh, Plus, Edit, Delete, InfoFilled, Star, StarFilled } from '@element-plus/icons-vue'
import FieldList from './DatasetFieldList.vue'

const props = defineProps({
  /** 归一化后的 [{id,name,code,reportId,resultType,fields:[{fieldName,fieldText,fieldType}]}] */
  datasets: { type: Array, default: () => [] },
  /** 当前报表的主接口 code（content.primaryDataset），空 = 没设 */
  primaryDataset: { type: String, default: '' },
  /** 父子关联 content.datasetLinks: [{name,master,child,mappings}] */
  links: { type: Array, default: () => [] }
})

defineEmits([
  'refresh',
  'insert-all',
  'create',
  'edit',
  'remove',
  'set-primary',
  'link-create',
  'link-edit',
  'link-remove'
])

const router = useRouter()
const keyword = ref('')
// 两组各自记展开项：共用一个 v-model 的话，展开一组会把另一组折起来
const activeInternal = ref([])
const activePublic = ref([])

/** 按字段名/字段说明过滤，数据集本身不匹配时若无字段命中则整体隐藏 */
const filteredDatasets = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return props.datasets
  return props.datasets
    .map((ds) => ({
      ...ds,
      fields: (ds.fields || []).filter(
        (f) =>
          (f.fieldName || '').toLowerCase().includes(kw) || (f.fieldText || '').toLowerCase().includes(kw)
      )
    }))
    .filter((ds) => ds.fields.length || ds.name.toLowerCase().includes(kw))
})

const internalDatasets = computed(() => filteredDatasets.value.filter((ds) => ds.reportId))
const publicDatasets = computed(() => filteredDatasets.value.filter((ds) => !ds.reportId))

/**
 * 主接口按钮的提示。只有分页型数据集当主接口才有分页条可言 —— 集合型是整份取回，
 * 没有总数也就没有页数，这里直接说清楚，免得设完了发现没反应。
 */
function primaryTip(ds) {
  if (ds.code === props.primaryDataset) return '当前主接口，点击取消'
  return ds.resultType === 'page'
    ? '设为主接口：预览页按它的总数分页（一张报表只能有一个）'
    : '设为主接口（一张报表只能有一个）。它是集合型数据集，不会出现分页条'
}

/** 悬浮时把参数传递也说清楚：`子表参数 ← 主表字段` */
function linkTip(link) {
  const pairs = (link.mappings || []).map((m) => `${m.param} ← ${m.field}`).join('，')
  return `${link.master} → ${link.child}${pairs ? `（${pairs}）` : ''}`
}

function goDataset() {
  router.push('/dataset')
}
</script>

<style scoped>
.dataset-panel {
  display: flex;
  flex-direction: column;
}
.title-actions {
  float: right;
}
.panel-search {
  padding: 8px;
  border-bottom: 1px solid var(--mz-border);
}
.dataset-scroll {
  flex: 1;
  overflow: auto;
}
.group-title {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--mz-panel-bg);
}
.group-hint {
  font-size: 13px;
}
.group-empty {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-wrap: wrap;
  padding: 8px 12px;
  font-size: 12px;
}
.dataset-collapse {
  border: none;
}
/* 主接口五角星在名字前面：一眼看到哪张是主接口，不用扫到行尾 */
.ds-star {
  flex: none;
  margin-right: 4px;
}
/* 名字太长时省略号截断：flex 子项默认 min-width:auto，撑得下整串文字，
   不写 min-width:0 的话 overflow 永远不触发，标题栏会被顶宽 */
.ds-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 「分页」标记与右侧按钮不参与压缩，被截掉的只能是名字 */
.ds-tag {
  flex: none;
  margin-left: 4px;
}
.ds-actions {
  display: flex;
  flex: none;
  align-items: center;
  margin-right: 8px;
}
/* 「插入全部」收进折叠里：标题栏只留星标与增删改，展开才用得上插入 */
.ds-body-actions {
  padding: 2px 12px 4px;
  text-align: right;
}
/* 关联区自己滚，最多占面板的四成，剩下的高度留给字段树 */
.link-section {
  flex: 0 0 auto;
  max-height: 40%;
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--mz-border);
  background: #fff;
}
.link-section .group-title {
  background: transparent;
}
/* 面板标题那个 .title-actions 是 float 的，这里的父级是 flex，float 不起作用 */
.link-section .title-actions {
  float: none;
  margin-left: auto;
}
.link-list {
  overflow: auto;
}
.link-item {
  display: flex;
  align-items: center;
  padding: 4px 8px 4px 12px;
  font-size: 12px;
}
.link-item:hover {
  background: var(--mz-panel-bg);
}
.link-main {
  flex: 1;
  min-width: 0;
}
.link-name,
.link-flow {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.link-actions {
  display: flex;
  align-items: center;
}
.panel-footer {
  padding: 8px 12px;
  border-top: 1px solid var(--mz-border);
  text-align: center;
  background: #fff;
}
:deep(.el-collapse-item__header) {
  padding: 0 4px 0 8px;
  flex-wrap: nowrap;
  min-width: 0;
}
/* el-collapse 会把 #title 插槽再包一层 .el-collapse-item__title（flex:auto、不是 flex 容器），
   星标/名字/按钮是它的行内子节点 —— 不把这层变成 flex 并允许收缩，上面那套 flex 规则等于没写，
   名字长了就是换行而不是截断 */
:deep(.el-collapse-item__title) {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
}
/* 箭头也别被名字挤没了 */
:deep(.el-collapse-item__arrow) {
  flex: none;
}
</style>
