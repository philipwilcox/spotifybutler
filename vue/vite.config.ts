import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const buildTimestamp = new Date().toISOString()

export default defineConfig({
  plugins: [vue()],
  define: { __BUTLER_BUILD_TIMESTAMP__: JSON.stringify(buildTimestamp) },
  build: { outDir: 'dist', emptyOutDir: true },
  server: { port: 5173 },
})
