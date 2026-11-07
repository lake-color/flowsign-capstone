import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
    plugins: [react()],

    server: {
        proxy: {
            '/api': {
                // 기본은 로컬 Spring(8080). 다른 포트로 띄웠다면 API_PROXY_TARGET으로 바꾼다.
                target: process.env.API_PROXY_TARGET ?? 'http://localhost:8080',
                changeOrigin: true,
            },
        },
    },
})
