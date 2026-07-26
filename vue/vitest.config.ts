import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  define: { __BUTLER_BUILD_TIMESTAMP__: JSON.stringify('test-build') },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
})
