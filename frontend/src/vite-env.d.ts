/// <reference types="vite/client" />

// 声明前端允许读取的 Vite 环境变量，避免对 import.meta.env 使用宽泛的 any 类型。
interface ImportMetaEnv {
  /** 可选的 API 根路径；未配置时默认使用同源 /api。 */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
