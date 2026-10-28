import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8090'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5180,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
      '/ws': { target: apiTarget, ws: true, changeOrigin: true }
    }
  },
  build: {
    // 单包 1.3MB 会拖慢首屏:把不常变的框架拆出去,业务代码改动时用户只重下小包
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'element'
          if (id.includes('@stomp') || id.includes('sockjs')) return 'ws'
          if (id.includes('/vue/') || id.includes('vue-router') || id.includes('@vue')) return 'vue'
          return 'vendor'
        }
      }
    },
    chunkSizeWarningLimit: 700
  }
})
