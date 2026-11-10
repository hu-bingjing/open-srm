import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()],
  server: { proxy: { '/api': 'http://127.0.0.1:8081' } },
  preview: { proxy: { '/api': 'http://127.0.0.1:8081' } },
  build: { rollupOptions: { output: { manualChunks(id: string) {
    if (id.includes('/node_modules/element-plus/')) return 'element-plus'
    if (id.includes('/node_modules/@vue/') || id.includes('/node_modules/vue/')) return 'vue'
  } } } },
})
