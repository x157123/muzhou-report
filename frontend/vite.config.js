import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { fortuneSheetPatchPlugin, fortuneSheetPatchEsbuildPlugin } from './build/fortuneSheetPatch.js'

export default defineConfig({
  // fortuneSheetPatch 修的是 @fortune-sheet/react 边框菜单里的几个 bug，见该文件头部注释；
  // dev 走预构建、build 走 rollup，两条路各挂一次（同一份替换）
  plugins: [fortuneSheetPatchPlugin(), vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    open: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8888',
        changeOrigin: true
      }
    }
  },
  optimizeDeps: {
    include: ['react', 'react-dom', 'react-dom/client', '@fortune-sheet/react', '@fortune-sheet/core'],
    esbuildOptions: {
      plugins: [fortuneSheetPatchEsbuildPlugin()]
    }
  },
  build: {
    chunkSizeWarningLimit: 2000,
    rollupOptions: {
      output: {
        manualChunks: {
          fortune: ['@fortune-sheet/react', '@fortune-sheet/core', 'react', 'react-dom'],
          element: ['element-plus', '@element-plus/icons-vue']
        }
      }
    }
  }
})
