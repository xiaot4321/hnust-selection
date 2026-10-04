/// <reference types="vite/client" />

// 告诉 TypeScript：Vue 单文件组件可以作为模块导入，由 Vue 插件负责编译其模板和样式。
// 没有此声明时，Vite 仍可能成功打包，但 IDE 会把 .vue 导入标成“找不到模块”。
declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<{}, {}, any>
  export default component
}

// 声明前端允许读取的 Vite 环境变量，避免对 import.meta.env 使用宽泛的 any 类型。
interface ImportMetaEnv {
  /** 可选的 API 根路径；未配置时默认使用同源 /api。 */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
