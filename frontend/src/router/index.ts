import { createRouter, createWebHashHistory } from 'vue-router'
import AuthView from '../views/AuthView.vue'

/**
 * 左侧导航对应可收藏、可前进/后退的工作窗口。
 * 工作台按当前会话和服务端授权展示；这里负责 URL 与页面入口的映射。
 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'home', component: AuthView, meta: { title: '系统首页' } },
    { path: '/workspace', name: 'workspace', component: AuthView, meta: { title: '我的工作台' } },
    { path: '/guide', name: 'guide', component: AuthView, meta: { title: '服务说明' } },
    { path: '/admin/personnel', name: 'personnel', component: AuthView, meta: { title: '人员与目录' } },
    { path: '/admin/accounts', name: 'admin-accounts', component: AuthView, meta: { title: '管理员账号' } },
    { path: '/admin/authorizations', name: 'authorizations', component: AuthView, meta: { title: '业务授权' } },
    { path: '/admin/batches', name: 'batches', component: AuthView, meta: { title: '批次与导师名额' } },
    { path: '/admin/governance', name: 'governance', component: AuthView, meta: { title: '审核与业务纠错' } },
    { path: '/teacher', name: 'teacher-workspace', component: AuthView, meta: { title: '导师工作台' } },
    { path: '/:pathMatch(.*)*', redirect: { name: 'home' } },
  ],
  scrollBehavior() {
    return { top: 0, left: 0 }
  },
})
