import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backend = process.env.BACKEND_URL ?? 'http://127.0.0.1:8180';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5273,
    proxy: { '/api': backend, '/mcp': backend },
  },
});
