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
  }
})
