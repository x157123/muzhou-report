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
    path: '/preview/:id',
    name: 'Preview',
    component: () => import('@/views/preview/ReportPreview.vue'),
    meta: { title: '报表预览', fullscreen: true }
  },
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
