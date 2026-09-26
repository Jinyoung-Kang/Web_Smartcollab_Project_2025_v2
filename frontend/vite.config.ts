/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath, URL } from 'node:url'

const backend = process.env.BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    // 개발 서버에서도 운영과 같은 "같은 출처(same-origin)" 조건이 되도록 API·WebSocket 을 백엔드로 프록시합니다.
    // changeOrigin: false 로 원래 Host 를 유지해야 서버의 CORS·WebSocket 출처 검사가 운영과 동일하게 동작합니다.
    proxy: {
      '/api': { target: backend, changeOrigin: false },
      '/ws': { target: backend.replace('http', 'ws'), ws: true, changeOrigin: false },
      '/v3': { target: backend, changeOrigin: false },
      '/swagger-ui': { target: backend, changeOrigin: false },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 600,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    css: false,
  },
})
