import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('@/layout/BasicLayout.vue'),
    redirect: '/report',
    children: [
      {
        path: 'report',
        name: 'ReportList',
        component: () => import('@/views/report/ReportList.vue'),
        meta: { title: '报表管理', icon: 'Document' }
      },
      {
        path: 'dataset',
        name: 'DatasetList',
        component: () => import('@/views/dataset/DatasetList.vue'),
        meta: { title: '数据集', icon: 'Coin' }
      },
      {
        path: 'datasource',
        name: 'DatasourceList',
        component: () => import('@/views/datasource/DatasourceList.vue'),
        meta: { title: '数据源', icon: 'Connection' }
      },
      {
        // 全局参数：所有报表共用的一份参数定义，见 CONTRACT §3.5
        path: 'param',
        name: 'ParamList',
        component: () => import('@/views/param/ParamList.vue'),
        meta: { title: '参数管理', icon: 'Key' }
      },
      {
        // 上传字体：服务器上没有的字体传一份上来，PDF 才印得出它，见 CONTRACT §3.6
        path: 'font',
        name: 'FontList',
        component: () => import('@/views/font/FontList.vue'),
        meta: { title: '字体管理', icon: 'EditPen' }
      }
    ]
  },
  {
    path: '/designer/:id',
    name: 'Designer',
    component: () => import('@/views/designer/ReportDesigner.vue'),
    meta: { title: '报表设计器', fullscreen: true }
  },
  {
    path: '/view/:id',
    name: 'Preview',
    component: () => import('@/views/preview/ReportPreview.vue'),
    meta: { title: '报表预览', fullscreen: true }
  },
  // 老地址：外部系统 / 已发出去的链接还指着 /preview/{id}，原样带着 query 转过去
  { path: '/preview/:id', redirect: (to) => ({ path: '/view/' + to.params.id, query: to.query }) },
  { path: '/:pathMatch(.*)*', redirect: '/report' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · 木舟报表` : '木舟报表'
})

export default router
