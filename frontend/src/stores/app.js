import { defineStore } from 'pinia'

const STORAGE_KEY = 'muzhou-report-sidebar-collapsed'

export const useAppStore = defineStore('app', {
  state: () => ({
    sidebarCollapsed: localStorage.getItem(STORAGE_KEY) === '1'
  }),
  actions: {
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
      localStorage.setItem(STORAGE_KEY, this.sidebarCollapsed ? '1' : '0')
    }
  }
})
