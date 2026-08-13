import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import '@fortune-sheet/react/dist/index.css'
import '@/styles/index.css'

import App from './App.vue'
import router from './router'
import { loadCustomFonts } from '@/utils/fontList'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 上传字体要赶在挂载之前并进字体清单、注册 @font-face：设计器一挂载就要按字体量文字算行高
// （utils/wrapHeight.js），字体还没到位的话量的是兜底字体的字宽。它自己不会抛，失败顶多少几款字体
loadCustomFonts().finally(() => app.mount('#app'))
