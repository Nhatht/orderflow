import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Dev: trình duyệt chỉ nói chuyện với localhost:5173, Vite chuyển /api và /auth
// sang gateway :8080. Cùng origin nên không cần CORS (gateway chưa bật CORS).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/auth': 'http://localhost:8080',
    },
  },
})
