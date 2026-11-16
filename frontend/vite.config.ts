import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Same-origin development proxy keeps Spring's session cookie and CSRF protection intact.
// Change VITE_BACKEND_ORIGIN only when explicitly testing another local backend.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: process.env.VITE_BACKEND_ORIGIN || 'http://127.0.0.1:8080', changeOrigin: true },
      '/actuator': { target: process.env.VITE_BACKEND_ORIGIN || 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
})
