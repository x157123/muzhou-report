<template>
  <div class="basic-layout">
    <!-- 侧边栏 -->
    <aside class="basic-sidebar" :style="{ width: sidebarWidth }">
      <div class="sidebar-logo">
        <span v-if="!appStore.sidebarCollapsed">木舟报表</span>
        <span v-else>木</span>
      </div>
      <el-menu
        :default-active="activePath"
        :collapse="appStore.sidebarCollapsed"
        :collapse-transition="false"
        class="sidebar-menu"
        background-color="#001529"
        text-color="rgba(255,255,255,0.75)"
        active-text-color="#ffffff"
        router
      >
        <el-menu-item v-for="item in menus" :key="item.path" :index="'/' + item.path">
          <el-icon>
            <component :is="item.meta?.icon" />
          </el-icon>
          <template #title>{{ item.meta?.title }}</template>
        </el-menu-item>
      </el-menu>
    </aside>

    <!-- 右侧主体 -->
    <div class="basic-main">
      <header class="basic-header">
        <el-icon class="collapse-btn" @click="appStore.toggleSidebar()">
          <Fold v-if="!appStore.sidebarCollapsed" />
          <Expand v-else />
        </el-icon>
        <el-breadcrumb separator="/">
          <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div class="flex-spacer" />
        <el-button class="help-btn" text @click="helpVisible = true">
          <el-icon><QuestionFilled /></el-icon>
          帮助
        </el-button>
      </header>
      <main class="basic-content">
        <router-view />
      </main>
    </div>

    <!-- 帮助抽屉：单元格表达式语法说明 -->
    <el-drawer v-model="helpVisible" title="单元格表达式语法" size="480px">
      <el-table :data="exprHelp" border size="small">
        <el-table-column prop="syntax" label="语法" width="180">
          <template #default="{ row }">
            <span class="mono">{{ row.syntax }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="meaning" label="含义" />
      </el-table>
      <div class="help-extra">
        <p class="text-muted">
          数据集 SQL 内使用 <span class="mono">${paramName}</span>，服务端替换为 JDBC
          <span class="mono">?</span> 占位并顺序绑定（防注入）；
          <span class="mono">$!{paramName}</span> 表示直接字符串拼接（用于动态表名/排序，需谨慎，会做标识符白名单校验）。
        </p>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import router from '@/router'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const appStore = useAppStore()

const helpVisible = ref(false)

// 侧边栏菜单从根路由的 children 动态生成
const rootRoute = router.options.routes[0]
const menus = computed(() => (rootRoute?.children || []).filter((r) => r.meta?.title))

const sidebarWidth = computed(() => (appStore.sidebarCollapsed ? '64px' : '200px'))

// 当前激活菜单跟随路由 path（去掉开头的 '/'，与根路由子路径拼接比较）
const activePath = computed(() => route.path)

const currentTitle = computed(() => route.meta?.title || '')

// CONTRACT §5 单元格表达式语法表
const exprHelp = [
  { syntax: '#{datasetCode.fieldName}', meaning: '数据单元格，按 cellConfig 的 expandType 扩展' },
  { syntax: '${paramName}', meaning: '报表参数替换（文本内）' },
  { syntax: '!{aviator 表达式}', meaning: '服务端 Aviator 计算，可用函数见文档 §6' },
  { syntax: '=SUM(B2:B5)', meaning: '原生公式，扩展时自动偏移引用' },
  { syntax: '纯文本', meaning: '原样输出' }
]
</script>

<style scoped>
.basic-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.basic-sidebar {
  flex-shrink: 0;
  background: #001529;
  transition: width 0.2s;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sidebar-logo {
  height: var(--mz-header-h);
  line-height: var(--mz-header-h);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  text-align: center;
  overflow: hidden;
  white-space: nowrap;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.sidebar-menu {
  flex: 1;
  border-right: none;
  overflow-y: auto;
  overflow-x: hidden;
}

.sidebar-menu:not(.el-menu--collapse) {
  width: 200px;
}

.basic-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.basic-header {
  height: var(--mz-header-h);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid var(--mz-border);
}

.collapse-btn {
  font-size: 20px;
  cursor: pointer;
  color: #606266;
}

.collapse-btn:hover {
  color: var(--mz-primary);
}

.help-btn {
  display: flex;
  align-items: center;
  gap: 4px;
}

.basic-content {
  flex: 1;
  overflow: auto;
  background: #f0f2f5;
}

.help-extra {
  margin-top: 16px;
  line-height: 1.8;
}
</style>
