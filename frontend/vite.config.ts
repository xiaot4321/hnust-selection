import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    // 仅监听本机回环地址，防止开发服务器意外暴露给同一局域网的其他设备。
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      // 浏览器请求前端同源地址 /api/...，Vite 在开发时转发给 Spring Boot 的 8080 端口。
      // 保留 /api 前缀，使开发与生产环境使用同一组前端 API 路径，也避免额外配置跨域访问。
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
})
