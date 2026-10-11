import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import { announceAppError } from './utils/errorAnnouncer'
// 导入全局样式，让根组件及其子组件共用同一套基础排版和认证页布局。
import './style.css'

// 先注册路由与 Vue 运行时异常播报，再挂载应用，让页面错误能被统一提示。
const app = createApp(App)
app.config.errorHandler = (error, _instance, info) => {
  announceAppError({
    source: 'runtime',
    code: 'VUE_RUNTIME_ERROR',
    message: error instanceof Error ? error.message : String(error),
    context: info,
  })
}
app.use(router).mount('#app')
