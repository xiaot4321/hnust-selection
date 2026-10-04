import { createApp } from 'vue'
import App from './App.vue'
// 导入全局样式，让根组件及其子组件共用同一套基础排版和认证页布局。
import './style.css'

// createApp 创建单页应用实例；index.html 提供 #app 挂载节点，App.vue 挂载当前应用视图。
createApp(App).mount('#app')
